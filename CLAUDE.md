# CLAUDE.md — AutoLock (Hyundai Ioniq 5 auto-locker)

> **This file is the source of truth for how this codebase is built and must be kept
> up to date.** Whenever you change architecture, conventions, dependencies, the
> detection flow, or the BlueLink integration, update the relevant section in the SAME
> change. Treat drift between code and this file as a bug.

## 1. Mission (do not scope-creep)
One job: **automatically detect that the user has left their (unlocked) Hyundai Ioniq 5
and lock it via the BlueLink API.** Everything else exists only to make that reliable,
private, and effortless. Do not add trip logging, widgets, multi-user, cloud, or
analytics. If a feature doesn't serve the one job, don't build it.

## 2. Tech stack
- 100% Kotlin, JDK 17, `minSdk 26`, `target/compileSdk 35`.
- UI: Jetpack Compose + Material 3 (dynamic color). No XML layouts.
- DI: Hilt. Async: Coroutines + Flow. HTTP: Ktor (OkHttp engine) + kotlinx.serialization.
- Storage: DataStore (settings), EncryptedSharedPreferences/Keystore (tokens).
- Background: Foreground Service (`connectedDevice`) + BroadcastReceivers. WorkManager wired via Hilt.
- Build: Gradle version catalog (`gradle/libs.versions.toml`), KSP (never kapt),
  configuration cache + build cache on.

## 3. Module / package map (`app/src/main/java/com/i5autolock`)
- `data/settings/` — `AppSettings`, `SettingsRepository` (DataStore). All non-secret prefs.
- `data/secure/` — `SecureStore` + `SessionTokens`. Encrypted token storage. **Never log tokens.**
- `data/device/` — `BluetoothDevices` (list bonded devices for car picker).
- `data/location/` — `LocationHelper` (last location + reverse-geocoded label for parked spot).
- `data/status/` — `StatusCache` (last lock state/summary for the home-screen widget).
- `data/metrics/` — `ApiMetrics` (in-memory telemetry: op name, duration, outcome, rate-limit).
- `data/bluelink/` — region-agnostic client contract + selection:
  - `BlueLinkClient` (interface), `Region`, `RegionConfig`, `BlueLinkProvider` (picks client).
  - `MeteredBlueLinkClient` — decorator that times every call into `ApiMetrics`.
  - `model/` — domain models (`Vehicle`, `VehicleStatus`, `LockState`, `CommandResult`).
  - `eu/` — real EU flow: `EuAuth` (Stamp + OAuth URL), `EuDtos`, `EuBlueLinkClient`.
  - `fake/` — `FakeBlueLinkClient` for DRY_RUN/demo/tests.
- `domain/` — orchestration & policy (framework-light, unit-testable):
  - `detection/` — `DetectionState`, `DetectionEvent`, `LockStateMachine` (pure).
  - `usecase/` — `LockPolicy` (pure decide()).
  - `AutoLockController` (owns timers + side effects), `ActivityLog`.
- `service/` — `AutoLockService` (foreground), `AutoLockNotification`.
- `receiver/` — `BluetoothStateReceiver` (primary trigger), `BootReceiver`.
- `tile/` — `AutoLockTileService` (Quick Settings on/off tile).
- `widget/` — `AutoLockWidget` (home-screen glance + Lock now).
- `ui/` — Compose: `home/`, `settings/`, `login/`, `stats/`, `help/`, `permissions/`, `theme/`, `AppNavigation`.
- `di/` — `AppModule` (Json, HttpClient).

## 4. The two hard parts
### 4a. "I left the car" detection (`receiver` + `domain/detection` + `AutoLockController`)
Layered, each toggleable in settings:
1. **Primary trigger:** car Bluetooth `ACL_DISCONNECTED` (user picks the paired device MAC).
2. **Confirmation:** Activity Recognition IN_VEHICLE → ON_FOOT (optional).
3. **Confirmation:** geofence — moved beyond radius from parked spot (optional).
State flow: `IDLE → (ARMED) → CONFIRMING → GRACE → VERIFYING → LOCKING → LOCKED/SKIPPED`.
`LockStateMachine` is pure (no I/O) so it is exhaustively unit tested.
`AutoLockController` owns the grace timer, verify (force status refresh) and lock call.
Reconnect or user-cancel → `ABORTED`.

