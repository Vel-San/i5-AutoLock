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
  - `eu/` — real EU flow: `EuAuth` (Stamp + RSA password), `EuDtos`, `EuBlueLinkClient`,
    `EuIdpAuth` (OneApp/CCI login + refresh).
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
EU sign-in is **fully automatic, headless email + password via the OneApp/CCI flow** (`EuIdpAuth`,
ported from `hyundai_kia_connect_api` #1277 / `bluelink-refresh-token` v6.9.0). Since 2026-08-11
Hyundai's IDPConnect **WAF blocks the legacy login `client_id`** (`6d477c38…` with the `:8080`
redirect) — "classified as an abusing request and blocked". The OneApp `client_id` is not on that
block list, so login now uses it (raw OkHttp — the root cause was the client_id, NOT a TLS
fingerprint, so no WebView is needed):
1. GET `idpconnect-eu.hyundai.com/auth/api/v2/user/oauth2/authorize?client_id=<oneAppClientId>&redirect_uri=<oneAppRedirectUri>&state=ccsp&country=de` (session cookies).
2. GET `/auth/api/v1/accounts/certs` → JWK; RSA/PKCS1v1.5-encrypt password to hex (`EuAuth.encryptPassword`).
3. POST `/auth/account/signin` (form: encryptedPassword=true, password hex, kid, client_id=oneApp, …) → **302 straight to the OneApp redirect with `?code=`** (no connector chase).
4. POST `cci-api-eu.hyundai.com/domain/api/v1/auth/token?code=…` (CCI headers) → **CCI token set** (accessToken, refreshToken, nonCcsToken, exchangeable*, idToken).
5. POST `cci-api-eu.hyundai.com/domain/api/v1/auth/token-exchange?serviceType=CCS` (CCI headers) → **CCS token** (`accessToken`/`ccsAccessToken` + `expiresTime`).
`EuBlueLinkClient.loginWithPassword()`. **Refresh** (`ensureFreshSession`): POST
`cci-api-eu…/domain/api/v2/auth/token-refresh` (full CCI set in a JSON body) → new CCI set → re-exchange
the CCS token. The CCI set is persisted in `SessionTokens` (`cciAccessToken`, `exchangeableToken`,
`exchangeableRefreshToken`, `nonCcsToken`, `nonCcsRefreshToken`, `idToken`); `accessToken` = CCS token,
`refreshToken` = CCI refresh token. No password is stored — an expired CCI refresh token → re-auth.
CCI headers: `client-id`=package id (`com.hyundai.oneapp.eu`), `client-name`, `client-version` (1.3.3),
`client-os-code`=ios, `client-os-version`, `client-device-id`=CCSP deviceId, `client-device-model`=iPhone,
`client-notification-provider-type` (APNS), `locale`, `timezone`, `authorization: Bearer <cciAccess>`,
`Authentication: <nonCcsToken>`, `exchangeable-token`/`non-ccs-token`, UA `okhttp/3.12.0`.
The **CCS token** is then used as a Bearer against the existing CCSP API
(`prd.eu-ccapi.hyundai.com:8080/api/v1/spa/...`) — unchanged. Every CCSP request still carries
`ccsp-service-id` (legacy `clientId`), `ccsp-application-id`, `ccsp-device-id`, `Stamp`
(`EuAuth.generateStamp`), `User-Agent: okhttp/3.12.0`, `Accept-Encoding: gzip`, `Connection: Keep-Alive`.

**EU lock uses a control-token flow** (ported from BlueDeck): register a device id via
`POST api/v1/spa/notifications/register` (stored in `SecureStore.deviceId`), then per command
`PUT api/v1/user/pin` with `{deviceId, pin, vehicleId}` → `controlToken`, then
`POST api/v1/spa/vehicles/{id}/control/door` (`action=close/open`) using the control token as
`Authorization`. The 4-digit BlueLink PIN is captured at login and stored in `SecureStore` (encrypted).

