from conftest import shared_db
import io
import json
import os
import time
import zipfile
from pathlib import Path

import pytest
from PIL import Image
from xml.sax.saxutils import escape

from manga_manager.comicinfo import chapter_label, normalize_title, read_comicinfo
from manga_manager.config import Config, LibraryConfig, load_config, ConfigError
from manga_manager.covers import CoverStore
from manga_manager.history import History
from manga_manager.kavita import KavitaClient, KavitaIndex, KavitaSeries
from manga_manager.notifier import Notifier
from manga_manager.pipeline import Pipeline
from manga_manager.suwayomi import SuwayomiIndex, SuwayomiManga


# ---------------------------------------------------------------- helpers
def make_cbz(path: Path, series: str, number: str, old: bool = True) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("ComicInfo.xml", f"<?xml version='1.0'?><ComicInfo><Series>{escape(series)}</Series>"
                                     f"<Number>{escape(number)}</Number></ComicInfo>")
        zf.writestr("001.jpg", b"fake")
    if old:
        # A finished download: the chapter and its manga folder haven't changed for an hour.
        past = time.time() - 3600
        os.utime(path, (past, past))
        if all(f.stat().st_mtime <= past for f in path.parent.iterdir()):
            os.utime(path.parent, (past, past))
    return path


def jpeg_bytes(color=(200, 30, 30)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (40, 60), color).save(buf, format="JPEG")
    return buf.getvalue()


class FakeSuwayomi:
    def __init__(self, mangas):
        self.mangas = mangas

    def library(self):
        return self.mangas

    def download_thumbnail(self, manga_id):
        return jpeg_bytes((0, 0, 200))


class FakeKavita:
    def __init__(self, series):
        self.series = list(series)
        self.calls = []
        self.appear_on_scan = []  # series that "appear" after a library scan

    def all_series(self):
        return list(self.series)

    def scan_library(self, library_id, force=False):
        self.calls.append(("scan_library", library_id))
        self.series.extend(s for s in self.appear_on_scan if s.library_id == library_id)

    def scan_series(self, library_id, series_id):
        self.calls.append(("scan_series", library_id, series_id))

    def upload_series_cover(self, series_id, png, lock=True):
        assert png.startswith(b"\x89PNG"), "cover must be a real PNG"
        self.calls.append(("cover", series_id, lock))
        for s in self.series:
            if s.id == series_id:
                s.cover_locked = lock
        return "file"


class FakeNotifier(Notifier):
    def __init__(self):
        super().__init__("http://ntfy", "t", enabled=True)
        self.sent = []

    def send(self, **kw):
        self.sent.append(kw)
        return True


@pytest.fixture
def env(tmp_path):
    downloads = tmp_path / "downloads" / "mangas"
    thumbs = tmp_path / "downloads" / "thumbnails"
    thumbs.mkdir(parents=True)
    downloads.mkdir(parents=True)
    libs = {name: tmp_path / "kavita" / name for name in ("Regular", "Adult", "Hentai")}
    for p in libs.values():
        p.mkdir(parents=True)
    cfg = Config(
        downloads=downloads, suwayomi_thumbnails=thumbs, data_dir=tmp_path / "data",
        suwayomi_url="http://suwa", kavita_url="http://kavita", kavita_api_key="k",
        libraries=[LibraryConfig("Regular", 1, libs["Regular"], "Regular"),
                   LibraryConfig("Adult", 2, libs["Adult"], "Adult"),
                   LibraryConfig("Hentai", 3, libs["Hentai"], "Hentai")],
        new_series_timeout=30, scan_delay=0,
    )
    return cfg, libs, tmp_path


def build(cfg, suwa, kavita):
    history = History(shared_db())
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, suwa, cfg.thumbnail_export, cfg.dry_run)
    notifier = FakeNotifier()
    pipe = Pipeline(cfg, suwa, kavita, covers, history, notifier, sleep=lambda s: None)
    return pipe, history, notifier


