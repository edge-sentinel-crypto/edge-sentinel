/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.detection.bluetooth

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identifies known BLE tracker protocols from scan records.
 *
 * Supported trackers:
 * - Apple AirTag (Find My network): Service UUID 0x7DFC, manufacturer ID 0x004C
 * - Samsung SmartTag: manufacturer ID 0x0075
 * - Tile: manufacturer ID 0x0059
 * - Generic Find My network devices (Apple manufacturer data with tracker payload)
 */
@Singleton
class BleTrackerIdentifier @Inject constructor() {

    companion object {
        /** Apple Find My network service UUID. */
        val APPLE_FIND_MY_SERVICE_UUID: ParcelUuid =
            ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

        /** Apple AirTag nearby service UUID (0x7DFC). */
        val AIRTAG_NEARBY_SERVICE_UUID: ParcelUuid =
            ParcelUuid.fromString("00007DFC-0000-1000-8000-00805F9B34FB")

        /** Apple manufacturer ID. */
        const val APPLE_MANUFACTURER_ID = 0x004C

        /** Samsung manufacturer ID. */
        const val SAMSUNG_MANUFACTURER_ID = 0x0075

        /** Tile manufacturer ID. */
        const val TILE_MANUFACTURER_ID = 0x0059

        /** Apple Find My advertisement type byte for separated devices. */
        private const val FIND_MY_SEPARATED_TYPE: Byte = 0x07
    }

    /**
     * Result of tracker identification.
     */
    data class TrackerIdentification(
        val protocol: TrackerProtocol,
        val confidence: Float,
        val details: String
    )

    /**
     * Known tracker protocols.
     */
    enum class TrackerProtocol(val displayName: String) {
        APPLE_AIRTAG("Apple AirTag"),
        APPLE_FIND_MY("Apple Find My"),
        SAMSUNG_SMARTTAG("Samsung SmartTag"),
        TILE("Tile"),
        GENERIC_TRACKER("Unknown Tracker")
    }

    /**
     * Analyze a BLE scan record to determine if it matches a known tracker protocol.
     *
     * @param scanRecord The BLE scan record from a scan result
     * @return Identification result if a tracker is detected, null otherwise
     */
    fun identify(scanRecord: ScanRecord?): TrackerIdentification? {
        if (scanRecord == null) return null

        // Check Apple AirTag / Find My first (most common tracker)
        identifyAppleTracker(scanRecord)?.let { return it }

        // Check Samsung SmartTag
        identifySamsungSmartTag(scanRecord)?.let { return it }

        // Check Tile
        identifyTile(scanRecord)?.let { return it }

        return null
    }

    /**
     * Identify Apple AirTag or Find My network devices.
     *
     * AirTag identification:
     * - Service UUID 0x7DFC in scan record
     * - Manufacturer ID 0x004C (Apple)
     * - Manufacturer data contains status byte indicating separated state
     */
    private fun identifyAppleTracker(scanRecord: ScanRecord): TrackerIdentification? {
        val serviceUuids = scanRecord.serviceUuids
        val appleData = scanRecord.getManufacturerSpecificData(APPLE_MANUFACTURER_ID)

        // Direct AirTag nearby advertisement (0x7DFC service UUID)
        if (serviceUuids != null && AIRTAG_NEARBY_SERVICE_UUID in serviceUuids) {
            return TrackerIdentification(
                protocol = TrackerProtocol.APPLE_AIRTAG,
                confidence = 0.95f,
                details = "AirTag nearby advertisement detected (service UUID 0x7DFC)"
            )
        }

        // Apple Find My network advertisement (0xFE2C service UUID)
        if (serviceUuids != null && APPLE_FIND_MY_SERVICE_UUID in serviceUuids) {
            return TrackerIdentification(
                protocol = TrackerProtocol.APPLE_FIND_MY,
                confidence = 0.90f,
                details = "Apple Find My network device detected (service UUID 0xFE2C)"
            )
        }

        // Apple manufacturer data with tracker payload
        if (appleData != null && appleData.size >= 3) {
            val type = appleData[0]

            // Type 0x07 indicates a separated Find My accessory (AirTag away from owner)
            if (type == FIND_MY_SEPARATED_TYPE) {
                val statusByte = appleData[2]
                val isSeparated = (statusByte.toInt() and 0x04) != 0
                return TrackerIdentification(
                    protocol = TrackerProtocol.APPLE_AIRTAG,
                    confidence = if (isSeparated) 0.95f else 0.80f,
                    details = buildString {
                        append("Apple tracker payload: type=0x%02X".format(type))
                        append(", status=0x%02X".format(statusByte))
                        if (isSeparated) append(" (SEPARATED from owner)")
                    }
                )
            }

            // Type 0x12 is a generic Find My device advertisement
            if (type == 0x12.toByte() && appleData.size >= 25) {
                return TrackerIdentification(
                    protocol = TrackerProtocol.APPLE_FIND_MY,
                    confidence = 0.85f,
                    details = "Apple Find My network device (type 0x12, ${appleData.size} bytes payload)"
                )
            }
        }

        return null
    }

