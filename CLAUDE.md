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
- Activity Recognition + Geofencing wiring: hooks exist in `AutoLockController`
  (`onWalkingConfirmed`, `onMovedBeyondGeofence`); the Play Services registration
  (transition receiver / geofence client) still needs to be added.
- "Confirm before locking" setting is stored but the notification-action confirm path
  is not yet wired.
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
