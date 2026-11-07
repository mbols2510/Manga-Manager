# Android Companion App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship per-device token auth and a `changes` feed on the Manga Manager server, plus a battery-friendly native Android app that browses updates, handles Doubles, runs jobs and notifies about new chapters without ntfy.

**Architecture:** The Flask server gains a `devices` module (hashed bearer tokens), a deny-by-default allowlist in `guard()`, and `/api/v1` routes keyed on the existing `updates.id` as cursor. The Android app (Kotlin, Compose, single module under `android/`) caches the feed in Room and checks for changes with one unique `PeriodicWorkRequest`, posting a grouped notification through a `Notifier` interface.

**Tech Stack:** Python 3 / Flask / psycopg / pytest (existing). Kotlin, Jetpack Compose (Material 3), Room, Retrofit + OkHttp, Coil, WorkManager, kotlinx.serialization, JUnit + MockWebServer, Gradle Kotlin DSL with version catalog.

**Spec:** `docs/superpowers/specs/2026-11-07-android-companion-design.md`

## Global Constraints

- Android min SDK 26. Package `com.mbols2510.mangamanager`. Single Gradle module `android/app`.
- No ntfy, FCM, analytics, ads or third-party SDKs. Network traffic only to the configured server.
- Token: 32 random bytes (`secrets.token_urlsafe(32)`), shown once, stored as SHA-256 hash, compared with `hmac.compare_digest`. Sent only in `Authorization: Bearer`, never in URLs or logs.
- Device tokens are never admin. They reach only the allowlist in Task 2 (deny by default). Device-management endpoints accept only an admin cookie session. Device tokens require `cfg.auth_enabled`.
- `GET /api/v1/changes?since=<id>&limit=50`: oldest first, `{items, next_cursor, has_more}`, `ETag`/`304`, `410` when `since` is older than the retention window or newer than the max id.
- Check interval: default 30 min, range 15 min to 6 h, or off. Flex about 25%. Constraints `CONNECTED` (`UNMETERED` when Wi-Fi only) and `requiresBatteryNotLow`. Backoff exponential, capped at the interval. Timeouts 10 s connect, 15 s read.
- No foreground service, wake locks, exact alarms or custom receivers. No timer-based refresh. No background cover prefetch.
- HTTP allowed only for loopback, RFC1918, `100.64.0.0/10`, link-local and `*.local`. HTTP to a public host is blocked. HTTPS to HTTP redirects refused. `Authorization` never forwarded to another host. No "trust all certificates" option.
- `android:allowBackup="false"`, `dataExtractionRules` excluding all app data, only the launcher activity exported, no custom URL scheme, immutable `PendingIntent`s, permissions limited to `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` (+ `CAMERA` only if in-app QR scanning is used). Release: not debuggable, R8 on, logging stripped.
- Sensitive items (server `mature` categories) use `VISIBILITY_PRIVATE` with a generic public text, no title and no cover in notifications.
- Existing dashboard, ntfy and Discord behavior must not change; the existing pytest suite must stay green.

## Review Focus

1. Server URL variants: trailing slash, sub-path behind a reverse proxy (`https://host/manga`), upper-case scheme, IPv6 ULA (`http://[fd00::1]`), and the CGNAT boundaries (`100.64.0.1` allowed, `100.128.0.1` blocked). Expect correct acceptance or a clear rejection. Owned by Task 6.
2. A 500-item backlog after weeks offline: paged through, one notification (not 500), cursor ends at the last id. Owned by Task 9.
3. Titles with emoji, quotes, HTML-like text, 300 characters, or an empty `chapters` list: notification builds without a crash and is truncated sensibly. Owned by Task 9.
4. Token revoked while the app is open: the next foreground call returns to the re-pair screen and the worker is cancelled, with no crash or retry loop. Owned by Task 7 and Task 9.
5. Notification permission denied or channel disabled by the user: items are still cached and nothing crashes. Owned by Task 9.

---

## Part A: Server

### Task 1: Device token store

