# Manga Manager API for the Android companion (v1)

This describes how a companion client authenticates and which endpoints it may call. Everything here requires
dashboard login to be enabled (`web.auth.enabled`): without it the API is open to anyone and device tokens
are refused (`409`).

## Authentication

### Creating and revoking a device

An administrator manages devices from **Settings > Devices** in the web UI, or through the API with a
logged-in administrator session cookie. Device tokens cannot manage devices.

| Method | URL | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/devices` | Body `{"name": "Pixel 8"}` (1 to 64 printable characters). Returns `201` with `id`, `token`, `pairing` and `qr_svg`. |
| `GET` | `/api/v1/devices` | Lists devices (`id`, `name`, `user`, `created_at`, `last_seen`, `revoked_at`), revoked ones included. Never returns tokens. |
| `DELETE` | `/api/v1/devices/<id>` | Revokes a device (`204`; `404` if unknown or already revoked). |

The token is shown once, in the `POST` response. The server stores only a hash, so it cannot show it again;
create a new device if it is lost. `qr_svg` is an inline SVG QR code of the `pairing` string, drawn by the
server (the token never goes to a third-party service).

### Pairing code

`pairing` is `mm1.` followed by the unpadded base64url encoding of this compact JSON:

```json
{"v":1,"url":"http://192.168.1.5:5000","token":"..."}
```

`url` is the base URL the administrator used to reach the dashboard. Treat the code and the QR like a
password: they contain the access token.

### Using the token

Send `Authorization: Bearer <token>` on every request. A bearer header always wins over a session cookie. A
device is never an administrator and can only call the endpoints listed below; anything else answers
`403 {"error": "this device cannot use this endpoint"}`. An unknown, revoked or malformed token answers
`401 {"error": "unauthorized"}` with `WWW-Authenticate: Bearer`, always with the same body.

## Endpoints a device may call

Everything not in this list is refused for device tokens, including `/health` (use `/api/health`).

| Method | URL | Endpoint name |
| --- | --- | --- |
| `GET` | `/api/updates` | `updates` |
| `GET` | `/api/status` | `status` |
| `POST` | `/api/run` | `run_now` |
| `GET` | `/thumb/<manga_id>.png` | `thumb` |
| `GET` | `/api/health` | `core.health_detail` |
| `GET` | `/api/double-chapters/series` | `double_chapters.list_series` |
| `GET` | `/api/double-chapters/series/<series_id>` | `double_chapters.series_detail` |
| `GET` | `/api/double-chapters/cover/<series_id>` | `double_chapters.cover` |
| `POST` | `/api/double-chapters/series/<series_id>/chapters/<chapter_id>/remediate` | `double_chapters.remediate` |
| `POST` | `/api/double-chapters/series/<series_id>/kavita-scan` | `double_chapters.kavita_scan` |
| `GET` | `/api/double-chapters/trash` | `double_chapters.trash` |
| `POST` | `/api/double-chapters/trash/<trash_id>/restore` | `double_chapters.trash_restore` |
| `GET` | `/api/double-chapters/status` | `double_chapters.status` |
| `GET` | `/api/v1/changes` | `v1.changes` |
| `GET` | `/api/v1/changes/latest` | `v1.latest` |

When auth is enabled, `text/html` and `application/json` responses carry `Cache-Control: no-store`. Other
responses, such as thumbnails and cover images, are not covered by that header.

`GET /api/updates` items carry the same `sensitive` flag as the change feed (below), so a client that fills its
list from the browse endpoint can hide mature thumbnails too.

## Change feed

### `GET /api/v1/changes/latest`

Returns `{"cursor": <int>}`, the id of the newest update (`0` when there are none). Use it to bootstrap.

### `GET /api/v1/changes?since=<cursor>&limit=<n>`

Updates with `id > since`, oldest first. `since` defaults to `0` and must be a non-negative integer (else `400`).
`limit` defaults to 50 and is clamped to 1..200.

```json
{
  "items": [
    {"id": 41, "title": "...", "category": "...", "is_new": true, "chapters": ["..."],
     "created_at": "...", "thumbnail": "...", "kavita_url": "...", "sensitive": false}
  ],
  "next_cursor": 41,
  "has_more": false
}
```

* `next_cursor` is the id of the last item returned, or `since` when there are none. Store it and pass it as
  the next `since`. When `has_more` is true, call again straight away.
* `sensitive` is true when the category (case-insensitive) is in `web.mature_categories` or
  `web.blur_categories`. Clients should hide those thumbnails and keep their titles out of notifications (the
  Android app blurs the covers and posts a generic notification text for them).
* **ETag and 304.** Every `200` has an `ETag` derived from `since` and the newest id. When `since` equals the
  newest id and the request carries a matching `If-None-Match`, the answer is `304` with no body.
* **410 Gone.** `{"error": "cursor too old or reset"}` when `since` is above the newest id (the database was
  reset) or below the oldest retained id minus one (retention pruning removed rows you have not seen).

### Client rules

1. **Bootstrap from `/api/v1/changes/latest`, never from `since=0`.** Retention pruning deletes old rows, so
   once the oldest id is above 1, `since=0` answers `410`. Start at the `cursor` that `/latest` returns and
   show nothing older.
2. **On `410`, re-bootstrap from `/latest`** and continue from there without notifying for older items.
3. **The cursor never skips rows.** Ids commit in order because inserts are serialized by the scheduler's run
   lock (a single call site in `pipeline.py`), so a row with a lower id never appears after a higher one.
4. **Health for devices is `GET /api/health`.** `/health` is refused for device tokens.

## Rate limits

* **Failed bearer attempts:** 8 per client IP per 15 minutes. After that every bearer request from that IP
  answers `429 {"error": "too many requests"}` with `Retry-After` until the window clears.
* **Per token:** 120 requests per minute. Over that, `429` with `Retry-After` (seconds).

The failed-attempt limit is keyed by the IP the server sees. Devices behind the same address (carrier NAT, a
shared Wi-Fi) share it, so one client with a bad token can lock out the others behind that IP for 15 minutes.
Behind a reverse proxy, every client looks like the proxy's IP unless `web.behind_proxy` is enabled; set it so
the real client address is used.
