# AirBridge: plan and architecture

AirBridge is an Android app (package `com.airbridge.airplay`, Android 10 or newer) that captures the
audio other apps are playing on the phone and streams it over local Wi-Fi to AirPlay 1 and AirPlay 2
speakers: Sonos, HomePod, Apple TV, Denon/Marantz/Yamaha receivers, WiiM, AirPort Express,
Shairport-Sync and others. Several speakers can play at once, each with its own volume.

## 1. The most important limitation: Spotify

Read this first if you use Spotify.

Android lets an app capture other apps' audio only through the AudioPlaybackCapture API, and every
app can refuse to be captured. Spotify refuses. Current Spotify builds play through native AAudio
code that sets "do not capture", so Android hands every capture app, AirBridge included, digital
silence while Spotify plays. This is documented by RootlessJamesDSP ("Apps such as Spotify block
internal audio capture") and by a 2026 attempt to patch Spotify that still captured silence. No app
can get around this on an unrooted phone.

What AirBridge does about it:

- It detects the situation. If Android reports that music is playing (`AudioManager.isMusicActive`)
  but the captured audio has been exact zeros for more than 3 seconds, the app shows a red banner
  that names the cause and the options.
- Options for Spotify: use Spotify Connect, which is built into Sonos, Denon/Marantz (HEOS),
  Yamaha (MusicCast), WiiM and many other speakers, or play the music from an app that allows
  capture. Most local music players, podcast apps and many browsers allow it. AirBridge works with
  every app that does not block capture.
- HomePod does not support Spotify Connect (Apple TV does only through its own Spotify app). For
  Spotify on AirPlay-only speakers there is no Android-side fix. The options are an iPhone/Mac, or
  a small always-on computer running a Spotify Connect to AirPlay bridge such as SpotConnect
  (spotraop) or owntone, which makes each AirPlay speaker appear as a Spotify Connect device.

## 2. How it works, in plain English

1. You turn on the speakers you want in AirBridge, start music in any app, and tap **Start
   streaming**.
2. Android asks once whether AirBridge may capture what is playing. AirBridge then runs as a
   foreground service with a notification and a Stop button, so streaming continues when the screen
   is off.
3. AirBridge reads the phone's audio mix 44,100 times per second (16-bit stereo), cuts it into
   352-sample packets (8 ms each), encodes each packet once, and sends a copy to every speaker
   you turned on.
4. Each speaker gets its own connection and thread. A slow or disconnected speaker cannot hold up
   the others. All speakers share one clock, so they play in step.
5. Speakers play about 2 seconds behind the phone. That delay is the AirPlay network buffer. It
   protects music against Wi-Fi hiccups, but makes AirBridge unsuitable for lip-sync with video.

## 3. Architecture

```
 Other apps' playback (USAGE_MEDIA / GAME / UNKNOWN)
        |  AudioPlaybackCapture (MediaProjection consent, RECORD_AUDIO)
        v
 AudioCaptureService (foreground service: mediaProjection|mediaPlayback, WakeLock, Wi-Fi lock)
        |  CaptureEngine: AudioRecord 44.1 kHz / 16-bit / stereo, READ_BLOCKING
        v
 capture thread --> PcmRingBuffer (lock-free SPSC, ~3 s) --> pump thread
                                                              | FrameChunker: 352 frames
                                                              | DriftCorrector: silence fill / trim
                                                              | LevelMeter + CaptureHealthMonitor
                                                              v
                                   AirPlaySessionManager.push()  (ALAC encoded once)
                                   shared StreamTimeline (RTP clock + NTP anchor)
                     +----------------------+----------------------+
                     v                      v                      v
             ReceiverSession A      ReceiverSession B      ReceiverSession N   (thread + bounded queue each)
             RaopSender (AP1)       Ap2Sender (AP2)        ...
             RTSP/TCP + RTP/UDP     HAP pairing + encrypted RTSP + sealed RTP

 Discovery: NsdManager (_raop._tcp, _airplay._tcp) + raw mDNS legacy-unicast browser
            -> ReceiverRegistry (merge by device id / IP) -> UI list (+ manual IP:port)
```

Source layout (`app/src/main/java/com/airbridge/airplay/`):

| Package | Contents |
|---|---|
| `audio/` | `AudioCaptureService`, `CaptureEngine`, `PcmRingBuffer`, `FrameChunker`, `LevelMeter`, `CaptureHealthMonitor`, `DriftCorrector` |
| `mdns/` | `DiscoveryManager` (NsdManager + MulticastLock), `RawMdnsBrowser`, `MdnsMessage` (DNS codec), `TxtRecord`, `AirPlayFeatures`, `AirPlayReceiver`, `ReceiverRegistry` |
| `codec/` | `AlacEncoder`/`AlacDecoder`, `PcmL16`, `BinaryPlist` (bplist00), `Tlv8` |
| `crypto/` | `SrpClient`/`SrpMath` (SRP-6a), `HapCrypto` (HKDF-SHA-512, ChaCha20-Poly1305, nonces), `X25519KeyPair`, `Ed25519KeyPair` |
| `airplay/` | `RtspClient`, `RtspCodec`, `HapSecureStreams`, `RtpPackets`, `RtpStreamer` (data/control/timing UDP), `AirPlaySender` contract, `StreamTimeline`, `AirPlayVolume` |
| `airplay/raop/` | `RaopSender`, `RaopEncryption` (RSA-OAEP + AES-128-CBC) |
| `airplay/ap2/` | `Ap2Sender`, `HapPairing`, `EventChannel`, `PairingStore` |
| `session/` | `AirPlaySessionManager`, `ReceiverSession`, `SenderFactory` |
| `ui/` | Compose Material 3 screen, dialogs, diagnostics sheet, theme, icons, `MainViewModel` |

The protocol engine (`codec`, `crypto`, `airplay`, `session`, most of `mdns` and `audio`) is plain
JVM Kotlin with no Android imports, so it runs unchanged in the unit and loopback tests.

## 4. System audio capture

- **API:** `AudioPlaybackCaptureConfiguration` (Android 10, API 29) built from a `MediaProjection`,
  matching `USAGE_MEDIA`, `USAGE_GAME` and `USAGE_UNKNOWN`. Format: 44,100 Hz, `CHANNEL_IN_STEREO`,
  `ENCODING_PCM_16BIT`. AudioRecord buffer at least 4x the minimum and at least 250 ms.
- **Permissions:** `RECORD_AUDIO` is required by Android for any AudioRecord, including playback
  capture. AirBridge never opens the microphone. `POST_NOTIFICATIONS` is requested on Android 13+.
- **Consent:** `MediaProjectionManager.createScreenCaptureIntent()`. On Android 14+ AirBridge passes
  `MediaProjectionConfig.createConfigForDefaultDisplay()` so the prompt offers only "entire screen".
  Nothing is recorded from the screen; only audio is captured.
- **Foreground service rules (Android 14/15):** the service declares
  `foregroundServiceType="mediaProjection|mediaPlayback"` with `FOREGROUND_SERVICE_MEDIA_PROJECTION`
  and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`. It calls `startForeground(..., MEDIA_PROJECTION |
  MEDIA_PLAYBACK)` **before** `getMediaProjection()`, uses the one-time consent token once, and
  registers a `MediaProjection.Callback` so a system-ended projection stops streaming cleanly.
- **Pipeline:** capture thread (`THREAD_PRIORITY_URGENT_AUDIO`) writes into a lock-free
  single-producer/single-consumer ring buffer (power-of-two size, about 3 s, release stores via
  `lazySet`; overflow drops new samples and counts them). The pump thread cuts exact 352-frame
  chunks, applies drift correction and pushes them to the session manager.
- **Drift and stalls:** the phone's audio clock and system clock differ by tens of ppm, and capture
  can stall. `DriftCorrector` compares chunks sent with elapsed time: more than 200 ms behind means
  silent chunks are inserted, more than 200 ms ahead means one chunk is dropped. Receivers stay fed
  and never overrun their 2 s buffer.
- **Meter and blocked-capture detection:** per chunk RMS/peak in dBFS (about 15 UI updates per
  second). `CaptureHealthMonitor` reports `OK`, `NOTHING_PLAYING` (silence, no music active) or
  `BLOCKED` (music active, all-zero PCM for more than 3 s; the Spotify case).
- **Teardown:** Stop (app or notification) stops AudioRecord, joins threads, stops the projection,
  releases the WakeLock and Wi-Fi lock, and sends TEARDOWN to every speaker off the main thread.

## 5. Receiver discovery

- **NsdManager** browses `_raop._tcp.` and `_airplay._tcp.`. Resolves are serialized because
  Android before 14 rejects concurrent resolves (`FAILURE_ALREADY_ACTIVE` is retried).
- **Raw mDNS fallback** (`RawMdnsBrowser`): sends PTR queries from an ephemeral port to
  224.0.0.251:5353. RFC 6762 section 6.7 says responders answer such "legacy unicast" queries by
  unicast, so AirBridge never competes with Android's own responder for port 5353. It parses
  PTR -> SRV/TXT -> A records, including name compression, and asks follow-up SRV/TXT/A questions
  when needed. It exists because some OEM NsdManager builds drop TXT records.
- **MulticastLock** (`WifiManager.createMulticastLock`) is held while discovery runs (app visible).
- **TXT parsing:** raw RDATA (length-prefixed strings) or NsdManager attributes. Keys are case
  insensitive; the first occurrence wins. Fields used: `features`/`ft` (64-bit "0xLOW,0xHIGH"),
  `flags`/`sf` status flags, `am`/`model`, `manufacturer`, `pw`, `cn` (codecs), `et` (encryption
  types), `ek`, `md` (metadata), `tp`, `vs`/`srcvers`, `vv`, `pk`, `deviceid`.
- **Feature bits used:** 9 audio, 38 unified media control, 40 buffered audio, 41 PTP, 46 HomeKit
  pairing, 48 transient (CoreUtils) pairing. **Status flags:** 0x8 PIN required, 0x80 password
  required, 0x200 one-time pairing required.
- **De-duplication:** most receivers advertise both services (`AABBCCDDEEFF@Kitchen` and `Kitchen`
  with `deviceid=AA:BB:..`). They are merged by device id, falling back to IP address; an entry
  keyed by IP is upgraded when the id appears. NSD "service lost" removes one advertisement;
  raw-mDNS entries expire after 5 minutes without a refresh.
- **Protocol choice (Auto):** AirPlay 2 when the `_airplay._tcp` service is present and feature bit
  38, 46 or 48 is set (pyatv's rule). Exception: Shairport-Sync (`model`/`am` contains
  "Shairport") uses AirPlay 1, because its AirPlay 2 mode only accepts PTP timing (see 7.6).
  Otherwise AirPlay 1. If AirPlay 2 fails for a non-auth reason and the speaker also offers
  AirPlay 1, the session falls back automatically. Each speaker's menu can force AirPlay 2 or 1.
- **Manual entry:** IP literal + port (7000 default; 5000 on old units) + protocol (auto / AP2 /
  AP1) for speakers on another subnet or with blocked multicast.

## 6. AirPlay 1 (RAOP) engine

Sequence over TCP RTSP/1.0 (`CSeq`, `User-Agent: AirPlay/550.10`, `DACP-ID`, `Active-Remote`,
`Client-Instance`):

1. `OPTIONS *` (with `Apple-Challenge` when encrypting, as iTunes does). Reads the `Public` header.
2. `POST /auth-setup` (byte 0x01 + a fresh Curve25519 public key; reply ignored): sent up front
   for AirPort units listing MFi (`et=4`), and for any receiver that answers ANNOUNCE with 403
   (owntone reports Sonos and AirPort Express firmware 7.8 do).
3. `ANNOUNCE` with SDP: `m=audio 0 RTP/AVP 96`, `a=rtpmap:96 AppleLossless`,
   `a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100` (or `L16/44100/2` when the receiver lists only
   PCM in `cn`). With encryption: `a=rsaaeskey` (random AES-128 key wrapped with RSA-OAEP/SHA-1 for
   the AirPort Express public key; verified against owntone and the Shairport-Sync key) and
   `a=aesiv`, base64 without padding.
4. `SETUP` with `Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=..;timing_port=..`;
   reads `Session` and `server_port`/`control_port`.
5. `RECORD` with `Range: npt=0-` and `RTP-Info: seq=..;rtptime=..`.
6. `SET_PARAMETER` `volume: -15.000000` (text/parameters; -30..0 dB, -144 = mute).
7. Keep-alive `OPTIONS *` every 25 s. `FLUSH` with `RTP-Info`. `TEARDOWN` on stop.

- **Digest auth:** on 401 with `WWW-Authenticate: Digest`, AirBridge sends
  `response = MD5(MD5(user:realm:pw):nonce:MD5(method:uri))` (user "iTunes") on every following
  request, and retries once with upper-case hex for older receivers.
- **Encryption policy (owntone's rules):** encrypt when `ek=1`, when `et` lists 1 but not 0, or for
  first/second generation AirPort Express (`am` absent or `AirPort4,*`). AES-128-CBC per packet,
  whole 16-byte blocks only, the tail stays clear.
- **UDP channels** (`RtpStreamer`):
  - audio RTP v2, PT 96 (marker 0xE0 on the first packet after RECORD/FLUSH), per-receiver
    sequence and SSRC, shared timestamps;
  - control port: sync packets (`0x90/0x80 0xD4`, rtp-less-latency, NTP time, rtp now, 0x07
    flag) every second, resend requests `0xD5` answered from a 1024-packet retransmit ring as
    `0xD6` packets;
  - timing port: NTP-style requests `0xD2` answered with `0xD3` (origin echo, receive and transmit
    times from one monotonic `NtpClock`).

## 7. AirPlay 2 engine

### 7.1 Connection and pairing decision

`GET /info` (binary plist: name, model, features, `statusFlags`) on a fresh TCP connection to the
`_airplay._tcp` port, then (owntone's decision table):

| Situation | Pairing |
|---|---|
| Saved pairing for this receiver | Pair-Verify only |
| Status flag PIN (0x8) or one-time pairing (0x200) | `POST /pair-pin-start` (receiver shows a code), user enters it, full Pair-Setup with the PIN, save keys, Pair-Verify |
| Status flag password (0x80) | full Pair-Setup with the password, save keys, Pair-Verify |
| Anything else | transient Pair-Setup with PIN `3939` (or the password if the user gave one) |
| Transient Pair-Setup answered with HTTP 470 | switch to the PIN flow |

### 7.2 Pair-Setup (HAP, `X-Apple-HKP: 4` transient / `3` normal)

- M1: TLV8 `State=1, Method=0` plus `Flags=0x10` for transient.
- M2: receiver salt and B. M3: our A (always 384 bytes) and proof M1. M4: receiver proof M2.
- SRP-6a, RFC 5054 3072-bit group, g = 5, SHA-512, user "Pair-Setup". Formulas follow Apple's
  HomeKit ADK (`PAL/Crypto/OpenSSL/HAPOpenSSL.c`): k = H(PAD(N)|PAD(g)), x = H(s|H(I:P)),
  u = H(PAD(A)|PAD(B)), K = H(S), M1 = H(H(N) xor H(g)|H(I)|s|A|B|K), M2 = H(PAD(A)|M1|K).
  AirBridge always picks an `a` whose A has no leading zero byte, so padded and unpadded
  conventions agree. Verified bit-for-bit against the HAP specification test vectors.
- Transient: done after M4; the 64-byte SRP key K is the session secret.
- PIN/password: M5 carries our pairing id, Ed25519 long-term public key and a signature over
  HKDF(K, "Pair-Setup-Controller-Sign-Salt/Info") | id | LTPK, sealed with ChaCha20-Poly1305
  (key HKDF(K, "Pair-Setup-Encrypt-Salt/Info"), nonce "PS-Msg05"). M6 returns the receiver's id,
  LTPK and signature (verified). The receiver's id and LTPK are stored.
- TLV8 values over 255 bytes (the 384-byte SRP keys) are fragmented and re-joined.

### 7.3 Pair-Verify

M1 our X25519 public key; M2 receiver X25519 key plus sealed (id, Ed25519 signature over
receiverKey|id|ourKey), checked against the stored LTPK; M3 our sealed id and signature over
ourKey|ourId|receiverKey; M4 done. Keys: HKDF(shared, "Pair-Verify-Encrypt-Salt/Info"), nonces
"PV-Msg02"/"PV-Msg03". The X25519 shared secret becomes the session secret. If a saved pairing is
rejected (receiver reset), it is deleted and the next attempt pairs again.

**Note on pairing order.** The order "pair-setup transient 3939 -> pair-verify" does not match
real receivers: after a transient pair-setup the connection is
already encrypted with keys from the SRP secret, and a plaintext pair-verify would be rejected.
Pair-Verify belongs after a PIN/password pair-setup and on reconnects with saved keys. AirBridge
implements both paths, and the loopback tests cover transient pairing, PIN pairing followed by
Pair-Verify, and Pair-Verify alone with saved keys.

### 7.4 Encrypted control channel

From the end of pairing, every RTSP message in both directions is framed as: 2-byte little-endian
length (at most 1024), ChaCha20-Poly1305 ciphertext, 16-byte tag; AAD = the length bytes; nonce =
4 zero bytes + 64-bit little-endian frame counter per direction. Keys: HKDF-SHA-512(secret,
"Control-Salt", "Control-Write-Encryption-Key") for sending and "...Read..." for receiving.

### 7.5 Session setup and streaming

1. `SETUP` #1 (bplist): `deviceID`, `sessionUUID`, `timingProtocol: "NTP"`, `timingPort`,
   `isMultiSelectAirPlay`, `groupContainsGroupLeader`, `macAddress`, `model`, `name`, `osName`,
   `senderSupportsRelay`, `sourceVersion`, `statsCollectionEnabled`. Reply: `eventPort`, `timingPort`.
2. Event channel: TCP to `eventPort`, HAP-framed with HKDF(secret, "Events-Salt") keys (we send
   with "Events-Read-Encryption-Key" and receive with "Events-Write-Encryption-Key", as owntone and
   pyatv do). Incoming requests get 200 OK. Best effort.
3. `RECORD` (iOS and owntone send it between the two SETUPs).
4. `SETUP` #2: `streams: [{type: 96 (realtime), ct: 2 (ALAC) or 1 (PCM), audioFormat: 0x40000
   (ALAC/44100/16/2) or 0x800 (PCM/44100/16/2), spf: 352, sr: 44100, latencyMin: 11025,
   latencyMax: 88200, controlPort, shk: 32 bytes, audioMode: "default", isMedia: true,
   supportsDynamicStreamID: false, streamConnectionID}]`. Reply: `dataPort`, `controlPort`.
   `shk` = first 32 bytes of the session secret (as owntone does). It works whether a receiver uses
   the `shk` it was sent or derives the key from the pairing.
5. `SET_PARAMETER volume` last (owntone: some Sonos ignore volume sent earlier).
6. `POST /feedback` every 2 s as keep-alive. `FLUSH`, `TEARDOWN` as in AirPlay 1.
7. **Audio packets:** 12-byte RTP header in clear, then ChaCha20-Poly1305(shk, nonce = 4 zero bytes
   + 8-byte little-endian counter, AAD = RTP bytes 4..11 = timestamp + SSRC) of the ALAC frame,
   16-byte tag, then the 8 nonce bytes. Matches Shairport-Sync's and owntone's realtime format.
   Retransmissions resend the stored sealed packet.

### 7.6 Timing: NTP, not PTP

AirBridge offers `timingProtocol: "NTP"` and answers the receiver's NTP-style timing requests, as
owntone and pyatv do. PTP (what current iOS uses) needs a PTP clock on UDP ports 319/320. Android
apps cannot bind ports below 1024 without root, so PTP is not possible. Consequences: Apple
devices and most third-party receivers accept NTP timing for realtime streams (owntone and pyatv
rely on this). Shairport-Sync in AirPlay 2 mode does not ("can not handle NTP streams"), so
AirBridge uses its AirPlay 1 service. Any other receiver that rejects NTP falls back to AirPlay 1
automatically when it advertises it.

## 8. Multi-speaker fan-out, volume, latency

- **One timeline:** `StreamTimeline` holds a random RTP base and an NTP anchor. Frame k has RTP
  time base + 352k for every speaker, and every speaker's sync packets map RTP time to the same
  phone clock. Speakers of the same protocol therefore play in step. A speaker that joins
  mid-stream starts at the current frame.
- **Isolation:** each speaker is a `ReceiverSession` with its own thread and a bounded 96-frame
  queue (0.77 s). When a queue is full the oldest frame is dropped and counted. A dead speaker never
  blocks capture or the other speakers.
- **Encoding once:** each 352-frame block is ALAC-encoded once on the capture thread and shared;
  per-speaker work is only the RTP header plus AES (AirPlay 1) or ChaCha20-Poly1305 (AirPlay 2).
- **Volume:** a 0-100 % slider maps linearly to -30..0 dB; 0 % sends -144 (mute). Changes go over
  each speaker's own control thread and never block the UI.
- **Latency:** sync packets announce 77,175 frames plus the 0x07 flag. Receivers add their fixed
  11,025 frames, so the total is 88,200 frames = 2.0 s. AirPlay 2 SETUP announces latencyMin 11,025
  and latencyMax 88,200. Speakers on different protocols may not be sample-aligned with each
  other; this needs a check on real hardware.
- **Reconnect:** a failed connect retries with backoff (1, 2, 4, 8, 15 s, then waits for "Retry").
  Repeated keep-alive or feedback failures trigger a reconnect. PIN and password prompts pause the
  session until the user answers. A wrong PIN makes the receiver show a fresh code.

## 9. User interface (Jetpack Compose, Material 3)

- Hero card: status ("Ready" / "Streaming to 2 speakers"), a 28-segment live level meter
  (teal/amber/red) with peak hold, an active/enabled speaker count pill, and a Start/Stop button.
- Red banner when capture is blocked, with the Spotify explanation.
- Battery optimization card ("Keep streaming with the screen off" -> system prompt).
- Speaker cards: name, model, IP, "AirPlay 2"/"AirPlay 1" badge, lock icon for PIN/password
  speakers, on/off switch, status line with actions (Enter code / Enter password / Retry), volume
  slider, menu with Automatic / Force AirPlay 2 / Force AirPlay 1 and Remove (manual speakers).
- Dialogs: add by IP, 4-digit code, password. Diagnostics sheet: capture state, help for Spotify,
  discovery, PIN/password and battery, per-speaker packet/retransmit/drop counters, recent log
  with Copy.
- Rendered screenshots (JVM, Robolectric): [`screenshots/01-ready.png`](../screenshots/01-ready.png),
  [`02-streaming.png`](../screenshots/02-streaming.png),
  [`03-capture-blocked.png`](../screenshots/03-capture-blocked.png), plus the launcher and
  status-bar icons.

## 10. Privacy and security

- No internet access is used. The only network traffic is mDNS and AirPlay to speakers on the LAN.
  No accounts, analytics, ads or crash reporting.
- Stored on the phone (app-private SharedPreferences, excluded from cloud backup and device
  transfer): selected speakers, volumes, protocol choices, manual speakers, a random sender
  identity, and the HAP long-term key plus PIN pairings. Receiver passwords and PINs are kept in
  memory only.
- The only key compiled into the app is the public RSA key of the AirPort Express protocol.

## 11. Testing strategy

| Layer | What is tested | Where |
|---|---|---|
| Codecs | ALAC bit layout and bit-exact round trips; FFmpeg decodes our ALAC bit-exactly; bplist round trip and decoding of a Python `plistlib` file; Python reads our bplist; TLV8 fragmentation | JVM |
| Crypto | SRP-6a against HAP spec vectors; RFC 8439 ChaCha20-Poly1305; RFC 5869 HKDF; HKDF-SHA-512 and HAP-nonce ChaCha against Python `cryptography`; RFC 7748 X25519; RFC 8032 Ed25519; HAP framing | JVM |
| Discovery | real TXT records (Sonos One, HomePod, Apple TV 4K, AirPort Express gen 1-3, Sony AVR, Shairport-Sync), feature bits, protocol choice, merge/loss/expiry, DNS codec with compression, raw browser over loopback UDP | JVM |
| Audio pipeline | ring buffer wrap/overflow and a 2-million-sample concurrent stress test, chunker, meter dBFS, blocked-capture detector timing, drift corrector | JVM |
| AirPlay 1 | full RTSP + UDP sessions against a socket mock receiver: plain ALAC, RSA/AES, digest password + L16, wrong/missing password, 403 -> auth-setup, retransmit, sync, timing, volume, FLUSH, TEARDOWN; decoded PCM equals sent PCM | loopback |
| AirPlay 2 | GET /info, transient pairing, PIN pairing + Pair-Verify + saved-key Pair-Verify, wrong PIN, password, HTTP 470, encrypted RTSP, both SETUPs, feedback, event channel, sealed RTP decrypted and decoded bit-exact, L16 variant | loopback |
| Sessions | AP1 + AP2 at once with identical timestamps and independent volumes; AP2 -> AP1 fallback; forced AP2 does not fall back; PIN prompt -> wrong code -> new code -> streaming | loopback |
| UI | Compose screen rendered to PNG (optional test) | Robolectric |

What tests cannot prove: that a specific Sonos/HomePod/Apple TV/AVR firmware accepts every detail
(especially NTP timing for AirPlay 2), Android capture behaviour on each phone model, battery
policy, and how the phone behaves with the screen locked. Those need the on-device checks in
section 13.

## 12. Limitations and risks

| Item | Status |
|---|---|
| Spotify, some DRM apps | Capture blocked by the app; detected and explained; no workaround on unrooted Android |
| AirPlay 2 with NTP timing | Implemented per owntone/pyatv; per-device acceptance must be checked on real speakers. Fallback to AirPlay 1 is automatic when available |
| PTP timing, buffered AAC (type 103), AirPlay video/mirroring, FairPlay | Not implemented (not possible without root, or not needed for music) |
| HomePod/Apple TV set to "Only people sharing this home" | HomeKit access control is not supported. Set "Allow speaker access" to "Everyone on the same network" |
| Lip sync with video | Not suitable: about 2 s of delay |
| Phone keeps playing locally | Android does not let capture apps silence the source. Lower the phone volume; on most phones the captured stream does not depend on it (check once) |
| Screen lock | Some Android versions end a capture session when the phone locks; AirBridge then stops cleanly and says so |
| Cross-protocol sync | AirPlay 1 and AirPlay 2 speakers in one room may be slightly offset |
| VPN apps, guest Wi-Fi, AP/client isolation | Can block discovery or streaming; turn off the VPN or use the main Wi-Fi |

## 13. Build plan (done) and on-device test plan (next)

Done in this build: protocol engine, Android capture service, discovery, UI, 83 automated tests,
lint clean, signed debug APK (`AirBridge-debug.apk`, attached to the
[v0.1.0-debug release](https://github.com/edge-sentinel-crypto/airbridge-android/releases/tag/v0.1.0-debug)).

Next, on a real phone and speakers:

1. Install ([INSTALL.md](INSTALL.md)), open AirBridge, check every speaker appears with the right badge.
2. Play from an app that allows capture (for example a downloaded MP3 in the Files or a podcast
   app). Start streaming to one speaker at a time: Sonos, HomePod, Apple TV, AVR, AirPort/WiiM.
   Note for each: connects (Y/N), protocol shown, audio (Y/N), volume works, dropouts in 10 min.
3. If an AirPlay 2 speaker shows "Playing" but stays silent, set it to "Force AirPlay 1" and
   repeat. Report which ones needed it.
4. Two and three speakers at once; check they are in step.
5. Lock the screen for 10 minutes; confirm audio continues (or note the Android message).
6. Start Spotify and confirm the blocked-capture banner appears within about 3 s.
7. Send the Diagnostics log (Copy button) for any failure.