**Files:**
- Create: `manga_manager/devices.py`, `tests/test_devices.py`
- Modify: `manga_manager/db.py` (add `device_tokens` to `SCHEMA`), `tests/conftest.py` (add `device_tokens` to `TABLES`)

**Interfaces:**
- Produces: `class DeviceStore(db)` with
  - `create(name: str, user: str) -> tuple[int, str]` (id, plaintext token, shown once)
  - `authenticate(token: str) -> dict | None` returns `{"id", "name", "user"}` and updates `last_seen`; `None` for unknown or revoked tokens
  - `list() -> list[dict]` with `id, name, user, created_at, last_seen, revoked_at` (never hashes)
  - `revoke(device_id: int) -> bool`
  - `pairing_code(url: str, token: str) -> str`: `"mm1." + base64url(json {"v":1,"url":url,"token":token})`, no padding

- [ ] **Step 1: Write failing tests in `tests/test_devices.py`:** `test_create_returns_token_once_and_stores_only_hash` (assert plaintext not in any column of `device_tokens`, `len(token) >= 43`), `test_authenticate_valid_updates_last_seen`, `test_authenticate_unknown_and_revoked_return_none`, `test_list_never_exposes_hash`, `test_pairing_code_round_trips` (decode and compare url/token, prefix `mm1.`).
- [ ] **Step 2: Run** `TEST_DATABASE_URL=... pytest tests/test_devices.py -v`. Expected: FAIL (`ModuleNotFoundError: manga_manager.devices`).
- [ ] **Step 3: Implement** `DeviceStore` and the table (`id BIGSERIAL`, `name`, `user_name`, `token_hash TEXT UNIQUE`, `created_at`, `last_seen`, `revoked_at`) with `secrets.token_urlsafe(32)` and `hashlib.sha256`. Look up by hash, then confirm with `hmac.compare_digest`.
- [ ] **Step 4: Run** the new tests and the full `pytest -q`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat(server): add hashed device token store`.

### Task 2: Bearer auth, allowlist, device management endpoints

**Files:**
- Create: `manga_manager/v1_devices_routes.py`, `tests/test_bearer_auth.py`
- Modify: `manga_manager/web.py` (`guard()`, wiring, `DEVICE_ALLOWED_ENDPOINTS`), `manga_manager/history.py` only if `log_activity` access is needed

**Interfaces:**
- Consumes: `DeviceStore` (Task 1); `LoginLimiter` (`manga_manager/auth.py`).
- Produces:
  - `DEVICE_ALLOWED_ENDPOINTS: frozenset[str]` in `web.py`: `updates`, `status`, `run_now`, `thumb`, `core.api_health` (confirm real names with `python -c "…app.url_map"`), the `double_chapters.*` endpoints `list_series, series_detail, cover, remediate, kavita_scan, trash, trash_restore, status`, `v1.changes`, `v1.latest`.
  - `g.device: dict | None` set by `guard()` for bearer requests.
  - `POST /api/v1/devices` (admin cookie only) body `{"name": str}` returns `201 {"id", "token", "pairing"}`; `GET /api/v1/devices`; `DELETE /api/v1/devices/<id>` returns `204`. All return `409 {"error": ...}` when `cfg.auth_enabled` is false, `401` for bearer auth, `403` for non-admin.

- [ ] **Step 1: Write failing tests** (build the app with `Config(auth_enabled=True, auth_users={"admin": hash})`, follow `tests/test_v27.py` and `test_pipeline.env`):
  - `test_valid_bearer_reaches_updates`, `test_revoked_bearer_gets_401_uniform_body`
  - `test_device_token_is_forbidden_on_every_admin_endpoint` (iterate `ADMIN_ENDPOINTS` via `app.url_map`)
  - `test_device_token_is_forbidden_on_every_route_not_in_allowlist` (iterate `app.url_map.iter_rules()`; skip `PUBLIC_ENDPOINTS` and allowlisted ones; assert 403)
  - `test_device_endpoints_reject_bearer_and_non_admin_cookie`
  - `test_devices_refused_when_auth_disabled` (409)
  - `test_failed_bearer_attempts_are_rate_limited` (9th failure from one IP returns 429)
  - `test_valid_token_is_rate_limited_per_token` (more than 120 requests per minute with one token returns 429 with `Retry-After`; a second token is unaffected)
  - `test_authorization_header_never_in_logs` (use `caplog`)
  - `test_remediate_by_device_is_recorded_in_activity_with_device_name`
- [ ] **Step 2: Run** `pytest tests/test_bearer_auth.py -v`. Expected: FAIL.
- [ ] **Step 3: Implement** the bearer branch in `guard()` before the cookie check: parse the header, `DeviceStore.authenticate`, set `g.device`, return `403` unless `request.endpoint in DEVICE_ALLOWED_ENDPOINTS`. Count failures in `LoginLimiter` keyed `bearer:<ip>`. Register the `v1` blueprint for devices. Pass the device name into the activity log for `remediate` and trash actions.
- [ ] **Step 4: Run** `pytest -q`. Expected: all PASS, including the existing suite.
- [ ] **Step 5: Commit** `feat(server): bearer device auth with deny-by-default allowlist`.

### Task 3: Changes feed

**Files:**
- Create: `manga_manager/v1_changes_routes.py`, `tests/test_v1_changes.py`
- Modify: `manga_manager/history.py` (add `changes_since`, `max_update_id`, `min_update_id`), `manga_manager/web.py` (register blueprint)

**Interfaces:**
- Consumes: `History.list_updates` row shape; `cfg.web_mature_categories`; `cfg.display_title`.
- Produces: `History.changes_since(since: int, limit: int) -> list[dict]` (ordered by `id` ascending), `History.max_update_id() -> int`, `History.min_update_id() -> int`. Item JSON: `id, title, category, is_new, chapters, created_at, thumbnail, kavita_url, sensitive` (`sensitive` is true when `category.lower()` is in `mature` categories).

- [ ] **Step 1: Write failing tests:** `test_returns_items_after_cursor_oldest_first`, `test_next_cursor_is_last_item_id_and_has_more_flag`, `test_empty_result_returns_304_with_matching_etag`, `test_etag_changes_when_new_update_added`, `test_since_older_than_retention_returns_410` (delete low ids via `prune_updates` then `since=1`), `test_since_newer_than_max_returns_410`, `test_latest_returns_max_id_zero_when_empty`, `test_mature_category_marked_sensitive`, `test_limit_is_clamped_to_1_200`.
- [ ] **Step 2: Run** `pytest tests/test_v1_changes.py -v`. Expected: FAIL.
- [ ] **Step 3: Implement** the routes. ETag = hash of `(since, max_id)`. 410 when `since > max_id`, or when the table is not empty and `since < min_id - 1`.
- [ ] **Step 4: Run** `pytest -q`. Expected: all PASS.
- [ ] **Step 5: Commit** `feat(server): add /api/v1/changes feed`.

### Task 4: Devices card in Settings and API docs

**Files:**
- Create: `docs/api-v1.md`
- Modify: `manga_manager/templates/settings.html`, `manga_manager/static/` (a small `devices.js` if the template pattern needs it), `README.md` (short "Android app" section), `tests/test_bearer_auth.py`

- [ ] **Step 1: Write failing test** `test_settings_page_has_devices_card_for_admin` (response contains `id="devices-h"`) and `test_create_device_returns_pairing_code_decodable`.
- [ ] **Step 2: Run** it. Expected: FAIL.
- [ ] **Step 3: Implement** a "Devices" `<section class="card" aria-labelledby="devices-h">` following the existing Settings cards: create by name, show the one-time code and a QR (render client-side or from the code string, no external service), list with last seen, revoke with confirm. Write `docs/api-v1.md` with the contract from the spec §3.
- [ ] **Step 4: Run** `pytest -q`. Expected: PASS. Manually open Settings in a local run and create and revoke a device.
- [ ] **Step 5: Commit** `feat(server): devices card in settings and API docs`.

---

## Part B: Android app

> The sandbox may not have the Android SDK. If `./gradlew` cannot run, write code and tests, push, and let CI be the verifier; state this plainly in each task's report.

### Task 5: Project scaffold, hardened manifest, CI

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle/libs.versions.toml`, `android/gradle/verification-metadata.xml`, `android/app/build.gradle.kts`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/xml/data_extraction_rules.xml`, `android/app/src/main/kotlin/com/mbols2510/mangamanager/MainActivity.kt`, `android/app/src/test/kotlin/com/mbols2510/mangamanager/ManifestHardeningTest.kt`, `.github/workflows/android.yml`

- [ ] **Step 1: Write failing test** `ManifestHardeningTest` (parses the manifest XML): `allowBackup_is_false`, `only_launcher_activity_is_exported`, `permissions_are_limited_to_allowed_set`, `no_custom_scheme_intent_filter`, `data_extraction_rules_exclude_everything`.
- [ ] **Step 2: Run** `cd android && ./gradlew testDebugUnitTest`. Expected: FAIL (no project / manifest).
- [ ] **Step 3: Implement** the scaffold: min SDK 26, version catalog with pinned versions, dependency verification, release build with R8 and `isDebuggable = false`, a proguard rule stripping `Log.*`. CI workflow with `permissions: contents: read`, pinned action versions, steps: `testDebugUnitTest`, `lintDebug`, `assembleDebug`, upload the APK artifact.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest lintDebug assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(android): project scaffold with hardened manifest and CI`.

