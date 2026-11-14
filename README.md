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

**3.2.2:** fixes the bulk preview's host/container path conflict, uses the existing
chapter-card design with editable choices, and reduces cover-loading queues on
both tabs. See [release notes](docs/releases/3.2.2.md). The 3.2.1 release restored
the 3.1.0 baseline, retired the Android app and introduced the PWA.

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

## Official library aliases

Manga in the Suwayomi category named by `suwayomi.official_library` (default `ADULT_OFFICIAL`, env
`SUWAYOMI_OFFICIAL_LIBRARY`, empty turns it off) are added to **Aliases** automatically on every run, as official
sources. Their Kavita source is left **empty** and shown as *pending*: set the Kavita series by hand under **Aliases**.
While it is empty, Manga Manager does not move that manga. Aliases that already exist are never changed, so nothing is
added twice. Deleting a pending alias adds it again on the next run while the manga stays in that category.

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
## Install the web app (PWA)

Open your Manga Manager address over HTTPS (for example,
`https://manga-manager.tech-bol.be`). On Chrome/Edge or Android, use **Install app**
when offered, or the browser menu's **Install app / Add to home screen**.
On iPhone/iPad, open it in Safari and choose **Share → Add to Home Screen**.
Launch the Manga Manager icon and sign in with your usual web account.

The installed app uses the same dashboard, tools and login as the website.
It includes a friendly offline screen; viewing library data and processing files
requires a connection. Only public style sheets, app icons, the install script
and the offline screen are cached. Account pages, manga covers and API responses
are never saved by the service worker, and offline writes are never queued.
The worker refreshes automatically when a new version is deployed.

HTTPS is required on real devices; a plain HTTP LAN IP does not support service
workers or normal PWA installation. Localhost works for development.
Keep your existing reverse-proxy and login configuration. PWA support does not
require an Android APK, device token, or new server component.

## Process all double chapters

In **Doubles**, click **Process all manga…**. This previews every manga in the
latest duplicate scan, including manga outside the currently open series panel.
Expand each manga to inspect the chapter choices, files to keep, and files to
move to Trash, with chapter/file totals and the current trash-retention period.
Opening or closing a preview does not move any files.

The default is **official, otherwise newest**. When several official files exist,
the newest official is kept. File size is not a higher priority than official
status or modification time. These are the same default choices used in the
individual chapter window; this action does not use a manually selected
"Largest" strategy from another window. In the bulk preview itself you can use
**Official, else newest / Newest / Largest** for all chapters or select individual
file cards. Apply keeps exactly those reviewed choices.

Choose **Apply choices** after reviewing. The server processes the reviewed plan
in the background and shows progress, failures and Kavita-refresh warnings. You
can close or reload the page and reconnect to the running operation. Kavita gets
one rescan per affected manga and the existing read-status reconciliation runs.
Files remain restorable through **Trash** until your configured retention period
expires or you delete them permanently.

Previews expire after 15 minutes. A changed scan, file, or folder setting requires
a fresh preview. Chapters with unavailable, unsafe or shared paths are skipped
for manual review. Scans and other Double Chapters writes are blocked while the
batch is running; it also reserves the existing Move Manga file-operation lock.
An interrupted server process does not resume the batch: already moved files stay
recorded in Trash, and you should rescan before making a new preview.

### Double Chapters folder settings

`MANGA_ROOT` in `.env` names the **host** folder that Compose mounts at `/manga`.
The tool uses **`DC_MANGA_ROOT=/manga` inside the container**, with a Settings
override for custom mounts. `KAVITA_MANGA_ROOT` is the corresponding path inside
Kavita when its mount differs. A host path must not be used as the tool's
container path.

3.2.2 also recognizes a legacy saved container setting that exactly equals the
old `MANGA_ROOT` host variable, when that host path is unavailable in the container
and the normal `/manga` directory exists. Existing valid custom paths are kept.
If the preview still reports an outside-path error, check **Settings → Double
chapters → Manga folder in this container** and the Kavita folder mapping.

Cover downloads on Updates and Doubles are limited to two at a time near the
viewport. Doubles covers are cached in `/data/double-chapters/covers` for one hour
and can still be served from that cache if Kavita is temporarily unavailable.
The service worker continues to exclude covers and account data from its cache.
