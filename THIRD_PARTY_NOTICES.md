# Third-Party Notices

Edge Sentinel bundles or depends on the following third-party open-source
components. Each remains under its own license held by its respective copyright
holders. This file is provided for attribution and compliance; it does not
relicense Edge Sentinel itself (see LICENSE).

## Apache License 2.0

The following components are licensed under the Apache License, Version 2.0
(https://www.apache.org/licenses/LICENSE-2.0):

- AndroidX / Jetpack libraries — © The Android Open Source Project
  - androidx.core:core-ktx
  - androidx.activity:activity-compose
  - androidx.compose (BOM: ui, ui-graphics, ui-tooling, material3,
    material-icons-extended, ui-test-*)
  - androidx.lifecycle:lifecycle-runtime-compose, -viewmodel-compose, -service
  - androidx.navigation:navigation-compose
  - androidx.hilt:hilt-navigation-compose, hilt-work, hilt-compiler
  - androidx.room:room-runtime, -ktx, -compiler, -testing
  - androidx.work:work-runtime-ktx
  - androidx.datastore:datastore-preferences
  - androidx.core:core-splashscreen
  - androidx.glance:glance-appwidget, glance-material3
  - androidx.test.ext:junit, androidx.test.espresso:espresso-core
- Dagger Hilt (com.google.dagger:hilt-android, hilt-android-compiler)
  — © Google LLC
- Kotlin standard library and Kotlin Gradle/Compose tooling
  — © JetBrains s.r.o. and contributors (Apache-2.0)

## Eclipse Public License 1.0

- JUnit 4 (junit:junit) — © the JUnit contributors (EPL-1.0)

## Apache License 2.0 (mapping)

- osmdroid (org.osmdroid:osmdroid-android) — © osmdroid contributors (Apache-2.0)

## Map tiles and data services (used at runtime, not bundled)

- OpenStreetMap tile data — © OpenStreetMap contributors, ODbL. Map tiles are
  fetched from public tile servers subject to their usage policies.
- OpenCelliD and Mozilla Location Services exports — public cell-tower datasets,
  used only when the user chooses to download them, under their respective terms.

Full license texts are available from each project. Maintain this file and an
SBOM as dependencies change.
