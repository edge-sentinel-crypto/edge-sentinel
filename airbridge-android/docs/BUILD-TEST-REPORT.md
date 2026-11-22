# AirBridge build and test report

Version 0.2.0 (versionCode 2), release `v0.2.0-debug`. Build date: 2026-11-22. Host: WSL2 Ubuntu
24.04.3 (x86_64, 24 cores), no sudo, no Android device or emulator attached (the user is not in the
`kvm` group, so no emulator could run).

## 1. Result

| Check | Result |
|---|---|
| `./gradlew clean testDebugUnitTest assembleDebug lintDebug` | BUILD SUCCESSFUL |
| Unit + loopback integration tests | **115 run, 115 passed, 0 failed, 0 skipped** (the 83 tests of v0.1.0 plus 32 new ones) |
| Android lint (`lintDebug`, `abortOnError = true`) | **No issues found** (0 errors, 0 warnings) |
| Optional UI screenshot test (Robolectric) | 6 run, 6 passed; PNGs in [`screenshots/`](../screenshots/) |
| `apksigner verify --verbose --print-certs` | **Verifies** (APK Signature Scheme v2), 1 signer, Android Debug certificate, the same certificate as v0.1.0-debug |
| `aapt2 dump badging` | `com.airbridge.airplay`, versionCode 2, versionName 0.2.0, minSdk 29, targetSdk 35, compileSdk 35 |
| Rebuild reproducibility | a clean `git archive` export of the published sources, built with the same toolchain and debug key, reproduced the released APK byte for byte (same SHA-256), both from the `airbridge-android` repository and from the `airbridge-android/` folder of the `edge-sentinel` repository |