### 4b. EU BlueLink auth (`data/bluelink/eu`)
EU uses OAuth2. **Primary path is fully automatic, headless email+password** (ported from
`bluelink-refresh-token`), which avoids reCAPTCHA by talking to the IDP (`idpconnect-eu.hyundai.com`):
1. GET `/auth/api/v2/user/oauth2/authorize` (session cookies — needs Ktor `HttpCookies`).
2. GET `/auth/api/v1/accounts/certs` → JWK; RSA/PKCS1v1.5-encrypt password to hex (`EuAuth.encryptPassword`).
3. POST `/auth/account/signin` (form: encryptedPassword=true, password hex, kid, …) → **302 with `?code=`**.
4. POST `/auth/api/v2/user/oauth2/token` (grant_type=authorization_code, client_id, client_secret) → tokens.
`EuBlueLinkClient.loginWithPassword()`. Refresh (`ensureFreshSession` / `loginWithRefreshToken`) hits the
same IDP token endpoint with `grant_type=refresh_token` + client_id/secret. A pasted 48-char refresh
token is kept as an **Advanced** fallback.
The **access token** is then used against the CCSP API (`prd.eu-ccapi.hyundai.com:8080/api/v1/spa/...`).
Every CCSP request carries `ccsp-service-id`, `ccsp-application-id`, `ccsp-device-id`, `Stamp`
(`EuAuth.generateStamp`), `User-Agent: okhttp/3.12.0`, `Accept-Encoding: gzip`, `Connection: Keep-Alive`.

**EU lock uses a control-token flow** (ported from BlueDeck): register a device id via
`POST api/v1/spa/notifications/register` (stored in `SecureStore.deviceId`), then per command
`PUT api/v1/user/pin` with `{deviceId, pin, vehicleId}` → `controlToken`, then
`POST api/v1/spa/vehicles/{id}/control/door` (`action=close/open`) using the control token as
`Authorization`. The 4-digit BlueLink PIN is captured at login and stored in `SecureStore` (encrypted).

> EU constants in `RegionConfig` (base URL, `clientId`/service-id, `appId`, `clientSecret`,
> `basicAuth`, `stampCfb`) are verified against **BlueDeck** and `hyundai_kia_connect_api`.
> These are public reverse-engineered app constants, NOT user credentials. Keep ALL EU
> specifics inside `data/bluelink/eu`.

> **Endpoints/keys in `RegionConfig` and `EuBlueLinkClient` are reverse-engineered from
> `bluelinky` (TS) and `hyundai_kia_connect_api` (Python) and change over time.** Verify
> against those projects before shipping. Keep ALL EU specifics inside `data/bluelink/eu`.

## 5. Safety model (never bypass)
- `RunMode.DRY_RUN` (default) runs the whole flow but NEVER sends a real lock — logs
  "would have locked". `RunMode.ARMED` sends real commands.
- `demoMode` swaps in `FakeBlueLinkClient` so the app works with no account/car.
- Always `status(forceRefresh=true)` and run `LockPolicy.decide` before locking. Skip if
  already locked / engine running / unknown.

## 5b. Security model (utmost care — this controls a real car)
- **Tokens:** `SecureStore` uses EncryptedSharedPreferences (AES-256-GCM) with a
  Keystore master key, StrongBox-backed when available; corrupted store → wipe + re-auth,
  never a plaintext fallback. Tokens never logged, never backed up.
- **Network:** `res/xml/network_security_config.xml` forbids cleartext everywhere and trusts
  system CAs only (no user-added certs). `usesCleartextTraffic=false`. Cert-pinning slots
  are ready in that file for the BlueLink hosts.
