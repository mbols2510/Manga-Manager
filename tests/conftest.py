import os

import pytest

from manga_manager.config import Config
from manga_manager.context import AppContext
from manga_manager.db import Database
from manga_manager.settings import Settings

TABLES = "updates, cover_uploads, runs, aliases, users, settings, kv, activity"


@pytest.fixture(scope="session")
def _db():
    url = os.environ.get("TEST_DATABASE_URL")
    if not url:
        pytest.skip("TEST_DATABASE_URL not set")
    db = Database.wait_for(url, timeout=30)
    db.init_schema()
    yield db
    db.close()


@pytest.fixture
def db(_db):
    with _db.connect() as c:
        c.execute(f"TRUNCATE {TABLES} RESTART IDENTITY")
    return _db


@pytest.fixture
def ctx(db, tmp_path):
    cfg = Config(data_dir=tmp_path / "data", config_dir=tmp_path / "config")
    return AppContext(cfg=cfg, db=db, settings=Settings(db))


_shared = {}


def shared_db():
    """A clean Database for tests that build their History inline (legacy test style)."""
    url = os.environ.get("TEST_DATABASE_URL")
    if not url:
        pytest.skip("TEST_DATABASE_URL not set")
    if "db" not in _shared:
        _shared["db"] = Database.wait_for(url, timeout=30)
        _shared["db"].init_schema()
    with _shared["db"].connect() as c:
        c.execute(f"TRUNCATE {TABLES} RESTART IDENTITY")
    return _shared["db"]
