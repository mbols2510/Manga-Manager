# Manga Manager

One self-hosted dashboard for a Suwayomi → Kavita manga setup. It replaces four separate apps:

| Tab | What it does |
|---|---|
| **Updates** | Moves finished Suwayomi downloads into the right Kavita library, tells Kavita to scan, syncs covers and sends an ntfy message (and optionally a Discord card) per manga. Shows everything that was added. |
| **Doubles** | Finds chapters that exist as more than one file in Kavita, lets you keep one and trash the rest, and sends a **weekly** ntfy digest. |
| **Collections** | Adds series to Kavita collections automatically from rules on genres, tags or names. |
| **CBZ** | Upload `.cbz` files, edit `ComicInfo.xml` (single or bulk), download the result. |
| **Settings** | Kavita, Suwayomi and ntfy are entered once for all tabs. Connection tests, test notification, job status. |

One container, one login, one PostgreSQL database.

## Install

You need Docker with Compose and a PostgreSQL server you already run. Put its address in `DATABASE_URL`.

```bash
mkdir manga-manager && cd manga-manager
curl -O https://raw.githubusercontent.com/mbols2510/Manga-Manager/main/compose.yaml
curl -o .env https://raw.githubusercontent.com/mbols2510/Manga-Manager/main/.env.example
$EDITOR .env          # database, folders, Kavita / Suwayomi / ntfy
docker compose up -d
```

Open `http://<host>:8080`. The first account you sign up becomes the login for everything.
Then go to **Settings** to check the connections.

Use an existing database (or create one first). The tables are created automatically on start.

### Folders

| Container path | Purpose |
|---|---|
| `/config` | `config.yml` (Suwayomi category → Kavita library mapping), cover frames. Created from `config.example.yml` on first start. |
| `/data` | thumbnail cache, logs, CBZ scratch space |
| `/suwayomi/downloads` | Suwayomi's download folder |
| `/manga` | the Kavita library folders |

`SUWAYOMI_DOWNLOADS` and `MANGA_ROOT` in `.env` set the host side.

## Official and uncensored versions

Suwayomi titles with `(Official)`, `(Uncensored)` or `(Official Uncensored)` in the name are recognised as preferred
sources (rank: official, uncensored, official uncensored, then normal). For titles with a completely different name, open
**Aliases**, pick the Suwayomi title and the Kavita series from the dropdowns and set the **Source type**.

When a chapter arrives from a higher-ranked source than the file Kavita already has, it replaces that file. The old file
goes to the trash (restorable on the **Doubles** tab, deleted after the retention period). A chapter from a lower-ranked
source than what Kavita has is dropped the same way. If the preferred file has far fewer pages (default under 60%) both
files are kept and the chapter shows as a duplicate. **Preview** on an alias shows what the waiting downloads would replace.
Chapters the official source doesn't have yet stay as they are. The switches are under **Settings → Preferred sources**.

## Discord cards (optional, next to ntfy)

Set `DISCORD_ENABLED=true` and `DISCORD_WEBHOOK_URL` (or use **Settings → Notifications (Discord)**) and every
update also posts a card to that channel; ntfy keeps working exactly as before.

- bar colour: gold = new series, red = Adult/Hentai update, green = regular update
- one row per status (new / replaced / duplicate) with chapters as ranges, plus the cover and a link to the dashboard
- optional custom emoji for the status badges (`emoji_new`, `emoji_replaced`, `emoji_duplicate`), otherwise 🟩 🟧 🟪

## Weekly double-chapter digest

Every Monday at 09:00 (container timezone; weekday and hour are in **Settings → Double chapters**) the
**Doubles** tab sends an ntfy message:

- title `📚 Weekly double chapters · 7 series`, or `✅ Double chapters · all clean`
- series grouped by library, chapters as ranges (`ch 12–14, 20`)
- `+3 new · −2 fixed since last week`
- priority and tags follow the situation; buttons **Open dashboard** and **Rescan**

Set `WEB_PUBLIC_URL` (or the Settings field) so the buttons know where the dashboard lives.

## Coming from the standalone apps

Move Manga, Kavita Double Chapters and Kavita Collection Management kept their data in SQLite / JSON files.
Import it once, into an empty database:

```bash
docker compose run --rm \
  -v /old/move-manga/data:/old/mm:ro -v /old/double-chapters/config:/old/dc:ro -v /old/collections/data:/old/col:ro \
  manga-manager import-legacy --move-manga /old/mm/history.db --double-chapters /old/dc --collections /old/col
```

## Android app

`android/` contains a companion app for this server (Android 8.0+, package `com.mbols2510.mangamanager`). It
shows the **Updates** feed with search and filters, **Doubles** (keep or trash a chapter, with confirmation),
**Jobs/Health** (run now, scheduler state, connection checks) and **Settings**. It also posts one grouped
notification when new chapters arrive. It talks only to your server, through [docs/api-v1.md](docs/api-v1.md),
and has no analytics or third-party SDKs.

**Battery design**
- One WorkManager periodic job (`manga_update_check`), every 30 min by default (15 min to 6 h, or off). It
  needs a network and a battery that is not low.
- The app has no foreground service, no alarms and no wake locks of its own.
- A check is one request with an ETag. When nothing changed, the server answers `304` and the app writes
  nothing and stays quiet.
- After a failure the job retries once, then waits for the next period.
- *Wi-Fi only* and *Quiet hours* (no alerts; with *Slow down at night*, at most one check every 2 h) cut use
  further.

