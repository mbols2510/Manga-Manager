import io
import zipfile
from pathlib import Path

import pytest
from flask import Flask

import manga_manager
from manga_manager.tools import cbz, cbz_comicinfo as ci

PKG = Path(manga_manager.__file__).parent


def make_cbz(name_info: dict | None = None, extra: dict | None = None) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("001.png", b"\x89PNG-fake")
        z.writestr("002.png", b"\x89PNG-fake2")
        if name_info is not None:
            body = "".join(f"<{k}>{v}</{k}>" for k, v in name_info.items())
            z.writestr("ComicInfo.xml", f'<?xml version="1.0"?><ComicInfo>{body}</ComicInfo>')
        for n, data in (extra or {}).items():
            z.writestr(n, data)
    return buf.getvalue()


@pytest.fixture
def client(ctx):
    app = Flask(__name__, template_folder=str(PKG / "templates"), static_folder=str(PKG / "static"))
    app.context_processor(lambda: {"has_endpoint": lambda n: n in app.view_functions})
    app.add_url_rule("/", "index", lambda: "home")
    app.add_url_rule("/logout", "logout", lambda: "bye", methods=["POST"])
    app.register_blueprint(cbz.init(ctx))
    return app.test_client()


def upload(client, *items):
    data = {"files": [(io.BytesIO(b), n) for n, b in items]}
    return client.post("/api/cbz/upload", data=data, content_type="multipart/form-data")


def files(client):
    return client.get("/api/cbz/files").get_json()


def read_xml(client, fid) -> dict:
    return ci.read_info(str(next((cbz_scratch(client) / fid).glob("*"))))["fields"]


def cbz_scratch(client):
    return Path(client.application.config["SCRATCH"])


@pytest.fixture(autouse=True)
def _scratch(client, ctx):
    client.application.config["SCRATCH"] = str(ctx.cfg.data_dir / "cbz-scratch")


def test_page_renders(client):
    r = client.get("/cbz")
    assert r.status_code == 200 and b"CBZ editor" in r.data


def test_upload_list_edit_download(client):
    r = upload(client, ("Series 2.cbz", make_cbz({"Series": "Old"})), ("Series 10.cbz", make_cbz()))
    assert r.status_code == 200
    res = r.get_json()
    assert all(x["id"] for x in res)
    listed = files(client)
    assert [f["name"] for f in listed] == ["Series 2.cbz", "Series 10.cbz"]   # natural order
    fid = listed[0]["id"]
    assert listed[0]["fields"]["Series"] == "Old" and listed[1]["has_info"] is False

    r = client.put(f"/api/cbz/files/{fid}", json={"fields": {"Series": "New & <Improved>", "Number": "3"}})
    assert r.status_code == 200
    assert read_xml(client, fid) == {"Series": "New & <Improved>", "Number": "3"}

    dl = client.get(f"/api/cbz/files/{fid}/download")
    assert dl.status_code == 200
    with zipfile.ZipFile(io.BytesIO(dl.data)) as z:
        assert sorted(z.namelist()) == ["001.png", "002.png", "ComicInfo.xml"]
        assert z.read("001.png") == b"\x89PNG-fake"
        assert b"&amp;" in z.read("ComicInfo.xml")

    z = client.post("/api/cbz/download", json={"ids": [x["id"] for x in listed]})
    with zipfile.ZipFile(io.BytesIO(z.data)) as zz:
        assert len(zz.namelist()) == 2


def test_clearing_field_and_unknown_field(client):
    fid = upload(client, ("a.cbz", make_cbz({"Series": "X", "Title": "T"}))).get_json()[0]["id"]
    assert client.put(f"/api/cbz/files/{fid}", json={"fields": {"Title": ""}}).status_code == 200
    assert read_xml(client, fid) == {"Series": "X"}
    assert client.put(f"/api/cbz/files/{fid}", json={"fields": {"Bogus": "1"}}).status_code == 400
    assert client.put(f"/api/cbz/files/{fid}", json={"nope": 1}).status_code == 400


def test_bulk_set_number_regex_with_preview(client):
    ups = upload(client, *[(f"Ch {i}.cbz", make_cbz({"Title": f"Vol. {i}"})) for i in (1, 2, 10)]).get_json()
    ids = [u["id"] for u in ups]
    ops = [{"type": "set", "field": "Series", "value": "Demo"},
           {"type": "number", "field": "Number", "start": "1", "step": "1", "pad": 2},
           {"type": "regex", "field": "Title", "find": r"^Vol\.\s*", "replace": "Volume "}]
    prev = client.post("/api/cbz/bulk", json={"ids": ids, "ops": ops, "preview": True}).get_json()
    assert [p["name"] for p in prev] == ["Ch 1.cbz", "Ch 2.cbz", "Ch 10.cbz"]
    assert {c["field"] for c in prev[0]["changes"]} == {"Series", "Number", "Title"}
    assert read_xml(client, ids[0]) == {"Title": "Vol. 1"}   # preview changed nothing
    assert client.post("/api/cbz/bulk", json={"ids": ids, "ops": ops}).status_code == 200
    by_name = {f["name"]: f["fields"] for f in files(client)}
    assert by_name["Ch 10.cbz"] == {"Title": "Volume 10", "Series": "Demo", "Number": "03"} or \
        by_name["Ch 10.cbz"]["Number"] == "03"
    assert by_name["Ch 1.cbz"]["Number"] == "01" and by_name["Ch 1.cbz"]["Title"] == "Volume 1"