### Task 6: `UrlPolicy` and `TokenStore`

**Files:**
- Create: `core/UrlPolicy.kt`, `core/TokenStore.kt`, `core/PairingCode.kt`; tests `UrlPolicyTest.kt`, `PairingCodeTest.kt`, `TokenStoreTest.kt` (under `app/src/test`; `TokenStore` uses an injectable `Cipher` seam so it runs on JVM)

**Interfaces:**
- Produces:
  - `sealed interface UrlCheck { data class Ok(val normalized: HttpUrl) ; data class Rejected(val reason: String) }`
  - `object UrlPolicy { fun check(raw: String): UrlCheck; fun isPrivateHost(host: String): Boolean; fun isUnencrypted(url: HttpUrl): Boolean }`
  - `data class Pairing(val url: String, val token: String)`; `object PairingCode { fun parse(raw: String): Pairing? }` (trims whitespace and newlines, requires the `mm1.` prefix, `v == 1`)
  - `interface TokenStore { fun save(token: String); fun load(): String?; fun clear() }`, with `KeystoreTokenStore(context)` using a non-exportable AES-GCM Keystore key.

- [ ] **Step 1: Write failing tests**, table-driven for `UrlPolicy.check`:
  - allowed: `https://example.com`, `HTTPS://Example.com/`, `https://host/manga` (normalized keeps the sub-path with a trailing slash), `http://192.168.1.10:8080`, `http://10.0.0.5`, `http://172.16.0.1`, `http://100.64.0.1`, `http://[fd00::1]`, `http://localhost`, `http://nas.local`
  - rejected: `http://example.com`, `http://8.8.8.8`, `http://100.128.0.1`, `http://172.32.0.1`, `ftp://x`, `` (empty), `https://` , `http://user:pw@192.168.1.1` (credentials in URL)
  - `PairingCode.parse` accepts a code with surrounding whitespace and newlines; returns `null` for wrong prefix, bad base64, `v != 1`, or a missing token.
  - `TokenStoreTest`: saved value is not present in plaintext in the backing storage; `clear` removes it.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*UrlPolicyTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Use `InetAddress` parsing for IPv4 and IPv6 (`fc00::/7` and `fe80::/10` count as private; no DNS lookup, hostnames other than `localhost` and `*.local` are public).
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): URL policy, pairing code and token store`.

### Task 7: API client, Room cache, repository

**Files:**
- Create: `data/api/MangaApi.kt`, `data/api/AuthInterceptor.kt`, `data/api/Models.kt`, `data/db/AppDatabase.kt`, `data/db/UpdateEntity.kt`, `data/db/SyncState.kt`, `data/repo/UpdatesRepository.kt`, `data/repo/AuthState.kt`; tests `AuthInterceptorTest.kt`, `UpdatesRepositoryTest.kt` (MockWebServer, Room in-memory via Robolectric or a DAO fake)

**Interfaces:**
- Consumes: `UrlPolicy`, `TokenStore`.
- Produces:
  - `interface MangaApi { suspend fun changes(since: Long, limit: Int = 50, ifNoneMatch: String?): Response<ChangesDto>; suspend fun latest(): LatestDto; suspend fun updates(...): UpdatesPageDto; suspend fun status(): StatusDto; suspend fun run(): Response<RunDto>; plus Doubles calls for Task 11 }`
  - `data class ChangesDto(items: List<UpdateDto>, nextCursor: Long, hasMore: Boolean)`, `UpdateDto(id, title, category, isNew, chapters, createdAt, thumbnail, kavitaUrl, sensitive)`
  - `AuthInterceptor`: adds the `Authorization` header, enforces `UrlPolicy` on every request and on redirects, removes the header when the host changes, refuses HTTPS to HTTP redirects, logs nothing.
  - `class UpdatesRepository { suspend fun checkForChanges(): CheckResult; suspend fun baseline(): Unit; val feed: Flow<List<UpdateEntity>> }`
  - `sealed interface CheckResult { object NoChange; data class NewItems(val items: List<UpdateEntity>); object Unauthorized; object Offline }`
  - `AuthState`: `StateFlow<Boolean>` `needsRepair`, set when any call returns 401.

- [ ] **Step 1: Write failing tests:** `AuthInterceptorTest` (`header_added`, `public_http_request_blocked_before_network`, `redirect_to_other_host_drops_authorization`, `https_to_http_redirect_refused`); `UpdatesRepositoryTest` (`304_makes_no_db_write_and_returns_NoChange`, `new_items_upserted_and_cursor_advanced_in_one_transaction` (inject a failure after upsert, cursor must not move), `410_resets_baseline_without_returning_new_items`, `401_sets_needsRepair_and_returns_Unauthorized`, `io_error_returns_Offline`, `duplicate_ids_are_upserted_once`).
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** with OkHttp timeouts 10 s connect and 15 s read, `kotlinx.serialization`, Room with a single transaction around upsert plus cursor and ETag update.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): API client, cache and repository`.

