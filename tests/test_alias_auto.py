"""#8: manga in the ADULT_OFFICIAL category are added to the aliases with an empty Kavita source,
and nothing is moved for them until the Kavita source is filled in."""
import logging
from pathlib import Path

from manga_manager.config import Config, LibraryConfig, load_config
from manga_manager.covers import CoverStore
from manga_manager.kavita import KavitaSeries
from manga_manager.pipeline import Pipeline
from manga_manager.suwayomi import SuwayomiIndex, SuwayomiManga

from test_pipeline import FakeKavita, FakeNotifier, FakeSuwayomi, env, make_cbz  # noqa: F401


class FakeHistory:
    """In-memory stand-in for the alias part of History (the Postgres tests are skipped locally)."""

    def __init__(self, aliases=None, ranks=None):
        self._aliases = dict(aliases or {})
        self._ranks = dict(ranks or {})
        self.auto_calls = []

    def aliases(self):
        return dict(self._aliases)

    def alias_ranks(self):
        return {k.casefold(): v for k, v in self._ranks.items() if v}

    def add_auto_alias(self, title, rank=0):
        self.auto_calls.append(title)
        if any(t.casefold() == title.casefold() for t in self._aliases):
            return False
        self._aliases[title] = ""
        self._ranks[title] = rank
        return True

    def prune_updates(self, days):
        return 0


def pipe_for(cfg, mangas, history):
    suwa = FakeSuwayomi(mangas)
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, suwa, None, False)
    kavita = FakeKavita([KavitaSeries(9, "Solo Leveling", 1, True, "/m/Solo Leveling")])
    return Pipeline(cfg, suwa, kavita, covers, history, FakeNotifier(), sleep=lambda s: None), kavita


MANGAS = [SuwayomiManga(1, "Alpha", ["ADULT_OFFICIAL"]),
          SuwayomiManga(2, "Beta", ["Regular"]),
          SuwayomiManga(3, "Gamma", ["Regular", "adult_official"]),
          SuwayomiManga(4, "Delta", [])]


def test_official_library_defaults_to_adult_official_and_is_configurable(tmp_path):
    assert Config().official_library == "ADULT_OFFICIAL"
    path = tmp_path / "c.yml"
    path.write_text("libraries: [{category: R, kavita_library_id: 1, path: /m}]\n"
                    "suwayomi: {official_library: My Official}\n", encoding="utf-8")
    assert load_config(path).official_library == "My Official"
    path.write_text("libraries: [{category: R, kavita_library_id: 1, path: /m}]\n", encoding="utf-8")
    assert load_config(path).official_library == "ADULT_OFFICIAL"
    path.write_text("libraries: [{category: R, kavita_library_id: 1, path: /m}]\nsuwayomi: {official_library: ''}\n",
                    encoding="utf-8")
    assert load_config(path).official_library == ""


def test_alias_pending_and_has_alias():
    cfg = Config(aliases={"Cfg": "Config Name"}, dashboard_aliases={"Alpha": "", "Done": "Done K"})
    assert cfg.alias_pending("alpha") and not cfg.alias_pending("Done") and not cfg.alias_pending("Nope")
    assert not cfg.alias_pending("Cfg")
    assert cfg.has_alias("ALPHA") and cfg.has_alias("cfg") and not cfg.has_alias("Nope")
    assert cfg.kavita_name_for("Alpha") == ""            # an empty alias never names a Kavita series
    assert cfg.suwayomi_title_for("") is None


def test_sync_adds_only_official_library_manga_with_empty_kavita_source(env, caplog):
    cfg, _, _ = env
    history = FakeHistory()
    pipe, _ = pipe_for(cfg, MANGAS, history)
    with caplog.at_level(logging.INFO):
        added = pipe.sync_official_aliases(SuwayomiIndex(MANGAS))
    assert added == ["Alpha", "Gamma"]
    assert history.aliases() == {"Alpha": "", "Gamma": ""}
    assert history.alias_ranks() == {"alpha": 1, "gamma": 1}      # official source
    assert cfg.alias_pending("Alpha") and cfg.alias_pending("Gamma")   # visible to the same run
    assert "Alpha" in caplog.text and "ADULT_OFFICIAL" in caplog.text


def test_sync_is_idempotent_and_keeps_existing_aliases(env):
    cfg, _, _ = env
    history = FakeHistory({"Alpha": "Kavita Alpha"})
    cfg.aliases = {"Gamma": "Config Gamma"}
    pipe, _ = pipe_for(cfg, MANGAS, history)
    pipe.refresh_aliases()
    assert pipe.sync_official_aliases(SuwayomiIndex(MANGAS)) == []
    assert history.aliases() == {"Alpha": "Kavita Alpha"} and history.auto_calls == []
    history2 = FakeHistory()
    pipe2, _ = pipe_for(Config(), MANGAS, history2)
    assert pipe2.sync_official_aliases(SuwayomiIndex(MANGAS)) == ["Alpha", "Gamma"]
    assert pipe2.sync_official_aliases(SuwayomiIndex(MANGAS)) == []
    assert list(history2.aliases()) == ["Alpha", "Gamma"]