Released APK: `AirBridge-debug.apk` in the GitHub release [v0.2.0-debug](https://github.com/edge-sentinel-crypto/airbridge-android/releases/tag/v0.2.0-debug),
29,284,516 bytes, SHA-256 `d4f047de85ba79afa138434ab0897aa55f9828c3cf446b09ea9cceb76c3ee0e2`.

## 2. Toolchain (user space, one folder, no sudo)

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

Environment file: `env.sh` in the toolchain folder sets `JAVA_HOME`, `ANDROID_HOME`,
`ANDROID_USER_HOME` (debug keystore) and `GRADLE_USER_HOME` inside the toolchain folder. Exact
setup commands: [INSTALL.md section 7](INSTALL.md#7-building-the-apk-from-source).

Side effects outside the toolchain folder, reported for completeness: on its first run, sdkmanager
from cmdline-tools 23.0 created `~/.android/bin/android-cli`, `~/.android/cli/` and Java
preferences in `~/.java/.userPrefs/`. AirBridge does not need them; they can be deleted. All later
builds set `ANDROID_USER_HOME` and `GRADLE_USER_HOME` into the toolchain folder. AGP also
auto-installed `build-tools;34.0.0` into the toolchain SDK during the first build, before the build
was pinned to 35.0.0; it is unused.

## 3. Commands (reproducible)

Run from the project folder (the root of the `airbridge-android` repository, or the
`airbridge-android/` folder of the `edge-sentinel` repository), on a Linux file system, after
setting up the toolchain ([INSTALL.md section 7](INSTALL.md#7-building-the-apk-from-source)):

```bash
. $HOME/android-toolchain/env.sh
./gradlew clean testDebugUnitTest assembleDebug lintDebug
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest -Pairbridge.screenshots --tests '*UiScreenshotTest*'   # optional
```

The raw outputs of the 2026-11-22 runs (per-test summary, console logs, lint, apksigner and aapt2
output) are not included in this repository; the tables in this report summarize them.

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
| `airplay.SyncOffsetTest` (new) | 5 | pass | sync offset -> 44.1 kHz frames for every 5 ms step from -250 to +250 ms against exact decimal arithmetic (±250 ms = ±11,025 frames, ±5 ms = ±221, symmetric, monotonic); clamping of out-of-range values; snapping to 5 ms; announced latency 66,150..88,200 frames around the default 77,175 and the 22,050-frame floor; labels "+40 ms (delay)", "-25 ms (earlier)", "0 ms" |
| `multiroom.MasterVolumeTest` (new) | 7 | pass | master = loudest speaker that is on; proportional scaling with the loudest speaker exactly on the slider; drag down to 0, up to 100 and back restores the exact start (chained step-by-step scaling would not); all speakers at 0 rise evenly; clamping of master and stored values; single speaker; the master display is derived and a drag snapshot is not changed by later speaker moves |
| `multiroom.SpeakerGroupTest` (new) | 8 | pass | group storage round trips names and ids with Unicode, emoji, quotes, pipes, commas, colons, line breaks, NUL and empty strings; damaged lines, wrong version and duplicate ids are dropped; save / update by name (any case) / rename / delete rules, empty names and empty groups refused, 24-group cap; name cleaning (40 characters, emoji not split); offline members skipped; group matching |
| `ui.MainUiStateTest` (new) | 3 | pass | the screen state derives the master value (loudest speaker that is on), the All on / All off label and each group chip's match and available/offline counts |
| `session.SessionOptionsTest` (new) | 1 | pass | a session hands the speaker's sync offset to its sender before connecting; a volume or sync-offset change made while connecting reaches the sender once connected; later changes go straight to the sender without a reconnect |
| `integration.SyncOffsetLoopbackTest` (new) | 3 | pass | real sockets: the first sync packet of an **AirPlay 1** and of an **AirPlay 2** session carries the configured offset (rtpNow minus the shifted rtpLessLatency = 77,175 + offset frames, and rtpNow matches the shared timeline at the packet's NTP time); a live `setSyncOffsetMs` sends one regular sync packet with the new latency on the very next tick (less than 1 s after the previous one), with clamping to ±250 ms; audio stays bit-exact with consecutive timestamps on the same session, with no new ANNOUNCE/SETUP/RECORD, pairing, FLUSH or TEARDOWN; through the session manager, two speakers (AirPlay 1 + AirPlay 2) get independent offsets and a live change reaches only its speaker |
| `integration.MultiRoomLoopbackTest` (new) | 5 | pass | the multi-room controller with the real session manager and mock speakers (one AirPlay 2, three AirPlay 1): All on connects every speaker and All off tears every one down while the stream keeps running; a saved group applied mid-stream skips its offline member, disconnects the other speaker and keeps the shared speaker on its session, then picks up the member once it is back; a switched-on speaker that appears joins by itself; groups saved, renamed, deleted and read back after a restart; master drag on live speakers (60/30 -> 30/15 -> mute -> back to exactly 60/30), saved on release, a speaker's own slider touches only that speaker, all-zero rises evenly; sync offset applied live, saved, and used again after the speaker reconnects |
| **Total** | **115** | **115 pass** | |

Optional `ui.UiScreenshotTest` (6 tests, Robolectric native graphics, Android 14 runtime): renders
the real Compose screen with sample data, the sync offset dialog and the app icons to
[`screenshots/`](../screenshots/). The sample speaker addresses are from the 192.0.2.0/24
documentation range. These images were reviewed visually.

## 5. Independent cross-checks used during the build

- **Sync offset semantics.** Shairport-Sync's `rtp.c` computes the latency from every sync packet
  as rtpNow minus rtpLessLatency (plus its fixed 11,025-frame offset for the 0x07 flag) and adopts
  a new value when it changes mid-stream; a larger value schedules each frame later. AirBridge's
  offset therefore changes only that difference; the RTP timestamps of the audio do not change.
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
- Upgrade path: the signer certificate is the same as the one of the published v0.1.0-debug APK
  (checked on the downloaded release file), the package name is unchanged and the version code
  goes from 1 to 2, so Android installs v0.2.0-debug over v0.1.0-debug as an update and keeps the
  app's data.
- `aapt2 dump badging`: package `com.airbridge.airplay`, versionCode 2, versionName 0.2.0,
  minSdkVersion 29, targetSdkVersion 35, compileSdkVersion 35, launchable activity
  `com.airbridge.airplay.MainActivity`, label AirBridge, `application-debuggable`.
- Permissions (unchanged from v0.1.0): INTERNET, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, ACCESS_NETWORK_STATE,
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

Tested on the build host, over real sockets on 127.0.0.1 against mock receivers written from the
protocol sources above: the complete AirPlay 1 and AirPlay 2 message sequences, pairing and
encryption, packet formats, retransmission, timing and sync packets, volume, multi-receiver
fan-out, fallback and PIN prompts, discovery parsing and the raw mDNS browser, and the v0.2.0
multi-room controls on live mock speakers (master volume, sync offset on connect and mid-stream,
groups, All on / All off).

The mock receivers share AirBridge's understanding of the protocol, so the loopback tests prove
internal consistency and correct implementation of that understanding. The parts that are pinned
to external truth are the published test vectors, FFmpeg, Python, and the reference
implementations' source.

Not tested (no phone, emulator or speaker was available), so it needs real hardware:

1. Each real speaker accepting the session, especially AirPlay 2 with NTP timing (Sonos, HomePod,
   Apple TV, WiiM, Denon/Marantz). AirPlay 1 fallback exists for speakers that refuse.
2. Android behaviour: the capture consent flow, the foreground service with the screen off,
   battery restrictions, capture ending on screen lock (varies by Android version and brand).
3. Real Wi-Fi: loss, latency, multicast discovery through a real router or mesh.
4. Audible sync between several speakers, especially AirPlay 1 mixed with AirPlay 2, and how each
   receiver follows a sync-offset change mid-stream (smoothly, or with a short skip), including
   AirPlay 2 receivers given more total delay than the `latencyMax` in their stream SETUP.
5. That Spotify triggers the blocked-capture warning on a real phone, as expected.
6. The new multi-room UI by touch on a phone (chips, long-press, sliders, dialogs); on the JVM it
   was checked through rendered screenshots and screen-state tests only.

## 8. SHA-256 manifest

| File | SHA-256 |
|---|---|
| `AirBridge-debug.apk` (v0.2.0-debug release asset, 29,284,516 bytes) | `d4f047de85ba79afa138434ab0897aa55f9828c3cf446b09ea9cceb76c3ee0e2` |
| `screenshots/00-launcher-icon.png` (unchanged) | `5296ca14457f4b1fbe9bde63b4c7c91851d3b81baaeee6dc34e0aed7a93c4051` |
| `screenshots/00-status-icon.png` (unchanged) | `4f38c451300cdaef2b7e305787a0ae31a7368dafdf45645b35d490075c1d8095` |
| `screenshots/01-ready.png` | `922b190e5e79e6e7beced71fdffaf2f3e59ee319fca6f960b226027e03e38827` |
| `screenshots/02-streaming.png` | `b107b69fd3f7581b88cab58122da323a226ef8b1f27b0db6688c00a940385296` |
| `screenshots/03-capture-blocked.png` | `9fc73bb600deb352589e9cd9a4b4f369cd5d9de8c36a3bee25b5833136c30b74` |
| `screenshots/04-multi-room.png` | `843afdc5833bb127e539bca1536e5f55fe06813429653d04d86cadf7c634ffde` |
| `screenshots/05-sync-offset.png` | `cb4b5562c752cba0c52dfce044b41157be79a35522f25321bb6dad65d5efec68` |
| `gradle/wrapper/gradle-wrapper.jar` | `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046` |

The PNG files contain only the IHDR, sRGB, sBIT, IDAT and IEND chunks (no text, EXIF or time
metadata).

The `gradle-wrapper.jar` hash equals Gradle's published checksum for 8.11.1
(`https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar.sha256`).
The working documents and raw logs of the original build are not part of this repository, so their
hashes are not listed.

## 9. Changes in v0.2.0 and the re-check of the published sources

What changed since v0.1.0-debug (APK SHA-256
`ad9ed70a1c9a80bcd333da8b28783187b7635436d63e7a34f51068f0d78ec279`):

- New: master volume that keeps the balance (`multiroom/MasterVolume.kt`), per-speaker sync
  offset wired from the settings to the RTP sync packets (`airplay/SyncOffset.kt`, `RtpStreamer`,
  `RaopSender`, `Ap2Sender`, `ReceiverSession`, `AirPlaySessionManager`), saved speaker groups and
  All on / All off (`multiroom/SpeakerGroup.kt`, `multiroom/MultiRoomController.kt`), and their UI
  (multi-room bar, master volume card, sync offset badge and dialog, group dialogs).
- Speaker selection, volumes, protocol choices and passwords moved from `AirBridgeController` into
  the Android-free `MultiRoomController`, so the loopback tests drive the same code as the app.
- Robustness fixes found while adding the master volume: volume commands are coalesced per
  speaker; a volume change while the speaker's sender is closing no longer throws; a volume or
  sync-offset change made while a speaker is connecting is applied once it is connected; a
  session's options are updated under a lock so concurrent changes are not lost.
- Version: versionCode 2, versionName 0.2.0 (v0.1.0-debug reported versionCode 1, versionName
  1.0.0). The Spotify capture limitation and its warning banner are unchanged.

On 2026-11-22, clean `git archive` exports of the published commits (the `airbridge-android`
repository and the `airbridge-android/` folder of the `edge-sentinel` repository), each built with
`./gradlew testDebugUnitTest assembleDebug lintDebug` and the same toolchain and debug key, gave
BUILD SUCCESSFUL, 115 tests passed, lint "No issues found", and an `app-debug.apk` byte-identical to
the released APK (SHA-256 `d4f047de85ba79afa138434ab0897aa55f9828c3cf446b09ea9cceb76c3ee0e2`).
