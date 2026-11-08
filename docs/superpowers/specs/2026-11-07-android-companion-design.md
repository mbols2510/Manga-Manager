# Android Companion App — Design

Date: 2026-11-07 · Status: draft for review

## 1. Goal and success criteria

A native Android companion for the self-hosted Manga Manager server.

- Browse the **Updates** feed with covers, search and filters.
- Run the server job, see health, and handle **Doubles** (keep / trash duplicate chapters).
- Receive **notifications** for new chapters and series, **without ntfy**.
- Be gentle on the battery: no persistent connection, one tiny batched check per interval.

Success means:
1. A new chapter on the server produces one grouped notification within one check interval.
2. Idle for 24 h, the app registers exactly one scheduled job, holds no wake locks, and appears near the bottom of Android's battery usage.
3. A stolen or lost phone can be cut off by revoking its token on the server.
4. The existing web dashboard and ntfy/Discord notifications behave exactly as before.

## 2. Decisions taken

| Topic | Decision |
|---|---|
| Platform | Native Kotlin, Jetpack Compose (Material 3), min SDK 26 |
| Scope | Companion client for this server (not a standalone reader) |
| Notifications | Own path, **no ntfy**. Periodic WorkManager check, `Notifier` interface so FCM can be added later |
| Connectivity | Configurable server URL, HTTPS or HTTP (see §7 for HTTP rules) |
| Auth | Per-device bearer token, created and revoked by an admin in the web UI |
| Distribution | Sideloaded APK from GitHub Releases, no Play Store |
| Location | `android/` folder inside the Manga-Manager repo |

Out of scope: FCM/ntfy, a reader, multi-server, iOS, Play Store, widgets.

## 3. Server changes (Flask, additive)

### 3.1 Device tokens
- New table `device_tokens`: `id`, `name`, `user`, `token_hash`, `created_at`, `last_seen`, `revoked_at`.
- Token: 32 random bytes (`secrets.token_urlsafe(32)`), shown **once**, stored as SHA-256 hash, looked up by hash and compared with `hmac.compare_digest`.
- `guard()` in `web.py`: if `Authorization: Bearer …` is present, resolve it to a device principal. Cookie sessions are unchanged.
- Device tokens are **never admin**, whatever the creating user's role is (see §7).
- Device tokens only work when `cfg.auth_enabled` is true. With auth disabled the v1 token endpoints return `409` and the UI explains why.

