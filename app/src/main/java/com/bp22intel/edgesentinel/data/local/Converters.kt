/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.data.local

import androidx.room.TypeConverter
import com.bp22intel.edgesentinel.domain.model.Confidence
import com.bp22intel.edgesentinel.domain.model.NetworkType
import com.bp22intel.edgesentinel.domain.model.ThreatLevel
import com.bp22intel.edgesentinel.domain.model.ThreatType

class Converters {

    @TypeConverter
    fun fromNetworkType(value: NetworkType): String = value.name

    @TypeConverter
    fun toNetworkType(value: String): NetworkType =
        NetworkType.valueOf(value)

    @TypeConverter
    fun fromThreatType(value: ThreatType): String = value.name

    @TypeConverter
    fun toThreatType(value: String): ThreatType =
        ThreatType.valueOf(value)

    @TypeConverter
    fun fromThreatLevel(value: ThreatLevel): String = value.name

    @TypeConverter
    fun toThreatLevel(value: String): ThreatLevel =
        ThreatLevel.valueOf(value)

    @TypeConverter
    fun fromConfidence(value: Confidence): String = value.name

    @TypeConverter
    fun toConfidence(value: String): Confidence =
        Confidence.valueOf(value)
}