- **Login surface:** the EU WebView runs under `FLAG_SECURE` (no screenshots/recents), with
  file/content access off, no password/form persistence, no cache; cookies + cache are
  cleared on exit so no Hyundai web session lingers.
- **No secrets in repo:** `local.properties`, keystores, `*.pem/.env/google-services.json`
  are git-ignored and blocked by CI. The `client_id/secret/stamp cfb` in `RegionConfig` are
  **public reverse-engineered app constants** (present in bluelinky / the Python lib), NOT
  the user's Hyundai credentials — safe for a public repo.
- **Telemetry:** `ApiMetrics` records only operation name / duration / outcome — never
  tokens, headers, VINs, or account data.
- Never enable Ktor request logging in release, and never log `Authorization`/`Stamp`.

## 6. Conventions
- Prefer pure functions in `domain`; keep Android/IO at the edges (`data`, `service`, `ui`).
- ViewModels expose `StateFlow`; collect with `collectAsStateWithLifecycle`.
- No secrets in logs, crash traces, or backups (`allowBackup=false`).
- Only request permissions contextually (`ui/permissions/PermissionGate`).
- New region = add a `RegionConfig` + client impl behind `BlueLinkProvider`; don't branch
  region logic elsewhere.
- Tests: pure logic (`LockPolicy`, `LockStateMachine`) and `FakeBlueLinkClient` must stay green.

## 7. Build & test commands
- Debug build: `./gradlew assembleDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lint`
- Install on a device/emulator: `./gradlew installDebug`

## 7b. CI (`.github/workflows/ci.yml`) — public-repo safe
- `guard` job: fails if `CLAUDE.md`/`README.md` are missing, if `CLAUDE.md` is too short or
  loses its Changelog, if any secret/keystore/config file is committed, or if high-confidence
  secret patterns are found (+ best-effort gitleaks). **Keep this file current so CI passes.**
- `android` job: validates the Gradle wrapper, JDK 17, installs SDK 35, then runs
  `testDebugUnitTest`, `lint`, `assembleDebug`, and uploads reports.
- `dependabot.yml` keeps Gradle + Actions deps patched.

## 8. Open TODOs / known gaps
- US/CA/AU clients not yet implemented (structure is ready in `BlueLinkProvider`).
- Activity Recognition IS wired (`data/detection/ActivityRecognitionManager` + `receiver/ActivityTransitionReceiver`,
  registered by `AutoLockService` on watch start when `useActivityRecognition`). Geofence confirmation is
  also wired but as **distance polling** during the confirm window (`AutoLockController` captures the
  location at trigger and polls `LocationHelper.currentLocation()` every 3s, firing `MovedBeyondGeofence`
  past `geofenceRadiusMeters`) rather than the Play Services Geofence API (unreliable for a 20s window).
- Cert-pinning: slots exist in `network_security_config.xml` AND an OkHttp `CertPins` pinner is wired
  into the Ktor engine (`di/AppModule`), but the pin map is empty (no-op) until real SPKI hashes are
  supplied — fake pins would break connectivity.
- Custom bundled display font not added (needs binary `.ttf` assets or a verified downloadable-fonts
  certs blob); type system uses system families. Drop a font into `res/font` to enable.
- Background geofence while the app is fully killed: current geofence is distance-polling during the
  confirm window (needs the service alive); a Play `GeofencingClient` registered at arrival would be
  more battery-friendly for long idle periods.
- US/CA/AU: routed to `UnsupportedRegionClient` (fails clearly) until real region endpoints/clients are
  implemented — the architecture routes by region in `BlueLinkProvider`.
- EU endpoint/stamp values need live verification against the reference projects.

## 9. Changelog (append notable changes)
- Initial scaffold: architecture, EU client skeleton, fake client, detection state machine,
  foreground service, Compose UI (home/settings/login/permissions), unit tests.
- Security hardening: network security config (no cleartext), StrongBox-backed token store
  with corruption recovery, FLAG_SECURE + locked-down login WebView with cookie/cache wipe.
