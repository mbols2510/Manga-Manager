"""/api/v1/changes: cursor feed of dashboard updates for the Android companion.

Reachable by device tokens (v1.changes and v1.latest are in DEVICE_ALLOWED_ENDPOINTS) and by cookie sessions;
the global guard() enforces authentication.
"""
from __future__ import annotations

import hashlib
from typing import Callable

from flask import Blueprint, Response, jsonify, request

from .config import Config
from .history import History

DEFAULT_LIMIT = 50
MAX_LIMIT = 200
GONE = {"error": "cursor too old or reset"}


def _etag(since: int, max_id: int) -> str:
    return '"' + hashlib.sha256(f"{since}:{max_id}".encode()).hexdigest()[:20] + '"'


def _matches(header: str | None, etag: str) -> bool:
    if not header:
        return False
    for part in header.split(","):
        part = part.strip()
        if part == "*" or part.removeprefix("W/") == etag:
            return True
    return False


def build_blueprint(cfg: Config, history: History, decorate: Callable[[dict], dict]) -> Blueprint:
    bp = Blueprint("v1", __name__)

    def no_store(resp: Response) -> Response:
        if cfg.auth_enabled:
            resp.headers["Cache-Control"] = "no-store"
        return resp

    @bp.get("/api/v1/changes")
    def changes():
        try:
            since = int(request.args.get("since", "0"))
        except ValueError:
            return jsonify({"error": "since must be a non-negative integer"}), 400
        if since < 0:
            return jsonify({"error": "since must be a non-negative integer"}), 400
        limit = min(max(request.args.get("limit", DEFAULT_LIMIT, type=int), 1), MAX_LIMIT)

        max_id = history.max_update_id()
        min_id = history.min_update_id()
        if since > max_id or (max_id and since < min_id - 1):
            return no_store(jsonify(GONE)), 410
        etag = _etag(since, max_id)
        if since == max_id and _matches(request.headers.get("If-None-Match"), etag):
            resp = Response(status=304)
            resp.headers["ETag"] = etag
            return no_store(resp)

        rows = history.changes_since(since, limit + 1)
        has_more = len(rows) > limit
        items = []
        for row in rows[:limit]:
            decorate(row)
            items.append({
                "id": row["id"], "title": row["title"], "category": row["category"], "is_new": row["is_new"],
                "chapters": row["chapters"], "created_at": row["created_at"], "thumbnail": row["thumbnail"],
                "kavita_url": row["kavita_url"], "sensitive": row["sensitive"],
            })
        resp = jsonify({"items": items, "next_cursor": items[-1]["id"] if items else since,
                        "has_more": has_more})
        resp.headers["ETag"] = etag
        return no_store(resp)

    @bp.get("/api/v1/changes/latest")
    def latest():
        return no_store(jsonify({"cursor": history.max_update_id()}))

    return bp
