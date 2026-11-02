"""Collections tab: files Kavita series into collections by genre, tag or series name.

Port of Kavita_Collection_Management. Rules, options and run history live in Postgres
(kv namespace "col"); the Kavita URL and key come from the shared Settings.
"""
from __future__ import annotations

import json
import logging
import re
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

import requests
from flask import Blueprint, jsonify, render_template, request, session

from .. import __version__, notify
from ..settings import Setting, register

log = logging.getLogger(__name__)

NS = "col"
MAX_HISTORY = 50
PLUGIN_NAME = "MangaManagerCollections"

register(
    Setting("col.enabled", "Run on schedule", "Collections", "bool", "COL_ENABLED", True),
    Setting("col.interval_hours", "Run every (hours)", "Collections", "int", "COL_INTERVAL_HOURS", 24,
            hint="How often rules are applied to your series"),
    Setting("col.library_ids", "Libraries to scan", "Collections", "text", "COL_LIBRARY_IDS", "5,6",
            hint="Kavita library ids, comma separated; empty = all libraries"),
    Setting("col.first_match_only", "Only first matching rule per series", "Collections", "bool",
            "COL_FIRST_MATCH_ONLY", False),
    Setting("col.topic", "Topic: collections", "Collections", "text", "COL_NTFY_TOPIC", "kavita-collections",
            hint="ntfy topic for 'series added to collections' messages"),
)


class KavitaError(Exception):
    pass


# ----------------------------------------------------------------------------- kavita
class Kavita:
    """Thin client for the Kavita REST API."""

    def __init__(self, base_url, api_key, plugin_name=PLUGIN_NAME, timeout=30):
        self.base = (base_url or "").rstrip("/")
        self.api_key = api_key
        self.plugin_name = plugin_name
        self.timeout = timeout
        self.s = requests.Session()
        self.token = None

    def _req(self, method, path, **kw):
        headers = kw.pop("headers", {})
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        try:
            r = self.s.request(method, self.base + path, headers=headers, timeout=self.timeout, **kw)
        except requests.RequestException as e:
            raise KavitaError(f"Cannot reach Kavita at {self.base}: {e}") from e
        if r.status_code >= 400:
            raise KavitaError(f"{method} {path.split('?')[0]} -> HTTP {r.status_code}: {r.text[:200]}")
        return r

    def authenticate(self):
        if not self.base:
            raise KavitaError("No Kavita URL configured (Settings → Kavita)")
        if not self.api_key:
            raise KavitaError("No Kavita API key configured (Settings → Kavita)")
        r = self._req("POST", "/api/Plugin/authenticate",
                      params={"apiKey": self.api_key, "pluginName": self.plugin_name})
        self.token = r.json().get("token")
        if not self.token:
            raise KavitaError("Authentication succeeded but no token was returned")
        return self.token

    def libraries(self):
        return self._req("GET", "/api/Library/libraries").json()

    def collections(self):
        return self._req("GET", "/api/Collection",
                         params={"ownedOnly": "false", "sortByLastModified": "false"}).json()

    def all_series(self):
        body = {"combination": 0, "sortOptions": {"sortField": 1}, "limitTo": 0}
        return self._req("POST", "/api/Series/all-v2?PageSize=0&context=1", json=body).json()

    def series_metadata(self, series_id):
        return self._req("GET", "/api/Series/metadata", params={"seriesId": series_id}).json()

    def genres(self):
        return self._req("GET", "/api/Metadata/genres").json()

    def tags(self):
        return self._req("GET", "/api/Metadata/tags").json()

    def collection_series_ids(self, collection_id):
        """IDs already in a collection, or None if that can't be determined."""
        try:
            data = self._req("GET", "/api/Series/series-by-collection",
                             params={"collectionId": collection_id, "PageNumber": 1, "PageSize": 0}).json()
            if isinstance(data, list):
                return {int(s["id"]) for s in data if "id" in s}
        except (KavitaError, ValueError, KeyError) as e:
            log.debug("series-by-collection unavailable: %s", e)
        try:  # OPDS feed, what the original n8n workflow used
            ids, page = set(), 1
            while page < 500:
                r = self._req("GET", f"/api/Opds/{self.api_key}/collections/{collection_id}",
                              params={"pageNumber": page})
                new = {int(x) for x in re.findall(r"<id>(\d+)</id>", r.text)} - ids
                if not new:
                    break
                ids |= new
                page += 1
            return ids
        except KavitaError as e:
            log.debug("OPDS lookup failed: %s", e)
        return None

    def add_to_collection(self, collection_id, series_ids):
        self._req("POST", "/api/Collection/update-for-series",
                  json={"collectionTagId": int(collection_id), "seriesIds": [int(i) for i in series_ids]})