# ---------------------------------------------------------------- tests
def test_new_manga_is_moved_scanned_notified_and_gets_cover(env):
    cfg, libs, _ = env
    make_cbz(cfg.downloads / "MangaDex (EN)" / "Solo Leveling" / "Ch 1.cbz", "Solo Leveling", "1")
    make_cbz(cfg.downloads / "MangaDex (EN)" / "Solo Leveling" / "Ch 2.cbz", "Solo Leveling", "2")
    (cfg.suwayomi_thumbnails / "42.jpg").write_bytes(jpeg_bytes())

    suwa = FakeSuwayomi([SuwayomiManga(42, "Solo Leveling", ["Regular"], "MangaDex")])
    kavita = FakeKavita([])
    kavita.appear_on_scan = [KavitaSeries(7, "Solo Leveling", 1, False, r"\\zeus\boeken\Regular\Solo Leveling")]
    pipe, history, notifier = build(cfg, suwa, kavita)

    result = pipe.run()

    assert [p.title for p in result.processed] == ["Solo Leveling"]
    assert sorted(f.name for f in (libs["Regular"] / "Solo Leveling").iterdir()) == ["Ch 1.cbz", "Ch 2.cbz"]
    assert not (cfg.downloads / "MangaDex (EN)" / "Solo Leveling").exists(), "empty source folder removed"
    assert ("scan_library", 1) in kavita.calls
    assert ("cover", 7, True) in kavita.calls
    assert notifier.sent[0]["is_new"] is True and notifier.sent[0]["chapters"] == ["1", "2"]
    items, total = history.list_updates()
    assert total == 1 and items[0]["kavita_series_id"] == 7 and items[0]["suwayomi_id"] == 42
    assert (cfg.thumbnail_cache / "42.png").read_bytes().startswith(b"\x89PNG")


def test_existing_manga_reuses_kavita_folder_and_scans_series(env):
    cfg, libs, _ = env
    (libs["Adult"] / "Some Title (2020)").mkdir()
    make_cbz(cfg.downloads / "Src" / "Some Title" / "c15.cbz", "Some Title", "15")
    suwa = FakeSuwayomi([SuwayomiManga(5, "Some Title", ["Adult stuff"], "Src")])
    kavita = FakeKavita([KavitaSeries(99, "Some Title", 2, True, r"\\zeus\boeken\Adult\Some Title (2020)")])
    pipe, history, notifier = build(cfg, suwa, kavita)

    result = pipe.run()

    assert result.processed[0].is_new is False
    assert (libs["Adult"] / "Some Title (2020)" / "c15.cbz").exists()
    assert ("scan_series", 2, 99) in kavita.calls
    assert not any(c[0] == "scan_library" for c in kavita.calls)
    assert not any(c[0] == "cover" for c in kavita.calls), "locked cover left alone"
    assert notifier.sent[0]["category"] == "Adult"


def test_unknown_manga_and_fresh_files_are_left_alone(env):
    cfg, libs, _ = env
    unknown = make_cbz(cfg.downloads / "Src" / "Not In Library" / "1.cbz", "Not In Library", "1")
    fresh = make_cbz(cfg.downloads / "Src" / "Known" / "2.cbz", "Known", "2", old=False)
    suwa = FakeSuwayomi([SuwayomiManga(1, "Known", ["Regular"])])
    pipe, history, notifier = build(cfg, suwa, FakeKavita([]))

    result = pipe.run()

    assert result.processed == []
    assert unknown.exists() and fresh.exists()
    assert len(result.skipped) == 1 and notifier.sent == []