> EU constants in `RegionConfig` (base URL, `clientId`/service-id, `appId`, `clientSecret`,
> `basicAuth`, `stampCfb`, `oneAppClientId`, `oneAppRedirectUri`, `cciApiBaseUrl`, cci client
> headers) are verified against **BlueDeck**, `hyundai_kia_connect_api` (#1277) and
> `bluelink-refresh-token` (v6.9.0). These are public reverse-engineered app constants, NOT
> user credentials. Keep ALL EU
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
- Localization: the whole UI — including the Help screen tutorial body, the `NotificationField`
  labels (`labelRes`) and the `StatusSummary` notification data line (`StatusSummary.Labels.from(ctx)`) —
  is externalized to `res/values/strings.xml` (single source, 379 strings) with **complete** translations
  in `values-es`, `values-de`, `values-nl`, `values-fr`. Only pure-domain `ActivityLog` diagnostic
  messages stay English (no `Context` in the domain layer). Adding a language = drop a new `values-xx`.
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
- Round 10 (background geofence + full localization):
  - Background geofence: `data/detection/GeofenceManager` (Play `GeofencingClient`, EXIT trigger,
    `FLAG_MUTABLE` PendingIntent, `@SuppressLint MissingPermission`, needs background-location) registers
    around the car on Bluetooth **arrival** (`BluetoothStateReceiver` ACL_CONNECTED captures location).
    `receiver/GeofenceReceiver` fires `AutoLockService.start` on EXIT (battery-friendly "you left"
    trigger, no polling/persistent service), then removes the one-shot geofence. Manifest receiver added.
  - Localization: extracted all strings for Home, Settings, Login, Stats, Permissions, Onboarding, and
    the notifications into `res/values/strings.xml`, with a complete `res/values-es` (Spanish). Notif
    strings use `context.getString`; `relativeTime(epochMs, now, context)` localizes "just now"/"min ago".
    The Help screen's chrome/hero are localized; its long-form tutorial body paragraphs remain inline.
- Round 11 (custom sound, 12V alert, refresh guard, app badge):
  - Custom lock sound: `data/sound/LockSound` (custom `RingtoneManager` URI, else `EvChime`, chime
    fallback). `AppSettings.customLockSoundUri`; Settings → Notification "Sound on lock" now has a
    ringtone picker (`ACTION_RINGTONE_PICKER`), "Use default", "Test" (`SettingsViewModel.testLockSound`)
    and "Play default" (`playDefaultSound`). Service plays via `LockSound.play`.
  - Low 12V warning: `AppSettings.lowVoltageAlert`/`lowVoltageThreshold`; `HomeViewModel` flags
    `VehicleStatusUi.lowVoltage` when `twelveVoltPercent < threshold`, shows a tertiary Home banner
    (`LowVoltageBanner`) + one-shot toast (`notice` flow). Settings section "Low 12V battery warning"
    (toggle + threshold slider).
  - Configurable refresh guard: `AppSettings.minRefreshSeconds` replaces the hardcoded 6s throttle in
    `HomeViewModel.refreshStatus`; rate-limit/throttle now also surface a toast via the `notice` flow.
    Settings → Timing slider (3–30s).
  - App badge toggle: `AppSettings.showAppBadge` (default off). `service/NotificationChannels.ensure`
    single-sources the (silent) channel and only recreates it when `canShowBadge()` differs (badge is a
    channel property). `AutoLockApp` (channel id bumped to `_v4`) reads the saved pref via a Hilt
    `EntryPoint`; `SettingsViewModel.setShowAppBadge` recreates the channel + re-asserts watch. Settings
    → Notification toggle.
- Round 12 (notification icon + session expiry):
  - Status-bar icon: added a dedicated `res/drawable/ic_stat_autolock.xml` (bold white padlock that
    fills the 24dp canvas, evenOdd keyhole) and pointed both `AutoLockNotification` builders' `setSmallIcon`
    at it. Previously used `ic_launcher_foreground` (large safe-zone padding + scaled 0.82), which the
    system rendered tiny in the collapsed status bar.
  - Session expiry readout: Stats → Session now shows "Session expires" with a live countdown + clock
    time, sourced from the stored **access token** `expiresAtEpochMs` (`StatsViewModel.sessionExpiresAtEpochMs`,
    injects `SecureStore`; `StatsScreen.sessionExpiryText` with a 30s ticking clock). The refresh token's
    own expiry is NOT exposed by Hyundai's OAuth (the token response only returns `expires_in` for the
    access token), so a refresh-token countdown isn't possible — the session line is the honest proxy.
