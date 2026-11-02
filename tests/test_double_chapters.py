import json
from datetime import datetime, timedelta, timezone

import pytest
from flask import Flask

from manga_manager import notify
from manga_manager.tools import double_chapters as dc


def chapter(cid, num, files=2):
    return {"id": cid, "sortOrder": num, "number": str(num), "range": str(num), "title": "", "files": [
        {"id": cid * 10 + i, "filePath": f"/manga/Regular/S/ch{num}-{i}.cbz", "bytes": 100 + i, "pages": 20} for i in range(files)]}


def series(sid, name, nums, lib=1):
    return {"id": sid, "name": name, "libraryId": lib, "chapters": [chapter(sid * 100 + int(n * 10), n) for n in nums]}


# ------------------------------------------------------------------ pure formatting
def test_compress_ranges():
    cs = lambda *n: [{"sortOrder": x} for x in n]
    assert dc.compress_chapters(cs(12, 13, 14, 20)) == "ch 12–14, 20"
    assert dc.compress_chapters(cs(5)) == "ch 5"
    assert dc.compress_chapters(cs(3, 1, 2, 2)) == "ch 1–3"
    assert dc.compress_chapters(cs(1, 2, 2.5, 3)) == "ch 1–2, 2.5, 3"      # halves never join a range
    assert dc.compress_chapters([{"sortOrder": "x", "title": "Extra"}]) == "Extra"


def test_digest_with_series_and_delta():
    current = [series(1, "Berserk", [12, 13, 14, 20]), series(2, "Akira", [1])]
    previous = [f"2:{100 * 2 + 10}", "9:999"]            # Akira ch1 was already there, plus one that got fixed
    previous = [f"2:{2 * 100 + 10}", "9:999"]
    d = dc.build_digest(current, previous, "https://m.example", {1: "Regular"})
    assert d["title"] == "📚 Weekly double chapters · 2 series"
    assert "ch 12–14, 20" in d["message"]
    assert "+4 new · −1 fixed since last week" in d["message"]
    assert d["message"].index("Akira") < d["message"].index("Berserk")
    assert "**Regular** (2)" in d["message"]
    assert d["tags"] == ["books"] and d["priority"] == 3
    assert [a["label"] for a in d["actions"]] == ["Open dashboard", "Rescan"]
    assert d["actions"][0]["url"] == "https://m.example/double-chapters"
    assert d["actions"][1] == {"action": "http", "label": "Rescan", "url": "https://m.example/api/double-chapters/scan",
                               "method": "POST", "clear": True}


def test_digest_groups_by_library_name():
    d = dc.build_digest([series(1, "A", [1], lib=1), series(2, "B", [1], lib=2)], None, "", {1: "Regular", 2: "Adult"})
    msg = d["message"]
    assert msg.index("**Adult**") < msg.index("**Regular**")
    assert d["actions"] == [] and "since last week" not in msg


def test_digest_empty_and_priority():
    d = dc.build_digest([], None, "")
    assert d["title"] == "✅ Double chapters · all clean"
    assert d["tags"] == ["white_check_mark"] and d["priority"] == 2
    d = dc.build_digest([], [f"1:1", "1:2"], "")
    assert "−2 fixed since last week" in d["message"]
    many = dc.build_digest([series(i, f"S{i}", [1]) for i in range(1, 11)], None, "")
    assert many["tags"] == ["warning"] and many["priority"] == 4


def test_digest_no_change():
    s = [series(1, "A", [1])]
    assert "No change since last week" in dc.build_digest(s, dc.snapshot_keys(s), "")["message"]


def test_digest_truncates_under_limit():
    big = [series(i, f"Series number {i} " + "x" * 40, list(range(1, 60, 2))) for i in range(1, 120)]
    d = dc.build_digest(big, None, "https://m.example")
    assert len(d["message"].encode()) <= 4000
    assert "…and" in d["message"] and "more lines" in d["message"]


def test_digest_due_once_per_iso_week():
    mon_8 = datetime(2026, 11, 2, 8, 0)     # Monday
    mon_9 = datetime(2026, 11, 2, 9, 5)
    wed = datetime(2026, 11, 4, 3, 0)
    assert not dc.digest_due(mon_8, 0, 9, None)
    assert dc.digest_due(mon_9, 0, 9, None)
    assert dc.digest_due(wed, 0, 9, "2026-W44")          # missed Monday: sent when next checked
    assert not dc.digest_due(mon_9, 0, 9, dc.iso_week(mon_9))
    assert dc.digest_due(datetime(2026, 11, 9, 9, 0), 0, 9, dc.iso_week(mon_9))
    assert not dc.digest_due(wed, 4, 9, None)             # Friday digest not due on Wednesday


