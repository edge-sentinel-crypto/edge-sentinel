/*
 * Edge Sentinel — Cellular Threat Detection for Android
 * Copyright (C) 2024-2026 BP22 Intel. All Rights Reserved.
 *
 * This software is proprietary and confidential. Unauthorized copying,
 * modification, distribution, or use of this software, in whole or in
 * part, is strictly prohibited without prior written permission from
 * BP22 Intel.
 */

package com.bp22intel.edgesentinel.di

import com.bp22intel.edgesentinel.detection.detectors.BehavioralBaselineDetector
import com.bp22intel.edgesentinel.detection.detectors.CipherModeDetector
import com.bp22intel.edgesentinel.detection.detectors.FakeBtsDetector
import com.bp22intel.edgesentinel.detection.detectors.NetworkDowngradeDetector
import com.bp22intel.edgesentinel.detection.detectors.NrDetector
import com.bp22intel.edgesentinel.detection.detectors.RegistrationFailureDetector
import com.bp22intel.edgesentinel.detection.detectors.SilentSmsDetector
import com.bp22intel.edgesentinel.detection.detectors.ThreatDetector
import com.bp22intel.edgesentinel.detection.detectors.TrackingPatternDetector
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class DetectionModule {

    @Binds
    @IntoSet
    abstract fun bindFakeBtsDetector(impl: FakeBtsDetector): ThreatDetector

    @Binds
    @IntoSet
    abstract fun bindNetworkDowngradeDetector(impl: NetworkDowngradeDetector): ThreatDetector

    @Binds
    @IntoSet
    abstract fun bindSilentSmsDetector(impl: SilentSmsDetector): ThreatDetector

    @Binds
    @IntoSet
    abstract fun bindTrackingPatternDetector(impl: TrackingPatternDetector): ThreatDetector

    @Binds
    @IntoSet
    abstract fun bindCipherModeDetector(impl: CipherModeDetector): ThreatDetector

    @Binds
    @IntoSet
    abstract fun bindNrDetector(impl: NrDetector): ThreatDetector

    @Binds
    @IntoSet
    abstract fun bindRegistrationFailureDetector(impl: RegistrationFailureDetector): ThreatDetector

    @Binds
    @IntoSet
    abstract fun bindBehavioralBaselineDetector(impl: BehavioralBaselineDetector): ThreatDetector
}