    /**
     * Identify Samsung SmartTag devices.
     *
     * SmartTag identification:
     * - Manufacturer ID 0x0075 (Samsung)
     * - Specific payload structure in manufacturer data
     */
    private fun identifySamsungSmartTag(scanRecord: ScanRecord): TrackerIdentification? {
        val samsungData = scanRecord.getManufacturerSpecificData(SAMSUNG_MANUFACTURER_ID)
            ?: return null

        // Samsung SmartTag advertisements typically have manufacturer data >= 14 bytes
        if (samsungData.size >= 14) {
            return TrackerIdentification(
                protocol = TrackerProtocol.SAMSUNG_SMARTTAG,
                confidence = 0.90f,
                details = "Samsung SmartTag detected (manufacturer ID 0x0075, ${samsungData.size} bytes payload)"
            )
        }

        // Shorter Samsung advertisements may still be SmartTag
        if (samsungData.size >= 4) {
            return TrackerIdentification(
                protocol = TrackerProtocol.SAMSUNG_SMARTTAG,
                confidence = 0.60f,
                details = "Possible Samsung SmartTag (manufacturer ID 0x0075, ${samsungData.size} bytes payload)"
            )
        }

        return null
    }

    /**
     * Identify Tile tracker devices.
     *
     * Tile identification:
     * - Manufacturer ID 0x0059
     * - Tile-specific service UUIDs
     */
    private fun identifyTile(scanRecord: ScanRecord): TrackerIdentification? {
        val tileData = scanRecord.getManufacturerSpecificData(TILE_MANUFACTURER_ID)

        if (tileData != null) {
            return TrackerIdentification(
                protocol = TrackerProtocol.TILE,
                confidence = 0.85f,
                details = "Tile tracker detected (manufacturer ID 0x0059, ${tileData.size} bytes payload)"
            )
        }

        // Some Tile devices use service UUID instead of manufacturer data
        val serviceUuids = scanRecord.serviceUuids
        if (serviceUuids != null) {
            val tileServiceUuid = ParcelUuid.fromString("0000FEED-0000-1000-8000-00805F9B34FB")
            if (tileServiceUuid in serviceUuids) {
                return TrackerIdentification(
                    protocol = TrackerProtocol.TILE,
                    confidence = 0.80f,
                    details = "Tile tracker detected (service UUID 0xFEED)"
                )
            }
        }

        return null
    }

    /**
     * Get the manufacturer ID from a scan record, if present.
     */
    fun getManufacturerId(scanRecord: ScanRecord?): Int? {
        if (scanRecord == null) return null
        val bytes = scanRecord.bytes ?: return null

        // Parse manufacturer specific data from raw bytes
        // Format: length, type (0xFF), company_id_low, company_id_high, data...
        var i = 0
        while (i < bytes.size - 1) {
            val length = bytes[i].toInt() and 0xFF
            if (length == 0 || i + length >= bytes.size) break
            val type = bytes[i + 1].toInt() and 0xFF
            if (type == 0xFF && length >= 3) {
                val companyId = (bytes[i + 2].toInt() and 0xFF) or
                    ((bytes[i + 3].toInt() and 0xFF) shl 8)
                return companyId
            }
            i += length + 1
        }
        return null
    }
}
