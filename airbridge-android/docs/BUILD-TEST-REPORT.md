# AirBridge build and test report

Build date: 2026-11-22. Host: WSL2 Ubuntu 24.04.3 (x86_64, 24 cores), no sudo, no Android device
or emulator attached (the user is not in the `kvm` group, so no emulator could run).

## 1. Result

| Check | Result |
|---|---|
| `./gradlew clean testDebugUnitTest assembleDebug lintDebug` | BUILD SUCCESSFUL |
| Unit + loopback integration tests | **83 run, 83 passed, 0 failed, 0 skipped** |
| Android lint (`lintDebug`, `abortOnError = true`) | **No issues found** (0 errors, 0 warnings) |
| Optional UI screenshot test (Robolectric) | 4 run, 4 passed; PNGs in [`screenshots/`](../screenshots/) |
| `apksigner verify --verbose --print-certs` | **Verifies** (APK Signature Scheme v2), 1 signer, Android Debug certificate |
| `aapt2 dump badging` | `com.airbridge.airplay`, versionCode 1, versionName 1.0.0, minSdk 29, targetSdk 35, compileSdk 35 |
| Rebuild reproducibility | `assembleDebug` on the same source tree and debug key reproduced the shipped APK byte for byte (same SHA-256) |

Shipped APK: `AirBridge-debug.apk`, attached to the
[v0.1.0-debug release](https://github.com/edge-sentinel-crypto/airbridge-android/releases/tag/v0.1.0-debug), 29,120,672 bytes,
SHA-256 `ad9ed70a1c9a80bcd333da8b28783187b7635436d63e7a34f51068f0d78ec279`.

## 2. Toolchain (user space, no sudo)

| Component | Version | Integrity |
|---|---|---|
| JDK | Eclipse Temurin 17.0.21.1+2 (`OpenJDK17U-jdk_x64_linux_hotspot_17.0.21.1_2.tar.gz`) | sha256 `0b0ac5eab6db6abaec68c9913bcddb31321e5da411c45073d520d9c125e6f7b4` (matches Adoptium API) |
| Android cmdline-tools | 23.0 (`commandlinetools-linux-16111833_latest.zip`) | sha1 `e025545c62a8e64c7559119566a569fb1dec5f60` (matches repository2-3.xml) |
| SDK packages | `platforms;android-35` (r2), `build-tools;35.0.0`, `platform-tools` 37.0.3 | installed by sdkmanager |
| Gradle | 8.11.1 via wrapper | sha256 `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6` (pinned in `gradle-wrapper.properties`) |
| Android Gradle Plugin | 8.7.3 (`buildToolsVersion = "35.0.0"`) | Maven |
| Kotlin | 2.0.21 + Compose compiler plugin 2.0.21 | Maven |
| Libraries | Compose BOM 2024.12.01 (Material 3), activity-compose 1.9.3, lifecycle 2.8.7, core-ktx 1.15.0, coroutines 1.9.0, BouncyCastle `bcprov-jdk18on` 1.79 | Maven |
| Test libraries | JUnit 4.13.2, Robolectric 4.14.1 (only for the optional screenshot test) | Maven |

Environment file: `env.sh` in the toolchain folder sets `JAVA_HOME`,
`ANDROID_HOME`, `ANDROID_USER_HOME` (debug keystore) and `GRADLE_USER_HOME` inside the toolchain
folder. Exact setup commands: [INSTALL.md section 7](INSTALL.md#7-building-from-source).

Side effects outside the toolchain folder, reported for completeness: on its first run, sdkmanager
from cmdline-tools 23.0 created `~/.android/bin/android-cli`, `~/.android/cli/` and Java
preferences in `~/.java/.userPrefs/`. AirBridge does not need them; they can be deleted. All
later builds set `ANDROID_USER_HOME` and `GRADLE_USER_HOME` into the toolchain folder. AGP also
auto-installed `build-tools;34.0.0` into the toolchain SDK during the first build, before the
build was pinned to 35.0.0; it is unused.

## 3. Commands (reproducible)

```bash
. $HOME/android-toolchain/env.sh   # toolchain from INSTALL.md section 7
cd airbridge-android               # inside a clone, on the Linux file system
./gradlew clean testDebugUnitTest assembleDebug lintDebug
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest -Pairbridge.screenshots --tests '*UiScreenshotTest*'   # optional
```

The raw outputs of the original build (per-test summary, console logs, lint results, apksigner
and aapt2 dumps) are not included in this folder. Running the commands above produces them
again: test results in `app/build/test-results/` and `app/build/reports/tests/`, lint results
in `app/build/reports/`.

## 4. Test results

| Test class | Tests | Result | What it proves |
|---|---|---|---|
| `codec.AlacCodecTest` | 10 | pass | ALAC frame layout bit by bit (CPE tag, 12 unused bits, escape flag, END tag), 1412-byte frames, bit-exact round trips of sine, extremes and random PCM, partial and mono frames, rejection of truncated/compressed input, magic cookie = SDP fmtp, big-endian L16 |
| `codec.ExternalInteropTest` | 2 | pass | **FFmpeg 6.1.1** decodes a CAF file of AirBridge ALAC frames back to the exact PCM; **Python `plistlib`** reads AirBridge's bplist00 (nested dicts, 32-byte data, negative and 49-bit ints, UTF-16 strings) |
| `codec.BinaryPlistAndTlvTest` | 9 | pass | decodes a bplist written by Python `plistlib`; round trip of every type; 2-byte object refs; malformed input rejected; TLV8 fragmentation of 384-byte keys (255 + 129), exact multiples, separators, truncation |
| `crypto.SrpTest` | 6 | pass | SRP-6a matches the **HomeKit spec test vectors** (k, x, v, A, B, u, S, K, M1) for the 3072-bit group with SHA-512; server/client round trips with PIN 3939; wrong PIN rejected; zero B rejected |
| `crypto.HapCryptoTest` | 7 | pass | **RFC 8439** ChaCha20-Poly1305 vector, ChaCha with a HAP counter nonce vs Python `cryptography`, **RFC 5869** HKDF vector, HKDF-SHA-512 HAP labels vs Python, **RFC 7748** X25519, **RFC 8032** Ed25519, HAP length-prefixed framing (multi-frame, tamper and truncation detection) |
| `mdns.DiscoveryFixturesTest` | 11 | pass | real TXT records: Sonos One, HomePod, Apple TV 4K (PIN flag), AirPort Express gen 1/2/3 encryption and auth-setup rules, Sony AVR (AirPlay 1 only), Shairport-Sync (forced to AirPlay 1); feature bitmasks; merge by device id then IP; service loss; manual receivers; expiry |
| `mdns.MdnsMessageTest` | 5 | pass | DNS query encoding (QU bit), compressed names, pointer-loop rejection, PTR/SRV/TXT/A assembly, and the raw browser discovering a fake responder over real loopback UDP |
| `audio.AudioPipelineTest` | 6 | pass | ring buffer wrap and overflow counting, 2,000,000-sample concurrent producer/consumer with order and checksum, exact 352-frame chunks, meter dBFS (-3 dBFS full-scale sine), blocked-capture after 3 s of zeros with music active, drift corrector decisions |
| `airplay.ProtocolPrimitivesTest` | 13 | pass | RTSP serialization/parsing (RTSP and HTTP status lines), digest auth vs Python hashlib, Transport parsing, RTP/sync/timing/resend/retransmit byte layouts, retransmit ring, timeline math incl. 32-bit wrap and a year without overflow, NTP clock, volume mapping, AirPort public key hash, AES tail-in-clear rule |
| `integration.RaopLoopbackTest` | 5 | pass | complete **AirPlay 1** sessions over real TCP/UDP sockets against a mock receiver: plain ALAC; RSA-OAEP + AES encrypted; digest password + L16; missing/wrong password errors; 403 -> auth-setup. Each checks bit-exact decoded PCM, consecutive timestamps, marker bit, a forced packet loss recovered via resend/retransmit, sync latency 77,175, timing replies, volume, keep-alive, FLUSH, TEARDOWN |
| `integration.Ap2LoopbackTest` | 5 | pass | complete **AirPlay 2** sessions: GET /info -> transient pair-setup (3939) -> encrypted RTSP -> SETUP session (NTP) -> event channel -> RECORD -> SETUP stream (shk, ct, audioFormat) -> volume -> feedback -> ChaCha20-Poly1305 RTP decrypted and decoded bit-exact -> FLUSH -> TEARDOWN; PIN pairing (pair-pin-start, M1-M6, Pair-Verify) then saved-key Pair-Verify; wrong PIN; password; HTTP 470 -> PIN; L16 stream |
| `integration.FanOutTest` | 1 | pass | the real session manager streams one signal to an AirPlay 1 and an AirPlay 2 mock at once: both decode the exact PCM with identical RTP timestamps; volumes -9.0 and -21.0 dB stay independent; removing one speaker leaves the other playing; TEARDOWN on stop |
| `integration.SessionBehaviorTest` | 3 | pass | AirPlay 2 receiver rejecting NTP SETUP -> automatic fallback to AirPlay 1 with bit-exact audio; forced AirPlay 2 does not fall back; PIN prompt -> wrong code -> receiver shows a new code -> right code -> streaming |
| **Total** | **83** | **83 pass** | |

Optional `ui.UiScreenshotTest` (4 tests, Robolectric native graphics, Android 14 runtime): renders
the real Compose screen with sample data and the app icons; the PNGs are in
[`screenshots/`](../screenshots/). These images were reviewed visually.

## 5. Independent cross-checks used during the build

- **Apple HomeKit ADK source** (`PAL/Crypto/OpenSSL/HAPOpenSSL.c`) for the exact SRP padding rules;
  confirmed by the HAP spec vectors in `SrpTest`.
- **owntone** (`src/outputs/airplay.c`, `raop.c`, `pair_ap/pair_homekit.c`) and **pyatv**
  (`protocols/raop/protocols/airplayv2.py`, `support/rtsp.py`) for SETUP bodies, request order,
  X-Apple-HKP values, status-flag decisions, event-channel key direction, audio packet sealing and
  `shk`. **Shairport-Sync** (`rtp.c`, `rtsp.c`) for sync-packet latency semantics, retransmit
  handling, realtime decryption and the fact that its AirPlay 2 mode refuses NTP timing.
- The AirPort Express RSA modulus was compared with owntone's constant and with the modulus of
  Shairport-Sync's receiver key; both match. No private key is used or shipped.
- FFmpeg and Python are run by `ExternalInteropTest` when present (they were present: FFmpeg
  6.1.1, Python 3.12).

## 6. APK inspection

- `apksigner`: Verifies. v2 scheme true (v1 not needed for minSdk 29). Signer DN
  `C=US, O=Android, CN=Android Debug`, certificate SHA-256
  `16594d8e1d8c02e8acb65785346cdcfd352e495289ae772173c4bcaee3528dfe`, RSA 2048.
- `aapt2 dump badging`: package `com.airbridge.airplay`, versionCode 1, versionName 1.0.0,
  minSdkVersion 29, targetSdkVersion 35, compileSdkVersion 35, launchable activity
  `com.airbridge.airplay.MainActivity`, label AirBridge, `application-debuggable`.
- Permissions: INTERNET, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, ACCESS_NETWORK_STATE,
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION, FOREGROUND_SERVICE_MEDIA_PLAYBACK,
  POST_NOTIFICATIONS, WAKE_LOCK, RECORD_AUDIO (required by Android for playback capture),
  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, and AndroidX's internal
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
- Manifest: `AudioCaptureService` `exported=false`, `foregroundServiceType=0x22`
  (mediaPlayback 0x02 | mediaProjection 0x20); application `allowAudioPlaybackCapture=false`,
  `allowBackup=false`, data extraction rules exclude all data. No `PreviewActivity` (UI tooling not
  shipped).
- Contents: 14 dex files (Compose + BouncyCastle, no minification in the debug build),
  `libandroidx.graphics.path.so` for 4 ABIs. No network URLs in app sources; no API keys.

## 7. What was tested on loopback versus what needs real hardware

Tested on the build machine, over real sockets on 127.0.0.1 against mock receivers written from the
protocol sources above: the complete AirPlay 1 and AirPlay 2 message sequences, pairing and
encryption, packet formats, retransmission, timing and sync packets, volume, multi-receiver
fan-out, fallback and PIN prompts, discovery parsing and the raw mDNS browser.

The mock receivers share AirBridge's understanding of the protocol, so the loopback tests prove
internal consistency and correct implementation of that understanding. The parts that are pinned
to external truth are the published test vectors, FFmpeg, Python, and the reference
implementations' source.

Not tested (no phone, emulator or speaker was available for the build), so it needs real hardware:

1. Each real speaker accepting the session, especially AirPlay 2 with NTP timing (Sonos, HomePod,
   Apple TV, WiiM, Denon/Marantz). AirPlay 1 fallback exists for speakers that refuse.
2. Android behaviour: the capture consent flow, the foreground service with the screen off,
   battery restrictions, capture ending on screen lock (varies by Android version and brand).
3. Real Wi-Fi: loss, latency, multicast discovery through a real router or mesh.
4. Audible sync between several speakers, especially AirPlay 1 mixed with AirPlay 2.
5. That Spotify triggers the blocked-capture warning on a real phone, as expected.

## 8. SHA-256 of the released APK and of binary files in this folder

| File | SHA-256 |
|---|---|
| `AirBridge-debug.apk` ([v0.1.0-debug release](https://github.com/edge-sentinel-crypto/airbridge-android/releases/tag/v0.1.0-debug)) | `ad9ed70a1c9a80bcd333da8b28783187b7635436d63e7a34f51068f0d78ec279` |
| [`screenshots/00-launcher-icon.png`](../screenshots/00-launcher-icon.png) | `5296ca14457f4b1fbe9bde63b4c7c91851d3b81baaeee6dc34e0aed7a93c4051` |
| [`screenshots/00-status-icon.png`](../screenshots/00-status-icon.png) | `4f38c451300cdaef2b7e305787a0ae31a7368dafdf45645b35d490075c1d8095` |
| [`screenshots/01-ready.png`](../screenshots/01-ready.png) | `f6c7dbda3cb27c67375ed73288bb0fc7753068a4dacb81290e50c3b8bd7ebf9c` |
| [`screenshots/02-streaming.png`](../screenshots/02-streaming.png) | `7b794387d8ce391967458db66ccd582d4f3781bef3e5f06579c61d0c035ef44c` |
| [`screenshots/03-capture-blocked.png`](../screenshots/03-capture-blocked.png) | `50b0e864426c834509f43446dcde8a3258e90fcc9418cd242c559de8041d4ee3` |
| `gradle/wrapper/gradle-wrapper.jar` | `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046` |

The `gradle-wrapper.jar` hash equals Gradle's published checksum for 8.11.1
(`https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar.sha256`).
