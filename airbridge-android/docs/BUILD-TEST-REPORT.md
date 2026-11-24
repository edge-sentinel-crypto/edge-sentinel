# AirBridge build and test report

Version 0.3.0 (versionCode 3), release `v0.3.0-debug`. Build date: 2026-11-24. Host: WSL2 Ubuntu
24.04.3 (x86_64, 24 cores), no sudo, no Android device or emulator attached (the user is not in the
`kvm` group, so no emulator could run). No real phone or speaker was used, and no network device
other than mock receivers on 127.0.0.1 was contacted by any test.

## 1. Result

| Check | Result |
|---|---|
| `./gradlew clean testDebugUnitTest assembleDebug lintDebug` | BUILD SUCCESSFUL |
| Unit + loopback integration tests | **155 run, 155 passed, 0 failed, 0 skipped** (the 115 tests of v0.2.0, unchanged, plus 40 new ones) |
| Android lint (`lintDebug`, `abortOnError = true`) | **No issues found** (0 errors, 0 warnings) |
| Optional UI screenshot test (Robolectric) | 7 run, 7 passed; PNGs in [`screenshots/`](../screenshots/) (`03-capture-blocked.png` re-rendered with the new banner text, `06-airplay1-fallback.png` new, the others byte-identical to v0.2.0) |
| `apksigner verify --verbose --print-certs` | **Verifies** (APK Signature Scheme v2), 1 signer, Android Debug certificate, the same certificate as v0.1.0-debug and v0.2.0-debug |
| `aapt2 dump badging` | `com.airbridge.airplay`, versionCode 3, versionName 0.3.0, minSdk 29, targetSdk 35, compileSdk 35 |
| Rebuild reproducibility | a clean `git archive` export of the v0.3.0 sources, built with the same toolchain and debug key, reproduced the release APK byte for byte (same SHA-256), both from the `airbridge-android` repository and from the `airbridge-android/` folder of the `edge-sentinel` repository |

