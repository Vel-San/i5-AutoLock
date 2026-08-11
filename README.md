# AutoLock

Automatically locks your (unlocked) **Hyundai Ioniq 5** when you leave it, via the
myHyundai **BlueLink** API. Single-purpose, private, on-device, background-first.

- 100% Kotlin · Jetpack Compose (Material 3, dynamic color) · Hilt · Coroutines/Flow · Ktor · WorkManager
- EU-first (OAuth token flow), with structure for US/CA/AU
- **Dry-run** and **demo** modes so you can try it safely with no car and no risk
- Hardware-encrypted tokens, no cleartext traffic, no analytics — everything stays on your device

> ⚠️ **Unofficial.** The BlueLink API is reverse-engineered and may change or rate-limit.
> A valid BlueLink account is required for real use. This app controls a real vehicle —
> use responsibly and at your own risk.

---

## Table of contents
- [How it works](#how-it-works)
- [Features](#features)
- [Requirements](#requirements)
- [Setup (macOS & Windows)](#setup-macos--windows)
- [Build & run](#build--run)
- [First launch](#first-launch)
- [Run modes & safety](#run-modes--safety)
- [Statistics & diagnostics](#statistics--diagnostics)
- [Security & privacy](#security--privacy)
- [Project structure](#project-structure)
- [Continuous integration](#continuous-integration)
- [Troubleshooting](#troubleshooting)
- [Contributing / making the repo public](#contributing--making-the-repo-public)
- [Disclaimer](#disclaimer)

---

## How it works
1. **Trigger** — your phone disconnects from the car's Bluetooth → an evaluation starts.
2. **Confirm** (optional) — Activity Recognition (driving → walking) and/or a geofence
   confirm you actually walked away, cutting false positives.
3. **Grace** — a configurable countdown you can cancel from the notification.
4. **Verify** — the app reads live vehicle status; it only acts if the car is unlocked
   and the engine is off.
5. **Lock** — sends the BlueLink lock command (or, in dry-run, just logs what it *would* do).

The detection lifecycle is a pure state machine:
`IDLE → ARMED → CONFIRMING → GRACE → VERIFYING → LOCKING → LOCKED / SKIPPED` (reconnect or
cancel → `ABORTED`). See [CLAUDE.md](CLAUDE.md) for full architecture and conventions.

## Features
- **Automatic locking** in the background via a short-lived foreground service with a
  cancellable notification.
- **Layered detection** — Bluetooth disconnect (primary) + optional activity recognition +
  optional geofence, each toggleable.
- **Configurable** grace period, geofence radius, region, run mode, and "confirm before locking".
- **Multi-region ready** — EU implemented; US/CA/AU slot in behind the same interface.
- **On-device EU login** — signs in through Hyundai's real page in a locked-down WebView and
  captures the OAuth token on-device; auto-refreshes.
- **Demo mode** — a simulated car so you can use the whole app with no account and no hardware.
- **API statistics** — call durations, success rate, rate-limit status, and full activity log.

## Requirements
- **JDK 17**
- **Android SDK** — platform `android-35` + build-tools `35.0.0` (Android Studio installs these)
- An Android device or emulator running **Android 8.0 (API 26)** or newer
- Android Studio (latest stable) is the easiest path; command-line tools also work

## Setup (macOS & Windows)
Full step-by-step (including installing the SDK from scratch and creating an emulator) is in
[docs/TESTING.md](docs/TESTING.md). Quick version:

**Easiest — Android Studio:** install it, `File → Open` this folder, let Gradle sync, press **Run ▶**.
It bundles JDK 17 and installs the SDK for you.

**Command line:** make sure `JAVA_HOME` points at a JDK 17, then point Gradle at your SDK:
```bash
# macOS/Linux
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
# Windows (PowerShell)
"sdk.dir=$env:LOCALAPPDATA\Android\Sdk" | Out-File -Encoding ascii local.properties
```
`local.properties` is git-ignored — it must never be committed.

## Build & run
```bash
# macOS/Linux                     # Windows
./gradlew testDebugUnitTest       gradlew.bat testDebugUnitTest   # fastest check, no device needed
./gradlew lint                    gradlew.bat lint                # static analysis
./gradlew assembleDebug           gradlew.bat assembleDebug       # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug            gradlew.bat installDebug        # install on a booted emulator / plugged-in phone
```
The first build downloads Gradle via the wrapper and the dependencies (a few minutes); later
builds are cached and fast.

## First launch
1. Grant the requested permissions (Bluetooth, location, activity, notifications).
2. Open **Settings → Account** and either **Sign in** (EU WebView flow) or turn on
   **Demo mode** to try it without an account.
3. **Load vehicles** and select your Ioniq 5.
4. In **Settings → Car Bluetooth**, pick the paired device that represents your car.
5. Keep **Dry run** on until you trust it, then tap **Simulate leaving** on the home screen
   to watch the full flow.
6. When ready, switch **Safety → Locking behaviour** to **Armed**.

## Run modes & safety
| Mode | What it does |
|------|--------------|
| **Demo** | Uses a simulated car (`FakeBlueLinkClient`). No account or hardware needed. |
| **Dry run** *(default)* | Runs the entire detect → verify flow but **never sends a real lock** — it logs "would have locked". |
| **Armed** | Sends real BlueLink lock commands. |

Before locking, AutoLock always force-refreshes vehicle status and **skips** if the car is
already locked, the engine is running, or the state is unknown.

## Statistics & diagnostics
**Settings → Open API statistics** shows detailed, privacy-safe metrics:
- Session: region, live/demo, command mode, account, selected vehicle
- **Rate-limit status** with a cooldown estimate, and total rate-limit hits
- Totals: call count, success rate, successes/failures/auth failures, average duration, last call
- **Recent API calls** — timestamp · operation · duration · outcome
- **Activity log** — human-readable events (each list has a Clear action)

Metrics record only operation name, duration, and outcome — never tokens, headers, VINs, or
account data.

## Security & privacy
- **Tokens** are stored in EncryptedSharedPreferences (AES-256-GCM) with an Android Keystore
  master key, **StrongBox-backed when available**. A corrupted store is wiped and you
  re-authenticate — there is never a plaintext fallback. Tokens are never logged or backed up.
- **Network** — cleartext traffic is disabled app-wide, only system CAs are trusted (no
  user-added certs), and certificate-pinning slots are ready for the BlueLink hosts
  (`app/src/main/res/xml/network_security_config.xml`).
- **Login** — the EU WebView runs under `FLAG_SECURE` (no screenshots / recents preview), with
  file & content access off, no password/form saving, no cache; cookies and cache are cleared
  on exit so no Hyundai web session lingers.
- **On-device only** — no analytics, no third-party backend. The only network calls go to
  Hyundai/Kia BlueLink servers. Backups are disabled (`allowBackup=false`).
- The `client_id` / `secret` / `stamp` constants in `RegionConfig` are **public,
  reverse-engineered app constants** (the same ones in the reference projects), **not** your
  Hyundai credentials — safe to keep in a public repo.

## Project structure
```
app/src/main/java/com/i5autolock/
├─ data/
│  ├─ settings/   AppSettings + DataStore repository
│  ├─ secure/     Encrypted token store (Keystore/StrongBox)
│  ├─ device/     Bonded Bluetooth device listing
│  ├─ metrics/    ApiMetrics telemetry
│  └─ bluelink/   Client interface, provider, metered decorator
│     ├─ eu/      Real EU OAuth + control flow
│     └─ fake/    Simulated client (demo/dry-run/tests)
├─ domain/        AutoLockController, ActivityLog, detection state machine, LockPolicy
├─ service/       Foreground service + notification
├─ receiver/      Bluetooth + boot receivers
└─ ui/            Compose: home, settings, login, stats, permissions, theme
```

## Continuous integration
[.github/workflows/ci.yml](.github/workflows/ci.yml) runs on every push and PR:
- **guard** — verifies `CLAUDE.md`/`README.md`/`CHANGELOG.md` exist and `CLAUDE.md` stays
  current (with a Changelog), blocks committed secrets/keystores/config, and scans for
  secret patterns (plus best-effort gitleaks).
- **android** — validates the Gradle wrapper, sets up JDK 17 + SDK 35, then runs
  `testDebugUnitTest`, `lint`, and `assembleDebug`, uploading the reports.

Dependabot ([.github/dependabot.yml](.github/dependabot.yml)) keeps Gradle and Actions deps patched.

### Releases
[.github/workflows/release.yml](.github/workflows/release.yml) publishes a GitHub Release with
a built APK whenever you push a version tag:

```bash
# bump versionName/versionCode in app/build.gradle.kts and add a CHANGELOG section first
git tag v0.1.0
git push origin v0.1.0
```

The release notes are taken from the matching `## [0.1.0]` section of
[CHANGELOG.md](CHANGELOG.md) (the same file the in-app **About → What's new** screen shows).
To ship a **signed** APK, add these repository secrets (otherwise the APK is unsigned):
`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
Locally you can sign by setting the same keys (minus the `_BASE64`, using `RELEASE_STORE_FILE`)
in `local.properties`.

## Troubleshooting
- **`SDK location not found`** — create `local.properties` with `sdk.dir=...` (see
  [Setup](#setup-macos--windows)), or set `ANDROID_HOME`.
- **`Unable to locate a Java Runtime`** — install JDK 17 and ensure `JAVA_HOME`/`java` point to it.
- **Emulator doesn't trigger on Bluetooth** — emulators have no real Bluetooth stack; use
  **Simulate leaving** on the emulator and test real disconnects on a physical phone.
- **Background trigger unreliable** — allow **background location** and disable battery
  optimization for AutoLock (Android Settings → Apps → AutoLock).
- **EU sign-in fails** — endpoints/stamp values may have changed; all EU specifics live in
  `data/bluelink/eu` and can be re-verified against the reference projects.

## Contributing / making the repo public
This repo is designed to be safe to open-source:
- Never commit `local.properties`, keystores, `*.pem`/`.env`, or `google-services.json`
  (they're git-ignored and CI blocks them).
- Never commit real credentials or tokens — they only ever live encrypted on-device.
- Keep [CLAUDE.md](CLAUDE.md) updated in the **same change** as any architecture/flow change;
  CI enforces its presence and Changelog.

## Disclaimer
Not affiliated with, endorsed by, or supported by Hyundai, Kia, or Genesis. "BlueLink" and
related marks belong to their owners. Endpoints are unofficial and may break at any time.
Use at your own risk.