def test_loose_title_match_and_uncategorised(env):
    cfg, libs, _ = env
    make_cbz(cfg.downloads / "Src" / "Re Zero" / "1.cbz", "Re:Zero", "1")
    make_cbz(cfg.downloads / "Src" / "Orphan" / "1.cbz", "Orphan", "1")
    suwa = FakeSuwayomi([SuwayomiManga(3, "Re: Zero", ["Regular"]), SuwayomiManga(4, "Orphan", [])])
    pipe, *_ = build(cfg, suwa, FakeKavita([]))
    result = pipe.run()
    assert [p.title for p in result.processed] == ["Re: Zero"]
    assert any("don't match any configured library" in s for s in result.skipped)


def test_dry_run_changes_nothing(env):
    cfg, libs, _ = env
    cfg.dry_run = True
    src = make_cbz(cfg.downloads / "Src" / "X" / "1.cbz", "X", "1")
    kavita = FakeKavita([KavitaSeries(1, "Y", 1, False, "/x/Y")])
    suwa = FakeSuwayomi([SuwayomiManga(1, "X", ["Regular"]), SuwayomiManga(2, "Y", ["Regular"])])
    pipe, history, _ = build(cfg, suwa, kavita)
    pipe.run()
    assert src.exists()
    assert kavita.calls == []
    assert history.list_updates()[1] == 0


def test_write_cover_file_option(env):
    cfg, libs, _ = env
    cfg.covers_write_file = True
    (libs["Regular"] / "Berserk").mkdir()
    (cfg.suwayomi_thumbnails / "8.webp").write_bytes(jpeg_bytes())
    kavita = FakeKavita([KavitaSeries(3, "Berserk", 1, True, "C:\\x\\Berserk")])
    suwa = FakeSuwayomi([SuwayomiManga(8, "Berserk", ["Regular"])])
    pipe, *_ = build(cfg, suwa, kavita)
    pipe.run()
    cover = libs["Regular"] / "Berserk" / "cover.png"
    assert cover.read_bytes().startswith(b"\x89PNG")


def test_unlocked_cover_uploaded_once_when_lock_disabled(env):
    cfg, *_ = env
    cfg.covers_lock = False
    (cfg.suwayomi_thumbnails / "8.jpg").write_bytes(jpeg_bytes())
    kavita = FakeKavita([KavitaSeries(3, "Berserk", 1, False, "/x/Berserk")])
    suwa = FakeSuwayomi([SuwayomiManga(8, "Berserk", ["Regular"])])
    pipe, *_ = build(cfg, suwa, kavita)
    pipe.run(); pipe.run()
    assert [c for c in kavita.calls if c[0] == "cover"] == [("cover", 3, False)]


# ---------------------------------------------------------------- units
def test_comicinfo_and_labels(tmp_path):
    p = make_cbz(tmp_path / "a.cbz", "Series & Co", "12.5")
    assert read_comicinfo(p).series == "Series & Co"
    assert chapter_label(p) == "12.5"
    bare = tmp_path / "Chapter 7.cbz"
    with zipfile.ZipFile(bare, "w") as zf:
        zf.writestr("1.jpg", b"x")
    assert chapter_label(bare) == "7"
    assert normalize_title("Re:Zero − Starting Life!") == normalize_title("re zero starting life")
    assert normalize_title("俺だけレベルアップな件") == "俺だけレベルアップな件"


def test_indexes():
    idx = SuwayomiIndex([SuwayomiManga(1, "Title", source="A"), SuwayomiManga(2, "Title", source="B")])
    assert idx.find("title", source="B").id == 2
    k = KavitaIndex([KavitaSeries(1, "Foo", 1, False, r"\\nas\lib\Foo Folder", localized_name="Bar")])
    assert k.find(1, "bar").id == 1
    assert k.find(1, "nothing", folder_name="Foo Folder").id == 1
    assert k.find(2, "Foo") is None
    assert k.series[0].folder_name == "Foo Folder"


class FakeResp:
    def __init__(self, status, body=None, text=""):
        self.status_code, self._body, self.text = status, body, text

    @property
    def ok(self):
        return 200 <= self.status_code < 300

    def json(self):
        return self._body