- API telemetry: `ApiMetrics` + `MeteredBlueLinkClient` decorator and a Statistics screen
  (`ui/stats`) showing durations, success rate, rate-limit status, recent calls, and logs.
- CI: GitHub Actions build/lint/test + repo-hygiene/secret guard + CLAUDE.md check; Dependabot.
- Help page (`ui/help/HelpScreen`) documenting every setting + login troubleshooting; reachable
  from Home top bar and Settings. Login WebView now enables cookies (fixes "Session Timedout : 401").
- Vehicle status card on Home: live lock/unlocked state, battery %, range, engine, door-open,
  last-refresh time + manual refresh (`HomeViewModel.refreshStatus`, enriched `VehicleStatus`).
- Theming: `ThemeMode` (System/Light/Dark) + dynamic-color toggle persisted in settings and
  applied via `ThemeViewModel` in `MainActivity`; Appearance section in Settings. Added
  animations (animated lock color/icon, spinning refresh, animated content/list items).
- Visual refresh: gradient adaptive icon + monochrome variant, richer light/dark color schemes
  (full container tokens), brand gradient status card with pulsing "watching" dot, tinted stat
  chips (incl. 12V battery). Customisable notification: `NotificationField` + `showStatusInNotification`
  in settings, `StatusSummary` helper builds the line, threaded through `AutoLockController` ->
  service -> `AutoLockNotification` (BigTextStyle).
- EU login: added external-browser method (open in Chrome/Firefox/Brave + paste redirect/code via
  `EuAuth.extractAuthCodeLoose`) alongside the in-app WebView; WebView now sets a real browser
  User-Agent (fixes the "; wv" 401). Stats "Last call" row no longer wraps (single-line ellipsis).
- Extras (all user-toggleable): notification "Lock now" action (`controller.lockNow`, skips grace);
  Quick Settings tile (`tile/AutoLockTileService`); home-screen widget (`widget/AutoLockWidget` +
  `data/status/StatusCache`); pull-to-refresh + auto-refresh-on-open toggle; haptic/sound lock
  confirmation; active-hours schedule (`AppSettings.isWithinSchedule`, overnight-aware); remember
  parked location (`data/location/LocationHelper`, shown on the status card).
- EU refresh-token login: verified constants against BlueDeck (`basicAuth`, idp URL), added full
  CCSP headers (`ccsp-device-id`, okhttp/3.12.0 UA, gzip) and `loginWithRefreshToken()` +
  a "paste EU refresh token" login method (the reliable EU path, since EU login uses reCAPTCHA).
- Polish: Ioniq 5 "Parametric Pixels" motif (`ui/theme/PixelDecor` — `ParametricPixels`/`PixelBand`)
  across home/help/login; status chips use `FlowRow` single-line + colored tint; vehicle status
  card + widget lock text are color-coded; detailed parked-location label (street + area);
  "Keep AutoLock running" settings section (battery-optimization + app-info shortcuts) + Help tips;
  confirm dialog before clearing API stats/log.
- EU lock: device registration (`notifications/register` -> `SecureStore.deviceId`) + PIN->control-token
  flow (`PUT user/pin` -> `controlToken` -> `POST control/door` v1). PIN captured at login, stored in
  `SecureStore` (encrypted). Ported from BlueDeck.
- EU automatic login: headless email+password token generation via IDP (`idpconnect-eu.hyundai.com`) —
  authorize (cookies) -> certs (JWK) -> RSA-encrypt password -> `/auth/account/signin` (302 code) ->
  token exchange. `EuBlueLinkClient.loginWithPassword`, `EuAuth.encryptPassword`, Ktor `HttpCookies`.
  Login UI now leads with email+password+PIN (refresh-token paste demoted to Advanced). Ported from
  `bluelink-refresh-token` (TMA84).
