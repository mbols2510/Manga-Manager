"""Discord cards: dashboard-style colours and rows, sent next to ntfy without touching it."""
from manga_manager.discord import GOLD, GREEN, RED, DiscordNotifier, build_embed, chapter_ranges
from manga_manager.notifier import NotifierGroup


def test_chapter_ranges():
    assert chapter_ranges(["95", "96", "90"]) == "90, 95–96"
    assert chapter_ranges(["12.5", "13"]) == "12.5, 13"
    assert chapter_ranges([]) == ""


def card(**kw):
    base = dict(title="T", is_new=False, category="Regular", chapters=["1"], replaced=[], duplicates=[])
    return build_embed(**{**base, **kw})


def test_bar_colours():
    assert card()["color"] == GREEN
    assert card(category="Adult")["color"] == RED and card(category="Hentai")["color"] == RED
    assert card(is_new=True)["color"] == GOLD and card(is_new=True, category="Adult")["color"] == GOLD


def test_rows_group_by_status_with_custom_emoji():
    e = card(chapters=["1", "2", "3", "9"], replaced=["9"], duplicates=["3"],
             icons={"new": "<:n:1>", "replaced": "<:r:2>", "duplicate": "<:d:3>"}, dashboard_url="https://dash",
             cover_url="https://dash/thumb/5.png")
    assert e["description"] == ("## [T](https://dash)\n<:n:1> **New** · Ch. 1–2\n"
                                "<:r:2> **Replaced** · Ch. 9\n<:d:3> **Duplicate** · Ch. 3")
    assert e["thumbnail"] == {"url": "https://dash/thumb/5.png"}


def test_header_marks_adult_and_new_series():
    assert "ADULT · 18+" in card(category="Adult")["author"]["name"]
    assert card(is_new=True)["author"]["name"].startswith("NEW SERIES · REGULAR")


class Capture:
    def __init__(self, status=204):
        self.calls, self.status = [], status

    def __call__(self, url, **kw):
        self.calls.append(kw)
        class R:
            ok = True
            status_code = self.status
            text = ""
        return R()


def test_send_posts_one_embed(monkeypatch):
    cap = Capture()
    monkeypatch.setattr("manga_manager.discord.requests.post", cap)
    ok = DiscordNotifier("https://hook", dashboard_url="https://dash", delay=0).send(
        title="T", is_new=False, category="Regular", chapters=["1"], suwayomi_id=5)
    assert ok and len(cap.calls) == 1
    call = cap.calls[0]
    assert call["json"]["embeds"][0]["thumbnail"]["url"] == "https://dash/thumb/5.png"


def test_disabled_without_webhook_sends_nothing(monkeypatch):
    cap = Capture()
    monkeypatch.setattr("manga_manager.discord.requests.post", cap)
    assert not DiscordNotifier("", delay=0).send(title="T", is_new=False, category="Regular", chapters=["1"])
    assert not DiscordNotifier("https://hook", enabled=False, delay=0).send(
        title="T", is_new=False, category="Regular", chapters=["1"])
    assert cap.calls == []


def test_group_keeps_going_when_one_notifier_breaks():
    class Boom:
        def send(self, **kw):
            raise RuntimeError("down")

    class Ok:
        sent = []

        def send(self, **kw):
            self.sent.append(kw)
            return True
    ok = Ok()
    assert NotifierGroup(Boom(), ok).send(title="T")
    assert ok.sent == [{"title": "T"}]