# ----------------------------------------------------------------------------- rules
def _rule(name, keywords, collection_id):
    return {"id": uuid.uuid4().hex[:8], "name": name, "keywords": keywords, "fields": ["genres", "tags"],
            "match": "contains", "collection_id": collection_id, "enabled": True}


def default_rules() -> list[dict]:
    """Defaults from the original n8n 'Kavita Auto Collection Add' workflow."""
    return [_rule("Ranma", ["ranma"], 6), _rule("Inuyasha", ["inuyasha"], 2),
            _rule("My Hero Academia", ["my hero"], 5),
            _rule("How To Train Your Dragon", ["how to train your dragon"], 3),
            _rule("Star Wars", ["star wars"], 4)]


def _titles(items):
    return [str(i.get("title") or i.get("name") or "") for i in (items or []) if isinstance(i, dict)]


def rule_matches(rule, series):
    """series = {'name', 'genres': [...], 'tags': [...]}. Case-insensitive, like the n8n Switch."""
    haystack = []
    for field in rule.get("fields") or ["genres"]:
        val = series.get(field)
        haystack += val if isinstance(val, list) else [val or ""]
    haystack = [h.lower().strip() for h in haystack if h]
    for kw in rule.get("keywords") or []:
        kw = kw.lower().strip()
        if not kw:
            continue
        if rule.get("match") == "exact":
            if kw in haystack:
                return True
        elif any(kw in h for h in haystack):
            return True
    return False


def parse_ids(text) -> set[int]:
    return {int(x) for x in re.findall(r"\d+", str(text or ""))}


# ----------------------------------------------------------------------------- notification
def build_notification(results: list[dict], dashboard_url: str = "") -> dict | None:
    """What to publish after a run: kwargs for ctx.ntfy_publish (title, message, tags, ...), or None.

    Pure function. Series are grouped per collection; the message stays under ntfy's size limit.
    """
    groups = [r for r in results if r.get("added")]
    if not groups:
        return None
    total = sum(len(r["added"]) for r in groups)
    lines: list[str] = []
    for r in groups:
        label = r.get("collection_title") or r["rule"]
        lines.append(f"**{label}** · {len(r['added'])}")
        lines.extend(f"- {s['name']}" for s in r["added"])
        lines.append("")
    if lines and lines[-1] == "":
        lines.pop()
    head = f"{len(groups)} collection{'s' if len(groups) != 1 else ''} updated\n\n"
    out = {"title": f"🗂️ {total} series added to collections",
           "message": notify.truncate_lines(head, lines, "…and {n} more lines"),
           "tags": ["card_file_box"], "priority": 3}
    if dashboard_url:
        out["click"] = f"{dashboard_url}/collections"
        out["actions"] = [{"action": "view", "label": "Open dashboard", "url": f"{dashboard_url}/collections"}]
    return out