- EU login via WebView (Akamai bypass): the IDP fronts Akamai bot protection that blocks OkHttp's
  TLS/HTTP2 fingerprint ("classified as an abusing request and blocked") on the HMGID2 connector hop,
  even with correct credentials. `EuWebLogin` now runs the identical headless flow inside a Chromium
  `WebView` (Chrome fingerprint + native cookies): `loadUrl(authorize)` -> same-origin `fetch(certs)` ->
  Kotlin RSA-encrypts password -> auto-submitting form POST to `/auth/account/signin` (native redirect
  follow through the connector) -> intercept `redirect_uri` code -> same-origin `fetch(token)`.
  `EuBlueLinkClient` uses `EuWebLogin` first (needs `@ApplicationContext`), falling back to the raw
  `EuIdpAuth` OkHttp path only if the WebView is unavailable. Still fully automatic — no user typing.
- EU login, the working approach — visible autofill WebView + IDP-direct OAuth: after research
  against TMA84's `bluelink-refresh-token` (the working reference), the OAuth flow does NOT go through
  CCSP — it hits the **IDP directly** for BOTH authorize and token exchange. `EuAuth.buildAuthorizeUrl`
  now returns `https://idpconnect-eu.hyundai.com/auth/api/v2/user/oauth2/authorize?...&country=de` and
  the WebView intercepts the `RegionConfig.idpRedirectUri` (`prd.eu-ccapi.hyundai.com:8080/api/v1/user/oauth2/token`)
  as the callback. `EuBlueLinkClient.login()` now POSTs the code to the IDP token endpoint (with
  `client_id`+`client_secret`), not the CCSP `/api/v1/user/oauth2/token`. Cookies wiped before every
  load; primary Sign in button drives it end-to-end with autofill; a Cancel + log strip stays visible
  above the WebView so the user can always see progress.
- UI makeover ("Electric Performance" design language): overhauled the design system in `ui/theme`.
  Richer Ioniq-5 palette (`Color.kt`: brighter Digital Teal, new Electric Lime charge accent, layered
  Phantom Black surfaces + Atlas White) wired into full M3 `surfaceContainer*` tokens in `Theme.kt`.
  New type system — heavy grotesk-style SansSerif display/headings with tight tracking, **Monospace**
  labels/data for an "EV instrument cluster" readout feel. Softer, larger shapes (18–32dp). `Brand.kt`
  gained multi-stop `brandGradient`, `heroGlow`, `ambientBackground`, `BrandTokens` and richer
  `AccentColors`. `PixelDecor.kt` now draws rounded pixels + a new animated `ScanningPixelBand`.
  Redesigned adaptive launcher icon (deeper radial-glow background, premium padlock with a parametric-
  pixel keyhole, matching monochrome). Home screen rebuilt: ambient background wash, brand wordmark +
  pixel logo, layered hero status card (glow + scanning band when watching), vehicle card with animated
  battery bar + tinted stat chips, taller pill action buttons, timeline-style activity log. Settings/
  Stats/Help got the ambient background + transparent app bars for a cohesive look. Widget bg tuned to
  the new gradient. No behaviour changes; tests/lint/assembleDebug green.
- UI + fixes round 2: reusable gradient `HeroBanner` (`ui/components/Hero.kt`) — brand gradient + glow +
  parametric-pixel accent — now headers Settings and Help. The Home "Vehicle" card is a state-driven
  gradient hero (teal=locked / red=unlocked / slate=unknown) with white content, glow, animated battery
  bar and translucent stat chips. Launcher icon symmetry fixed (lock body was centred at x=52 vs the
  shackle/shadow/keyhole at x=54 → now all centred at 54). Notifications/haptics/sound now fire on
  "Simulate leaving": `HomeViewModel.runNow` starts `AutoLockService` (foreground) instead of calling the
  controller directly, so the live notification + lock confirmation feedback run exactly like a real BT
  trigger. Crash hardening: `AutoLockController.runEvaluation` is wrapped in try/catch (rethrows
  `CancellationException`) so any error becomes a logged ERROR instead of an uncaught crash in the
  controller's coroutine. Vehicle-list persistence: loaded vehicles are cached in DataStore
  (`AppSettings.knownVehicles` / `KnownVehicle`, `SettingsRepository` KNOWN_VEHICLES key); the Settings
  picker seeds from the cache instantly on open (survives restarts/nav), refreshes in the background, and
  keeps the cache on transient load failures; sign-out clears it.
