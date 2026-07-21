# Contributing to Edge Sentinel

Thanks for your interest. Edge Sentinel is **proprietary, source-available**
software owned by BP22 Intel (see [LICENSE](LICENSE)). The source is published
for transparency and review; it is not open source and contributions are
accepted only under the terms below.

## Before contributing

- By submitting a contribution (patch, PR, or suggestion), you agree that BP22
  Intel may use it under the project's proprietary license, and you represent
  that you have the right to submit it and that it is your original work.
- Do **not** submit code copied from GPL/AGPL/other copyleft projects or from any
  source you are not authorized to relicense. Edge Sentinel is an independent,
  clean-room implementation and must remain free of incompatible third-party code.

## Ground rules

- **Never commit secrets.** No keystores, passwords, tokens, or
  `keystore.properties`. These are gitignored — keep it that way.
- **No inflated claims.** Keep capability descriptions accurate; clearly mark
  stubs, demo-mode, and root-only features.
- **Security issues** go through [SECURITY.md](SECURITY.md), not public issues.

## Development

- Requirements: JDK 17, Android SDK (compileSdk 35), NDK, CMake 3.22.x.
- Build and check:
  ```bash
  ./gradlew :app:assembleDebug :app:lintVitalRelease :app:testDebugUnitTest
  ```
  `lintVitalRelease` is the release-blocking lint gate and must pass. The
  broader `./gradlew :app:lint` analysis pass is optional and currently emits
  advisory-only findings that do not block a build.
- Match the existing architecture (Clean Architecture: data / domain / detection
  / ui, Hilt DI, Room, WorkManager) and keep source headers consistent with the
  proprietary license.

## Reporting bugs

Open an issue with device model, Android version, build number, and clear
reproduction steps. For anything security-sensitive, use the private channel in
[SECURITY.md](SECURITY.md).