### 3.2 Endpoints
| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/v1/devices` | admin cookie session only | Create token. Returns token once plus a pairing payload |
| `GET /api/v1/devices` | admin cookie session only | List devices (name, created, last seen) |
| `DELETE /api/v1/devices/<id>` | admin cookie session only | Revoke |
| `GET /api/v1/changes?since=<id>&limit=50` | device or cookie | Updates with `id > since`, oldest first. Returns `{items, next_cursor, has_more}`. `ETag`/`304` when nothing is new. `410` if `since` is older than the retention window |
| `GET /api/v1/changes/latest` | device or cookie | Current max update id (baseline after pairing or `410`) |

The cursor is the existing `updates.id`, so no extra table is needed. Items reuse the existing fields: `id`, `title` (display title), `category`, `is_new`, `chapters`, `created_at`, `thumbnail`, `kavita_url`, plus a server-computed `sensitive` flag (§7.5).

### 3.3 Existing endpoints reachable with a device token
`GET /api/updates`, `GET /api/status`, `POST /api/run`, `GET /api/health`, `GET /thumb/<id>.png`, and the `/api/double-chapters/*` endpoints. Device tokens use a **deny-by-default allowlist** of exactly these endpoints, enforced in `guard()`. Anything not listed returns `403` for a device principal: everything in `ADMIN_ENDPOINTS` (settings, restart, anything exposing credentials), and also the non-admin routes the app does not need, such as alias create/delete/reprocess, CBZ and collections tools, and signed-link or login pages. New routes added to the server later are therefore closed to devices until deliberately allowlisted.

### 3.4 Web UI
A small "Devices" card in Settings: create (name → QR + one-time code), list, revoke.

## 4. Android app

### 4.1 Structure (single Gradle module, package by feature)
```
android/app/src/main/kotlin/.../
  data/    api (Retrofit, AuthInterceptor), db (Room: UpdateEntity, SyncState), repo
  work/    CheckWorker, Scheduler
  notify/  Notifier (interface), LocalNotifier, channels, grouping
  ui/      updates/ doubles/ jobs/ settings/ pairing/ + navigation
  core/    TokenStore (Keystore), UrlPolicy, Clock
```
Stack: Compose, Room, Retrofit/OkHttp, Coil, WorkManager, kotlinx.serialization.

### 4.2 Screens
- **Pairing**: scan QR or paste code, validate with `/api/v1/changes/latest`, store baseline cursor.
- **Updates**: Room-cached feed, search, new/updated and category filters, open Kavita link.
- **Doubles**: series with duplicates, per-chapter keep/trash with confirmation, trash list.
- **Jobs/Health**: run now, scheduler status, connection checks.
- **Settings**: server URL, pairing, check interval, Wi-Fi only, quiet hours, last successful check, notification permission help.

## 5. Data flow

### 5.1 Background check
1. WorkManager runs `CheckWorker` (network connected, battery not low).
2. It sends `GET /api/v1/changes?since=<cursor>` with the stored `ETag`.
3. `304` or an empty list: finish, no DB write, no notification.
4. Otherwise: upsert items in Room and advance the cursor in **one transaction**, loop while `has_more`, then post **one grouped notification** for IDs not yet notified.

### 5.2 Foreground
Show the Room cache immediately, refresh once on open and on pull-to-refresh. No timer-based refresh.

### 5.3 Failure handling
| Situation | Behaviour |
|---|---|
| Unreachable server, VPN off, timeout | `Result.retry()` once per period (WorkManager exponential backoff for that single retry), then wait for the next period, silent. Settings shows last successful check |
| Offline for days | Cursor unchanged. Next success pages through everything |
| `401` (revoked) | Cancel periodic work, one "Re-pair this device" notification, no retry loop |
| `410` or cursor reset | Fetch `changes/latest` as a new baseline and **do not notify** for older items |
| TLS failure | Fail closed, visible in Settings |
| Notification permission denied | Cache and show an in-app banner, no nagging |
| Doze or battery saver | Runs in maintenance windows, may be late. Accepted trade-off of the polling design |
| Duplicates | Stable server IDs, Room upsert, notify only for IDs not seen before |

## 6. Battery budget
- The app's own code and source manifest use no foreground service, wake locks, exact alarms or custom receivers. One unique `PeriodicWorkRequest`. WorkManager's own merged-in `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` (so the periodic job survives reboots) are accepted; its `FOREGROUND_SERVICE` permission and `SystemForegroundService` are stripped, so expedited and foreground work are not allowed.
- Interval: default 30 min, range 15 min – 6 h, or off. Flex about 25%. Optional night stretch to 2 h during quiet hours.
- Constraints: `CONNECTED` (or `UNMETERED` when Wi-Fi only) and `requiresBatteryNotLow`.
- After a failed check: one retry per period (WorkManager exponential backoff, 30 s initial delay, for that single retry); after that the job waits for the next period (ruling 22). 10 s connect, 15 s read timeouts.
- ETag/`304`; no Room write when nothing changed (one small DataStore write records the last successful check time).
- Covers load only while visible, Coil memory and disk caches with a size cap, no background prefetch.

## 7. Security

### 7.1 Threats considered
1. A leaked or stolen device token.
2. A token intercepted on the network (especially over HTTP).
3. A malicious app on the phone stealing the token or the pairing link.
4. Privilege escalation from a device token to server settings (which hold Kavita, Suwayomi and Discord credentials).
5. Sensitive titles or covers exposed on the lock screen or in notification history.
6. Brute force against token or login endpoints.
7. Accidental exposure when the server runs with auth disabled.
8. Supply chain or release-signing compromise.

### 7.2 Server controls
- 256-bit random tokens, hashed at rest, constant-time comparison, uniform `401` with no hint on why.
- **Device tokens are never admin** and can only reach the allowlist in §3.3 (deny by default), so a token cannot reach `ADMIN_ENDPOINTS` or unrelated tools such as alias editing. Device management endpoints accept **only an admin cookie session**, so a token cannot mint or list tokens.
- Revocation takes effect on the next request (the lookup checks `revoked_at`). `last_seen` is shown so stale devices can be spotted. Optional expiry is a later addition.
- Per-token and per-IP rate limiting on bearer requests, reusing the login limiter pattern. Failed bearer attempts are counted by IP.
- Bearer requests carry no Origin, so the CSRF check passes them, and they are immune to CSRF because no ambient credentials are involved.
- Destructive actions (`remediate`, trash) stay behind the existing endpoints and are logged to `log_activity` with the device name.
- Tokens, hashes and `Authorization` headers are never logged. Error responses never echo them.
- `POST /api/v1/devices` refuses when auth is disabled.

### 7.3 Transport
- HTTPS is the default. The app uses the system trust store and fails closed on any TLS error. No "trust all certificates" option.
- **HTTP is only accepted for private destinations**: loopback, RFC1918 ranges, `100.64.0.0/10` (Tailscale/CGNAT), link-local, and `*.local`. HTTP to a public host is **blocked**, not just warned about. A private-HTTP server shows a persistent "unencrypted" notice in Settings, because anyone on that network can read the token.
- Android cleartext traffic cannot be restricted per host in the manifest, so the app enforces this in `UrlPolicy` (validated at pairing and in an OkHttp interceptor on every request, and again after redirects). Redirects from HTTPS to HTTP are refused, and the `Authorization` header is not forwarded to a different host.
- Optional later: trust-on-first-use certificate pinning for self-signed setups.

### 7.4 Pairing and storage
- The pairing code is shown once as a QR and a copyable string, with the server URL embedded. It is a **one-time display**, not a link: the app does **not** register a custom URL scheme or exported deep-link activity, so another app cannot intercept the token through an intent.
- The token is stored encrypted with an Android Keystore AES-GCM key (non-exportable, hardware-backed where available). `android:allowBackup="false"` and `dataExtractionRules` exclude all app data from cloud and device-transfer backup.
- Tokens are never passed in URLs or query strings, only in the `Authorization` header (thumbnails use the interceptor).
- In the app's own code and source manifest, all components are non-exported except the launcher activity, and the app requests only `INTERNET`, `ACCESS_NETWORK_STATE` and `POST_NOTIFICATIONS` (plus `CAMERA` only if in-app QR scanning is used, requested at use). Notification actions use immutable `PendingIntent`s. Accepted merged-in library items, enforced by an allowlist check on the merged release manifest: `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` from WorkManager so the periodic job survives reboots; the androidx.core `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` signature permission; and exported WorkManager/profileinstaller components guarded by system permissions (`SystemJobService` with `BIND_JOB_SERVICE`, `DiagnosticsReceiver` and `ProfileInstallReceiver` with `DUMP`). `FOREGROUND_SERVICE` and `SystemForegroundService` are stripped, so expedited and foreground work are not allowed.
- Release builds are not debuggable, with R8 enabled and logging stripped. OkHttp logging is disabled in release.

### 7.5 Sensitive content
The server already knows "mature" and blur categories. The app inherits that:
- `sensitive` is computed server-side from those categories and sent with each item.
- For sensitive items the notification uses `VISIBILITY_PRIVATE` with a generic public version ("New update"), **no title and no cover image**. Covers in the feed respect the same blur setting.
- Notification groups are collapsed per channel. Users can mute or hide the channel per category in Android settings, and "hide details on lock screen" is honoured.

### 7.6 Auth disabled
If `cfg.auth_enabled` is false, device tokens are unavailable and the web UI states that the API is open to anyone who can reach it. The app refuses to pair against such a server by default, with an explicit "I understand" override only on private addresses.

### 7.7 Supply chain and release
- Dependencies pinned through a Gradle version catalog and a checked-in lockfile (dependency verification). Dependabot/renovate for updates.
- CI uses least-privilege `permissions:` and pinned action versions. Signing keystore and passwords live only in repository secrets, never in the repo or logs. Releases publish the APK with its SHA-256 checksum.
- No analytics, ads or third-party SDKs. Network traffic goes only to the configured server.

### 7.8 Security tests
- Server (pytest): bearer auth accepted and revoked tokens rejected; device token gets `403` on every `ADMIN_ENDPOINTS` route and on every registered route not in the allowlist (test iterates `app.url_map`, so new routes are covered automatically); device endpoints reject bearer auth; auth-disabled behaviour; rate limiting; hash-only storage (the plain token never appears in the DB); no token in logs.
- App (JUnit/Compose): `UrlPolicy` table tests (public HTTP rejected, private HTTP allowed, HTTPS→HTTP redirect refused, cross-host redirect strips the header); sensitive items never put title or cover into a notification; token is absent from logs and backups (manifest lint).
- CI runs Android Lint and a dependency vulnerability scan.

## 8. Testing
- **Server:** pytest alongside the existing `tests/` (token auth, cursor edge cases `304`/`410`/empty/paging, revocation).
- **App:** JUnit and MockWebServer for repository and `CheckWorker` (`TestListenableWorkerBuilder` for success, `304`, `401`, `410`, network error); a fake `Notifier` to assert grouping and de-duplication; Compose tests for pairing and the feed.
- **Battery:** 24 h idle on a real device. Verify with `adb shell dumpsys batterystats` and `dumpsys jobscheduler` that there is exactly one job, wake-ups match the interval and no wake locks are held. Report the numbers.
- **Manual end-to-end:** pair, trigger a server run, confirm one grouped notification.

## 9. Build and delivery
- Gradle (Kotlin DSL), Compose BOM, `android/` folder, `docs/api-v1.md` as the shared contract.
- GitHub Actions: build debug APK, run unit tests and lint on every push. Signed release APK attached to a GitHub Release.
- Order of work, each step independently testable:
  1. Server: device tokens, bearer auth, `changes` endpoints, tests, Settings "Devices" card.
  2. App skeleton: pairing, token store, `UrlPolicy`, Updates feed with cache.
  3. Background check and notifications with the battery rules and sensitive-content handling.
  4. Doubles, Jobs/Health, Settings screens.
  5. CI, signed release, battery verification, README section.

## 10. Environment caveat
The authoring sandbox may lack the Android SDK or an emulator. Server code and JVM unit tests can be run here and CI builds the APK. The real-device battery measurement (§8) has to be run on the user's phone, with exact commands provided.

## 11. Open questions
None blocking. Later options: FCM trigger behind `Notifier`, token expiry, certificate pinning.