class FakeSession:
    def __init__(self, upload_status=200):
        self.upload_status = upload_status
        self.requests = []

    def post(self, url, **kw):
        return FakeResp(200, {"token": "jwt"})

    def request(self, method, url, **kw):
        self.requests.append((method, url, kw))
        if url.endswith("/api/Upload/upload-by-file"):
            return FakeResp(self.upload_status, text='"coverupload_x.png"')
        return FakeResp(200, [])


def test_kavita_upload_prefers_file_then_falls_back():
    k = KavitaClient("http://kavita", "key")
    k.session = FakeSession(200)
    assert k.upload_series_cover(5, b"\x89PNGdata") == "file"
    body = k.session.requests[-1][2]["json"]
    assert body == {"id": 5, "fileName": "coverupload_x.png", "url": "", "lockCover": True}
    assert k.session.requests[0][2]["headers"]["Authorization"] == "Bearer jwt"

    old = KavitaClient("http://kavita", "key")
    old.session = FakeSession(404)
    assert old.upload_series_cover(5, b"\x89PNGdata") == "base64"
    assert old.upload_series_cover(6, b"\x89PNGdata") == "base64"
    upload_calls = [r for r in old.session.requests if r[1].endswith("upload-by-file")]
    assert len(upload_calls) == 1, "only probes upload-by-file once"


def test_config_env_expansion(tmp_path, monkeypatch):
    monkeypatch.setenv("KAVITA_API_KEY", "secret")
    cfg_file = tmp_path / "c.yml"
    cfg_file.write_text("""
suwayomi: {url: "http://s:4567/"}
kavita: {url: "http://k:5000", api_key: "${KAVITA_API_KEY}"}
libraries:
  - {category: Regular, kavita_library_id: 1, path: /manga/Regular}
""")
    cfg = load_config(cfg_file)
    assert cfg.kavita_api_key == "secret" and cfg.suwayomi_url == "http://s:4567"
    cfg_file.write_text("kavita: {}")
    with pytest.raises(ConfigError):
        load_config(cfg_file)


def test_web(env):
    from manga_manager.web import create_app
    cfg, *_ = env
    history = History(shared_db())
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, FakeSuwayomi([]))
    cfg.kavita_public_url = "http://kavita"
    history.add_update(title="A <b>", category="Regular", is_new=True, chapters=["1", "2"], suwayomi_id=9,
                       kavita_series_id=4, kavita_library_id=1)
    history.add_update(title="B", category="Adult", is_new=False, chapters=["3"])
    client = create_app(cfg, history, covers, None).test_client()
    assert b"Manga updates" in client.get("/").data
    data = client.get("/api/updates?kind=new").get_json()
    assert data["total"] == 1 and data["items"][0]["kavita_url"] == "http://kavita/library/1/series/4"
    assert client.get("/api/updates?category=Adult").get_json()["items"][0]["title"] == "B"
    assert client.get("/api/status").get_json()["chapters"] == 3
    thumb = client.get("/thumb/9.png")
    assert thumb.status_code == 200 and thumb.data.startswith(b"\x89PNG")
    assert client.post("/api/run").status_code == 403


# ---------------------------------------------------------------- v2.0.1: renamed series
from manga_manager.comicinfo import set_series_name, stripped_title