- Round 13 (release automation + in-app changelog/version):
  - Repo cleanup: untracked a stray `.kotlin/sessions/*.salive` compiler-cache file that had been
    committed (already in `.gitignore`). Rest of the tree is clean (no `build/`, secrets, or IDE junk
    committed).
  - User-facing `CHANGELOG.md` (Keep a Changelog format) is now the single source of truth for release
    notes. `app/build.gradle.kts` copies it into a generated asset dir (`syncChangelogAsset` Copy task →
    `build/generated/changelog`, registered as a main assets srcDir, hooked to `preBuild`) so the app can
    read it without a committed duplicate under `src`.
  - In-app About/What's-new: `ui/about/AboutScreen` shows `BuildConfig.VERSION_NAME`/`VERSION_CODE`, a
    **Markdown-rendered** changelog from the bundled `CHANGELOG.md` asset (headings, bullets, horizontal
    rules + inline **bold**/*italic*/`code`/clickable [links] via a dependency-free `parseInline` →
    `AnnotatedString` using Compose `LinkAnnotation`), and a "View project" link. New
    `Routes.ABOUT`; Settings gained an "About" section (`onAbout`) and `AppNavigation` wires it. Strings
    added to `values` + `values-es`.
  - Release CI: `.github/workflows/release.yml` triggers on `v*` tags — builds `assembleRelease`,
    extracts the matching `## [x.y.z]` section from `CHANGELOG.md` as the release body, and publishes a
    GitHub Release with the APK (`softprops/action-gh-release`, `contents: write`). Prerelease when the
    version has a `-suffix`.
  - Optional release signing in `app/build.gradle.kts`: reads `RELEASE_STORE_FILE`/`_STORE_PASSWORD`/
    `_KEY_ALIAS`/`_KEY_PASSWORD` from env (CI secrets) or `local.properties`; applied to the `release`
    build type only when the keystore exists, else the APK is built unsigned. Keys documented in
    `local.properties.example`; CI keystore via `RELEASE_KEYSTORE_BASE64` secret.
  - CI guard now also requires `CHANGELOG.md` to exist. README "Continuous integration" section documents
    the release/tag flow and signing secrets.
- Round 14 (notification icon colour + status-bar-icon toggle):
  - Brand-tinted notification: both `AutoLockNotification` builders `setColor(R.color.notification_accent)`
    (`#32D6C0` Digital Teal, `res/values/colors.xml`) so the small icon + app-name accent are tinted in the
    shade/heads-up. `ic_stat_autolock` is the **app-icon padlock silhouette** (viewport 108, shackle arch +
    rounded body + the parametric-pixel keyhole carved out via `evenOdd`), NOT a generic lock. NOTE: Android
    always renders the collapsed status-bar small icon as a monochrome white alpha mask — full colour there
    is impossible by design.
  - Show/hide status-bar icon toggle: `AppSettings.showNotificationIcon` (default true, `SettingsRepository`
    `SHOW_NOTIF_ICON` key). When off, the ongoing notification posts on the minimal-importance channel
    (`AutoLockApp.CHANNEL_ID_MINIMAL`, `IMPORTANCE_MIN`) which keeps the foreground notification **out of the
    status bar entirely — no icon, no empty gap**. Because a notification's channel can't change once posted,
    `AutoLockService.postForeground()` tracks `postedChannelId` and does `stopForeground(STOP_FOREGROUND_REMOVE)`
    + re-`startForeground` whenever the target channel changes, so `IMPORTANCE_MIN` actually takes effect (an
    earlier transparent-icon hack left an empty status-bar slot and was removed). Builders take a `channelId`
    param; `AutoLockService.channelId()` picks visible vs minimal from `showNotificationIcon`.
    `SettingsViewModel.setShowNotificationIcon` re-asserts watch so it applies immediately. Settings →
    Notification "Show status-bar icon"; strings in all locales.
- Round 15 (v1.0.0 release prep + full localization):
  - Version bumped to `1.0.0`; `CHANGELOG.md` trimmed to a single first-release entry (no semver/Keep-a-
    Changelog preamble). `AboutScreen.PROJECT_URL` + changelog link point at
    `https://github.com/Vel-San/i5-AutoLock`. Removed the CLAUDE.md monetization/paywall section entirely
    (no paywall anywhere in the app).
  - Full localization: the Help screen's ~100 inline strings were externalized (`help_*` keys) and it now
    renders via `stringResource`. `NotificationField.label` → `@StringRes labelRes` (Settings chips use
    `stringResource(field.labelRes)`). `StatusSummary` gained a `Labels` holder (`Labels.from(context)`,
    default `ENGLISH` so the pure unit test is unchanged); `AutoLockController` (now `@ApplicationContext`),
    `HomeViewModel`, and `StatusRefreshWorker` pass localized labels so the notification data line
    translates. Added **complete** `values-de`, `values-nl`, `values-fr` (and back-filled the new keys in
    `values-es`) — all four locales at 379 strings, no missing keys, lint clean. Only pure-domain
    `ActivityLog` messages remain English (no `Context` in the domain layer).
