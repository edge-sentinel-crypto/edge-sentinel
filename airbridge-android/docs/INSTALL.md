# Installing AirBridge on your Android phone

The app file is `AirBridge-debug.apk` (version 0.3.0, 29,333,668 bytes), attached to the GitHub
release [v0.3.0-debug](https://github.com/edge-sentinel-crypto/airbridge-android/releases/tag/v0.3.0-debug). Direct download link:

```
https://github.com/edge-sentinel-crypto/airbridge-android/releases/download/v0.3.0-debug/AirBridge-debug.apk
```

Requirements: Android 10 or newer, and the phone on the same Wi-Fi network as the speakers.

Already using v0.1.0-debug or v0.2.0-debug? Install this file the same way, without uninstalling
first; Android updates AirBridge in place (same package name and signing key, higher version code)
and keeps your speakers, volumes, sync offsets, groups and pairings. See section 6.

## 0. Check the file (optional, recommended)

The expected SHA-256 of the v0.3.0-debug `AirBridge-debug.apk` is:

```
6c10a385e5f222e86f4b5ed410b68d0837f013a45b7182422a9561ba6d3d5dc5
```

On a computer, in the folder where you saved the APK:

```powershell
# Windows PowerShell (prints the hash in upper case; compare without regard to case)
Get-FileHash .\AirBridge-debug.apk -Algorithm SHA256
```

```bash
shasum -a 256 AirBridge-debug.apk    # macOS
sha256sum AirBridge-debug.apk        # Linux
```

The hash printed must match the one above.

## 1. Install method A: put the file on the phone and tap it (no PC tools)

1. Get the APK onto the phone in one of these ways:
   - **Download on the phone:** open the release page (link above) in the phone's browser and tap
     `AirBridge-debug.apk` under **Assets**, or open the direct download link. If the browser warns
     that this type of file can harm your device, confirm the download.
   - **Copy from a computer:** download the APK on the computer, connect the phone with USB, choose
     **File transfer** on the phone's USB prompt, and copy `AirBridge-debug.apk` into the phone's
     `Download` folder. (Or send the APK to yourself another way you already use, such as your own
     Google Drive.)
2. On the phone, open the **Files** app (or "My Files" on Samsung), go to **Downloads**, and tap
   `AirBridge-debug.apk`. (After a browser download you can also tap the download notification.)
3. Android says the app is not allowed to install unknown apps. Tap **Settings**, turn on
   **Allow from this source** for the app you opened the file from (Files or the browser), then go
   back.
4. Tap **Install**. If Google Play Protect warns about an unknown developer, tap **More details**
   then **Install anyway**. (The APK is signed with a debug key, not a Play Store key, so this
   warning is expected.)
5. Tap **Open**.

## 2. Install method B: USB with ADB from Windows

1. Download Google's "SDK Platform-Tools for Windows" zip from
   developer.android.com/tools/releases/platform-tools and unzip it, for example to
   `C:\platform-tools`.
2. On the phone: **Settings > About phone**, tap **Build number** 7 times to enable Developer
   options. Then **Settings > System > Developer options** (location varies by brand), turn on
   **USB debugging**.
3. Connect the phone by USB. On the phone accept **Allow USB debugging** for this computer.
4. In PowerShell (this assumes you saved the APK in your Downloads folder):

```powershell
cd C:\platform-tools
.\adb devices
.\adb install -r "$HOME\Downloads\AirBridge-debug.apk"
```

`adb devices` must list the phone as `device` (not `unauthorized`). `adb install` prints
`Success`. Use the Windows adb, not one inside WSL: WSL does not see USB devices by default.
On macOS or Linux, use the Platform-Tools package for that system and run
`adb install -r AirBridge-debug.apk` in the folder that holds the APK.

## 3. First run: permissions

1. Open **AirBridge**. Speakers on your Wi-Fi appear within a few seconds.
2. If the **Keep streaming with the screen off** card appears, tap **Allow**, then **Allow** in the
   system dialog. (Or later: Settings > Apps > AirBridge > Battery > **Unrestricted**.)
3. Turn on the switch next to each speaker you want.
4. Start music in another app (see section 5 if the speakers stay silent), then tap
   **Start streaming** in AirBridge. Android then asks:
   - **Notifications**: tap **Allow**, so the streaming notification and its Stop button show.
   - **"Allow AirBridge to record audio?"**: tap **While using the app** / **Allow**. Android
     requires this permission for capturing other apps' sound. AirBridge does not use the
     microphone.
   - **"Start recording or casting with AirBridge?"** (Android 14+: "Share your screen"): tap
     **Start** (or **Start now**). AirBridge captures only audio; nothing from the screen is
     recorded or sent.
5. The speakers start playing about 2 seconds behind the phone. AirBridge does not request audio
   focus, so the app you are playing from keeps playing.

Every time you start streaming, Android asks the capture question again. That is an Android rule.

## 4. Everyday use

- **Volume per speaker:** use the slider under each speaker. 0 % mutes that speaker.
- **Master volume:** the **Master volume** slider above the speaker list moves every speaker that is
  on and keeps their balance. Dragging it down and back up returns each speaker to where it was. If
  every speaker is at 0 %, the master raises them all together.
- **Add or remove a speaker while playing:** flip its switch.
- **All on / All off:** the first chip above the speaker list turns every speaker on (**All on**),
  or, when they are all on, every speaker off (**All off**). It works while streaming too.
- **Speaker groups:** turn on the speakers you want together, tap **Save group** and give the group a
  name (for example "Downstairs"). Tap the group's chip later to play exactly those speakers; the
  chip shows a check mark while they are the ones playing. Speakers of the group that are not on the
  network are skipped (the chip then shows, for example, "2/3"). Long-press a chip to rename or
  delete the group. Saving under an existing group's name replaces its speakers.
- **Sync offset:** if one speaker sounds slightly ahead of or behind the others, open its ⋮ menu >
  **Sync offset**. Slide to a positive value to delay it (for example **+40 ms (delay)**) or to a
  negative value to make it play earlier (for example **-25 ms (earlier)**), up to 250 ms either way
  in 5 ms steps. The change applies while the music plays, without reconnecting; **Reset** sets it
  back to 0. A small badge such as **+40 ms** on the speaker card shows the offset.
- **Stop:** tap **Stop streaming** in the app, or **Stop** in the notification.
- **Phone also plays the music:** Android does not let AirBridge silence the original app. Turn the
  phone's own volume down. On most phones this does not change what the speakers get; check once
  on your phone.
- **Speaker not in the list:** tap the **+** icon and enter its IP address (port 7000; older units
  5000). Find the IP in your router's device list or the speaker's own app.
- **Protocol:** each speaker shows **AirPlay 2** or **AirPlay 1**. In Automatic mode, if AirPlay 2
  does not connect and the speaker also offers AirPlay 1, AirBridge uses AirPlay 1 and the badge
  reads **AirPlay 1 (fallback)** (section 5 explains why). The ⋮ menu offers Automatic, Force
  AirPlay 2 and Force AirPlay 1.
- **Speaker with a new IP address:** if a speaker comes back with a new address (for example after
  a router restart), AirBridge updates its card instead of adding a second one. Its volume, sync
  offset, group membership and pairing stay. AirBridge looks for speakers only while it is open on
  screen, so it sees the new address only then:
  - With AirBridge open on screen: if the speaker was playing and lost its connection, AirBridge
    reconnects to the new address as soon as the speaker announces it.
  - With AirBridge in the background or the screen off: if the speaker was playing, AirBridge keeps
    trying the old address, and after six failed attempts the speaker card shows the error in red
    with a **Retry** button. Open AirBridge; once it finds the speaker at the new address, it
    reconnects by itself.

## 5. Troubleshooting

### Music plays but the speakers are silent

AirBridge uses Android's standard playback capture (Android 10 and newer; no root and no
workarounds). It works with Spotify and most other media apps when the app and the phone allow
capture. An app, or a particular version of it, can opt out of capture (for example with
`android:allowAudioPlaybackCapture="false"` in its manifest). Android then gives every capture app,
AirBridge included, silence for that app. When music is playing but AirBridge has received only
silence for about 3 seconds, it shows a red banner, "Music is playing, but AirBridge hears silence".
Options:

- Check the playing app's settings.
- Try another player for the same music (another music app, a podcast app, or a web browser).
- Use **Spotify Connect** or **Cast** from the playing app on speakers that support it (for
  example Sonos, Denon/Marantz HEOS, Yamaha MusicCast, WiiM).

The banner appears only while Android reports that music is playing. If the speakers are silent and
there is no banner, see the next sections.

### A speaker does not appear

- The phone and the speaker must be on the same network. Guest networks usually isolate devices.
- Turn off VPN apps on the phone; many send local traffic into the tunnel.
- Some mesh/router settings ("AP isolation", "client isolation", multicast filtering) hide
  AirPlay. Add the speaker by IP with the **+** icon.
- Tap the refresh icon to search again.

### A speaker connects ("Playing") but stays silent

Open its ⋮ menu and choose **Force AirPlay 1**, then turn it off and on again. Some AirPlay 2
receivers do not accept the timing method Android apps can use (details in
[PLAN.md, section 7.6](PLAN.md#76-timing-ntp-not-ptp)).

### A speaker shows "AirPlay 1 (fallback)"

The speaker advertises AirPlay 2, but the AirPlay 2 connection failed, so AirBridge plays to it over
AirPlay 1. The usual cause is a receiver that accepts only PTP timing (Android apps can offer only
NTP timing). The speaker keeps playing normally over AirPlay 1. Tap the **i** icon (Diagnostics) to
see the AirPlay 2 error for that speaker. The speaker's ⋮ menu can force AirPlay 2 to try again, or
force AirPlay 1 to skip the AirPlay 2 attempt.

### Two speakers play slightly out of step

All speakers share one clock, but receivers (especially AirPlay 1 next to AirPlay 2) can differ by a
few tens of milliseconds. Open the ⋮ menu of the speaker that is ahead and give it a positive sync
offset (it plays later), or give the one that lags a negative offset. Adjust in small steps while
the music plays. If a speaker skips or pauses briefly when you change the offset, that is it
re-aligning; it should then play steadily.

### The speaker asks for a code (Apple TV) or a password

- **Code:** the TV shows a 4-digit code; the speaker card says "Enter code". Type the code. AirBridge
  remembers the pairing, so you do this once. If you type a wrong code, the TV shows a new one.
- **Password:** speakers set to "Require password" show "Enter password". AirBridge keeps the
  password only until the app is closed.
- **HomePod / Apple TV with "Only people sharing this home":** AirBridge cannot join an Apple Home.
  In the Apple Home app, set **Allow Speaker & TV Access** to **Everyone on the same network** (or
  "Anyone on the same network").

### Music stops when the screen turns off

- Set battery use to **Unrestricted** (Settings > Apps > AirBridge > Battery).
- Some Android versions end screen/audio capture when the phone locks. AirBridge then stops and
  shows "Android ended audio capture". Keep the phone unlocked (screen can dim) if that happens on
  your phone.

### Something else

Tap the **i** icon for Diagnostics. It shows the capture state and format (44.1 kHz, or 48 kHz
converted to 44.1 kHz), each speaker's state, protocol, address and packet counts, and the recent
log. **Copy** puts the log on the clipboard so you can send it.

## 6. Update or remove

- Update: install the new APK the same way, without uninstalling first. With ADB use
  `adb install -r`. v0.3.0-debug has the same package name (`com.airbridge.airplay`) and signing key
  as v0.1.0-debug and v0.2.0-debug and a higher version code (3), so it installs over either of them
  as an update and keeps your settings, sync offsets, groups and pairings.
- Remove: long-press the AirBridge icon > App info > Uninstall. This also deletes saved pairings.

## 7. Building the APK from source

The build runs on Linux without sudo (the released APK was built on Ubuntu 24.04 under WSL2). The
commands below install JDK 17 and the Android SDK into one folder, `$HOME/android-toolchain`; any
folder works if you change the paths.

```bash
T=$HOME/android-toolchain
mkdir -p $T/downloads && cd $T/downloads
# JDK 17. The release was built with Temurin 17.0.21.1+2
# (OpenJDK17U-jdk_x64_linux_hotspot_17.0.21.1_2.tar.gz, sha256
# 0b0ac5eab6db6abaec68c9913bcddb31321e5da411c45073d520d9c125e6f7b4). This URL serves the latest 17.x;
# if that is newer, use its folder name in the ln command.
curl -L -o temurin17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
mkdir -p $T/jdk && tar -xzf temurin17.tar.gz -C $T/jdk && ln -sfn $T/jdk/jdk-17.0.21.1+2 $T/jdk-17
# Android command-line tools 23.0 (sha1 e025545c62a8e64c7559119566a569fb1dec5f60)
curl -L -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
mkdir -p $T/android-sdk/cmdline-tools $T/tmp-ct
python3 -c "import zipfile; zipfile.ZipFile('cmdline-tools.zip').extractall('$T/tmp-ct')"
mv $T/tmp-ct/cmdline-tools $T/android-sdk/cmdline-tools/latest && chmod +x $T/android-sdk/cmdline-tools/latest/bin/*
export JAVA_HOME=$T/jdk-17
yes | $T/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$T/android-sdk --licenses
$T/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$T/android-sdk "platforms;android-35" "build-tools;35.0.0" "platform-tools"
# Environment (saved as $T/env.sh)
cat > $T/env.sh <<'EOF'
export AIRBRIDGE_TOOLCHAIN=$HOME/android-toolchain
export JAVA_HOME=$AIRBRIDGE_TOOLCHAIN/jdk-17
export ANDROID_HOME=$AIRBRIDGE_TOOLCHAIN/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_USER_HOME=$AIRBRIDGE_TOOLCHAIN/android-user-home
export GRADLE_USER_HOME=$AIRBRIDGE_TOOLCHAIN/gradle-user-home
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/build-tools/35.0.0:$ANDROID_HOME/platform-tools:$PATH
EOF
```

Clone and build on the Linux file system (under WSL, inside your Linux home folder, not on a
Windows drive), then test, assemble and lint. (The same project is also published in the
`airbridge-android/` folder of `https://github.com/edge-sentinel-crypto/edge-sentinel`; there, run
the commands from inside that folder.)

```bash
. $HOME/android-toolchain/env.sh
git clone https://github.com/edge-sentinel-crypto/airbridge-android.git
cd airbridge-android
./gradlew testDebugUnitTest assembleDebug lintDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk
# Optional UI screenshots (downloads Robolectric's Android 14 runtime on first use):
./gradlew testDebugUnitTest -Pairbridge.screenshots --tests '*UiScreenshotTest*'   # -> app/build/screenshots/
```

The Gradle wrapper downloads Gradle 8.11.1 (sha256-pinned in
`gradle/wrapper/gradle-wrapper.properties`). A rebuild is signed with that machine's own debug key,
so its APK hash differs from the released file; the released APK's SHA-256 is in section 0.