def test_renamed_series_joins_existing_kavita_series(env):
    """Source dropped "(Official)": new chapters must go to the existing series, not a new one."""
    cfg, libs, _ = env
    (libs["Regular"] / "Governess of a Tyrant (Official)").mkdir()
    make_cbz(cfg.downloads / "Src" / "Governess of a Tyrant" / "c41.cbz", "Governess of a Tyrant", "41")
    suwa = FakeSuwayomi([SuwayomiManga(11, "Governess of a Tyrant", ["Regular_Complete"])])
    kavita = FakeKavita([KavitaSeries(50, "Governess of a Tyrant (Official)", 1, True,
                                      "C:/Nas/Kavita/Manga/Regular/Governess of a Tyrant (Official)")])
    pipe, history, notifier = build(cfg, suwa, kavita)

    result = pipe.run()

    moved = libs["Regular"] / "Governess of a Tyrant (Official)" / "c41.cbz"
    assert moved.exists()
    assert not (libs["Regular"] / "Governess of a Tyrant").exists()
    assert read_comicinfo(moved).series == "Governess of a Tyrant (Official)"
    assert read_comicinfo(moved).number == "41", "other ComicInfo fields untouched"
    with zipfile.ZipFile(moved) as zf:
        assert zf.read("001.jpg") == b"fake" and zf.testzip() is None
    assert result.processed[0].is_new is False
    assert ("scan_series", 1, 50) in kavita.calls


def test_align_can_be_disabled(env):
    cfg, libs, _ = env
    cfg.align_series_name = False
    (libs["Regular"] / "T (Official)").mkdir()
    make_cbz(cfg.downloads / "Src" / "T" / "1.cbz", "T", "1")
    pipe, *_ = build(cfg, FakeSuwayomi([SuwayomiManga(1, "T", ["Regular"])]),
                     FakeKavita([KavitaSeries(2, "T (Official)", 1, True, "/m/T (Official)")]))
    pipe.run()
    assert read_comicinfo(libs["Regular"] / "T (Official)" / "1.cbz").series == "T"


def test_alias_maps_completely_different_title(env):
    cfg, libs, _ = env
    cfg.aliases = {"Ore dake Level Up na Ken": "Solo Leveling"}
    (libs["Regular"] / "Solo Leveling").mkdir()
    make_cbz(cfg.downloads / "Src" / "Ore dake Level Up na Ken" / "1.cbz", "Ore dake Level Up na Ken", "1")
    pipe, *_ = build(cfg, FakeSuwayomi([SuwayomiManga(1, "Ore dake Level Up na Ken", ["Regular"])]),
                     FakeKavita([KavitaSeries(9, "Solo Leveling", 1, True, "/m/Solo Leveling")]))
    result = pipe.run()
    assert result.processed[0].kavita_series.id == 9
    assert (libs["Regular"] / "Solo Leveling" / "1.cbz").exists()


def test_stripped_matching_only_when_unambiguous():
    assert stripped_title("Evangeline's Sword (Official)") == stripped_title("Evangeline's Sword")
    assert stripped_title("Guro Gyaru 〘Official〙") == stripped_title("Guro Gyaru")
    one = SuwayomiIndex([SuwayomiManga(1, "Bride of the Barrier Master")])
    assert one.match("Bride of the Barrier Master [Official]") == (one.mangas[0], "stripped")
    two = SuwayomiIndex([SuwayomiManga(1, "Title (Mature)"), SuwayomiManga(2, "Title (Official)")])
    assert two.match("Title") == (None, "none"), "ambiguous: don't guess"
    k = KavitaIndex([KavitaSeries(1, "A (Official)", 1, True, "/m/A (Official)"),
                     KavitaSeries(2, "A [Digital]", 1, True, "/m/A [Digital]")])
    assert k.find(1, "A") is None


def test_set_series_name_keeps_archive_intact(tmp_path):
    p = make_cbz(tmp_path / "x.cbz", "Old & Name", "3")
    assert set_series_name(p, "New <Name>") is True
    assert read_comicinfo(p).series == "New <Name>" and read_comicinfo(p).number == "3"
    assert set_series_name(p, "New <Name>") is False, "no rewrite when already correct"
    assert not (tmp_path / "x.cbz.tmp").exists()