def test_sync_disabled_or_dry_run_adds_nothing(env):
    cfg, _, _ = env
    history = FakeHistory()
    cfg.official_library = ""
    pipe, _ = pipe_for(cfg, MANGAS, history)
    assert pipe.sync_official_aliases(SuwayomiIndex(MANGAS)) == []
    cfg.official_library, cfg.dry_run = "ADULT_OFFICIAL", True
    assert pipe.sync_official_aliases(SuwayomiIndex(MANGAS)) == []
    assert history.aliases() == {}


def test_pending_alias_blocks_the_move(env):
    cfg, libs, _ = env
    cfg.libraries = [LibraryConfig("ADULT_OFFICIAL", 2, libs["Adult"], "Adult")]
    folder = cfg.downloads / "Src" / "Alpha"
    make_cbz(folder / "1.cbz", "Alpha", "1")
    history = FakeHistory({"Alpha": ""})
    pipe, kavita = pipe_for(cfg, [SuwayomiManga(1, "Alpha", ["ADULT_OFFICIAL"])], history)
    result = pipe.run()
    assert not result.processed
    assert (folder / "1.cbz").exists() and not list(libs["Adult"].iterdir())
    assert any("Alpha" in s and "Kavita source" in s for s in result.skipped)


def test_run_adds_aliases_then_skips_their_moves(env):
    cfg, libs, _ = env
    cfg.libraries = [LibraryConfig("ADULT_OFFICIAL", 2, libs["Adult"], "Adult")]
    folder = cfg.downloads / "Src" / "Alpha"
    make_cbz(folder / "1.cbz", "Alpha", "1")
    history = FakeHistory()
    pipe, _ = pipe_for(cfg, [SuwayomiManga(1, "Alpha", ["ADULT_OFFICIAL"])], history)
    result = pipe.run()
    assert history.aliases() == {"Alpha": ""}
    assert not result.processed and (folder / "1.cbz").exists()
    pipe.run()
    assert list(history.aliases()) == ["Alpha"]


def test_alias_api_marks_pending_aliases(env):
    from manga_manager.web import create_app
    cfg, *_ = env
    history = FakeHistory({"Alpha": "", "Beta": "Kavita Beta"})
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None)
    client = create_app(cfg, history, covers, None).test_client()
    items = {i["suwayomi_title"]: i for i in client.get("/api/aliases").get_json()["items"]}
    assert items["Alpha"]["pending"] is True and items["Beta"]["pending"] is False


def test_reprocess_explains_pending_alias(env):
    cfg, _, _ = env
    pipe, _ = pipe_for(cfg, MANGAS, FakeHistory({"Alpha": ""}))
    out = pipe.reprocess_alias("Alpha")
    assert "Kavita source" in out["message"] and not out["moves"]


# ---------------------------------------------------------------- #9: alias menu data and page
def test_title_lists_carry_cover_id_and_category_for_the_alias_cards(env):
    from manga_manager.__main__ import title_lists
    cfg, libs, _ = env
    cfg.libraries = [LibraryConfig("ADULT_OFFICIAL", 2, libs["Adult"], "Adult"), *cfg.libraries]

    class Kav:
        def all_series(self):
            return [KavitaSeries(9, "Kav A", 2, True, "/m/a")]

    data = title_lists(cfg, FakeSuwayomi(MANGAS), Kav())()
    assert data["suwayomi"][:2] == ["Alpha", "Beta"] and data["kavita"] == ["Kav A"]
    by_title = {m["title"]: m for m in data["manga"]}
    assert by_title["Alpha"] == {"title": "Alpha", "id": 1, "category": "Adult"}
    assert by_title["Beta"]["category"] == "Regular" and by_title["Delta"]["category"] == ""


def test_suggestions_endpoint_passes_the_manga_list_through(env):
    from manga_manager.web import create_app
    cfg, *_ = env
    manga = [{"title": "Alpha", "id": 1, "category": "Adult"}]
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None)
    client = create_app(cfg, FakeHistory(), covers, None,
                        titles=lambda: {"suwayomi": ["Alpha"], "kavita": [], "manga": manga}).test_client()
    assert client.get("/api/aliases/suggestions").get_json()["manga"] == manga


def test_alias_menu_page_has_cover_cards_filters_and_search(env):
    from manga_manager.web import create_app
    cfg, *_ = env
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None)
    html = create_app(cfg, FakeHistory(), covers, None).test_client().get("/").get_data(as_text=True)
    for needle in ('id="alias-search"', 'id="alias-tabs"', 'data-filter="all"', 'data-filter="missing-alias"',
                   'data-filter="missing-source"', 'data-filter="done"', 'class="alias-grid"',
                   "data-cover-src", "BLUR.includes", "status-badge"):
        assert needle in html, needle
    css = (Path(__file__).parent.parent / "manga_manager" / "static" / "app.css").read_text(encoding="utf-8")
    assert ".alias-grid" in css and ".status-badge" in css and "auto-fill" in css.split(".alias-grid", 1)[1][:200]
