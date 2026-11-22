# Installing AirBridge on your Android phone

The app file is `AirBridge-debug.apk`. It is attached to the GitHub release
[v0.1.0-debug](https://github.com/edge-sentinel-crypto/airbridge-android/releases/tag/v0.1.0-debug).
Download it on the phone or on a computer.

Requirements: Android 10 or newer, and the phone on the same Wi-Fi network as the speakers.

## 0. Check the file (optional, recommended)

The expected SHA-256 of `AirBridge-debug.apk` is:

```
ad9ed70a1c9a80bcd333da8b28783187b7635436d63e7a34f51068f0d78ec279
```

In Windows PowerShell, in the folder where you saved the file:

```powershell
Get-FileHash .\AirBridge-debug.apk -Algorithm SHA256
```

On macOS: `shasum -a 256 AirBridge-debug.apk`. On Linux: `sha256sum AirBridge-debug.apk`.
The printed hash must match the one above. PowerShell prints it in capital letters; it is the
same hash.

## 1. Install method A: copy the file to the phone (no PC tools)

1. Connect the phone to the PC with USB and choose **File transfer** on the phone's USB prompt.
   (Or download the APK on the phone itself from the release page, or send it to yourself
   another way you already use, such as your own Google Drive.)
2. Copy `AirBridge-debug.apk` into the phone's `Download` folder.
3. On the phone, open the **Files** app (or "My Files" on Samsung), go to **Downloads**, and tap
   `AirBridge-debug.apk`.
4. Android says the app is not allowed to install unknown apps. Tap **Settings**, turn on
   **Allow from this source** for Files, then go back.
5. Tap **Install**. If Google Play Protect warns about an unknown developer, tap **More details**
   then **Install anyway**. (The APK is signed with a debug key, not a Play Store key, so this
   warning is expected.)
6. Tap **Open**.

## 2. Install method B: USB with ADB from Windows

1. Download Google's "SDK Platform-Tools for Windows" zip from
   developer.android.com/tools/releases/platform-tools and unzip it, for example to
   `C:\platform-tools`.
2. On the phone: **Settings > About phone**, tap **Build number** 7 times to enable Developer
   options. Then **Settings > System > Developer options** (location varies by brand), turn on
   **USB debugging**.
3. Connect the phone by USB. On the phone accept **Allow USB debugging** for this computer.
4. In PowerShell:

```powershell
cd C:\platform-tools
.\adb devices
.\adb install -r "$HOME\Downloads\AirBridge-debug.apk"
```

If you saved the APK somewhere other than your Downloads folder, use that path instead.

`adb devices` must list the phone as `device` (not `unauthorized`). `adb install` prints
`Success`. Use the Windows adb, not one inside WSL: WSL does not see USB devices by default.

## 3. First run: permissions

1. Open **AirBridge**. Speakers on your Wi-Fi appear within a few seconds.
2. If the **Keep streaming with the screen off** card appears, tap **Allow**, then **Allow** in the
   system dialog. (Or later: Settings > Apps > AirBridge > Battery > **Unrestricted**.)
3. Turn on the switch next to each speaker you want.
4. Start music in another app (see section 5 about Spotify), then tap **Start streaming** in
   AirBridge. Android then asks:
   - **Notifications**: tap **Allow**, so the streaming notification and its Stop button show.
   - **"Allow AirBridge to record audio?"**: tap **While using the app** / **Allow**. Android
     requires this permission for capturing other apps' sound. AirBridge does not use the
     microphone.
   - **"Start recording or casting with AirBridge?"** (Android 14+: "Share your screen"): tap
     **Start** (or **Start now**). AirBridge captures only audio; nothing from the screen is
     recorded or sent.
5. The speakers start playing about 2 seconds behind the phone.

Every time you start streaming, Android asks the capture question again. That is an Android rule.

## 4. Everyday use

- **Volume per speaker:** use the slider under each speaker. 0 % mutes that speaker.
- **Add or remove a speaker while playing:** flip its switch.
- **Stop:** tap **Stop streaming** in the app, or **Stop** in the notification.
- **Phone also plays the music:** Android does not let AirBridge silence the original app. Turn the
  phone's own volume down. On most phones this does not change what the speakers get; check once
  on your phone.
- **Speaker not in the list:** tap the **+** icon and enter its IP address (port 7000; older units
  5000). Find the IP in your router's device list or the speaker's own app.
- **Protocol:** each speaker shows **AirPlay 2** or **AirPlay 1**. The ⋮ menu offers Automatic,
  Force AirPlay 2 and Force AirPlay 1.

## 5. Troubleshooting

### Spotify plays but the speakers are silent

Spotify blocks Android's audio capture, so every capture app gets silence. AirBridge shows a red
banner, "Music is playing, but AirBridge hears silence", after about 3 seconds. This cannot be fixed
from the phone without root. Options:

- Use **Spotify Connect** from the Spotify app (the speaker icon in Spotify) on speakers that have
  it: Sonos, Denon/Marantz (HEOS), Yamaha (MusicCast), WiiM and many others.
- Play the same music from an app that allows capture (local music players, many podcast apps, a
  web browser for some sites) and stream it with AirBridge.
- For HomePod or AirPort Express with Spotify: use an iPhone/Mac, or a Spotify Connect to AirPlay
  bridge such as SpotConnect or owntone on an always-on computer.

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

Tap the **i** icon for Diagnostics. It shows each speaker's state and packet counts and the recent
log. **Copy** puts the log on the clipboard so you can send it.

## 6. Update or remove

- Update: install the new APK the same way. With ADB use `adb install -r`.
- Remove: long-press the AirBridge icon > App info > Uninstall. This also deletes saved pairings.

## 7. Building from source

These are the Linux steps used for the published build (WSL Ubuntu 24.04, no sudo). Choose a
folder for the toolchain; the steps below use `$HOME/android-toolchain`.

```bash
T=$HOME/android-toolchain
mkdir -p $T/downloads && cd $T/downloads
# JDK 17 (Temurin 17.0.21.1+2, sha256 0b0ac5eab6db6abaec68c9913bcddb31321e5da411c45073d520d9c125e6f7b4)
# "latest" can serve a newer 17.x release; if it does, change the folder name in the ln command.
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
# Environment (also saved as $T/env.sh)
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

Then get the source and run every Gradle command from inside the `airbridge-android/` folder. Under
WSL, keep the clone on the Linux file system, not on a Windows drive (builds there are much slower).
Test, assemble and lint:

```bash
. $HOME/android-toolchain/env.sh
cd $HOME   # or any other folder on the Linux file system
git clone --depth 1 https://github.com/edge-sentinel-crypto/edge-sentinel.git
cd edge-sentinel/airbridge-android
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