- Meaningful pixel bar: the hero card's pixel band is now state-driven instead of always animating —
  `ScanningPixelBand` only while an evaluation is in flight (ARMED/CONFIRMING/GRACE/VERIFYING/LOCKING),
  a solid full `PixelBand(dim=false)` once LOCKED/SKIPPED ("secured"), and a dim static bar when armed
  and simply waiting. The status dot pulses only while active and is a solid dot otherwise. Added a
  `dim` flag to `PixelBand`.
- Persistent watching + fixes round 3:
  - Locked-notification bug: the status line was built from the pre-lock status (still "Unlocked").
    `AutoLockController.performLock` now takes the `status`, and on a real lock success rebuilds the
    summary + `StatusCache` from `status.copy(lockState = LOCKED, anyDoorOpen = false)` so the "Locked"
    notification and widget read "Locked".
  - Persistent background watching: `AutoLockService` gained a watch mode (`ACTION_START_WATCH` /
    `ACTION_STOP_WATCH`, `AutoLockNotification.buildWatching` — ongoing LOW-priority "AutoLock is
    watching" notification with a "Turn off" action). It stays foreground (START_STICKY) while enabled,
    runs one-off evaluations on trigger, and reverts to the watching notification afterwards instead of
    stopping. Wired to the enable toggle (`HomeViewModel.setEnabled`, tile `onClick`), resumed on boot
    (`BootReceiver`), and re-ensured on Home open. `AutoLockService.start*` are wrapped in try/catch to
    survive OS background-start restrictions.
  - Parked location → Google Maps: `LocationHelper.currentParkedPlace()` now returns coords + label
    (`ParkedPlace`); `AppSettings.parkedLat/parkedLng` persisted; the Home vehicle card's "Parked near …"
    row is tappable (`openParkedInMaps` → geo: intent preferring Google Maps, falling back to a web
    maps URL).
- Sparkle eye-candy: new `SparklingPixels` (theme/PixelDecor) twinkles each parametric-pixel cell
  independently (per-cell sine phase) with a soft bloom behind bright cells. The System Status corner
  pixels sparkle while watching is enabled; the Vehicle card pixels sparkle while the car is locked;
  both fall back to a calm static cluster otherwise (`active=false`).
- Watch service resilience: (1) a null-intent `onStartCommand` (system `START_STICKY` recreation after a
  kill) now resumes watch mode if `enabled` instead of firing a bogus evaluation; (2) `onTaskRemoved`
  re-asserts the foreground notification when the app is swiped from Recents; (3) the watching
  notification carries a `deleteIntent` (`getForegroundService` → `ACTION_START_WATCH`) so if the user
  swipes it away it immediately re-posts — watching stays visible/persistent while enabled. OEM battery
  optimisation can still kill it, hence the existing "Keep AutoLock running" settings shortcuts.
- Blurred pixel backdrop + pinnable notification: replaced the corner `SparklingPixels` on the System
  Status and Vehicle cards with a full-card `PixelField` (theme/PixelDecor — scattered twinkling pixels)
  rendered behind the content via `Modifier.matchParentSize().blur(14.dp)` at low alpha, so it's a
  subtle animated texture rather than overlapping the controls. New user setting `AppSettings.pinNotification`
  (default on, Settings → Notification "Pin the notification"): when on, the watching notification's
  `deleteIntent` re-posts it if swiped (effectively unswipeable); when off it's dismissable. The service
  reads it (`pinNotification` field → `buildWatching(pinned)`); toggling re-asserts immediately via
  `SettingsViewModel.setPinNotification` → `startWatching`.
- Notification flow fixes round 4:
  - Watching notification now keeps the vehicle status line: `AutoLockService` injects `StatusCache`,
    tracks `lastSummary` (seeded from the cache, updated from `controller.state.statusSummary` and a
    live `statusCache.cached` collector), and `startForegroundWatch` passes it to `buildWatching`
    (gated by `showStatusInNotification`). So after locking it stays "AutoLock is watching · Locked · …"
    instead of dropping the status.
  - "Turn off" notification action now also flips the in-app toggle: `ACTION_STOP_WATCH` does
    `settingsRepo.update { enabled = false }` before `stop()` (so Home/Tile reflect off).
  - Pixel backdrop blur reduced 14dp→5dp so the pixels still read as pixels.
  - The "watching" heartbeat dot pulses again whenever enabled (was only pulsing during an active
    evaluation); removed the now-unused `StaticDot`.
- Notification flow core rework (round 5): the watching notification's status line is now single-sourced
  from `StatusCache` via a **service-lifetime** collector in `AutoLockService.onCreate` (not inside
  `observe()`), so `lastSummary` is always fresh regardless of code path. `buildWatching` now shows the
  vehicle status as the **primary** content line ("AutoLock is watching" title + e.g. "Locked · 72% ·
  318 km"), falling back to "Monitoring for you leaving the car." only when no status is known. The
  controller saves the post-lock status to `StatusCache` (armed), so after locking the watching
  notification keeps showing "Locked …" instead of dropping the status.
- Status flow overhaul (real-time + resilient): `StatusCache` now persists the **full** `VehicleStatus`
  (battery/range/engine/12V/doors/climate/charging via `saveStatus` + `toVehicleStatus`), not just the
  lock string. `HomeViewModel` seeds the vehicle card from the cache instantly (no blanks on reopen),
  collects `statusCache.cached` for **live** updates (lock flow + worker write to the cache), and
  `refreshStatus` no longer blanks on error/partial data — it **merges** fresh values onto the previous
  ones (a null field keeps the old value) and preserves detail when sign-in/refresh fails. Controller
  writes full status via `saveStatus`. New `work/StatusRefreshWorker` (`@HiltWorker`, unique periodic)
  does a background status poll on a user schedule; Settings → Behaviour "Background auto-check"
  (Off/15/30/60m, `AppSettings.autoRefreshIntervalMinutes`, WorkManager 15-min floor) via
  `SettingsViewModel.setAutoRefreshInterval`, re-scheduled on Home open. Home animations: battery %
  count via `animateIntAsState`, stat-chip values crossfade (`AnimatedContent` fade), so old values
  fade smoothly to new. "Updated …" time now tracks the freshest cache write.
- Round 6 (optimizations + fixes):
  - Merge fix (notification/refresh losing detail): `VehicleStatus.mergedOnto(old)` (model) prefers the
    fresh value per optional field, else keeps the previous. `HomeViewModel.refreshStatus` and the
    controller build the summary + cache from the **merged** status, so a minimal EU force-refresh no
    longer strips battery/range from the card or notification.
  - Simulate guard: "Simulate leaving" is disabled (with a hint) when `runMode==ARMED && !demoMode`
    (would send a real lock); allowed in Demo / Dry run.
  - Skeleton loading: shimmering `SkeletonBar`/`SkeletonChip` on the vehicle card only when nothing is
    cached yet and loading (cache seeding means this is rare).
  - Confirm-before-lock wired: new `DetectionState.AWAITING_CONFIRM`; when `requireConfirmationBeforeLock`
    the controller waits (2-min timeout) for the notification "Lock now" (reuses `skipGrace`) before
    locking, else aborts. Notification + Home label handle the new state.
  - Refresh throttle: `HomeViewModel` blocks forced refreshes within 6s (rate-limit friendliness).
  - Worker hardening: `StatusRefreshWorker` adds `setRequiresBatteryNotLow` + exponential backoff.
  - Activity Recognition wired: `ActivityRecognitionManager` (ENTER WALKING/ON_FOOT transitions) +
    `ActivityTransitionReceiver` → `controller.onWalkingConfirmed()`; started/stopped by `AutoLockService`
    with watch mode when `useActivityRecognition`. Uses `FLAG_MUTABLE` PendingIntent.
  - Widget shows the full summary (2 lines). Tests: `VehicleStatusMergeTest`.
  - Deferred (need assets/real values): cert-pinning hashes, bundled font, exit-geofence (arrival-capture
    redesign).
- Round 7 (persist stats, EV sound, icon):
  - `ApiMetrics` is now **persisted** (DataStore JSON, `@Serializable` snapshot) so the Statistics screen
    survives app restarts; restored on init, saved on each `record`/`clear` (calls capped at 120).
  - Ioniq-style chime: `data/sound/EvChime` synthesises a soft bell arpeggio at runtime via `AudioTrack`
    (no audio assets). `playLock` (ascending D5·F#5·A5·D6) fires on lock, `playNotify` (A5·D6) when an
    evaluation begins — both gated by `soundOnLock`. The notification channel is now **silent** (v3,
    `setSound(null,null)`) so the system ding doesn't clash with the chime.
  - Launcher icon: the padlock group scale reduced 1.08→0.82 (foreground + monochrome) so the whole lock
    fits inside the adaptive-icon safe zone (it was being clipped).
- Round 8 (timestamp + geofence):
  - "Updated …" now ticks: `rememberNow()` (15s recomposing clock) drives `relativeTime(epochMs, now)`
    so "just now" ages to "N min ago" / a clock time without needing to reopen the app.
  - Geofence confirmation implemented via distance polling: `LocationHelper.currentLocation()` +
    `AutoLockController` captures the spot at trigger and, when `useGeofence`, polls every 3s during
    CONFIRMING, firing `DetectionEvent.MovedBeyondGeofence` once you pass `geofenceRadiusMeters`. If it
    never fires, the 20s confirm timeout still proceeds — so it only ever speeds up confirmation.
- Round 9 (reliability, security, onboarding, per-vehicle, i18n, regions, tests):
  - EU status/latest fallback: `EuBlueLinkClient.status` splits into `fetchStatus(path)`; a forced read
    merges `status` over `status/latest` so battery/range aren't lost.
  - Rate-limit surfacing: `HomeViewModel` injects `ApiMetrics`; a forced refresh during
    `snapshot.isRateLimited()` shows "Rate-limited — try again in Ns" instead of calling out.
  - Re-auth: `VehicleStatusUi.needsReauth` + a Home `ReauthBanner` (→ Login) when the session expires.
  - PIN-gated manual lock: Home "Lock now" button → `LockNowDialog` (asks the BlueLink PIN when stored
    via `SecureStore.loadPin`) → `HomeViewModel.manualLock` → `client.lock`, result via Toast.
  - Cert-pinning wired but no-op: `data/bluelink/CertPins` (empty pin map) applied to the OkHttp engine
    in `di/AppModule` only when `CertPins.enabled`.
  - Per-vehicle: `HomeViewModel.selectVehicle(KnownVehicle)` + Home `VehicleSwitcher` FilterChips shown
    when `knownVehicles.size > 1`.
  - Onboarding wizard: `ui/onboarding/OnboardingScreen` (HorizontalPager, 4 steps) gated by
    `AppSettings.onboardingComplete`; `AppNavigation` shows it first-run, then the NavHost.
  - Localization: onboarding + channel strings extracted to `res/values/strings.xml` with a full
    `values-es` translation (proves i18n; rest of the app still has inline strings to migrate).
  - Regions: US/CA/AU now route to `UnsupportedRegionClient` (fails clearly) instead of silently hitting
    EU endpoints.
  - Tests: `CachedStatusTest`, `UnsupportedRegionClientTest`.
  - Still deferred: real cert-pin hashes, a bundled/downloadable font (needs assets/certs), and a
    battery-friendly background `GeofencingClient` (needs arrival-capture).
