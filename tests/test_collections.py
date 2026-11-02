import json

import pytest
from flask import Flask

from manga_manager.tools import collections as col


def test_rule_matches():
    s = {"name": "Star Wars: Visions", "genres": ["Sci-Fi"], "tags": ["Clone Wars"]}
    assert col.rule_matches({"keywords": ["clone"], "fields": ["tags"]}, s)
    assert not col.rule_matches({"keywords": ["clone"], "fields": ["genres"]}, s)
    assert col.rule_matches({"keywords": ["star wars"], "fields": ["name"]}, s)
    assert not col.rule_matches({"keywords": ["clone"], "fields": ["tags"], "match": "exact"}, s)
    assert col.rule_matches({"keywords": ["CLONE WARS"], "fields": ["tags"], "match": "exact"}, s)


def test_parse_ids():
    assert col.parse_ids("5, 6;7") == {5, 6, 7}
    assert col.parse_ids("") == set()


def test_build_notification_groups_and_links():
    results = [
        {"rule": "Ranma", "collection_title": "Ranma ½", "added": [{"id": 1, "name": "Ranma A"}, {"id": 2, "name": "Ranma B"}]},
        {"rule": "Star Wars", "added": [{"id": 3, "name": "SW"}]},
        {"rule": "Empty", "added": []},
    ]
    msg = col.build_notification(results, "https://mm.example")
    assert msg["title"] == "🗂️ 3 series added to collections"
    assert "**Ranma ½** · 2" in msg["message"] and "- SW" in msg["message"] and "Empty" not in msg["message"]
    assert msg["actions"][0]["url"] == "https://mm.example/collections"
    assert "actions" not in col.build_notification(results)
    assert col.build_notification([{"rule": "x", "added": []}]) is None


def test_build_notification_truncates():
    added = [{"id": i, "name": "S" * 60 + str(i)} for i in range(200)]
    msg = col.build_notification([{"rule": "Big", "added": added}])
    assert len(msg["message"].encode()) <= 4096 and "more" in msg["message"]


class FakeKavita:
    added = []

    def __init__(self, *a, **k):
        pass

    def authenticate(self):
        return "t"

    def all_series(self):
        return [{"id": 1, "name": "Ranma 1/2", "libraryId": 5}, {"id": 2, "name": "Other", "libraryId": 5},
                {"id": 3, "name": "Ranma Extra", "libraryId": 9}]

    def series_metadata(self, sid):
        return {"genres": [{"title": "Comedy"}], "tags": [{"title": "Ranma"}] if sid != 2 else []}

    def collections(self):
        return [{"id": 6, "title": "Ranma Collection", "itemCount": 0}]

    def collection_series_ids(self, cid):
        return set()

    def add_to_collection(self, cid, ids):
        FakeKavita.added.append((cid, list(ids)))


@pytest.fixture
def engine(ctx, monkeypatch):
    FakeKavita.added = []
    monkeypatch.setattr(col, "Kavita", FakeKavita)
    return col.Engine(ctx)


def test_run_adds_matching_in_selected_libraries(engine, ctx):
    ctx.settings.set_many({"col.library_ids": "5"})
    entry = engine.run(trigger="test")
    assert entry["status"] == "ok" and entry["series_scanned"] == 2
    ranma = next(r for r in entry["results"] if r["rule"] == "Ranma")
    assert [s["id"] for s in ranma["added"]] == [1] and ranma["collection_title"] == "Ranma Collection"
    assert FakeKavita.added == [(6, [1])]
    assert engine.history()[0]["trigger"] == "test"
    with ctx.db.connect() as c:
        assert c.execute("SELECT count(*) AS n FROM activity WHERE tool='collections'").fetchone()["n"] == 1


def test_dry_run_changes_nothing(engine):
    entry = engine.run(dry_run=True)
    assert FakeKavita.added == [] and entry["dry_run"]
    assert engine.history()[0]["dry_run"] is True


def test_run_without_kavita_url_records_error(ctx):
    entry = col.Engine(ctx).run()
    assert entry["status"] == "error" and "Kavita URL" in entry["error"]


def test_rule_api(ctx):
    app = Flask(__name__, template_folder=str(__import__("pathlib").Path(col.__file__).parents[1] / "templates"))
    app.register_blueprint(col.init(ctx))
    c = app.test_client()
    seeded = c.get("/api/collections/rules").get_json()
    assert len(seeded) == 5
    r = c.post("/api/collections/rules", json={"name": "New", "keywords": ["a", " "], "fields": ["tags"], "collection_id": 9})
    assert r.status_code == 200 and r.get_json()["keywords"] == ["a"]
    rid = r.get_json()["id"]
    assert c.post("/api/collections/rules", json={"name": "", "keywords": ["a"], "fields": ["tags"], "collection_id": 1}).status_code == 400
    assert c.put(f"/api/collections/rules/{rid}", json={"name": "N2", "keywords": ["b"], "fields": ["name"], "collection_id": 9, "enabled": False}).get_json()["enabled"] is False
    assert c.delete(f"/api/collections/rules/{rid}").status_code == 200
    assert len(c.get("/api/collections/rules").get_json()) == 5
    assert c.get("/api/collections/status").get_json()["running"] is False


def test_import_legacy(ctx, tmp_path):
    (tmp_path / "config.json").write_text(json.dumps({
        "settings": {"kavita_url": "http://k:5000", "api_key": "abc", "library_ids": [5, 6],
                     "schedule_enabled": False, "first_match_only": True},
        "rules": [{"id": "x1", "name": "R", "keywords": ["r"], "fields": ["tags"], "match": "contains",
                   "collection_id": 2, "enabled": True}]}))
    (tmp_path / "history.json").write_text(json.dumps([{"started": "2026-01-01T00:00:00+00:00", "results": []}]))
    msg = col.import_legacy(ctx, tmp_path)
    assert "1 rule" in msg
    e = col.Engine(ctx)
    assert e.rules()[0]["id"] == "x1" and len(e.history()) == 1
    assert ctx.settings.get("col.first_match_only") is True and ctx.settings.get("col.enabled") is False
    assert ctx.settings.get("col.library_ids") == "5,6"
    assert ctx.settings.get("kavita.url") == "http://k:5000"
    assert "no config.json" in col.import_legacy(ctx, tmp_path / "nope")
