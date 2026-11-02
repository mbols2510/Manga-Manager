# How it works

## One run, step by step

```
 read dashboard aliases, delete dashboard updates older than web.keep_days
        │
        ▼
Suwayomi downloads/mangas/<source>/<manga>/*.cbz
        │  whole manga folder untouched for settle_seconds
        │  and no chapters left in Suwayomi's download queue
        ▼
 ComicInfo.xml <Series>  ──►  Suwayomi GraphQL: which manga, which category?
        │
        ▼
 category → library (config)  ──►  Kavita API: does the series exist in that library?
        │
        ▼
 move .cbz files to <library>/<series folder>
        │
        ├─ new series      → POST /api/Library/scan   (once per library)
        └─ existing series → POST /api/Series/scan
        │
        ▼
 wait for new series to appear → ntfy notification → dashboard history
        (Kavita series name; each chapter marked new, replaced or duplicate)
        │
        ▼
 every unlocked Kavita series → Suwayomi cover → upload + lock
```

## Matching

A downloaded folder is matched to a Suwayomi manga by, in order: the exact ComicInfo `<Series>`,
the exact folder name, then a loose comparison of both (case, accents, punctuation and spaces
ignored; Japanese/Korean characters kept). If two Suwayomi entries share a title, the one from the
same source folder wins.

A Suwayomi manga is matched to a Kavita series inside the target library by name, original name or
localized name (exact, then loose), then by folder name, and finally with bracketed decorations
removed (`(Official)`, `[Digital]`, `〘Official〙`, `【Mature】` …). That last step is only accepted when
it points to a single series, so it never guesses between two candidates. An alias is tried first:
one added on the dashboard, else one from `aliases` in `config.yml`.

New chapters are always moved into the folder Kavita already uses for the series, so a series is
never split across two folders.

### Renamed series

Kavita groups chapters by the `<Series>` name in each chapter's `ComicInfo.xml`, and Suwayomi writes
the manga's *current* title there. When a source renames a manga (for example drops "(Official)"),
new chapters would carry a different name than the existing Kavita series and Kavita would create a
second series. With `kavita.align_series_name: true` (default) the service rewrites `<Series>` in
the newly moved chapters to the name Kavita already uses. Only that one field changes; the archive
is rebuilt and swapped in atomically.

## Suwayomi API calls

Two GraphQL queries per run (`POST /api/graphql`, basic auth if configured). The library:

```graphql
query MoveMangaLibrary {
  mangas(condition: {inLibrary: true}) {
    nodes {
      id title thumbnailUrl
      source { id displayName }
      categories { nodes { id name } }
    }
  }
}
```

and the download queue, so a manga that still has chapters `QUEUED` or `DOWNLOADING` waits for the
next run (`ERROR` and `FINISHED` entries are ignored; if the query fails, only the folder timer is
used). Suwayomi downloads a chapter's pages to its cache and writes the `.cbz` into the manga folder
at the end, so a big chapter can leave the folder unchanged for minutes; the queue covers that gap.

```graphql
query MoveMangaDownloadQueue {
  downloadStatus { queue { state manga { id title } } }
}
```

Covers are read from Suwayomi's `thumbnails/<id>.*` files. When a file is missing it is downloaded
from `GET /api/v1/manga/<id>/thumbnail`. Every cover is converted to a real PNG and cached in
`data/thumbnails/<id>.png`.

## Kavita API calls

| Call | Used for |
|---|---|
| `POST /api/Plugin/authenticate?apiKey=…&pluginName=MoveManga` | get a JWT; renewed automatically on HTTP 401 |
| `POST /api/Series/all-v2?PageNumber=1&PageSize=0` | every series with `libraryId`, `folderPath`, `coverImageLocked` (`PageSize=0` = no paging) |
| `POST /api/Library/scan?libraryId=…&force=false` | import new series |
| `POST /api/Series/scan` `{libraryId, seriesId}` | import new chapters of an existing series |
| `POST /api/Upload/upload-by-file` (multipart `file`) | stage a cover image (Kavita ≥ 0.9.1) |
| `POST /api/Upload/series` `{id, fileName, lockCover}` | set the staged image as series cover |
| `POST /api/Upload/series` `{id, url: <base64>, lockCover}` | fallback for Kavita < 0.9.1 |

Scan requests are sent one at a time, `kavita.scan_delay` seconds apart (60 by default), because
Kavita handles several simultaneous scans badly. Library scans go first. A series scan is skipped
when its library already gets a library scan in the same run, since that scan covers it. If one
request fails, the others are still sent. With many updated series a run takes longer (10 series ≈
9 minutes); the next run is scheduled only after the current one ends, so runs never overlap.

Only libraries listed in the config are touched; other Kavita libraries (4, 5, 6 in v1) are ignored.

## Dashboard API

| Endpoint | Returns |
|---|---|
| `GET /api/updates?limit=&offset=&category=&kind=new\|updated&q=` | paged history, newest first |
| `GET /api/status` | totals, last run, scheduler state |
| `POST /api/run` | start a run now (403 when `web.allow_manual_run` is false, 409 when one is running) |
| `GET /api/aliases` | every alias (`source`: `dashboard` or `config`; `overridden` when a dashboard alias replaces a config one) and `can_edit` |
| `POST /api/aliases` | add or change an alias: `{"suwayomi_title", "kavita_name", "previous"?}` (403 for accounts not in `config.yml` when the login is on) |
| `POST /api/aliases/delete` | delete a dashboard alias: `{"suwayomi_title"}` |
| `GET /api/aliases/suggestions` | Suwayomi titles and Kavita series names for the alias form (cached 10 minutes) |
| `GET /frame/<category>/<n>` | the category's n-th frame (`/frame/<category>` is the first) |
| `GET /thumb/<suwayomi id>.png` | cached cover (`?s=<signature>` opens it without a login) |
| `GET/POST /login`, `GET/POST /signup`, `POST /logout` | dashboard login (when `web.auth.enabled`) |
| `GET /health` | container health check (never needs a login) |

Each update in `/api/updates` has `chapters`, `replaced` (a file with the same name was overwritten)
and `duplicates` (Kavita already had that chapter under another file name; both files are kept).
The other chapters are new. Updates from before 2.1.0 have an empty `replaced` list; updates from
before 2.4.0 have an empty `duplicates` list, and back then a duplicate was listed under `replaced`.

With `web.auth.enabled`, every endpoint except `/health`, `/login` and `/signup` returns 401 (API) or
redirects to the login page. POST requests from another site are refused.

All links in the page are relative, so it also works behind a reverse proxy on a sub-path.

## Files in `data/`

| Path | What |
|---|---|
| `history.db` | SQLite: `updates` (dashboard, `web.keep_days` kept), `runs` (last 500 runs), `cover_uploads`, `users` (sign-up accounts), `aliases` (added on the dashboard) |
| `secret_key` | signs logins and cover links (only when `web.auth.secret_key` / `WEB_SECRET_KEY` is empty) |
| `thumbnails/` | PNG cover cache |
| `logs/move-manga.log` | log file, rotated at midnight, 14 days kept |

Deleting `data/` is safe: you lose the dashboard history, the aliases added on the dashboard and the
accounts made on the sign-up page, and everyone has to log in again.