def test_matching_report(env, capsys):
    from manga_manager.__main__ import matching_report
    cfg, *_ = env
    suwa = FakeSuwayomi([SuwayomiManga(1, "Kept", ["Regular_Complete"]), SuwayomiManga(2, "Other", ["Weird"])])
    kavita = FakeKavita([KavitaSeries(1, "Kept (Official)", 1, True, "/m/k"),
                         KavitaSeries(2, "Gone", 2, True, "/m/g"), KavitaSeries(3, "Elsewhere", 9, True, "/m/e")])
    matching_report(cfg, suwa, kavita)
    out = capsys.readouterr().out
    assert "Regular_Complete: 1 -> Regular" in out and "Weird: 1 -> NOT MAPPED" in out
    assert "without decorations 1, no match 1" in out and "[2] Gone" in out and "Elsewhere" not in out


# ---------------------------------------------------------------- v2.0.3: spaced scans
def test_scans_are_spaced_and_redundant_series_scans_skipped(env):
    cfg, libs, _ = env
    cfg.scan_delay = 60
    for name, lib in [("New A", "Regular"), ("New B", "Adult"), ("Old C", "Hentai"), ("Old D", "Hentai"),
                      ("Old E", "Regular")]:
        make_cbz(cfg.downloads / "Src" / name / "1.cbz", name, "1")
    suwa = FakeSuwayomi([SuwayomiManga(1, "New A", ["Regular"]), SuwayomiManga(2, "New B", ["Adult"]),
                         SuwayomiManga(3, "Old C", ["Hentai"]), SuwayomiManga(4, "Old D", ["Hentai"]),
                         SuwayomiManga(5, "Old E", ["Regular"])])
    kavita = FakeKavita([KavitaSeries(30, "Old C", 3, True, "/m/Old C"), KavitaSeries(40, "Old D", 3, True, "/m/Old D"),
                         KavitaSeries(50, "Old E", 1, True, "/m/Old E")])
    kavita.appear_on_scan = [KavitaSeries(10, "New A", 1, True, "/m/New A"), KavitaSeries(20, "New B", 2, True, "/m/New B")]
    history = History(shared_db())
    covers = CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, suwa)
    pipe = Pipeline(cfg, suwa, kavita, covers, history, FakeNotifier(),
                    sleep=lambda secs: kavita.calls.append(("sleep", secs)))

    pipe.run()

    scan_part = [c for c in kavita.calls if c[0] != "sleep" or c[1] == 60]
    assert scan_part == [
        ("scan_library", 2), ("sleep", 60),
        ("scan_library", 1), ("sleep", 60),
        ("scan_series", 3, 30), ("sleep", 60),
        ("scan_series", 3, 40),
    ], "Old E is in library 1, which already gets a library scan; libraries go smallest first"


def test_failed_scan_does_not_stop_the_others(env):
    cfg, libs, _ = env
    for name in ("One", "Two"):
        make_cbz(cfg.downloads / "Src" / name / "1.cbz", name, "1")
    suwa = FakeSuwayomi([SuwayomiManga(1, "One", ["Regular"]), SuwayomiManga(2, "Two", ["Regular"])])
    kavita = FakeKavita([KavitaSeries(1, "One", 1, True, "/m/One"), KavitaSeries(2, "Two", 1, True, "/m/Two")])
    original = kavita.scan_series

    def flaky(library_id, series_id):
        if series_id == 1:
            raise RuntimeError("Kavita busy")
        original(library_id, series_id)
    kavita.scan_series = flaky
    pipe, _, notifier = build(cfg, suwa, kavita)
    pipe.run()
    assert ("scan_series", 1, 2) in kavita.calls
    assert len(notifier.sent) == 2, "notifications still go out"


def test_old_series_scan_delay_key_still_read(tmp_path):
    f = tmp_path / "c.yml"
    base = """suwayomi: {url: "http://s"}
libraries: [{category: R, kavita_library_id: 1, path: /m}]
kavita: {url: "http://k", api_key: k%s}
"""
    f.write_text(base % ", series_scan_delay: 7")
    assert load_config(f).scan_delay == 7
    f.write_text(base % "")
    assert load_config(f).scan_delay == 10
