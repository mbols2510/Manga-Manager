"""Tests for 2.2.0: issue #7, a frame around mature covers on the dashboard."""
from conftest import shared_db
import json
import re

import pytest
from PIL import Image

from manga_manager.auth import hash_password
from manga_manager.config import ConfigError, load_config
from manga_manager.covers import CoverStore
from manga_manager.frames import BUILTIN_WINDOW, FrameStore
from manga_manager.history import History
from manga_manager.web import create_app

from test_pipeline import env  # noqa: F401


def png(path, size=(1400, 1000)):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new("RGBA", size, (0, 0, 0, 0)).save(path)
    return path


def client(cfg, history=None):
    history = history or History(shared_db())
    return create_app(cfg, history, CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None), None).test_client()


def frames_on_page(html: str) -> dict:
    return json.loads(re.search(r"const FRAMES = (.*?);\n", html).group(1))


# ---------------------------------------------------------------- lookup
def test_builtin_frame_for_mature_categories_only(tmp_path):
    store = FrameStore(tmp_path / "frames", ["Adult", "Hentai"])
    adult = store.for_category("Adult")
    assert adult and adult.path == store.builtin and adult.window == BUILTIN_WINDOW
    assert adult.columns == 2                       # the built-in frame is wider than tall
    assert store.for_category("hentai")             # case-insensitive
    assert store.for_category("Regular") is None


def test_builtin_window_matches_image(tmp_path):
    f = FrameStore(tmp_path / "none", ["Adult"]).for_category("Adult")
    t, r, b, l = f.window
    assert (f.width - l - r) / (f.height - t - b) == pytest.approx(2 / 3)   # the window has a cover's shape


def test_user_files_override_in_order(tmp_path):
    d = tmp_path / "frames"
    store = FrameStore(d, ["Adult", "Hentai"])
    png(d / "mature.png")
    assert store.for_category("Hentai").path == d / "mature.png"
    png(d / "hentai.webp")
    assert store.for_category("Hentai").path == d / "hentai.webp"
    assert store.for_category("Adult").path == d / "mature.png"


def test_same_size_user_frame_uses_builtin_window(tmp_path):
    d = tmp_path / "frames"
    png(d / "mature.png")
    assert FrameStore(d, ["Adult"]).for_category("Adult").window == BUILTIN_WINDOW


def test_other_size_needs_window_and_one_column(tmp_path):
    d = tmp_path / "frames"
    png(d / "mature.png", (800, 1200))
    f = FrameStore(d, ["Adult"], window=(150, 100, 150, 100)).for_category("Adult")
    assert f.window == (150, 100, 150, 100) and f.columns == 1
    css = f.css()
    assert css["left"] == "12.5000%" and css["width"] == "75.0000%"
    assert css["top"] == "12.5000%" and css["height"] == "75.0000%"


def test_window_bigger_than_image_is_ignored(tmp_path):
    d = tmp_path / "frames"
    png(d / "mature.png", (300, 300))
    assert FrameStore(d, ["Adult"], window=(200, 0, 200, 0)).for_category("Adult") is None


def test_svg_frame_size_from_viewbox(tmp_path):
    d = tmp_path / "frames"
    d.mkdir()
    (d / "mature.svg").write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1400 1000"></svg>')
    f = FrameStore(d, ["Adult"]).for_category("Adult")
    assert (f.width, f.height) == (1400, 1000) and f.mimetype == "image/svg+xml"


def test_install_defaults_copies_once(tmp_path):
    d = tmp_path / "config" / "frames"
    store = FrameStore(d, ["Adult"])
    store.install_defaults()
    # 2.5.0 (#13): the built-in frame goes into the mature/ folder of frames
    assert (d / "mature" / "default.png").read_bytes() == store.builtin.read_bytes()
    assert (d / "frame-guide.png").is_file()
    (d / "mature" / "default.png").write_bytes(b"mine")
    store.install_defaults()                         # never overwrites the user's own design
    assert (d / "mature" / "default.png").read_bytes() == b"mine"


def test_install_defaults_skipped_when_frames_off(tmp_path):
    FrameStore(tmp_path / "frames", []).install_defaults()
    assert not (tmp_path / "frames").exists()


# ---------------------------------------------------------------- config
def write_cfg(tmp_path, frame_yaml=""):
    p = tmp_path / "config.yml"
    p.write_text(f"""
suwayomi: {{url: http://s}}
kavita: {{url: http://k, api_key: x}}
libraries:
  - {{category: Regular, kavita_library_id: 1, path: /r}}
  - {{category: Adult, kavita_library_id: 2, path: /a}}
web:
{frame_yaml}
""")
    return p


def test_config_defaults(tmp_path):
    cfg = load_config(write_cfg(tmp_path))
    assert cfg.frame_categories == ["Adult", "Hentai"]
    assert cfg.frame_window is None and cfg.frame_columns == 0
    assert cfg.config_dir == tmp_path


def test_config_values_and_errors(tmp_path):
    cfg = load_config(write_cfg(tmp_path, "  frame: {categories: [], window: [1, 2, 3, 4], columns: 1}"))
    assert cfg.frame_categories == [] and cfg.frame_window == (1, 2, 3, 4) and cfg.frame_columns == 1
    with pytest.raises(ConfigError):
        load_config(write_cfg(tmp_path, "  frame: {window: [1, 2]}"))
    with pytest.raises(ConfigError):
        load_config(write_cfg(tmp_path, "  frame: {columns: 3}"))


# ---------------------------------------------------------------- dashboard
def test_dashboard_frames_mature_categories(env):
    cfg, *_ = env
    cfg.config_dir = env[2] / "config"
    cfg.frame_categories = ["Adult", "Hentai"]
    c = client(cfg)
    frames = frames_on_page(c.get("/").get_data(as_text=True))
    assert set(frames) == {"adult", "hentai"}
    assert len(frames["adult"]) == 1                # 2.5.0: a list of frames per category (#13)
    assert frames["adult"][0]["span"] == 2 and frames["adult"][0]["url"].startswith("frame/adult/0?v=")
    r = c.get("/frame/adult")
    assert r.status_code == 200 and r.mimetype == "image/png"
    assert c.get("/frame/regular").status_code == 404
    assert c.get("/frame/nope").status_code == 404


def test_dashboard_columns_setting_and_off(env):
    cfg, *_ = env
    cfg.config_dir = env[2] / "config"
    cfg.frame_columns = 1
    assert frames_on_page(client(cfg).get("/").get_data(as_text=True))["adult"][0]["span"] == 1
    cfg.frame_categories = []
    assert frames_on_page(client(cfg).get("/").get_data(as_text=True)) == {}


def test_user_frame_served(env):
    cfg, _, tmp = env
    cfg.config_dir = tmp / "config"
    png(cfg.config_dir / "frames" / "hentai.png", (1400, 1000))
    body = client(cfg).get("/frame/hentai").data
    assert body == (cfg.config_dir / "frames" / "hentai.png").read_bytes()


def test_frame_needs_login(env):
    cfg, _, tmp = env
    cfg.config_dir = tmp / "config"
    cfg.auth_enabled = True
    cfg.auth_users = {"maarten": hash_password("correct horse battery")}
    c = client(cfg)
    assert c.get("/frame/adult").status_code in (302, 401)
    c.post("/login", data={"username": "maarten", "password": "correct horse battery"})
    assert c.get("/frame/adult").status_code == 200
