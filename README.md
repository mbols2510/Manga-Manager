# Manga Manager

One self-hosted dashboard for a Suwayomi → Kavita manga setup. It replaces four separate apps:

| Tab | What it does |
|---|---|
| **Updates** | Moves finished Suwayomi downloads into the right Kavita library, tells Kavita to scan, syncs covers and sends an ntfy message per manga. Shows everything that was added. |
| **Doubles** | Finds chapters that exist as more than one file in Kavita, lets you keep one and trash the rest, and sends a **weekly** ntfy digest. |
| **Collections** | Adds series to Kavita collections automatically from rules on genres, tags or names. |
| **CBZ** | Upload `.cbz` files, edit `ComicInfo.xml` (single or bulk), download the result. |
| **Settings** | Kavita, Suwayomi and ntfy are entered once for all tabs. Connection tests, test notification, job status. |

One container, one login, one PostgreSQL database.

## Install

You need Docker with Compose and a PostgreSQL server (or use the bundled one).

```bash
mkdir manga-manager && cd manga-manager
curl -O https://raw.githubusercontent.com/mbols2510/Manga-Manager/main/compose.yaml
curl -o .env https://raw.githubusercontent.com/mbols2510/Manga-Manager/main/.env.example
$EDITOR .env          # database, folders, Kavita / Suwayomi / ntfy
docker compose up -d                      # with your own PostgreSQL (DATABASE_URL)
# docker compose --profile db up -d       # or with the bundled PostgreSQL
```

Open `http://<host>:8080`. The first account you sign up becomes the login for everything.
Then go to **Settings** to check the connections.

With your own PostgreSQL create the database first:

```sql
CREATE DATABASE manga_manager;
```

Tables are created automatically on start.

### Folders

| Container path | Purpose |
|---|---|
| `/config` | `config.yml` (Suwayomi category → Kavita library mapping), cover frames. Created from `config.example.yml` on first start. |
| `/data` | thumbnail cache, logs, CBZ scratch space |
| `/suwayomi/downloads` | Suwayomi's download folder |
| `/manga` | the Kavita library folders |

`SUWAYOMI_DOWNLOADS` and `MANGA_ROOT` in `.env` set the host side.

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