# ----------------------------------------------------------------------------- engine
class Engine:
    def __init__(self, ctx):
        self.ctx = ctx
        self.lock = threading.Lock()          # one run at a time
        self.data_lock = threading.RLock()    # read-modify-write of kv documents
        self.state = {"running": False, "progress": "", "started": None}

    # -- storage
    def rules(self) -> list[dict]:
        with self.data_lock:
            rules = self.ctx.db.kv_get(NS, "rules")
            if rules is None:
                rules = default_rules()
                self.ctx.db.kv_set(NS, "rules", rules)
            return rules

    def save_rules(self, rules: list[dict]) -> None:
        with self.data_lock:
            self.ctx.db.kv_set(NS, "rules", rules)

    def history(self) -> list[dict]:
        return self.ctx.db.kv_get(NS, "history", []) or []

    def add_history(self, entry: dict) -> None:
        with self.data_lock:
            hist = self.history()
            hist.insert(0, entry)
            self.ctx.db.kv_set(NS, "history", hist[:MAX_HISTORY])

    # -- kavita
    def client(self) -> Kavita:
        cfg = self.ctx.cfg
        return Kavita(cfg.kavita_url, cfg.kavita_api_key)

    def connected_client(self) -> Kavita:
        k = self.client()
        k.authenticate()
        return k

    # -- run
    def run(self, dry_run=False, trigger="manual", only_rule=None) -> dict:
        if not self.lock.acquire(blocking=False):
            return {"error": "A run is already in progress"}
        started = time.time()
        self.state.update(running=True, progress="Authenticating", started=started)
        entry = {"started": datetime.now(timezone.utc).isoformat(), "trigger": trigger, "dry_run": dry_run,
                 "status": "ok", "series_scanned": 0, "results": [], "error": None}
        try:
            settings = self.ctx.settings
            rules = [r for r in self.rules() if r.get("enabled")]
            if only_rule:
                rules = [r for r in self.rules() if r["id"] == only_rule]
            if not rules:
                raise KavitaError("No enabled rules")
            k = self.connected_client()
            self.state["progress"] = "Fetching series"
            series = k.all_series()
            libs = parse_ids(settings.get("col.library_ids"))
            if libs:
                series = [s for s in series if int(s.get("libraryId", -1)) in libs]
            entry["series_scanned"] = len(series)
            self.state["progress"] = f"Reading metadata for {len(series)} series"

            def fetch(s):
                try:
                    md = k.series_metadata(s["id"])
                except KavitaError as e:
                    log.warning("metadata for %s failed: %s", s.get("id"), e)
                    md = {}
                return {"id": int(s["id"]), "name": s.get("name") or s.get("localizedName") or f"#{s['id']}",
                        "genres": _titles(md.get("genres")), "tags": _titles(md.get("tags"))}

            with ThreadPoolExecutor(max_workers=6) as pool:
                enriched = list(pool.map(fetch, series))

            first_only = bool(settings.get("col.first_match_only"))
            claimed, per_rule = set(), []
            for rule in rules:
                matched = [s for s in enriched
                           if not (first_only and s["id"] in claimed) and rule_matches(rule, s)]
                claimed.update(s["id"] for s in matched)
                per_rule.append((rule, matched))

            try:
                titles = {int(c["id"]): c.get("title") for c in k.collections()}
            except (KavitaError, KeyError, TypeError):
                titles = {}

            for rule, matched in per_rule:
                cid = rule["collection_id"]
                self.state["progress"] = f"Updating collection for {rule['name']}"
                res = {"rule": rule["name"], "collection_id": cid, "collection_title": titles.get(int(cid)),
                       "matched": len(matched), "added": [], "error": None}
                try:
                    existing = k.collection_series_ids(cid)
                    new = [s for s in matched if existing is None or s["id"] not in existing]
                    if new and not dry_run:
                        k.add_to_collection(cid, [s["id"] for s in new])
                    res["added"] = [{"id": s["id"], "name": s["name"]} for s in new]
                    res["already"] = len(matched) - len(new)
                except KavitaError as e:
                    res["error"] = str(e)
                    entry["status"] = "partial"
                entry["results"].append(res)
        except Exception as e:  # noqa: BLE001 - surface every failure on the page
            log.exception("collections sync failed")
            entry["status"] = "error"
            entry["error"] = str(e)
        else:
            if not dry_run:
                self._notify(entry["results"])
        finally:
            entry["duration_s"] = round(time.time() - started, 1)
            self.add_history(entry)
            added = sum(len(r["added"]) for r in entry["results"])
            if not dry_run and (added or entry["status"] != "ok"):
                self.ctx.db.log_activity(
                    "collections", "run" if entry["status"] == "ok" else "error",
                    f"{added} series added to collections" if entry["status"] != "error" else f"Run failed: {entry['error']}",
                    {"trigger": trigger, "added": added})
            self.state.update(running=False, progress="", started=None)
            self.lock.release()
        return entry

    def _notify(self, results) -> None:
        msg = build_notification(results, self.ctx.dashboard_url())
        if not msg:
            return
        try:
            self.ctx.ntfy_publish(self.ctx.settings.get("col.topic"), **msg)
        except notify.NtfyError as e:
            log.warning("Collections notification not sent: %s", e)