Release APK: `AirBridge-debug.apk` in the GitHub release [v0.3.0-debug](https://github.com/edge-sentinel-crypto/airbridge-android/releases/tag/v0.3.0-debug),
29,333,668 bytes, SHA-256 `6c10a385e5f222e86f4b5ed410b68d0837f013a45b7182422a9561ba6d3d5dc5`.

Toolchain and signing continuity: before any change, the unchanged v0.2.0 sources were rebuilt with
the same toolchain and debug key; they gave 115 of 115 tests passed, lint clean, and an APK
byte-identical to the published v0.2.0-debug file (SHA-256
`d4f047de85ba79afa138434ab0897aa55f9828c3cf446b09ea9cceb76c3ee0e2`).

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

The raw outputs of the 2026-11-24 runs (per-test summary, console logs, lint, apksigner and aapt2
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
| `airplay.SyncOffsetTest` (v0.2.0) | 5 | pass | sync offset -> 44.1 kHz frames for every 5 ms step from -250 to +250 ms against exact decimal arithmetic (±250 ms = ±11,025 frames, ±5 ms = ±221, symmetric, monotonic); clamping of out-of-range values; snapping to 5 ms; announced latency 66,150..88,200 frames around the default 77,175 and the 22,050-frame floor; labels "+40 ms (delay)", "-25 ms (earlier)", "0 ms" |
| `multiroom.MasterVolumeTest` (v0.2.0) | 7 | pass | master = loudest speaker that is on; proportional scaling with the loudest speaker exactly on the slider; drag down to 0, up to 100 and back restores the exact start (chained step-by-step scaling would not); all speakers at 0 rise evenly; clamping of master and stored values; single speaker; the master display is derived and a drag snapshot is not changed by later speaker moves |
| `multiroom.SpeakerGroupTest` (v0.2.0) | 8 | pass | group storage round trips names and ids with Unicode, emoji, quotes, pipes, commas, colons, line breaks, NUL and empty strings; damaged lines, wrong version and duplicate ids are dropped; save / update by name (any case) / rename / delete rules, empty names and empty groups refused, 24-group cap; name cleaning (40 characters, emoji not split); offline members skipped; group matching |
| `ui.MainUiStateTest` (v0.2.0) | 3 | pass | the screen state derives the master value (loudest speaker that is on), the All on / All off label and each group chip's match and available/offline counts |
| `session.SessionOptionsTest` (v0.2.0) | 1 | pass | a session hands the speaker's sync offset to its sender before connecting; a volume or sync-offset change made while connecting reaches the sender once connected; later changes go straight to the sender without a reconnect |
| `integration.SyncOffsetLoopbackTest` (v0.2.0) | 3 | pass | real sockets: the first sync packet of an **AirPlay 1** and of an **AirPlay 2** session carries the configured offset (rtpNow minus the shifted rtpLessLatency = 77,175 + offset frames, and rtpNow matches the shared timeline at the packet's NTP time); a live `setSyncOffsetMs` sends one regular sync packet with the new latency on the very next tick (less than 1 s after the previous one), with clamping to ±250 ms; audio stays bit-exact with consecutive timestamps on the same session, with no new ANNOUNCE/SETUP/RECORD, pairing, FLUSH or TEARDOWN; through the session manager, two speakers (AirPlay 1 + AirPlay 2) get independent offsets and a live change reaches only its speaker |
| `integration.MultiRoomLoopbackTest` (v0.2.0) | 5 | pass | the multi-room controller with the real session manager and mock speakers (one AirPlay 2, three AirPlay 1): All on connects every speaker and All off tears every one down while the stream keeps running; a saved group applied mid-stream skips its offline member, disconnects the other speaker and keeps the shared speaker on its session, then picks up the member once it is back; a switched-on speaker that appears joins by itself; groups saved, renamed, deleted and read back after a restart; master drag on live speakers (60/30 -> 30/15 -> mute -> back to exactly 60/30), saved on release, a speaker's own slider touches only that speaker, all-zero rises evenly; sync offset applied live, saved, and used again after the speaker reconnects |
| `airplay.Ap2LatencyBoundsTest` (new) | 6 | pass | the AirPlay 2 SETUP window derived from the base latency is latencyMin 11,025 / latencyMax 99,225 frames; baseline math: 77,175 announced (1.75 s) + 11,025 added by the receiver = 88,200 (2.0 s); -250 / +250 ms announce 66,150 / 88,200 and total 77,175 / 99,225; every offset from -400 to +400 ms (clamped to ±250) lies inside the window both as announced and as total, with no clamping needed; the v0.2.0 window (latencyMax 88,200) fails from +5 ms on; the window follows any base latency (22,050 to 132,300 frames) and is never narrower than the 11,025..88,200 that owntone and pyatv send; a window too narrow for the receiver's 11,025 frames is rejected; `Ap2Sender` declares the derived window (110,250 for an 88,200 base) |
| `integration.Ap2LatencyWindowLoopbackTest` (new) | 2 | pass | real sockets, **strict mock AirPlay 2 receiver** (answers 400 to a stream SETUP without a valid latencyMin/latencyMax and records every sync packet outside the window, as announced or as total): three speakers at -250, 0 and +250 ms through the session manager with 2.5 s of real-time audio; the SETUP declares 11,025..99,225; the first and at least two periodic mid-stream sync packets per speaker are inside the window with announced 66,150 / 77,175 / 88,200 and total +11,025 (+250 ms reaches 99,225 exactly); a live sweep from +250 to -250 to +250 ms in 5 ms steps plus out-of-range ±999 ms (clamped) sends one forced sync packet per step, every one inside the window, the extremes 66,150 and 99,225 are reached, the sender never had to clamp; audio delivered and TEARDOWN; no mock errors |
| `audio.PcmResamplerTest` (new) | 7 | pass | 48 kHz -> 44.1 kHz converter: ratio exactly 147/160, 80-frame look-ahead, exact output counts (44,027 frames for 1 s, 2,645,927 for 60 s); sines from 50 Hz to 19 kHz at -1 dBFS match the ideal 44.1 kHz signal with SNR of at least 90 dB and level within 0.01 dB; a silent channel stays exact zeros next to a tone, each channel matches only its own tone (SNR of at least 85 dB at -6 dBFS) and swapping the inputs swaps the outputs exactly; chunks of 1 frame, 352 frames, random sizes up to 4,000 and mixed sizes give identical output with no drift at any point; full-scale sines from 20 Hz to 20 kHz have no clipped sample (peak at least 32,700); a full-scale square wave saturates at 32,767 / -32,768 without wrap-around and keeps its sign; 60 s in random chunks keeps the exact frame count and the last second is still phase-locked (SNR of at least 90 dB); silence stays exact silence, DC passes with gain 0.9999 |
| `audio.CaptureFallbackTest` (new) | 5 | pass | capture opens at 44.1 kHz when the phone allows it (48 kHz never tried); falls back to 48 kHz when the 44.1 kHz AudioRecord does not initialize or does not start, and the refused one is released first; both errors are reported and nothing stays open when neither rate works; a missing permission is rethrown at once instead of trying 48 kHz |
| `audio.NoAudioFocusTest` (new) | 2 | pass | the app's own source code (comments removed) contains no audio-focus API (`requestAudioFocus`, `AudioFocusRequest`, `AUDIOFOCUS_`, ...) and no local player (`AudioTrack`, `MediaPlayer`, `SoundPool`, `ExoPlayer`, ...); capture uses `setAudioPlaybackCaptureConfig` and no microphone source |
| `util.LockSetTest` (new) | 4 | pass | WakeLock / Wi-Fi lock / MulticastLock handling: locks are taken in order and released in reverse; a failed acquire releases the locks already taken; a release that throws does not keep the others held; repeated starts and stops never acquire or release twice |
| `mdns.ReceiverMoveTest` (new) | 6 | pass | a speaker with a device id that changes IP address and port keeps one entry and its id; one without a device id is matched by its instance name (case-insensitive) and keeps its key; a host-keyed entry is upgraded when the id arrives from the new address; a re-resolve without a TXT record keeps the known features; two speakers that swap addresses stay apart; 50 re-announcements over 5 addresses leave one entry |
| `integration.SpeakerMoveLoopbackTest` (new) | 2 | pass | with the app's real registry, multi-room controller and session manager: a PIN-paired AirPlay 2 mock that moves to a new port while streaming reconnects at its new address as soon as it is announced (under 1.5 s, not after the 2 s backoff), with its volume 70 % and sync offset +30 ms (77,175 + 1,323 frames), Pair-Verify with the saved keys and no new PIN; one card with the same id; a playing AirPlay 1 speaker announced at a new address keeps its connection (no new SETUP) and its next connection goes to the new address with volume 40 % and offset -20 ms |
| `integration.FallbackStatusTest` (new) | 3 | pass | an AirPlay 2 mock that rejects the NTP session SETUP falls back to AirPlay 1 and its status and speaker row read "AirPlay 1 (fallback)" with the reason "SETUP (session) failed with 500", audio flows over AirPlay 1; a working AirPlay 2 speaker reads "AirPlay 2"; a forced AirPlay 1 reads "AirPlay 1" (not a fallback) and the replaced session's late "stopped" status does not overwrite the new one; before connecting the label follows the chosen protocol |
| `ui.CaptureWordingTest` (new) | 3 | pass | the silent-capture banner and help text contain no blanket claim about Spotify or any app ("Spotify blocks", "cannot be streamed", ...); they say the playing app (or this version of it) seems to block Android's playback capture, mention `allowAudioPlaybackCapture="false"` and "no root and no workarounds", and suggest the app's settings, another player, Spotify Connect or Cast; the capture format reads " (44.1 kHz)" or " (48 kHz, converted to 44.1 kHz)" |
| **Total** | **155** | **155 pass** | |

Test helpers changed for v0.3.0: `MockAp2Receiver` gained the strict latency-window mode and can
move to a new port with the same keys and pairings; `MockAudioSink` hands each sync packet to the
strict check as it arrives. None of the 115 existing tests was changed. In the optional screenshot
test, `spotifyBlockedWarning` was renamed `captureBlockedWarning` and `airPlay1Fallback` was added.

Optional `ui.UiScreenshotTest` (7 tests, Robolectric native graphics, Android 14 runtime): renders
the real Compose screen with sample data, the sync offset dialog and the app icons to
[`screenshots/`](../screenshots/). v0.3.0 adds `06-airplay1-fallback.png` and re-renders
`03-capture-blocked.png` with the new banner text; the other images came out byte-identical to the
v0.2.0 files. The rendered PNGs contain only the IHDR, sRGB, sBIT, IDAT and IEND chunks. The sample
speaker addresses are from the 192.0.2.0/24 documentation range. These images were reviewed
visually.

## 5. Independent cross-checks used during the build

- **Sync offset semantics.** Shairport-Sync's `rtp.c` computes the latency from every sync packet
  as rtpNow minus rtpLessLatency (plus its fixed 11,025-frame offset for the 0x07 flag) and adopts
  a new value when it changes mid-stream; a larger value schedules each frame later. AirBridge's
  offset therefore changes only that difference; the RTP timestamps of the audio do not change.
- **Latency window (v0.3.0).** Shairport-Sync's `rtp.c` (checked again on 2026-11-24) adds the fixed
  11,025 frames first and then clamps that total to the session's minimum and maximum latency
  (from `min-latency` / `max-latency` in an AirPlay 1 announcement, see `rtsp.c`). A receiver that
  treats the AirPlay 2 `latencyMin` / `latencyMax` the same way would have capped v0.2.0's positive
  offsets at 88,200 frames (2.0 s). AirBridge v0.3.0 therefore declares a window that contains both
  the announced latency and that total for every offset (11,025..99,225), and AirBridge's AirPlay 1
  announcement declares no latency limits.
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
- Upgrade path: the signer certificate is the same as the one of the published v0.1.0-debug and
  v0.2.0-debug APKs (both downloaded from their GitHub releases on 2026-11-24 and checked with
  apksigner: SHA-256 `ad9ed70a…ec279`, versionCode 1, and `d4f047de…ee0e2`, versionCode 2), the
  package name is unchanged and the version code goes to 3, so Android installs v0.3.0-debug over
  either one as an update and keeps the app's data (speakers, volumes, sync offsets, groups,
  pairings).
- `aapt2 dump badging`: package `com.airbridge.airplay`, versionCode 3, versionName 0.3.0,
  minSdkVersion 29, targetSdkVersion 35, compileSdkVersion 35, launchable activity
  `com.airbridge.airplay.MainActivity`, label AirBridge, `application-debuggable`.
- Permissions (unchanged since v0.1.0): INTERNET, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, ACCESS_NETWORK_STATE,
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
fan-out, fallback and PIN prompts, discovery parsing and the raw mDNS browser, the v0.2.0
multi-room controls on live mock speakers (master volume, sync offset on connect and mid-stream,
groups, All on / All off), and the v0.3.0 changes (AirPlay 2 latency window against a strict mock
receiver, speakers that change address, the AirPlay 1 fallback label). The 48 kHz converter, the
capture-rate fallback and the lock handling are tested on the JVM with Android stand-ins.

The mock receivers share AirBridge's understanding of the protocol, so the loopback tests prove
internal consistency and correct implementation of that understanding. The parts that are pinned
to external truth are the published test vectors, FFmpeg, Python, and the reference
implementations' source.

Not tested (no phone, emulator or speaker was available), so it needs real hardware:

1. Each real speaker accepting the session, especially AirPlay 2 with NTP timing (Sonos, HomePod,
   Apple TV, WiiM, Denon/Marantz), and which ones show "AirPlay 1 (fallback)". AirPlay 1 fallback
   exists for speakers that refuse.
2. Android behaviour on Android 10 to 15: the capture consent flow, the foreground service with the
   screen off, battery restrictions, capture ending on screen lock (varies by Android version and
   brand), and that every stop path leaves no lock held.
3. Playback capture on real phones: which phones need the 48 kHz fallback, and how the converted
   audio sounds there; which apps and app versions allow capture (for example Spotify and other
   players), and that the silent-capture warning appears only for an app that opts out.
4. Real Wi-Fi: loss, latency, multicast discovery through a real router or mesh, and a speaker
   getting a new IP address while streaming, with AirBridge open on screen and with the screen off.
5. Audible sync between several speakers, especially AirPlay 1 mixed with AirPlay 2, and how each
   receiver follows a sync-offset change mid-stream (smoothly, or with a short skip), including
   AirPlay 2 receivers at -250 and +250 ms with the v0.3.0 latency window.
6. The multi-room UI and the fallback badge by touch on a phone (chips, long-press, sliders,
   dialogs); on the JVM they were checked through rendered screenshots and screen-state tests only.

## 8. SHA-256 manifest

| File | SHA-256 |
|---|---|
| `AirBridge-debug.apk` (v0.3.0-debug release asset, 29,333,668 bytes) | `6c10a385e5f222e86f4b5ed410b68d0837f013a45b7182422a9561ba6d3d5dc5` |
| `screenshots/00-launcher-icon.png` (unchanged) | `5296ca14457f4b1fbe9bde63b4c7c91851d3b81baaeee6dc34e0aed7a93c4051` |
| `screenshots/00-status-icon.png` (unchanged) | `4f38c451300cdaef2b7e305787a0ae31a7368dafdf45645b35d490075c1d8095` |
| `screenshots/01-ready.png` (unchanged) | `922b190e5e79e6e7beced71fdffaf2f3e59ee319fca6f960b226027e03e38827` |
| `screenshots/02-streaming.png` (unchanged) | `b107b69fd3f7581b88cab58122da323a226ef8b1f27b0db6688c00a940385296` |
| `screenshots/03-capture-blocked.png` (new banner text) | `531a53cebd5328633419a4967e1103066c347b3cb9117068244c281b6484be6a` |
| `screenshots/04-multi-room.png` (unchanged) | `843afdc5833bb127e539bca1536e5f55fe06813429653d04d86cadf7c634ffde` |
| `screenshots/05-sync-offset.png` (unchanged) | `cb4b5562c752cba0c52dfce044b41157be79a35522f25321bb6dad65d5efec68` |
| `screenshots/06-airplay1-fallback.png` (new) | `6f52521de4cac7193a53f2872cac5137539b522806b001cafffc9e561fc609e7` |
| `gradle/wrapper/gradle-wrapper.jar` | `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046` |

The PNG files contain only the IHDR, sRGB, sBIT, IDAT and IEND chunks (no text, EXIF or time
metadata).

The `gradle-wrapper.jar` hash equals Gradle's published checksum for 8.11.1
(`https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar.sha256`).
The working documents and raw logs of the original build are not part of this repository, so their
hashes are not listed.

## 9. Changes in v0.3.0 and the re-check of the sources

What changed since v0.2.0-debug (APK SHA-256
`d4f047de85ba79afa138434ab0897aa55f9828c3cf446b09ea9cceb76c3ee0e2`):

- **AirPlay 2 latency window:** the stream SETUP declared `latencyMax` 88,200 frames (2.0 s) while a
  positive sync offset asked for up to 99,225 frames (2.25 s) in total. The window is now derived
  from the sync-packet latency (`SyncOffset.ap2LatencyBounds`): `latencyMin` 11,025, `latencyMax`
  99,225, which contains the announced latency (66,150..88,200) and the total with the receiver's
  11,025 frames (77,175..99,225) for every offset from -250 to +250 ms. `RtpStreamer` keeps every
  AirPlay 2 sync packet inside the declared window as a safety net (never needed for the offset
  range). Files: `airplay/SyncOffset.kt`, `airplay/AirPlaySender.kt`, `airplay/RtpStreamer.kt`,
  `airplay/ap2/Ap2Sender.kt`.
- **Capture:** 44.1 kHz first, 48 kHz fallback with the new pure-Kotlin `PcmResampler`
  (`audio/CaptureFallback.kt`, `audio/PcmResampler.kt`, `audio/CaptureEngine.kt`); the capture
  format is shown in Diagnostics. AirBridge requests no audio focus (now guarded by a test).
- **Lifecycle and locks:** one idempotent stop path for every way streaming ends, with the
  AudioRecord, projection callback, projection, WakeLock and Wi-Fi lock released in `finally`
  blocks; a late `onStop` from an earlier projection is ignored; a failed start no longer has its
  error message replaced by a plain "stopped"; discovery's MulticastLock is released in a `finally`
  (`audio/AudioCaptureService.kt`, `util/LockSet.kt`, `mdns/NsdDiscovery.kt`).
- **Speakers that change address:** re-announcements update the existing entry (device id, then
  instance name), TXT records survive a TXT-less re-resolve, and a streaming speaker's session
  learns the new address (reconnecting at once if its connection was lost); late status updates
  from replaced sessions are ignored (`mdns/ReceiverRegistry.kt`, `multiroom/MultiRoomController.kt`,
  `session/AirPlaySessionManager.kt`, `session/ReceiverSession.kt`). Announcements are seen only
  while AirBridge is open on screen (discovery stops in `MainActivity.onStop`), so a speaker that
  moves while AirBridge is in the background reconnects after AirBridge is opened again.
- **"AirPlay 1 (fallback)":** the speaker card badge (amber), its status line and Diagnostics say
  when Auto mode could not use AirPlay 2 and plays over AirPlay 1, with the AirPlay 2 error
  (`session/ReceiverSession.kt`, `ui/AirBridgeScreen.kt`, `ui/Dialogs.kt`, `ui/MainViewModel.kt`).
- **Capture wording:** the banner, help text, log message and documents no longer claim that Spotify
  always blocks capture. They state that Android's playback capture works with Spotify and most
  media apps when the app and the phone allow it, and gives silence only for an app (or app
  version) that opts out.
- Version: versionCode 3, versionName 0.3.0.

v0.2.0 (for reference) added the master volume, the per-speaker sync offset, saved speaker groups
and All on / All off, moved speaker selection into the Android-free `MultiRoomController`, and
coalesced volume commands; its release APK was
`d4f047de85ba79afa138434ab0897aa55f9828c3cf446b09ea9cceb76c3ee0e2` (v0.1.0-debug:
`ad9ed70a1c9a80bcd333da8b28783187b7635436d63e7a34f51068f0d78ec279`).

On 2026-11-24, clean `git archive` exports of the v0.3.0 commits (the `airbridge-android`
repository and the `airbridge-android/` folder of the `edge-sentinel` repository), each built with
`./gradlew clean testDebugUnitTest assembleDebug lintDebug` and the same toolchain and debug key,
gave BUILD SUCCESSFUL, 155 tests passed, lint "No issues found", and an `app-debug.apk`
byte-identical to the release APK (SHA-256
`6c10a385e5f222e86f4b5ed410b68d0837f013a45b7182422a9561ba6d3d5dc5`).