- Round 16 (perf, settings-freeze fix, local backup/restore):
  - Home lag (root-cause fix, animations KEPT): the two background `PixelField` textures were animating
    **behind `Modifier.blur(5.dp)`**, and Compose's blur is a graphics-layer `RenderEffect` that is
    recomputed every frame when the layer content changes — that per-frame blur re-eval was the jank. Fix:
    removed the layer blur entirely and baked the soft "wash" into `PixelField`'s draw (each pixel = two
    faint oversized halos + a crisp core, all solid `drawRoundRect`s — no allocations, no RenderEffect). The
    twinkle animation runs at full frame rate again (`active = enabled`/`locked` restored). (An earlier pass
    had wrongly set `active = false` to mask the lag — that's reverted.)
  - Settings freeze (~3s on open): `SettingsViewModel.init` / `refreshDevices` / `loadVehicles` / `signOut`
    ran blocking work on `viewModelScope` (Main dispatcher) — `provider.client()` + `isAuthenticated()` read
    the EncryptedSharedPreferences/Keystore store (slow on first access) and blocked the UI. All blocking
    bits now run in `withContext(Dispatchers.IO)`; `refreshDevices` is a coroutine that computes
    `bondedDevices()` on IO. Auto-load-on-open kept but off-main.
  - Local backup/restore: `data/backup/BackupManager` + `@Serializable SettingsBackup` (mirrors non-secret
    `AppSettings`; **excludes** tokens/PIN/deviceId, and does NOT restore `enabled`/`onboardingComplete` to
    avoid surprise activation). Pretty-printed JSON. `exportToAppFolder()` writes to
    `getExternalFilesDir/backups/autolock-backup-<ts>.json`; `exportToUri`/`restoreFromUri` use the SAF
    (`CreateDocument`/`OpenDocument`) via `contentResolver`, all on `Dispatchers.IO`. `SettingsViewModel`
    injects `BackupManager`, adds a `_notice` toast flow + `exportToAppFolder`/`exportToUri`/`restoreFromUri`
    (restore calls `provider.invalidate()`). Settings → "Backup & restore" section (Export to app folder /
    Export to… / Restore from file); strings in all five locales.
- Round 17 (CCS2 DTE parse fix): live Ioniq 5 CCS2 responses have a **flat** `DTE` object
  (`{"EV":234,"Total":234,"Unit":1}`) — `Total`/`EV` are numeric ranges and `Unit` is a sibling — not the
  nested `Total:{Value,Unit}` shape suggested by older docs. That mismatch made the whole `carstatus/latest`
  payload fail with `Expected start of the object, but had '2'` at `$.resMsg.state.Vehicle.Drivetrain.FuelSystem.DTE`,
  wiping the vehicle card on refresh. `Ccs2Dte` is now flat (`ev`, `total`, `unit`), `Ccs2DteValue` removed,
  and `parseCcs2Status` reads `dte.total ?: dte.ev` with the sibling `dte.unit` (1=km, 3=miles). Unrelated:
  the intermittent `503 "Unavailable remote control"` on `ccs2/carstatus` (wake) is Hyundai's server; the
  client already falls back to `ccs2/carstatus/latest`, so no code change needed there.
- Round 18 (silent lock/unlock failure — CCSP envelope validation): symptom was the API log showing
  `POST …/ccs2/control/door → 200 ✓` yet the physical car doing nothing and myHyundai showing no change.
  Root cause: Hyundai's CCSP frequently answers **HTTP 200 with `{"retCode":"F","resCode":"…","resMsg":"…"}`**
  when the car rejects a command (offline, ignition, state check, backend cooldown) — but
  `sendDoorCommand` only checked `response.ok()` (HTTP 2xx) and reported success. Added
  `readCommandOutcome(HttpResponse)` in `EuBlueLinkClient`: reads the response body once, tolerates
  `resMsg` being either a string or an object, extracts `retCode/resCode/resMsg/msgId`, and only
  reports success when `retCode == "S"` (falls back to HTTP-status truth for empty / non-JSON bodies).
  Both the CCS2 (`/api/v2/spa/vehicles/{id}/ccs2/control/door`, body `{"command":"close|open"}`) and
  legacy v1 (`/api/v1/spa/vehicles/{id}/control/door`, body `{"action":"…","deviceId":"…"}`) success
  branches now use it: on real success the activity log includes `retCode/resCode/msgId`; on
  200-but-failure it logs `HTTP 200 but body says failure … | body: …` and returns
  `CommandResult.Failure("Car rejected close: <resMsg>")` so the UI toast surfaces Hyundai's actual
  reason instead of a false green tick. Endpoints/payloads themselves are unchanged (verified against
  BlueDeck, bluelinky, and `hyundai_kia_connect_api`). No polling of `msgId` yet — the reference libs
  don't poll either, and the retCode check alone catches the "silent failure" class of bugs.
- Round 19 (silent lock/unlock failure — async msgId polling): Round 18 caught the "backend rejected"
  class of failures, but the user hit a second, more subtle class: **HTTP 200 + `retCode:"S" resCode:"0000"
  msgId:…`** — the backend fully queued the request — yet the car itself never executed it and stayed
  locked. Reason: EU CCS2 control commands are **asynchronous**. `retCode:"S"` only means "queued in
  CCSP"; the car's actual execution result appears later on a separate polling endpoint,
  `GET /api/v2/spa/notifications/{vehicleId}/records`, keyed by `recordId == msgId`, with
  `result: "success" / "fail" / (pending)` and a `resultCode`. `hyundai_kia_connect_api` and `bluelinky`
  both use this. Added `pollCommandStatus(vehicleId, msgId, timeoutMs=30_000, pollEveryMs=3_000)` +
  `confirmActionOrReport()` in `EuBlueLinkClient`; both the CCS2 and v1 success branches of
  `sendDoorCommand` now switch from "queued → Success" to "queued → poll → report actual outcome":
  `Success("Locked"/"Unlocked")` only when the car reports `result:"success"`; `Failure("Car reported
  close failed (resultCode X) — likely offline, asleep or state-blocked")` on `result:"fail"`; and
  `Failure("Command queued but the car didn't confirm within 30s. It may be offline / in a poor signal
  area / asleep. Try again in a minute.")` on timeout. Activity log now shows `queued…` on dispatch and
  `action poll: ✓ close confirmed by car (resultCode=0000)` on real completion (or the failure/timeout
  equivalents), so the UI can no longer report a false green tick. Endpoints/headers unchanged; the
  poll uses regular authed CCSP headers.
- Round 20 (poll diagnostics + v1 fallback + surface Hyundai's message + device re-registration):
  Round 19's poll had two blind spots. (1) The whole 30 s window burned on a single hung request
  (Ktor's global 30 s request timeout) so we got "1 tries" and no data. Fix: per-request timeout of
  **8 s** via Ktor `timeout { requestTimeoutMillis = 8_000 }` inside the loop — three probes fit in
  the window now. (2) The v2 records endpoint returns **HTTP 404 "Not Found"** for our account,
  while **v1** works. Fix: `pollCommandStatus` now tries both candidates
  (`/api/v2/spa/notifications/{id}/records` first, `/api/v1/spa/notifications/{id}/records` fallback)
  and drops the failing one after the first successful hit, so subsequent polls in the same window
  only call the working path. First-probe log line now shows `action poll (v2|v1) #N → HTTP <code>
  in <ms>ms | body: <snippet>` and, on a match, `action poll (v1): matched record → {…full JSON…}`
  so the actual failure reason is visible.
  The matched-record log then revealed Hyundai's real refusal on the user's Ioniq 5: `{"result":
  "fail","record":"[Fail] Cannot unlock door. Please check your vehicle status.", createdAt … updatedAt
  (Δ 187ms)}` — a **server-side pre-check reject** by CCSP (car never contacted), which the persistent
  `503 resCode 5031 "Unavailable remote control"` on the wake endpoint had already been hinting at.
  Two client-side changes made this actionable: `ActionCheck` now carries the raw `record` field;
  `confirmActionOrReport` uses it directly — the UI toast now reads `Hyundai says: Cannot unlock door.
  Please check your vehicle status. Usually means remote services are disabled in the car, the car is
  offline/asleep, ignition is on, or the door is already in the requested state.` instead of a cryptic
  `resultCode=fail`. And `BlueLinkClient` gained `resetDeviceRegistration()` (default no-op; EU impl
  calls `SecureStore.clearDeviceId()` so `ensureDeviceRegistered` re-registers on the next call), with
  a Settings → Account "Reset device registration" button + short hint (`set_reset_device` /
  `set_reset_device_hint` / `reset_device_done`, translated across all 5 locales). Useful when a stale
  CCSP deviceId is silently blocking every remote command.
- Round 21 (EU login rewrite — OneApp/CCI WAF bypass): since 2026-08-11 Hyundai's IDPConnect WAF
  blocks the legacy login `client_id` (`6d477c38…` + `:8080` redirect) entirely — "classified as an
  abusing request and blocked". Root cause confirmed (hyundai_kia_connect_api#1277 /
  bluelink-refresh-token v6.9.0) as the **client_id block, NOT a TLS fingerprint**, so the whole
  WebView machinery was redundant. Rewrote EU sign-in to the **OneApp/CCI flow** over raw OkHttp:
  authorize with `oneAppClientId` (`4f4953b5…`, redirect `oneapp.hyundai.com/redirect`) → certs →
  RSA-encrypt password → `/auth/account/signin` (302 direct to the OneApp redirect with `?code=`) →
  exchange the code at `cci-api-eu.hyundai.com/domain/api/v1/auth/token` for the CCI token set →
  exchange that at `…/v1/auth/token-exchange?serviceType=CCS` for a **CCS token** that the existing
  `ccapi:8080` vehicle/control endpoints still accept as a Bearer. Refresh = CCI
  `…/v2/auth/token-refresh` (full CCI set as JSON) → re-exchange CCS. Changes:
  `RegionConfig` gained `oneAppClientId`/`oneAppRedirectUri`/`cciApiBaseUrl`/`cciPackageId`/
  `cciClientName`/`cciClientVersion`/`cciClientOsVersion`/`cciNotificationProvider` (removed the old
  "FUTURE OneApp" comment block); `SessionTokens` gained the CCI fields (`cciAccessToken`,
  `exchangeableToken`/`Refresh`, `nonCcsToken`/`Refresh`, `idToken`); `EuDtos` swapped the OAuth
  `TokenResponse` for `CciTokenResponse` + `CcsExchangeResponse`; `EuIdpAuth` fully rewritten
  (`login()` + `refresh()`, CCI header/timezone helpers, shared cookie jar); `EuBlueLinkClient`
  `loginWithPassword`/`ensureFreshSession`/`persist` use the CCI flow; the dead `login(code)` OAuth
  exchange, `loginWithRefreshToken`, `idpTokenRefresh`, `idpHeaders` and the `appContext` ctor param
  were removed. **Deleted redundant code:** `EuWebLogin.kt` (WebView login), the whole WebView +
  external-browser + 48-char-refresh-token UI in `LoginScreen`/`LoginViewModel` (now a single
  email + password + PIN form + demo), `EuAuth.buildAuthorizeUrl`/`urlEncode`,
  `BlueLinkClient.loginWithRefreshToken` (+ Fake/Unsupported/Metered overrides), and the manifest
  `i5autolock://oauth` deep-link intent-filter. Also fixed a Round-20 bug: `MeteredBlueLinkClient`
  now forwards `resetDeviceRegistration()` (previously the interface default no-op swallowed it before
  it reached the EU client). Added the login hosts (`idpconnect-eu`, `cci-api-eu` for hyundai+kia) to
  `network_security_config.xml`. Vehicle status, lock/unlock, control-token, device registration,
  diagnostics and the msgId polling are unchanged — they still run on the CCS token against ccapi:8080.
  Tests/lint/assembleDebug green.
- Round 22 (dead-code cleanup after the login rewrite): removed now-unused `RegionConfig` fields left
  over from the OAuth era — `userApiBaseUrl`, `clientSecret`, `basicAuth`, `redirectUri`,
  `appRedirectScheme`, `idpRedirectUri` (login uses `oneApp*`/`cci*`; vehicle calls use `clientId` as
  `ccsp-service-id` + `Stamp`). Dropped the unused `Region.requiresOauthLogin` enum property, the
  production-dead `EuAuth.extractAuthCodeLoose` (rewrote `EuAuthTest` to cover the still-used
  `extractAuthCode`), and three dead imports in `EuBlueLinkClient` (`FormDataContent`,
  `io.ktor.http.parameters`, `jsonArray`). Pruned dead strings across all 5 locales
  (`login_title_hyundai`, `login_browser_hint`/`_button`, `login_webview_progress`, the
  `help_signin_4` "Advanced: refresh token" entry + its HelpScreen row) and corrected `login_intro` /
  `login_password` wording (no more "refresh token" / "48-char"). Tests/lint/assembleDebug green.
- Round 23 (reliability + QoL features): one-tap **device re-registration** and a batch of lock-
  reliability features.
  - `BlueLinkClient.resetDeviceRegistration()` now returns `CommandResult` and (EU) does the full
    **reset → register fresh id → verify** (clears deviceId, re-registers via notifications/register,
    then tests with an auth-only `vehicles()` call). Settings → Account button shows a spinner
    (`SettingsUiExtras.resettingDevice`) and reports the new id + reachable-vehicle count.
  - **#3/#4 door + window open guard:** `VehicleStatus` gained `anyWindowOpen` (+ `isOpenSomewhere`);
    CCS2 parser reads `Cabin.Window` (best-effort, null if absent). `LockPolicy.decide(status,
    dontLockIfOpen)` skips with "A door/window is open" when the guard is on (`AppSettings.dontLockIfOpen`,
    default on). The car can't lock with something open anyway, so we skip + explain instead of a false fail.
  - **#1 verified lock:** after a successful lock, if `verifyLock` (default on) re-reads cached status
    and sends one more lock if the car still shows unlocked.
  - **#2/#6 retry window + auto re-register:** `performLock` retries retriable failures (RateLimited /
    Failure, incl. 5031 / "didn't confirm") with exponential backoff up to `retryWindowMinutes`
    (0 = off); the **first retry re-registers the device** (stale/throttled id is the common cause).
  - **#5 require walk-away confirmation:** `requireWalkAwayConfirmation` (default off) — only lock if
    Activity Recognition or the geofence confirms you left; otherwise skip instead of locking on the
    Bluetooth disconnect alone. Controller tracks `walkAwayConfirmed`.
  - **#8 departure summary:** `AutoLockNotification.postDeparture` posts a one-shot dismissible summary
    after each attempt ("Car secured · Locked ✓ · 72% · doors closed · Parked near …"), gated by
    `departureSummary` (default on).
  - **#7 widget last-locked:** `StatusCache` persists `lastLockedAtEpochMs`; the widget shows
    "Locked · 5m ago".
  - New Settings "Lock reliability" section + a walk-away toggle in Detection; setters in
    `SettingsViewModel`; DataStore keys in `SettingsRepository`; strings in all 5 locales;
    `LockPolicyTest` covers the open-guard. Tests/lint/assembleDebug green.
- Round 24 (the "5031" wake 503 — cached-first refresh): the recurring
  `503 {"resCode":"5031","resMsg":"Unavailable remote control - Service Temporary Unavailable"}` on
  `GET /ccs2/carstatus` (the live **wake**) is **Hyundai server-side and has no client fix** —
  confirmed by `hyundai_kia_connect_api` #1280 + `kia_uvo` #1774 (a transient backend refusal; the
  maintainer's guidance is "retry", and one permanently-5031 vehicle shouldn't break others). Crucially,
  `hyundai_kia_connect_api`'s **normal** `update_vehicle_with_cached_state` only reads the cached
  `/ccs2/carstatus/latest` snapshot and **never wakes**; waking is a separate, rarely-used force op.
  Our app was waking on every manual refresh, so it hit the 503 every time even though the cached
  fallback already returned good data (battery/range/lock all parsed). Fix: **default refresh to the
  cached snapshot** and make waking opt-in. Added `AppSettings.liveWakeRefresh` (default **off**);
  `EuBlueLinkClient.status(forceRefresh)` only wakes when `forceRefresh && liveWakeRefresh` (and the
  cooldown elapsed) — otherwise it reads `carstatus/latest` (reliable 200, no 503). `triggerCcs2Wake`
  now logs an expected 5031/503 softly ("wake: car busy … using cached snapshot") instead of a scary
  `✗` failure line, and skips the 25 s wait. New Settings → Timing "Live refresh (wake the car)" toggle
  (+ hint explaining the 503 is normal), setter, DataStore key, strings in all 5 locales. The auto-lock
  pre-lock check + verify already used cached reads, so they were unaffected. Net: normal syncs are now
  fast, reliable, and 503-free; users who want a true live poll can opt in and accept the occasional
  server-side 5031. Tests/lint/assembleDebug green.