# ----------------------------------------------------------------------------- legacy import
def import_legacy(ctx, directory) -> str:
    """Import the old data/config.json (rules + options) and data/history.json into Postgres."""
    d = Path(directory)
    cfg_file, hist_file = d / "config.json", d / "history.json"
    if not cfg_file.exists() and not hist_file.exists():
        return f"collections: no config.json or history.json in {d}"
    parts = []
    engine = Engine(ctx)
    if cfg_file.exists():
        data = json.loads(cfg_file.read_text(encoding="utf-8"))
        rules = data.get("rules") or []
        engine.save_rules(rules)
        s = data.get("settings") or {}
        ctx.settings.set_many({
            "col.enabled": s.get("schedule_enabled", True),
            "col.library_ids": ",".join(str(i) for i in s.get("library_ids") or []),
            "col.first_match_only": bool(s.get("first_match_only")),
        })
        # Kavita URL/key are shared settings: only seed them when nothing is configured yet.
        seed = {}
        if s.get("kavita_url") and not ctx.cfg.kavita_url:
            seed["kavita.url"] = s["kavita_url"]
        if s.get("api_key") and not ctx.cfg.kavita_api_key:
            seed["kavita.api_key"] = s["api_key"]
        if seed:
            ctx.settings.set_many(seed)
        parts.append(f"{len(rules)} rule(s)")
    if hist_file.exists():
        hist = json.loads(hist_file.read_text(encoding="utf-8"))
        ctx.db.kv_set(NS, "history", hist[:MAX_HISTORY])
        parts.append(f"{min(len(hist), MAX_HISTORY)} history entr(ies)")
    return "collections: imported " + ", ".join(parts)


