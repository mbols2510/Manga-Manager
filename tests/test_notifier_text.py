"""ntfy never applies a template to a message with an attachment, so Move Manga builds the text
itself when it attaches the cover. These tests keep that text identical to ntfy/MangaUpdates.yml."""
import json
import os
import shutil
import socket
import subprocess
import time
from itertools import product
from pathlib import Path

import pytest
import requests

from manga_manager.notifier import _LINES, _TITLES, Notifier, render_message

TEMPLATE = Path(__file__).parent.parent / "ntfy" / "MangaUpdates.yml"
CASES = list(product([True, False], ["Regular", "Adult", "Hentai", "Other"], ["", "\u25cf Chapter: 7 (New)\n"]))


def test_builtin_text_uses_the_template_wording():
    text = TEMPLATE.read_text(encoding="utf-8")
    for title in _TITLES.values():
        assert title in text, title
    for line in _LINES.values():
        assert line.replace("{t}", "{{ .MangaTitle }}") in text, line


def test_render_message():
    t, m = render_message(is_new=False, category="Regular", title="Saint", chapter_text="\u25cf Chapter: 100 (New)\n")
    assert t == "\U0001F389 Manga Updated \U0001F389"
    assert m == '\U0001F389 The Manga: "Saint" Has Been Updated \U0001F389\n\nCHAPTERS:\n\u25cf Chapter: 100 (New)'
    assert render_message(is_new=True, category="Manhwa", title="X", chapter_text="")[0] == \
        "\U0001F4DA New Manga Added \U0001F4DA"


class Capture:
    def __init__(self):
        self.calls = []

    def __call__(self, **kw):
        self.calls.append(kw)

        class R:
            ok = True
        return R()


def test_with_cover_the_text_is_built_here(monkeypatch):
    cap = Capture()
    monkeypatch.setattr("manga_manager.notifier.requests.post", cap)
    Notifier("http://ntfy", "Manga_Updates", "MangaUpdates", token="tk", delay=0,
             dashboard_url="https://dash").send(title="T", is_new=False, category="Regular",
                                                chapters=["1"], suwayomi_id=5)
    call = cap.calls[0]
    assert call["url"] == "http://ntfy" and "params" not in call, "JSON publish, no template"
    body = call["json"]
    assert body["topic"] == "Manga_Updates" and body["title"] == "\U0001F389 Manga Updated \U0001F389"
    assert body["attach"] == body["icon"] == "https://dash/thumb/5.png" and body["click"] == "https://dash"
    assert call["headers"] == {"Authorization": "Bearer tk"}


def test_without_cover_the_server_template_is_used(monkeypatch):
    cap = Capture()
    monkeypatch.setattr("manga_manager.notifier.requests.post", cap)
    Notifier("http://ntfy", "t", "MangaUpdates", delay=0, dashboard_url="https://dash", attach_cover=False).send(
        title="T", is_new=True, category="Adult", chapters=["1"], suwayomi_id=5)
    call = cap.calls[0]
    assert call["url"] == "http://ntfy/t" and call["params"] == {"template": "MangaUpdates"}
    assert call["json"]["MangaTitle"] == "T" and call["headers"]["Icon"] == "https://dash/thumb/5.png"
    assert "Attach" not in call["headers"]


# ---- against a real ntfy server (runs when the ntfy binary is available, e.g. NTFY_BIN=/path/to/ntfy)
def _ntfy_bin():
    return os.environ.get("NTFY_BIN") or shutil.which("ntfy")


@pytest.fixture(scope="module")
def ntfy_server(tmp_path_factory):
    binary = _ntfy_bin()
    if not binary:
        pytest.skip("ntfy binary not available")
    d = tmp_path_factory.mktemp("ntfy")
    (d / "tpl").mkdir()
    shutil.copy(TEMPLATE, d / "tpl" / "MangaUpdates.yml")
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    (d / "server.yml").write_text(f'base-url: "{base}"\nlisten-http: "127.0.0.1:{port}"\n'
                                  f'cache-file: "{d}/cache.db"\ntemplate-dir: "{d}/tpl"\n')
    proc = subprocess.Popen([binary, "serve", "--config", str(d / "server.yml")],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(50):
        try:
            if requests.get(base + "/v1/health", timeout=1).ok:
                break
        except requests.RequestException:
            time.sleep(0.1)
    yield base
    proc.terminate()
    proc.wait(10)


def _last(base, topic):
    return json.loads(requests.get(f"{base}/{topic}/json?poll=1", timeout=5).text.strip().splitlines()[-1])


@pytest.mark.parametrize("is_new,category,chapters", CASES)
def test_real_ntfy_builtin_text_matches_template(ntfy_server, is_new, category, chapters):
    topic = f"t{abs(hash((is_new, category, chapters)))}"
    data = {"New": "Yes" if is_new else "No", "category": category, "MangaTitle": "A \"quoted\" title",
            "Chapters": chapters}
    requests.post(f"{ntfy_server}/{topic}", params={"template": "MangaUpdates"}, json=data, timeout=5)
    server = _last(ntfy_server, topic)
    title, message = render_message(is_new=is_new, category=category, title="A \"quoted\" title",
                                    chapter_text=chapters)
    assert (title, message) == (server["title"], server["message"])


def test_real_ntfy_cover_notification(ntfy_server):
    Notifier(ntfy_server, "cover", "MangaUpdates", delay=0, dashboard_url="https://dash").send(
        title="Saint", is_new=False, category="Regular", chapters=["100", "101"], suwayomi_id=7, replaced=["101"])
    m = _last(ntfy_server, "cover")
    assert m["title"] == "\U0001F389 Manga Updated \U0001F389"
    assert m["message"].endswith("\u25cf Chapter: 100 (New)\n\u25cf Chapter: 101 (Replaced)")
    assert m["attachment"]["url"] == "https://dash/thumb/7.png"