# ------------------------------------------------------------------ tool with fake Kavita
class FakeKavita:
    def __init__(self, root):
        self.root = root
        self.scans = []

    def get_all_series(self, library_ids=None):
        return [{"id": 1, "name": "Berserk", "libraryId": 1}, {"id": 2, "name": "Clean", "libraryId": 1}]

    def get_libraries(self):
        return {1: "Regular"}

    def get_series_volumes(self, sid):
        files = lambda n: [{"id": n * 10 + i, "filePath": f"/manga/Regular/Berserk/ch{n}-{i}.cbz", "bytes": 100 + i, "pages": 20}
                           for i in range(2 if sid == 1 else 1)]
        return [{"id": 1, "name": "-100000", "chapters": [{"id": n, "number": str(n), "sortOrder": n, "files": files(n)}
                                                          for n in (1, 2)]}]

    def scan_series(self, sid, lib):
        self.scans.append(sid)
        return True

    def get_cover(self, sid):
        return b"png", "image/png"


@pytest.fixture
def tool(ctx, tmp_path):
    root = tmp_path / "manga"
    (root / "Regular" / "Berserk").mkdir(parents=True)
    for n in (1, 2):
        for i in (0, 1):
            (root / "Regular" / "Berserk" / f"ch{n}-{i}.cbz").write_bytes(b"x" * (10 + i))
    ctx.db.set_setting("dc.manga_root", str(root))
    ctx.db.set_setting("dc.kavita_manga_root", "/manga")
    ctx.settings.reload()
    t = dc.DoubleChapters(ctx)
    t._client = FakeKavita(root)
    t._client_key = (ctx.cfg.kavita_url, ctx.cfg.kavita_api_key)
    return t


def test_scan_remediate_and_trash(tool):
    tool.run_scan()
    found = tool.load("series", {})
    assert list(found) == ["1"] and len(found["1"]["chapters"]) == 2
    assert tool.meta()["lastScan"]

    keep = found["1"]["chapters"][0]["files"][0]["id"]
    res = tool.remediate_chapter(1, found["1"]["chapters"][0]["id"], keep)
    assert len(res["moved"]) == 1
    assert len(tool.load("series", {})["1"]["chapters"]) == 1
    assert len(tool.trash_list()) == 1

    entry = tool.trash_list()[0]
    assert (tool.trash_dir.exists() and entry["expiresAt"])
    tool.restore_trash(entry["id"])
    assert tool.trash_list() == []

    with pytest.raises(ValueError):
        tool.remediate_chapter(1, 999, 1)


def test_remediate_refuses_paths_outside_root(tool, tmp_path):
    tool.run_scan()
    outside = tmp_path / "outside.cbz"
    outside.write_bytes(b"x")
    s = tool.load("series", {})
    s["1"]["chapters"][0]["files"][1]["filePath"] = str(outside)
    tool.save("series", s)
    cid = s["1"]["chapters"][0]["id"]
    res = tool.remediate_chapter(1, cid, s["1"]["chapters"][0]["files"][0]["id"])
    assert res["moved"] == [] and outside.exists()


def test_purge_expired_trash(tool):
    tool.run_scan()
    s = tool.load("series", {})["1"]["chapters"][0]
    tool.remediate_chapter(1, s["id"], s["files"][0]["id"])
    trash = tool.load("trash", {})
    for e in trash.values():
        e["deletedAt"] = (datetime.now(timezone.utc) - timedelta(days=30)).isoformat()
    tool.save("trash", trash)
    assert len(tool.purge_expired_trash()) == 1 and tool.trash_list() == []


def test_notify_tick_sends_once_per_week(tool, monkeypatch):
    sent = []
    monkeypatch.setattr(tool.ctx, "ntfy_publish", lambda topic, title, msg, **kw: sent.append((topic, title, kw)))
    tool.ctx.cfg.ntfy_enabled = True
    tool.run_scan()
    tool.ctx.db.set_setting("dc.notify_weekday", "0")
    tool.ctx.db.set_setting("dc.notify_hour", 0)
    tool.ctx.settings.reload()
    tool.notify_tick()
    tool.notify_tick()
    assert len(sent) == 1
    assert sent[0][0] == "Double_Chapters" and "Weekly double chapters" in sent[0][1]
    meta = tool.meta()
    assert meta["lastDigestWeek"] and meta["lastDigestSnapshot"]