### Task 8: Pairing screen and navigation shell

**Files:**
- Create: `ui/pairing/PairingScreen.kt`, `ui/pairing/PairingViewModel.kt`, `ui/App.kt`, `ui/Navigation.kt`; tests `PairingViewModelTest.kt`, `PairingScreenTest.kt` (Compose)

**Interfaces:**
- Consumes: `PairingCode.parse`, `UrlPolicy.check`, `TokenStore`, `UpdatesRepository.baseline()`, `AuthState`.
- Produces: `PairingViewModel.submit(code: String)` with `StateFlow<PairingUiState>` (`Idle | Validating | Success | Error(message)`); navigation routes `pairing`, `updates`, `doubles`, `jobs`, `settings`; the app starts on `pairing` when no token is stored or `needsRepair` is true.

- [ ] **Step 1: Write failing tests:** `valid_code_validates_stores_token_and_sets_baseline`, `invalid_code_shows_error_and_stores_nothing`, `rejected_url_shows_reason`, `server_401_during_validation_shows_error_and_clears_token`, Compose `pairing_screen_shows_paste_field_and_error`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** paste-field pairing. Add QR scanning only if a CameraX/ML Kit dependency passes the dependency policy; otherwise leave paste only and note it in the report. The "server has auth disabled" case (server returns `409`/`404` on `/api/v1/changes/latest`) shows the explicit refusal from spec §7.6.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): pairing screen and navigation`.

### Task 9: Background check, scheduler and notifications

**Files:**
- Create: `work/CheckWorker.kt`, `work/Scheduler.kt`, `notify/Notifier.kt`, `notify/LocalNotifier.kt`, `notify/NotificationText.kt`; tests `CheckWorkerTest.kt`, `SchedulerTest.kt`, `NotificationTextTest.kt`, `LocalNotifierTest.kt`

**Interfaces:**
- Consumes: `UpdatesRepository.checkForChanges()`, `CheckResult`, `AuthState`.
- Produces:
  - `interface Notifier { fun notifyUpdates(items: List<UpdateEntity>); fun notifyRepairNeeded() }`
  - `object NotificationText { fun summary(items: List<UpdateEntity>): Summary }` where `Summary(publicTitle: String, publicText: String, privateTitle: String, privateText: String, allSensitive: Boolean)`; truncation to 100 chars per title, at most 5 titles listed, HTML-like text rendered literally.
  - `class Scheduler(workManager: WorkManager) { fun apply(settings: CheckSettings); fun cancel() }`, `data class CheckSettings(intervalMinutes: Int?, wifiOnly: Boolean, quietNightStretch: Boolean)`; `intervalMinutes == null` means off; values are clamped to 15..360. Uses a unique periodic work name with `ExistingPeriodicWorkPolicy.UPDATE`, `setConstraints(NetworkType.CONNECTED or UNMETERED, requiresBatteryNotLow = true)`, flex about 25%, exponential backoff.
  - `CheckWorker.doWork()`: `NoChange` returns `success`, `NewItems` returns `success` after one `notifyUpdates`, `Offline` returns `retry`, `Unauthorized` calls `notifyRepairNeeded`, cancels the periodic work and returns `success`.

- [ ] **Step 1: Write failing tests** (use `TestListenableWorkerBuilder`, a fake `Notifier`, a fake repository):
  - `NoChange_posts_nothing`, `new_items_post_exactly_one_notification`, `offline_returns_retry`, `unauthorized_cancels_periodic_work_and_posts_repair_once`
  - Review Focus 2: `backlog_of_500_items_posts_one_notification_and_cursor_ends_at_last_id` (fake API with 10 pages)
  - Review Focus 3: `title_with_emoji_quotes_html_and_300_chars_is_truncated_safely`, `empty_chapters_list_does_not_crash`
  - Review Focus 5: `notification_permission_denied_still_caches_items` (`LocalNotifierTest` with `NotificationManagerCompat.areNotificationsEnabled() == false`, no exception)
  - Sensitive: `sensitive_items_use_private_visibility_and_generic_public_text_without_title_or_cover`, `mixed_group_with_any_sensitive_item_hides_all_titles_in_public_version`
  - `SchedulerTest`: `interval_below_15_is_clamped_to_15`, `interval_above_360_is_clamped`, `null_interval_cancels_work`, `wifi_only_uses_unmetered`, `exactly_one_unique_work_exists_after_two_applies`, `battery_not_low_constraint_present`
  - Review Focus 4: `worker_after_401_does_not_reschedule_itself`
  - Quiet hours (`CheckSettings` gains `quietHours: LocalTimeRange?`, read by the worker through `SettingsStore`): `quiet_hours_cache_items_but_post_no_notification`, `items_cached_during_quiet_hours_are_not_notified_later_as_new`, `night_stretch_applies_2h_interval_only_inside_quiet_hours`
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** the notification channels (`updates`, `updates_sensitive`, `system`), group summary, immutable `PendingIntent` to the feed. The worker never starts a foreground service.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): periodic check worker and grouped notifications`.