[android/BATTERY.md](android/BATTERY.md) has the 24-hour `adb` measurement procedure and an empty results
table. That measurement has not been run yet.

### Install

1. Download `app-release.apk` and `app-release.apk.sha256` from a GitHub Release tagged `android-vX.Y.Z`.
2. Check the file with `sha256sum -c app-release.apk.sha256`. The release notes also contain the checksum.
3. Sideload it. Android asks you to allow installs from your browser or file manager.

The `app-debug-apk` artifact of CI runs is for testing only. **Never use it with a real device token**: it is
debuggable (any computer with USB debugging can read its data) and it exports test activities.

### Pairing

1. On the web dashboard (login must be enabled), open **Settings > Devices**, name the device and press
   **Create device**.
2. Copy the pairing code (it starts with `mm1.`) and paste it into the app.

The app has no QR scanner. To move the code from the QR to your phone, read the QR with any camera app and copy
the text. The code contains the access token, so treat it like a password. It is shown only once.

**Transport rules.** HTTPS works with any host. Plain HTTP is accepted only for private addresses: LAN
(RFC 1918), loopback, link-local, `*.local` and Tailscale/CGNAT `100.64.0.0/10`. The app then shows an
"unencrypted" notice. Plain HTTP to a public host is refused, and so are redirects from HTTPS to HTTP.

**Notification privacy.** On the lock screen a notification only says that there are new updates. Chapters in
mature/blurred categories never show their title or cover in a notification. In the feed their covers are
blurred.

**Unpair and revoke.** *Settings > Unpair* in the app removes the token and all cached data from the phone and
stops background checks. To cut a device off for good, revoke it under **Settings > Devices** on the dashboard.
The app then asks to be paired again.

### Building from source

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

You need JDK 17 and the Android SDK (compileSdk 37). Every push that touches `android/` runs
`.github/workflows/android.yml`. That workflow covers:
- unit tests, lint, debug and release builds, with strict Gradle dependency verification;
- release hardening and the merged-manifest allowlist;
- checks on the built release APK, from `android/tools/apk_check.py`: not debuggable, only the allowed
  permissions, no `android.util.Log` calls in app classes after R8, minSdk 26;
- an OSV-Scanner vulnerability scan of every dependency in `android/gradle/verification-metadata.xml`.

The vulnerability scan fails on HIGH or CRITICAL findings, and on findings with no severity. A suppression goes
in `android/osv-allowlist.toml`, applies to one package only, needs a reason and an expiry date, and is ignored
for any package that ships in the APK (release runtime classpath), so such a finding always fails. The current
entries cover build/test toolchain artifacts (AGP, Kotlin, Robolectric) that are not packaged in the APK. Without signing secrets the release build
is simply unsigned.

**Dependency updates.** Dependabot (`.github/dependabot.yml`) opens weekly PRs for Gradle and GitHub Actions. It
does **not** update `android/gradle/verification-metadata.xml`, so a Gradle PR fails CI until you regenerate the
checksums:
1. Run **Actions > Android > Run workflow** on the PR branch with `write_verification_metadata` ticked, or
   `gh workflow run android.yml --ref <branch> -f write_verification_metadata=true`.
2. Download the `verification-metadata` artifact. The log also prints it gzip+base64.
3. Review the diff: only the expected new artifacts should appear.
4. Commit the file to `android/gradle/` on the PR branch and push. CI then verifies strictly again.

The write run is always marked failed on purpose, so an unverified build can never be green.

### Release signing (maintainer)

Create a keystore once and keep it and its passwords outside the repository. `*.keystore` and `*.jks` are in
`.gitignore`, but never commit one anyway. If you lose the key, installed apps cannot be updated.

```bash
keytool -genkeypair -v -keystore mangamanager-release.keystore -alias mangamanager \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 mangamanager-release.keystore | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD   # prompts; nothing lands in shell history
gh secret set ANDROID_KEY_ALIAS --body mangamanager
gh secret set ANDROID_KEY_PASSWORD
```

keytool's default PKCS12 keystore uses the same password for the store and the key, so both password secrets
get that value.

Gradle signs the release only when `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD` are set in the environment. The release workflow sets them, and only on tag builds.

### Cutting a release (maintainer)

1. Set `versionName` (and raise `versionCode`) in `android/app/build.gradle.kts` and merge to `main`.
2. Tag that commit `android-v<versionName>`, for example `git tag android-v0.1.0 && git push origin android-v0.1.0`.

The tag starts `.github/workflows/android-release.yml`:
- It builds the tagged commit and signs it. The keystore is written to the runner's temp directory and deleted
  after the build.
- It checks the APK, including `apksigner verify` and the tag/versionName match.
- It creates a GitHub Release with the APK and its SHA-256. The release is not marked "latest", so server
  releases keep that spot.

Running the workflow by hand (**Run workflow**) is always a **dry run**. It builds the unsigned release, runs
the same checks and uploads the APK and checksum as a workflow artifact. It uses no secrets and publishes
nothing.

## Development

```bash
docker run -d --name pg -e POSTGRES_PASSWORD=test -e POSTGRES_DB=manga_test -p 5432:5432 postgres:16-alpine
pip install -r requirements.txt pytest
TEST_DATABASE_URL=postgresql://postgres:test@localhost:5432/manga_test pytest -q
```

More on the move pipeline: [docs/how-it-works.md](docs/how-it-works.md), [docs/covers.md](docs/covers.md).

## Credits

Combines [Move Manga](https://github.com/mbols2510/Move_Manga), Kavita Double Chapters,
Kavita Collection Management and CBZ ComicInfo Editor.
