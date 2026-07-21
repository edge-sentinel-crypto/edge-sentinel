/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.sensor

/**
 * Device motion state inferred from accelerometer data.
 */
enum class MotionState {
    /** Device is still — acceleration variance below threshold for 30+ seconds. */
    STATIONARY,

    /** Device shows moderate motion consistent with walking. */
    WALKING,

    /** Device shows high, sustained acceleration variance consistent with driving. */
    DRIVING,

    /** Motion state has not yet been determined (sensor warming up). */
    UNKNOWN
}
