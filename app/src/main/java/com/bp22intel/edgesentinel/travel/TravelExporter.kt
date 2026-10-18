/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.travel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class TravelExporter(private val context: Context) {

    companion object {
        private const val AES_KEY_SIZE = 256
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val PBKDF2_ITERATIONS = 600_000
        private const val SALT_LENGTH = 32
        private const val EXPORT_DIR = "travel_exports"

        /**
         * N9: 4-byte format header "ES" + format version 0x02 + KDF id 0x01
         * (PBKDF2-HMAC-SHA256, 600k iterations, AES-256-GCM). Layout:
         * header(4) || salt(32) || iv(12) || ciphertext+tag.
         */
        val FILE_HEADER: ByteArray = byteArrayOf(0x45, 0x53, 0x02, 0x01)
        private const val TAG = "TravelExporter"

        /** NF2: export query for cell observations; the Room table is `cells` (schema 9.json). */
        internal const val CELLS_EXPORT_SQL =
            "SELECT id, cid, lac_tac, mcc, mnc, signal_strength, network_type, " +
                "latitude, longitude, first_seen, last_seen, times_seen, earfcn, pci " +
                "FROM cells ORDER BY last_seen DESC"
    }

    /** NF2: errors met while gathering export data; written into the export and logged. */
    private val gatherErrors = mutableListOf<String>()

    private fun recordGatherError(section: String, e: Exception) {
        val msg = "$section: ${e.javaClass.simpleName}: ${e.message}"
        gatherErrors.add(msg)
        try { android.util.Log.w(TAG, "Export gather failed: $msg", e) } catch (_: Throwable) {}
    }

    internal fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val chars = passphrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, AES_KEY_SIZE)
        try {
            val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            // N9: clear the passphrase copies after key derivation.
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    fun exportEncrypted(passphrase: String): Uri? {
        require(passphrase.isNotBlank()) { "A passphrase is required; plaintext export is not allowed" }
        val exportData = gatherExportData()
        val jsonBytes = exportData.toString(2).toByteArray(Charsets.UTF_8)
        val encrypted = encrypt(jsonBytes, passphrase)

        val exportDir = File(context.filesDir, EXPORT_DIR)
        exportDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(exportDir, "es_travel_export_$timestamp.enc")
        exportFile.writeBytes(encrypted)

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )
    }

    // exportPlaintext() removed (F5): exports must always be encrypted.

    fun createShareIntent(uri: Uri, encrypted: Boolean): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = if (encrypted) "application/octet-stream" else "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Edge Sentinel Travel Data Export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun gatherExportData(): JSONObject {
        val root = JSONObject()
        root.put("export_version", 1)
        root.put("app_version", getAppVersion())
        root.put("export_timestamp", System.currentTimeMillis())
        root.put("export_date", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))

        // Export alerts from database
        root.put("alerts", gatherAlerts())

        // Export scan logs
        root.put("scans", gatherScans())

        // Export detected threats summary
        root.put("threat_summary", gatherThreatSummary())

        // Export cell tower observations
        root.put("cell_towers", gatherCellTowers())

        // NF2: gather failures are reported in the export, never dropped.
        root.put("export_errors", JSONArray(gatherErrors.toList()))

        return root
    }

    private fun openAppDatabase(): android.database.sqlite.SQLiteDatabase? {
        val candidates = listOf(
            context.getDatabasePath("edge_sentinel.db"),
            context.getDatabasePath("edge_sentinel_database")
        )
        for (f in candidates) {
            if (f.exists()) {
                try {
                    return android.database.sqlite.SQLiteDatabase.openDatabase(
                        f.absolutePath, null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                    )
                } catch (_: Exception) {
                    // Try next candidate
                }
            }
        }
        return null
    }

    private fun gatherAlerts(): JSONArray {
        val alerts = JSONArray()
        try {
            openAppDatabase()?.use { db ->
                val cursor = db.rawQuery("SELECT * FROM alerts ORDER BY timestamp DESC", null)
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val alert = JSONObject()
                        for (i in 0 until c.columnCount) {
                            alert.put(c.getColumnName(i), c.getString(i) ?: "")
                        }
                        alerts.put(alert)
                    }
                }
            }
        } catch (e: Exception) {
            recordGatherError("alerts", e)
        }
        return alerts
    }

    private fun gatherScans(): JSONArray {
        val scans = JSONArray()
        try {
            openAppDatabase()?.use { db ->
                val cursor = db.rawQuery("SELECT * FROM scans ORDER BY timestamp DESC", null)
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val scan = JSONObject()
                        for (i in 0 until c.columnCount) {
                            scan.put(c.getColumnName(i), c.getString(i) ?: "")
                        }
                        scans.put(scan)
                    }
                }
            }
        } catch (e: Exception) {
            recordGatherError("scans", e)
        }
        return scans
    }

    private fun gatherCellTowers(): JSONArray {
        val towers = JSONArray()
        try {
            openAppDatabase()?.use { db ->
                // NF2: the Room table is `cells` (schema 9.json), not `cell_towers`.
                val cursor = db.rawQuery(CELLS_EXPORT_SQL, null)
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val tower = JSONObject()
                        for (i in 0 until c.columnCount) {
                            tower.put(c.getColumnName(i), c.getString(i) ?: "")
                        }
                        towers.put(tower)
                    }
                }
            }
        } catch (e: Exception) {
            recordGatherError("cell_towers", e)
        }
        return towers
    }

    private fun gatherThreatSummary(): JSONObject {
        val summary = JSONObject()
        try {
            openAppDatabase()?.use { db ->
                val cursor = db.rawQuery(
                    "SELECT threat_type, COUNT(*) as count FROM alerts GROUP BY threat_type",
                    null
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        summary.put(c.getString(0) ?: "unknown", c.getInt(1))
                    }
                }
            }
        } catch (e: Exception) {
            recordGatherError("threat_summary", e)
        }
        return summary
    }

    fun encrypt(data: ByteArray, passphrase: String): ByteArray {
        val random = SecureRandom()

        // Generate salt for key derivation
        val salt = ByteArray(SALT_LENGTH)
        random.nextBytes(salt)

        // Derive key from passphrase using PBKDF2
        val secretKey = deriveKey(passphrase, salt)

        // Generate IV for GCM
        val iv = ByteArray(GCM_IV_LENGTH)
        random.nextBytes(iv)

        // Encrypt with AES-256-GCM
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        cipher.updateAAD(FILE_HEADER) // D5: bind the format header
        val ciphertext = cipher.doFinal(data)

        // Combine: header + salt + iv + ciphertext (includes GCM tag)
        return FILE_HEADER + salt + iv + ciphertext
    }

    fun decrypt(encryptedData: ByteArray, passphrase: String): ByteArray {
        val h = FILE_HEADER.size
        val minLength = h + SALT_LENGTH + GCM_IV_LENGTH + (GCM_TAG_LENGTH / 8)
        require(encryptedData.size >= minLength) { "Encrypted data too short" }
        // N9: reject unknown formats / versions before any KDF work.
        require(encryptedData.copyOfRange(0, h).contentEquals(FILE_HEADER)) {
            "Unsupported export format or version"
        }

        val salt = encryptedData.copyOfRange(h, h + SALT_LENGTH)
        val iv = encryptedData.copyOfRange(h + SALT_LENGTH, h + SALT_LENGTH + GCM_IV_LENGTH)
        val ciphertext = encryptedData.copyOfRange(h + SALT_LENGTH + GCM_IV_LENGTH, encryptedData.size)

        val secretKey = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        cipher.updateAAD(encryptedData, 0, h) // D5: header is authenticated
        return cipher.doFinal(ciphertext)
    }

    private fun getAppVersion(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
