"""Tests for 2.7.0: the Night Shelf redesign (dark, cover-first dashboard, drawer for aliases, dark login)."""
from conftest import shared_db
import re
from pathlib import Path

import pytest

from manga_manager.covers import CoverStore
from manga_manager.history import History
from manga_manager.web import create_app

from test_pipeline import env  # noqa: F401

TEMPLATES = Path(__file__).parent.parent / "manga_manager" / "templates"
INDEX = (TEMPLATES / "index.html").read_text(encoding="utf-8") + (TEMPLATES.parent / "static" / "app.css").read_text(encoding="utf-8")
LOGIN = (TEMPLATES / "login.html").read_text(encoding="utf-8")


def tokens(css: str) -> dict[str, str]:
    root = re.search(r":root \{(.*?)\n\}", css, re.S).group(1)
    return dict(re.findall(r"--([\w-]+):\s*(#[0-9a-fA-F]{6})\s*;", root))


def luminance(hex_color: str) -> float:
    r, g, b = (int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5))
    f = lambda c: c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)


def contrast(a: str, b: str) -> float:
    hi, lo = sorted((luminance(a), luminance(b)), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


def test_new_series_sticker_is_readable():
    """#17 stays fixed: the sticker's text colour is a token with at least 4.5:1 against its fill, in the one (dark) theme."""
    t = tokens(INDEX)
    sticker = re.search(r"\n\.sticker \{[^}]*\}", INDEX).group(0)
    assert "background: var(--signal)" in sticker and "color: var(--signal-ink)" in sticker
    assert contrast(t["signal"], t["signal-ink"]) >= 4.5
    assert "prefers-color-scheme" not in INDEX          # one theme, so no state where the colours can clash


def test_text_and_category_colours_are_readable_on_the_black_ground():
    t = tokens(INDEX)
    for name in ("ink", "ink-soft", "regular", "adult", "hentai", "ok", "warn"):
        assert contrast(t[name], t["ground"]) >= 4.5, name
    for name in ("ink", "ink-soft"):
        assert contrast(t[name], t["surface"]) >= 4.5, name
    assert t["ground"] == "#08080a"                      # pure black, as chosen


def test_login_uses_the_same_tokens():
    a, b = tokens(INDEX), tokens(LOGIN)
    for name in ("ground", "surface", "ink", "ink-soft", "signal", "signal-ink"):
        assert a[name] == b[name], name
    assert contrast(b["signal"], b["signal-ink"]) >= 4.5


def test_frames_keep_working_with_the_sticker_outline():
    assert "const FRAMES = {{ frames|tojson }};\n" in INDEX
    assert ".framed > .frame" in INDEX and "drop-shadow(1px 0 0 #fff)" in INDEX
    assert "span-2" in INDEX and "fr.span" in INDEX       # a frame wider than tall still gets a double-width card
    assert ".framed .cover .veil" in INDEX                # the reveal text stays clear of the characters' hands


def test_dashboard_features_are_in_the_page():
    for needle in ('id="spot"', "mm-last-seen", 'id="seen"', "mm-dense", "data-act=\"search\"", 'class="bottom"',
                   "function spotlight", "item.duplicates", 'class="duplicate"'):
        assert needle in INDEX, needle
    assert "BLUR.includes" in INDEX and "MATURE.includes" in INDEX      # the spotlight never shows a mature cover
    assert "confirm(" not in INDEX                        # deleting and moving ask inside the panel, not in a browser box


def test_dashboard_and_login_render(env):
    cfg, *_ = env
    app = create_app(cfg, History(shared_db()), CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None), None)
    c = app.test_client()
    html = c.get("/").data.decode()
    assert 'id="aliases"' in html and "const BLUR" in html
    assert c.get("/api/updates").status_code == 200


def test_login_page_renders_in_both_modes(env):
    cfg, *_ = env
    from manga_manager.auth import hash_password
    cfg.auth_enabled, cfg.auth_allow_signup = True, True
    cfg.auth_users = {"alice": hash_password("correct horse battery")}
    app = create_app(cfg, History(shared_db()), CoverStore(cfg.thumbnail_cache, cfg.suwayomi_thumbnails, None), None)
    c = app.test_client()
    login = c.get("/login").data.decode()
    assert 'class="mk"' in login and 'name="password"' in login and "Create one" in login
    signup = c.get("/signup").data.decode()
    assert 'name="confirm"' in signup
