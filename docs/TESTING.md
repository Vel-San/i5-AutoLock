# Testing AutoLock locally (macOS & Windows)

This app can be fully exercised **without a car and without a BlueLink account** using
**Demo mode** + **Dry run**. You only need the Android SDK and an emulator or a phone.

---

## 1. Install the toolchain

### Option A — Android Studio (easiest, macOS & Windows)
1. Install **Android Studio** (latest stable) from https://developer.android.com/studio.
2. First run installs the Android SDK, an emulator system image, and JDK 17.
3. `File → Open` this project folder. Let Gradle sync.
4. Create an emulator: **Device Manager → Create device** (e.g. Pixel 7, API 34+).

### Option B — Command line only
Install JDK 17 and the Android command-line tools.

**macOS (Homebrew):**
```bash
brew install --cask temurin@17
brew install --cask android-commandlinetools
# set env (add to ~/.zshrc)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
sdkmanager "platform-tools" "platforms;android-35" "system-images;android-34;google_apis;arm64-v8a" "emulator"
avdmanager create avd -n pixel_api34 -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_7
```

**Windows (PowerShell, with winget):**
```powershell
winget install --id EclipseAdoptium.Temurin.17.JDK
winget install --id Google.AndroidStudio   # or install command-line tools manually
# Then set ANDROID_HOME to %LOCALAPPDATA%\Android\Sdk and add platform-tools + emulator to PATH.
sdkmanager "platform-tools" "platforms;android-35" "system-images;android-34;google_apis;x86_64" "emulator"
avdmanager create avd -n pixel_api34 -k "system-images;android-34;google_apis;x86_64" -d pixel_7
```

> Create `local.properties` (copy from `local.properties.example`) if Gradle can't find
> your SDK, and set `sdk.dir` to your Android SDK path.

---

## 2. Build & run

```bash
# macOS/Linux
./gradlew assembleDebug
./gradlew testDebugUnitTest

# Windows
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```

Start an emulator, then install:
```bash
emulator -avd pixel_api34 &     # or launch from Android Studio Device Manager
./gradlew installDebug          # gradlew.bat installDebug on Windows
```

Or just press **Run ▶** in Android Studio.

---

## 3. Exercise the app (no car needed)

1. Launch the app, grant permissions.
2. **Settings → Safety:** turn on **Demo mode**, keep **Dry run** selected.
3. **Settings → Account:** tap **Sign in → "Skip and use Demo mode instead"**
   (or it's already demo). Then **Load vehicles** and select **My Ioniq 5**.
4. Back on **Home:** flip the master switch **on**, then tap **Simulate leaving**.
5. Watch the status card go `Confirming → Locking in Ns → Verifying → Locked ✓` and the
   activity log fill in. In dry-run it logs *"would have locked"* and never calls the API.
6. Tap **Cancel** mid-countdown to verify aborts work.

Run the unit tests to validate the core logic:
```bash
./gradlew testDebugUnitTest        # LockPolicy, LockStateMachine, FakeBlueLinkClient
```

---

## 4. Simulate a real Bluetooth-disconnect trigger (optional)

The production trigger is a Bluetooth `ACL_DISCONNECTED` from your car. On a physical
phone you can test end-to-end:
1. Pair the phone with any Bluetooth device (even earbuds or your actual car).
2. In **Settings → Car Bluetooth**, select that device as "the car".
3. Enable AutoLock, then turn the device off / walk out of range → the foreground
   service starts and the evaluation runs.

Emulators have no real Bluetooth stack, so use **Simulate leaving** there, and use a
physical device for true Bluetooth testing.

---

## 5. Going live (EU)

1. **Settings → Safety:** turn **Demo mode off**, choose region **EU**.
2. **Account → Sign in:** complete the Hyundai login in the WebView. The app captures the
   OAuth code on-device and stores tokens encrypted.
3. **Load vehicles**, pick your Ioniq 5.
4. Keep **Dry run** until you're confident, then switch to **Armed**.
5. On the phone, allow **background** location and disable battery optimization for the app
   (Android Settings → Apps → AutoLock) so the background trigger is reliable.

> If sign-in fails, the EU endpoints/stamp in `data/bluelink/eu` may need updating against
> the `bluelinky` / `hyundai_kia_connect_api` reference projects. All EU specifics live in
> that one package.