def test_notify_tick_waits_for_scan(tool, monkeypatch):
    sent = []
    monkeypatch.setattr(tool.ctx, "ntfy_publish", lambda *a, **k: sent.append(a))
    tool.ctx.cfg.ntfy_enabled = True
    tool.ctx.db.set_setting("dc.notify_hour", 0)
    tool.ctx.settings.reload()
    tool.notify_tick()                      # no scan yet
    assert sent == []


def test_force_digest_does_not_touch_state(tool, monkeypatch):
    monkeypatch.setattr(tool.ctx, "ntfy_publish", lambda *a, **k: None)
    tool.ctx.cfg.ntfy_enabled = True
    tool.run_scan()
    assert tool.send_digest(force=True)
    assert "lastDigestWeek" not in tool.meta()


# ------------------------------------------------------------------ http layer
@pytest.fixture
def client(ctx, tool):
    app = Flask("manga_manager.web")
    app.add_url_rule("/", "index", lambda: "")
    app.add_url_rule("/logout", "logout", lambda: "", methods=["POST"])
    app.context_processor(lambda: {"has_endpoint": lambda n: n in app.view_functions})
    bp = dc.init(ctx)
    ctx.extras["double_chapters"]._client = tool._client
    ctx.extras["double_chapters"]._client_key = (ctx.cfg.kavita_url, ctx.cfg.kavita_api_key)
    app.register_blueprint(bp)
    tool.ctx.extras["double_chapters"].ctx.settings.reload()
    return app.test_client()


def test_api_flow(client, tool):
    tool.run_scan()
    assert client.get("/api/double-chapters/status").json["seriesWithDuplicates"] == 1
    assert client.get("/api/double-chapters/series").json[0]["name"] == "Berserk"
    detail = client.get("/api/double-chapters/series/1").json
    ch = detail["chapters"][0]
    r = client.post(f"/api/double-chapters/series/1/chapters/{ch['id']}/remediate", json={"keepFileId": ch["files"][0]["id"]})
    assert r.status_code == 200 and len(r.json["moved"]) == 1
    assert len(client.get("/api/double-chapters/trash").json) == 1
    assert client.post("/api/double-chapters/series/1/chapters/999/remediate", json={"keepFileId": 1}).status_code == 400
    assert client.get("/api/double-chapters/series/77").status_code == 404
    assert client.get("/api/double-chapters/cover/1").data == b"png"
    assert client.post("/api/double-chapters/trash/purge-all").json == {"purged": "all"}
    assert client.get("/api/double-chapters/trash").json == []
    assert client.get("/api/double-chapters/digest-preview").json["title"].startswith("📚")


def test_notify_test_reports_ntfy_errors(client):
    r = client.post("/api/double-chapters/notify-test")       # notifications are off in the test config
    assert r.status_code == 502 and "turned off" in r.json["error"]


def test_page_renders(client):
    r = client.get("/double-chapters")
    assert r.status_code == 200 and b"Double chapters" in r.data


def test_settings_registered():
    from manga_manager.settings import all_settings
    keys = {s.key for s in all_settings()}
    assert {"dc.topic", "dc.notify_weekday", "dc.notify_hour", "dc.trash_retention_days", "dc.library_ids"} <= keys


# ------------------------------------------------------------------ legacy import
def test_import_legacy(ctx, tmp_path):
    old = tmp_path / "old"
    (old / "trash" / "20260926T202457").mkdir(parents=True)
    (old / "trash" / "20260926T202457" / "a.cbz").write_bytes(b"abc")
    (old / "state.json").write_text(json.dumps({
        "lastScan": "2026-10-01T00:00:00+00:00", "lastNotified": "2026-10",
        "series": {"1": series(1, "Berserk", [1])},
        "trash": {"t1": {"id": "t1", "originalPath": "/manga/a.cbz", "trashPath": "/config/trash/20260926T202457/a.cbz",
                         "seriesId": 1, "seriesName": "Berserk", "size": 3, "deletedAt": "2026-09-26T20:24:57+00:00"}}}))
    msg = dc.import_legacy(ctx, old)
    assert "1 series" in msg and "1 trashed" in msg
    t = dc.DoubleChapters(ctx)
    assert t.meta()["lastScan"].startswith("2026-10-01")
    entry = t.load("trash", {})["t1"]
    assert entry["trashPath"].startswith(str(t.trash_dir)) and (t.trash_dir / "20260926T202457" / "a.cbz").exists()
    assert "no state.json" in dc.import_legacy(ctx, tmp_path / "missing")
