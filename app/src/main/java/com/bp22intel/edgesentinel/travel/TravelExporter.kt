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
        private const val PBKDF2_ITERATIONS = 310_000
        private const val SALT_LENGTH = 32
        private const val EXPORT_DIR = "travel_exports"
    }

    fun exportEncrypted(passphrase: String): Uri? {
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

    fun exportPlaintext(): Uri? {
        val exportData = gatherExportData()

        val exportDir = File(context.filesDir, EXPORT_DIR)
        exportDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportFile = File(exportDir, "es_travel_export_$timestamp.json")
        exportFile.writeText(exportData.toString(2))

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )
    }

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

        return root
    }

    private fun gatherAlerts(): JSONArray {
        val alerts = JSONArray()
        try {
            val dbFile = context.getDatabasePath("edge_sentinel_database")
            if (dbFile.exists()) {
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )
                db.use {
                    val cursor = it.rawQuery("SELECT * FROM alerts ORDER BY timestamp DESC", null)
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
            }
        } catch (_: Exception) {
            // Database may not be available
        }
        return alerts
    }

    private fun gatherScans(): JSONArray {
        val scans = JSONArray()
        try {
            val dbFile = context.getDatabasePath("edge_sentinel_database")
            if (dbFile.exists()) {
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )
                db.use {
                    val cursor = it.rawQuery("SELECT * FROM scans ORDER BY timestamp DESC", null)
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
            }
        } catch (_: Exception) {
            // Database may not be available
        }
        return scans
    }

    private fun gatherCellTowers(): JSONArray {
        val towers = JSONArray()
        try {
            val dbFile = context.getDatabasePath("edge_sentinel_database")
            if (dbFile.exists()) {
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )
                db.use {
                    val cursor = it.rawQuery("SELECT * FROM cell_towers ORDER BY last_seen DESC", null)
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
            }
        } catch (_: Exception) {
            // Database may not be available
        }
        return towers
    }

    private fun gatherThreatSummary(): JSONObject {
        val summary = JSONObject()
        try {
            val dbFile = context.getDatabasePath("edge_sentinel_database")
            if (dbFile.exists()) {
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )
                db.use {
                    val cursor = it.rawQuery(
                        "SELECT threat_type, COUNT(*) as count FROM alerts GROUP BY threat_type",
                        null
                    )
                    cursor.use { c ->
                        while (c.moveToNext()) {
                            summary.put(c.getString(0) ?: "unknown", c.getInt(1))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Database may not be available
        }
        return summary
    }

    private fun encrypt(data: ByteArray, passphrase: String): ByteArray {
        val random = SecureRandom()

        // Generate salt for key derivation
        val salt = ByteArray(SALT_LENGTH)
        random.nextBytes(salt)

        // Derive key from passphrase using PBKDF2
        val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_SIZE)
        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = keyFactory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")

        // Generate IV for GCM
        val iv = ByteArray(GCM_IV_LENGTH)
        random.nextBytes(iv)

        // Encrypt with AES-256-GCM
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(data)

        // Combine: salt + iv + ciphertext (includes GCM tag)
        return salt + iv + ciphertext
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
