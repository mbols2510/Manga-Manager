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

A companion Android app lives in `android/` and is under development; it is not built or released yet. It will
show the same updates feed on a phone and notify on new chapters.

To prepare a device, sign in as an administrator, open **Settings > Devices**, name the device and press
**Create device**. The pairing code and QR appear once: scan or paste them into the app to pair it, and revoke
the device from the same card whenever you like. Device tokens need dashboard login to be enabled. The API the
app uses is documented in [docs/api-v1.md](docs/api-v1.md).

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