- Round 25 (CCS2 lock state was inverted — showed Unlocked while locked): the car showed UNLOCKED on
  refresh even though it was locked (battery/range were correct). Root cause: the CCS2 door `Lock`
  field is an **UNLOCKED flag** — `0 = locked, 1 = unlocked` — confirmed by `hyundai_kia_connect_api`'s
  `_update_vehicle_properties_ccs2` (`front_left_door_is_locked = not bool(Cabin.Door.Row1.Driver.Lock)`).
  Our `parseCcs2Status` had it backwards (`1 → LOCKED, 0 → UNLOCKED`) and read only the driver door.
  Fixed: map `Lock == 0` → locked, and derive the car's lock from **all** reporting doors
  (`is_locked = all doors locked`; any door reporting unlocked → UNLOCKED; none reporting → UNKNOWN),
  matching the reference's `fl AND fr AND bl AND br`. The door/window **`Open`** fields are used
  directly (not inverted) in the reference, so those were already correct — no change. Legacy v1
  `doorLock` is a real boolean (true=locked), also unaffected. Diag log now prints the raw per-door
  lock array (`doorLocks(0=locked)=[…]`) for future verification. Tests/lint/assembleDebug green.
- Round 26 (lock/unlock rejected "check your vehicle status" — v1 control fallback): CCS2 control was
  `retCode=S` queued then the car reported `result:"fail" "[Fail] Cannot unlock door. Please check your
  vehicle status."` ~200 ms later (backend pre-check, car never contacted), while myHyundai worked.
  Root cause: `hyundai_kia_connect_api` picks the control protocol by `ccu_ccs2_protocol_support`, and
  this Ioniq 5's vehicle list reports **`ccuCCS2ProtocolSupport=0`** — so the reference would use the
  **legacy v1 control** (`POST /api/v1/spa/vehicles/{id}/control/door` `{action, deviceId}`), NOT the
  CCS2 v2 path. We were forcing CCS2 control because CCS2 *status* works — but some Ioniq 5s report CCS2
  status yet need v1 control. Fix: (1) added the `AuthorizationCCSP` header (control token in BOTH
  `Authorization` + `AuthorizationCCSP`, matching the reference's `_get_control_headers`); (2) when a
  CCS2 control command is **car-rejected** (poll `result:fail`), `sendDoorCommand` now **falls back to
  the v1 control path** (safe — the CCS2 command was rejected, not executed) instead of giving up;
  (3) an in-memory `v1ControlVehicles` set remembers a car that needed v1 (learned when v1 succeeds
  after CCS2 rejection) so the wasted CCS2 attempt is skipped for the rest of the session. PENDING/
  timeout and submission-`retCode=F`/429/401 still return as-is (no v1 fallback). Extracted
  `actionFailureReason(check, action)` shared by both paths. Status reads stay on CCS2 (unchanged).
  Tests/lint/assembleDebug green.
- Round 27 (v1 legacy control got 403 "disallowed" — correct legacy headers): the Round-26 v1 fallback
  failed with `403 { "error": "Access to this API has been disallowed" }` because it reused the CCS2
  headers — `ccuCCS2ProtocolSupport=1` + the control token in `Authorization`/`AuthorizationCCSP`. The
  reference's **legacy** `lock_action` uses `_get_authenticated_headers`: the **access token** (not a
  control token), **no `AuthorizationCCSP`**, and the vehicle's real protocol flag (**`0`** for this
  car). Sending `=1` on the v1 endpoint makes the backend disallow it. Fix: `baseHeaders(builder,
  ccs2: Boolean = true)` now emits `ccuCCS2ProtocolSupport` as `1` (CCS2) or `0` (legacy); the v1
  control POST calls `baseHeaders(this, ccs2 = false)` with `Authorization = authHeader()` (CCS access
  token) and no `AuthorizationCCSP`/control token, `{action, deviceId}` body — matching the reference
  exactly. So a flag-0 Ioniq 5 whose CCS2 control is car-rejected now retries v1 with headers the
  legacy endpoint accepts. Tests/lint/assembleDebug green.