### Task 10: Updates feed screen

**Files:**
- Create: `ui/updates/UpdatesScreen.kt`, `ui/updates/UpdatesViewModel.kt`, `ui/common/CoverImage.kt`; tests `UpdatesViewModelTest.kt`, `UpdatesScreenTest.kt`

**Interfaces:**
- Consumes: `UpdatesRepository.feed`, `MangaApi.updates`, Coil with the `AuthInterceptor` client.
- Produces: `UpdatesViewModel` exposing `StateFlow<UpdatesUiState>` (`items`, `query`, `kind: All|New|Updated`, `category: String?`, `refreshing`, `error`), `refresh()`, `setQuery`, `setKind`, `setCategory`.

- [ ] **Step 1: Write failing tests:** `shows_cached_items_before_network_returns`, `refresh_called_once_on_open_not_on_a_timer`, `pull_to_refresh_forces_refresh`, `filters_apply_to_list`, `sensitive_items_blur_cover`, `tapping_item_opens_kavita_url_intent`, `401_navigates_to_pairing`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** the Compose feed with Coil memory and disk caches (disk cap 50 MB), covers requested only while composed.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): updates feed`.

### Task 11: Doubles and Jobs/Health screens

**Files:**
- Create: `ui/doubles/DoublesScreen.kt`, `ui/doubles/DoublesViewModel.kt`, `ui/jobs/JobsScreen.kt`, `ui/jobs/JobsViewModel.kt`; tests for both view models

**Interfaces:**
- Consumes: `MangaApi` Doubles calls (`/api/double-chapters/series`, `/series/<id>`, `/series/<id>/chapters/<id>/remediate`, `/trash`, `/trash/<id>/restore`), `status`, `run`, `health`.
- Produces: `DoublesViewModel.remediate(seriesId, chapterId, keep: …)` only after `confirm()`; `JobsViewModel.runNow()` mapping `202` to started and `409` to "already running"; `403` to "manual run disabled".

- [ ] **Step 1: Write failing tests:** `remediate_requires_explicit_confirmation_step`, `remediate_failure_keeps_list_unchanged_and_shows_error`, `trash_restore_refreshes_list`, `run_now_202_409_403_map_to_distinct_messages`, `health_failure_shows_unreachable_not_crash`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** the two screens with confirm dialogs for destructive actions.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): doubles and jobs screens`.

