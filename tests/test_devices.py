import base64
import json

from manga_manager.devices import DeviceStore


def test_create_returns_token_once_and_stores_only_hash(db):
    store = DeviceStore(db)
    device_id, token = store.create("Pixel", "alice")
    assert len(token) >= 43
    with db.connect() as c:
        rows = c.execute("SELECT * FROM device_tokens").fetchall()
    assert len(rows) == 1 and rows[0]["id"] == device_id
    for value in rows[0].values():
        assert token not in str(value)


def test_authenticate_valid_updates_last_seen(db):
    store = DeviceStore(db)
    device_id, token = store.create("Pixel", "alice")
    assert store.list()[0]["last_seen"] is None
    assert store.authenticate(token) == {"id": device_id, "name": "Pixel", "user": "alice"}
    assert store.list()[0]["last_seen"] is not None


def test_authenticate_unknown_and_revoked_return_none(db):
    store = DeviceStore(db)
    device_id, token = store.create("Pixel", "alice")
    assert store.authenticate("nope") is None
    assert store.authenticate("") is None
    assert store.revoke(device_id) is True
    assert store.authenticate(token) is None
    assert store.revoke(device_id) is False
    assert store.revoke(9999) is False


def test_list_never_exposes_hash(db):
    store = DeviceStore(db)
    store.create("Pixel", "alice")
    rows = store.list()
    assert set(rows[0]) == {"id", "name", "user", "created_at", "last_seen", "revoked_at"}


def test_pairing_code_round_trips():
    code = DeviceStore.pairing_code("http://192.168.1.5:5000", "tok_en")
    assert code.startswith("mm1.")
    body = code[4:]
    assert "=" not in body
    data = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
    assert data == {"v": 1, "url": "http://192.168.1.5:5000", "token": "tok_en"}
