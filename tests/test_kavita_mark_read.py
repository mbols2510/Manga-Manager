"""Chapters are marked read through /api/Reader/mark-multiple-read (Kavita has no mark-chapter-read route)."""
import pytest

from manga_manager.kavita import KavitaClient, KavitaError


class FakeResp:
    def __init__(self, status=200):
        self.status_code = status
        self.ok = status < 400
        self.text = ""


def make_client(status=200):
    client = KavitaClient("http://kavita.invalid", "key")
    calls = []

    def fake_request(method, path, **kwargs):
        calls.append((method, path, kwargs))
        return FakeResp(status)

    client._request = fake_request
    return client, calls


def test_mark_chapter_read_uses_mark_multiple_read():
    client, calls = make_client()
    client.mark_chapter_read(7, 42)
    [(method, path, kwargs)] = calls
    assert (method, path) == ("POST", "/api/Reader/mark-multiple-read")
    assert kwargs["json"] == {"seriesId": 7, "volumeIds": [], "chapterIds": [42]}
    assert "params" not in kwargs


def test_mark_chapter_read_raises_on_http_error():
    client, _ = make_client(400)
    with pytest.raises(KavitaError):
        client.mark_chapter_read(7, 42)


def test_double_chapters_client_uses_mark_multiple_read():
    from manga_manager.tools import double_chapters as dc

    cls = next(v for v in vars(dc).values()
               if isinstance(v, type) and "mark_chapter_read" in vars(v))
    obj = cls.__new__(cls)
    calls = []
    obj._request = lambda method, path, **kw: (calls.append((method, path, kw)), FakeResp(200))[1]
    assert obj.mark_chapter_read(7, 42) is True
    [(method, path, kwargs)] = calls
    assert (method, path) == ("POST", "/api/Reader/mark-multiple-read")
    assert kwargs["json"] == {"seriesId": 7, "volumeIds": [], "chapterIds": [42]}
