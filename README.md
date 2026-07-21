# Edge Sentinel

Edge Sentinel is an on-device cellular-threat awareness app for Android. It uses
Android's public telephony, Wi-Fi, and Bluetooth APIs to watch the radio
environment around a phone and raise alerts for patterns commonly associated
with cell-site simulators ("IMSI catchers"/fake base stations), forced network
downgrades, and other suspicious cellular behavior. It runs fully on-device and
does not require any Edge Sentinel backend.

> **Status: pre-release / test builds.** Edge Sentinel is an experimental
> defensive research tool. It provides *indicators*, not proof. It cannot
> guarantee detection of any specific attack and can produce false positives and
> false negatives. Do not rely on it as your only protection in a high-risk
> situation. See [Limitations](#limitations) and
> [Responsible use](#responsible-use).

## What it does

- **Cell monitoring** — polls `TelephonyManager` cell info, tracks serving/neighbor
  cells (CID, LAC/TAC, MCC/MNC), and flags unexpected cell/identity churn.
- **Network-downgrade detection** — watches RAT changes (e.g. LTE/5G → 2G/3G)
  that can indicate a forced downgrade.
- **Signal / tower behavior heuristics** — looks for abnormally strong signals,
  missing neighbor lists, and reselection patterns while stationary.
- **5G NR awareness** — uses `CellInfoNr` / `CellSignalStrengthNr` on supported
  devices (Android 10+).
- **Wi-Fi and BLE surface checks** — optional scans that feed the fusion layer.
- **Composite threat scoring** — a six-category weighted model that maps
  indicators to a threat level, with an optional fusion layer for compound
  patterns. See [`ThreatScorer`](app/src/main/java/com/bp22intel/edgesentinel/detection/scoring/ThreatScorer.kt).
- **On-device alerting** — foreground monitoring service, alert history, and an
  alert-detail view with an optional map (OpenStreetMap tiles).

## What it does NOT do

- It does **not** read your SMS or message content. `RECEIVE_SMS` is not used.
- It does **not** decode baseband/DIAG traffic on a normal phone. The native
  DIAG bridge is **root + Qualcomm only** and is inactive on standard devices.
- **Silent-SMS (Type-0) detection is a stub** on non-rooted devices and is
  permanently inactive there; it exists to demonstrate the UI in demo mode.
- **Cipher-mode detection requires root and a Qualcomm baseband.**
- It does **not** phone home, upload telemetry, or use any cloud service or
  account. All analysis is local.

## Device requirements

- Android 8.0 (API 26) or newer.
- **Non-root tier (default):** works on standard Android using public APIs.
  Capability is limited by what the OS exposes to apps.
- **Root tier (optional):** DIAG-based and cipher-mode features require a rooted
  device with a Qualcomm baseband and `/dev/diag` access. These features fail
  safe (return errors) when root/Qualcomm are absent.

## Security model & privacy

- **Local-only.** No Edge Sentinel servers. External network use is limited to
  optional, user-initiated downloads of public tower databases (e.g. OpenCelliD,
  Mozilla Location Services export) and public map tiles. An OpenCelliD API token,
  if used, is entered by the user and stored in app preferences on-device.
- **No content interception.** Edge Sentinel observes radio *metadata* exposed by
  Android APIs; it does not read messages or calls.
- **Permissions** are limited to what monitoring requires (location for cell
  identification, phone state, Wi-Fi/Bluetooth scan, notifications, foreground
  service). Location is used on-device only.

## Limitations

- Non-root detection relies on OEM/Android API behavior, which varies widely by
  device and Android version; some indicators are unavailable on some phones.
- Heuristic detection produces false positives and false negatives.
- Several advanced features are root-only or stubbed (see "What it does NOT do").
- This is research-grade software, not a certified security product.

## Build

Requirements: JDK 17, Android SDK (compileSdk 35, build-tools 34/35), Android
NDK (for the native DIAG helper), CMake 3.22.x.

```bash
# from repo root
./gradlew :app:assembleDebug        # debug build
./gradlew :app:lint :app:testDebugUnitTest
```

Release builds require a signing key supplied out-of-tree (see
[Signing](#signing-releases)); without one the release build stays **unsigned**
by design.

## Signing releases

Signing credentials are **never** committed. Provide them via an untracked
`keystore.properties` at the repo root (see
[`keystore.properties.template`](keystore.properties.template)) or via
`EDGE_SENTINEL_*` environment variables. If no credentials are present, the
release build is intentionally left unsigned rather than embedding a key.

> **Installation warning:** Any pre-built APKs published as test builds are
> **debug-signed** and are not production artifacts. A prior release signing key
> was rotated; installs signed with the old key must be uninstalled before
> installing a build signed with the new key.

## License

Copyright © 2024–2026 BP22 Intel. All rights reserved. Edge Sentinel is
**proprietary, source-available** software; see [LICENSE](LICENSE). It is not an
open-source license and does not grant redistribution or reuse rights.

## Attribution

Edge Sentinel is an independent implementation. Its detection approach is
*informed by* public research and prior art in the cellular-security community —
including SRLabs' SnoopSnitch project and peer-reviewed academic work (NDSS,
ACSAC, IEEE S&P). No SnoopSnitch/SRLabs source code is included; the native
DIAG bridge is a clean-room implementation written against the public Qualcomm
DIAG ICD (80-V1294-1) and public MSM kernel interfaces. Bundled third-party
open-source libraries are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Responsible use

Edge Sentinel is intended for lawful, defensive, personal situational-awareness
use. You are responsible for complying with all applicable laws, including radio,
privacy, and wiretap regulations in your jurisdiction. Alerts are indicators for
your own awareness and are not evidence of a crime. See [SECURITY.md](SECURITY.md)
for how to report vulnerabilities.