# ----------------------------------------------------------------------------- blueprint
def init(ctx) -> Blueprint:
    engine = Engine(ctx)
    bp = Blueprint("collections", __name__)

    def interval() -> float:
        return max(1, int(ctx.settings.get("col.interval_hours") or 24)) * 3600

    def scheduled():
        if ctx.settings.get("col.enabled"):
            engine.run(trigger="schedule")

    ctx.jobs.add("collections", scheduled, interval, run_on_start=False)

    def kavita_or_502():
        try:
            return engine.connected_client(), None
        except KavitaError as e:
            return None, (jsonify({"error": str(e)}), 502)

    @bp.get("/collections")
    def page():
        return render_template("collections.html", page_title="Collections", active="collections",
                               user=session.get("user") if ctx.cfg.auth_enabled else None, version=__version__)

    @bp.get("/api/collections/status")
    def status():
        job = ctx.jobs.jobs.get("collections")
        hist = engine.history()
        return jsonify({"running": engine.state["running"], "progress": engine.state["progress"],
                        "scheduled": bool(ctx.settings.get("col.enabled")),
                        "interval_hours": ctx.settings.get("col.interval_hours"),
                        "library_ids": sorted(parse_ids(ctx.settings.get("col.library_ids"))),
                        "next_run": job.status()["next_run"] if job else None,
                        "last_run": hist[0] if hist else None})

    @bp.get("/api/collections/rules")
    def rules_list():
        return jsonify(engine.rules())

    def clean_rule(data: dict):
        name = str(data.get("name") or "").strip()
        keywords = [str(k).strip() for k in data.get("keywords") or [] if str(k).strip()]
        fields = [f for f in data.get("fields") or [] if f in ("genres", "tags", "name")]
        match = data.get("match") if data.get("match") in ("contains", "exact") else "contains"
        try:
            cid = int(data.get("collection_id"))
        except (TypeError, ValueError):
            return None, "Pick a collection."
        if not name:
            return None, "Give the rule a name."
        if not keywords:
            return None, "Add at least one keyword."
        if not fields:
            return None, "Pick at least one place to look in."
        return {"name": name, "keywords": keywords, "fields": fields, "match": match, "collection_id": cid,
                "enabled": bool(data.get("enabled", True))}, None

    @bp.post("/api/collections/rules")
    def rule_add():
        rule, err = clean_rule(request.get_json(silent=True) or {})
        if err:
            return jsonify({"error": err}), 400
        with engine.data_lock:
            rule = {"id": uuid.uuid4().hex[:8], **rule}
            engine.save_rules(engine.rules() + [rule])
        return jsonify(rule)

    @bp.put("/api/collections/rules/<rule_id>")
    def rule_update(rule_id):
        rule, err = clean_rule(request.get_json(silent=True) or {})
        if err:
            return jsonify({"error": err}), 400
        with engine.data_lock:
            rules = engine.rules()
            for i, r in enumerate(rules):
                if r["id"] == rule_id:
                    rules[i] = {"id": rule_id, **rule}
                    engine.save_rules(rules)
                    return jsonify(rules[i])
        return jsonify({"error": "Rule not found"}), 404

    @bp.delete("/api/collections/rules/<rule_id>")
    def rule_delete(rule_id):
        with engine.data_lock:
            engine.save_rules([r for r in engine.rules() if r["id"] != rule_id])
        return jsonify({"ok": True})

    @bp.get("/api/collections/kavita/test")
    def kavita_test():
        k, err = kavita_or_502()
        if err:
            return err
        try:
            return jsonify({"ok": True, "libraries": len(k.libraries()), "collections": len(k.collections())})
        except KavitaError as e:
            return jsonify({"error": str(e)}), 502

    @bp.get("/api/collections/kavita/collections")
    def kavita_collections():
        k, err = kavita_or_502()
        if err:
            return err
        try:
            return jsonify([{"id": c["id"], "title": c.get("title"), "itemCount": c.get("itemCount")}
                            for c in k.collections()])
        except KavitaError as e:
            return jsonify({"error": str(e)}), 502

    @bp.get("/api/collections/kavita/metadata")
    def kavita_metadata():
        k, err = kavita_or_502()
        if err:
            return err
        out = {}
        for key, fn in (("genres", k.genres), ("tags", k.tags)):
            try:
                out[key] = sorted({x.get("title", "") for x in fn() if x.get("title")}, key=str.lower)
            except KavitaError:
                out[key] = []
        return jsonify(out)

    @bp.post("/api/collections/run")
    def run():
        body = request.get_json(silent=True) or {}
        if engine.state["running"]:
            return jsonify({"error": "A run is already in progress"}), 409
        threading.Thread(target=engine.run, daemon=True, name="collections-run",
                         kwargs={"dry_run": bool(body.get("dry_run")), "trigger": "dashboard",
                                 "only_rule": body.get("rule_id") or None}).start()
        return jsonify({"started": True}), 202

    @bp.get("/api/collections/history")
    def history():
        return jsonify(engine.history())

    return bp