@pytest.mark.parametrize("op", [
    {"type": "set", "field": "Nope", "value": "x"},
    {"type": "bogus", "field": "Series"},
    {"type": "regex", "field": "Series", "find": "("},
    {"type": "regex", "field": "Series", "find": ""},
    {"type": "number", "field": "Number", "start": "abc"},
    {"type": "number", "field": "Number", "pad": 99},
])
def test_bulk_rejects_bad_ops(client, op):
    fid = upload(client, ("a.cbz", make_cbz({"Series": "X"}))).get_json()[0]["id"]
    assert client.post("/api/cbz/bulk", json={"ids": [fid], "ops": [op]}).status_code == 400


def test_rejects_non_zip_and_traversal(client):
    res = upload(client, ("bad.cbz", b"not a zip")).get_json()
    assert res[0]["error"] == "Not a valid CBZ/zip file"
    slip = make_cbz({"Series": "x"}, extra={"../../evil.txt": b"x"})
    res = upload(client, ("slip.cbz", slip)).get_json()
    assert "Unsafe path" in res[0]["error"]
    assert files(client) == []


@pytest.mark.parametrize("name", ["/abs", "../x", "a/../../b", "C:/x", "a\\..\\b", ""])
def test_unsafe_name_detection(name):
    assert ci.unsafe_name(name)


def test_safe_names_allowed():
    assert not ci.unsafe_name("dir/001.png") and not ci.unsafe_name("..hidden.png")


def test_filename_sanitised(client):
    res = upload(client, ("../../etc/pass<wd>.cbz", make_cbz())).get_json()
    assert res[0]["name"] == "pass_wd_.cbz"
    fid = res[0]["id"]
    assert (cbz_scratch(client) / fid / "pass_wd_.cbz").exists()


def test_xml_entities_rejected(client):
    evil = make_cbz()
    buf = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(evil)) as zin, zipfile.ZipFile(buf, "w") as z:
        for i in zin.infolist():
            z.writestr(i, zin.read(i))
        z.writestr("ComicInfo.xml", '<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><ComicInfo><Title>&a;</Title></ComicInfo>')
    res = upload(client, ("e.cbz", buf.getvalue())).get_json()
    assert res[0]["error"] and "invalid" in res[0]["error"]
    assert client.post("/api/cbz/bulk", json={"ids": [res[0]["id"]], "ops": []}).status_code in (200, 400)


def test_zip_bomb_guards(monkeypatch):
    monkeypatch.setattr(ci, "MAX_UNCOMPRESSED_BYTES", 10)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("a.png", b"x" * 100)
    with zipfile.ZipFile(buf) as zf, pytest.raises(ci.UnsafeArchive):
        ci.check_archive(zf)


def test_oversized_comicinfo_rejected(tmp_path):
    p = tmp_path / "big.cbz"
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("ComicInfo.xml", "<ComicInfo>" + "a" * (ci.MAX_COMICINFO_BYTES + 10) + "</ComicInfo>")
    assert "unreasonably large" in ci.read_info(str(p))["error"]


def test_upload_limit(client, ctx):
    ctx.settings.db.set_setting("cbz.max_upload_mb", 1)
    ctx.settings.reload()
    big = make_cbz(extra={"big.bin": b"\0" * (3 * 1024 * 1024)})
    r = upload(client, ("big.cbz", big))
    assert r.status_code == 413 or r.get_json()[0]["error"].startswith("Over the")
    assert files(client) == []


def test_unknown_and_bad_ids(client):
    assert client.get("/api/cbz/files/nothex/download").status_code == 404
    assert client.get("/api/cbz/files/" + "0" * 32 + "/download").status_code == 404
    assert client.delete("/api/cbz/files/../x").status_code in (404, 405)


def test_remove_one_and_all(client):
    ups = upload(client, ("a.cbz", make_cbz()), ("b.cbz", make_cbz())).get_json()
    assert client.delete(f"/api/cbz/files/{ups[0]['id']}").status_code == 200
    assert len(files(client)) == 1
    assert client.delete("/api/cbz/files").status_code == 200
    assert files(client) == []


def test_original_entries_preserved_on_rewrite(client):
    fid = upload(client, ("a.cbz", make_cbz({"Series": "X"}, extra={"sub/p3.png": b"3"}))).get_json()[0]["id"]
    client.put(f"/api/cbz/files/{fid}", json={"fields": {"Series": "Y"}})
    p = next((cbz_scratch(client) / fid).glob("*"))
    with zipfile.ZipFile(p) as z:
        assert z.testzip() is None and z.read("sub/p3.png") == b"3"