### Task 12: Settings screen

**Files:**
- Create: `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `data/SettingsStore.kt`; tests `SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `Scheduler.apply`, `UrlPolicy`, `TokenStore`.
- Produces: `SettingsStore` (DataStore) holding `CheckSettings`, quiet hours and last successful check time; `SettingsViewModel.setInterval(minutes: Int?)`, `setWifiOnly`, `setQuietHours`, `unpair()`.

- [ ] **Step 1: Write failing tests:** `changing_interval_reschedules_via_scheduler`, `off_cancels_work`, `unpair_clears_token_cursor_cache_and_cancels_work`, `unencrypted_private_http_shows_persistent_notice`, `last_successful_check_is_shown`, `notification_permission_denied_shows_help_banner_without_repeating_prompt`.
- [ ] **Step 2: Run.** Expected: FAIL.
- [ ] **Step 3: Implement** the screen (server URL change goes back through `UrlPolicy.check`).
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): settings screen`.

### Task 13: Release pipeline, battery verification, docs

**Files:**
- Create: `android/BATTERY.md`, `.github/workflows/android-release.yml`
- Modify: `README.md`, `.github/workflows/android.yml`

- [ ] **Step 1: Write the failing check:** add a CI step that fails if `./gradlew assembleRelease` output is debuggable or contains `android.util.Log` calls in the shrunken mapping (`apkanalyzer` or a grep of the R8 mapping), and a dependency vulnerability scan step (for example OWASP dependency-check or OSV-Scanner).
- [ ] **Step 2: Run** the workflow on the branch. Expected: FAIL until release config exists.
- [ ] **Step 3: Implement** the release workflow: triggered by a `android-v*` tag, signs with keystore secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; never echoed), attaches `app-release.apk` and its `SHA-256` file to the GitHub Release. Write `android/BATTERY.md` with the exact adb commands: `adb shell dumpsys batterystats --reset`, 24 h idle, `adb shell dumpsys batterystats com.mbols2510.mangamanager`, `adb shell dumpsys jobscheduler | grep -A5 mangamanager` (expect exactly one job), `adb shell dumpsys power | grep -i wake` (expect no wake locks held by the app), plus a table to record results. Add the README "Android app" section (install APK, create device, pair, interval advice).
- [ ] **Step 4: Run** the full pipeline on the branch. Expected: PASS. Then run the manual end-to-end from spec §8 on a real phone and fill in `BATTERY.md`. If no device is available in this session, say so in the report and leave the table empty.
- [ ] **Step 5: Commit** `feat(android): release pipeline, battery guide and docs`.
