import copy
import os
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest
from flask import Flask
from itsdangerous import URLSafeTimedSerializer

from manga_manager.config import Config
from manga_manager.context import AppContext
from manga_manager.tools import double_chapters as dc
from manga_manager.tools.double_bulk import BulkConflict


class MemoryDB:
    def __init__(self):
        self.data = {}

    def kv_get(self, ns, key, default):
        return copy.deepcopy(self.data.get((ns, key), default))

    def kv_set(self, ns, key, value):
        self.data[ns, key] = copy.deepcopy(value)

    def log_activity(self, *args):
        pass


class Client:
    def __init__(self):
        self.scans = []

    def scan_series(self, sid, library):
        self.scans.append(sid)
        return True

    def get_chapter(self, cid):
        return {"pages": 2, "pagesRead": 0}


@pytest.fixture
def tool(tmp_path):
    root = tmp_path / 'manga'
    root.mkdir()
    settings = {'dc.manga_root': str(root), 'dc.trash_retention_days': 7}
    cfg = Config(data_dir=tmp_path / 'data', config_dir=tmp_path / 'config')
    ctx = AppContext(cfg, MemoryDB(), SimpleNamespace(get=lambda key, default=None: settings.get(key, default)))
    tool = dc.DoubleChapters(ctx)
    tool._client = Client()
    tool._client_key = (cfg.kavita_url, cfg.kavita_api_key)
    def file(fid, name, mtime):
        path = root / name
        path.write_bytes(b'chapter contents')
        os.utime(path, (mtime, mtime))
        return {'id': fid, 'filePath': str(path), 'bytes': 16, 'pages': 2}
    tool.save('series', {
        '1': {'id': 1, 'name': 'First manga', 'libraryId': 3, 'chapters': [
            {'id': 11, 'number': '1', 'files': [file(1, 'official-old.cbz', 1000),
                file(2, 'official-new.cbz', 2000), file(3, 'fan-newest.cbz', 3000)]}]},
        '2': {'id': 2, 'name': 'Second manga', 'libraryId': 3, 'chapters': [
            {'id': 21, 'number': '2', 'files': [file(4, 'unofficial.cbz', 2000), file(5, 'newest.cbz', 3000)]}]}})
    tool.update_meta(lastScan='2026-11-09T12:00:00Z')
    return tool


def wait_finished(tool):
    deadline = time.monotonic() + 5
    while tool.bulk.running and time.monotonic() < deadline:
        time.sleep(.01)
    assert not tool.bulk.running


@pytest.fixture
def api(tool, monkeypatch):
    app = Flask('manga_manager.web')
    app.secret_key = 'bulk-test-secret'
    monkeypatch.setattr(dc, 'DoubleChapters', lambda ctx: tool)
    app.register_blueprint(dc.init(tool.ctx))
    return app.test_client()


def test_preview_is_read_only_and_defaults_match_current_ui(tool):
    before = tool.load('series', {})
    preview = tool.bulk.preview()
    assert [p['keep']['id'] for p in preview['chapters']] == [2, 5]
    assert preview['seriesCount'] == 2 and preview['chapterCount'] == 2 and preview['fileCount'] == 3
    assert tool.load('series', {}) == before
    assert tool.trash_list() == []
    assert len(list(Path(tool.manga_root).glob('*.cbz'))) == 5


def test_apply_moves_only_reviewed_files_and_rescans_each_series_once(tool):
    preview = tool.bulk.preview()
    tool.bulk.start(preview['fingerprint'])
    wait_finished(tool)
    assert tool.bulk.status()['moved'] == 3 and tool.bulk.status()['failed'] == 0
    assert set(p.name for p in Path(tool.manga_root).glob('*.cbz')) == {'official-new.cbz', 'newest.cbz'}
    assert len(tool.trash_list()) == 3
    assert tool.client.scans == [1, 2]
    assert tool.load('series', {}) == {}


@pytest.mark.parametrize('change', ['replace', 'remove', 'rescan'])
def test_stale_preview_rejected_before_any_move(tool, change):
    preview = tool.bulk.preview()
    path = Path(tool.manga_root) / 'official-new.cbz'
    if change == 'replace':
        path.write_bytes(b'changed chapter')
    elif change == 'remove':
        path.unlink()
    else:
        tool.update_meta(lastScan='later scan')
    with pytest.raises(BulkConflict, match='changed'):
        tool.bulk.start(preview['fingerprint'])
    assert tool.trash_list() == []


def test_missing_unsafe_and_shared_files_are_skipped(tool, tmp_path):
    data = tool.load('series', {})
    data['3'] = copy.deepcopy(data['2'])
    data['3'].update(id=3, name='Shared manga')
    tool.save('series', data)
    preview = tool.bulk.preview()
    assert preview['chapterCount'] == 1 and len(preview['skipped']) == 2
    (Path(tool.manga_root) / 'official-new.cbz').unlink()
    outside = tmp_path / 'outside.cbz'
    outside.write_bytes(b'outside')
    data['2']['chapters'][0]['files'][0]['filePath'] = str(outside)
    tool.save('series', data)
    preview = tool.bulk.preview()
    assert len(preview['skipped']) == 3
    assert outside.exists() and not tool.trash_list()


def test_missing_file_to_keep_never_trashes_alternatives(tool):
    (Path(tool.manga_root) / 'official-new.cbz').unlink()
    with pytest.raises(ValueError, match='file to keep is missing'):
        tool.remediate_chapter(1, 11, 2)
    assert (Path(tool.manga_root) / 'fan-newest.cbz').exists()
    assert not tool.trash_list()


def test_partial_failure_preserves_trash_records_and_continues(tool, monkeypatch):
    real_move = dc.shutil.move
    calls = []
    def move(src, dest):
        calls.append(src)
        if len(calls) == 2:
            raise OSError('Simulated unavailable NAS')
        return real_move(src, dest)
    monkeypatch.setattr(dc.shutil, 'move', move)
    tool.bulk.start(tool.bulk.preview()['fingerprint'])
    wait_finished(tool)
    result = tool.bulk.status()
    assert result['failed'] == 1 and result['moved'] == 2 and result['done'] == 2
    assert len(tool.trash_list()) == 2 and len(tool.client.scans) == 2
    assert all(Path(entry['trashPath']).exists() for entry in tool.trash_list())


def test_preview_token_required_signed_expiring_and_bound_to_user(api, tool, monkeypatch):
    assert api.post('/api/double-chapters/bulk-apply', json={}).status_code == 400
    assert api.post('/api/double-chapters/bulk-apply', json={'token': 'forged'}).status_code == 400
    plan = api.post('/api/double-chapters/bulk-preview').json
    with api.session_transaction() as s:
        s['user'] = 'another-user'
    assert api.post('/api/double-chapters/bulk-apply', json={'token': plan['token']}).status_code == 403
    with api.session_transaction() as s:
        s.clear()
    signer = URLSafeTimedSerializer('bulk-test-secret', salt='double-chapters-bulk-preview')
    monkeypatch.setattr('itsdangerous.timed.TimestampSigner.get_timestamp', lambda self: 1)
    expired = signer.dumps({'fingerprint': tool.bulk.preview()['fingerprint'], 'user': None})
    monkeypatch.undo()
    assert api.post('/api/double-chapters/bulk-apply', json={'token': expired}).status_code == 409
    assert tool.trash_list() == []


def test_running_batch_blocks_other_writes_and_survives_browser_polling(api, tool):
    entered, release = threading.Event(), threading.Event()
    def scan(sid, lib):
        entered.set()
        assert release.wait(5)
        return True
    tool.client.scan_series = scan
    plan = api.post('/api/double-chapters/bulk-preview').json
    response = api.post('/api/double-chapters/bulk-apply', json={'token': plan['token']})
    assert response.status_code == 202 and entered.wait(5)
    try:
        assert api.get('/api/double-chapters/bulk-status').json['running']
        assert api.post('/api/double-chapters/scan').status_code == 409
        assert api.post('/api/double-chapters/bulk-apply', json={'token': plan['token']}).status_code == 409
        assert api.post('/api/double-chapters/trash/purge-all').status_code == 409
        with pytest.raises(BulkConflict):
            tool.bulk.preview()
        tool.run_scan()  # No scan can overwrite the in-progress result.
    finally:
        release.set()
        wait_finished(tool)
    assert api.get('/api/double-chapters/bulk-status').json['moved'] == 3


def test_pipeline_lock_prevents_bulk_moves(tool):
    tool.ctx.extras['scheduler'] = SimpleNamespace(run_exclusive=lambda work: (False, None))
    tool.bulk.start(tool.bulk.preview()['fingerprint'])
    wait_finished(tool)
    assert not tool.trash_list()
    assert 'Move Manga is moving files' in tool.bulk.status()['errors'][0]


def test_bulk_endpoints_require_login_and_reject_cross_site_requests(tool):
    from manga_manager.web import create_app
    tool.ctx.cfg.auth_enabled = True
    history = SimpleNamespace(db=None, aliases=lambda: {}, alias_ranks=lambda: {})
    app = create_app(tool.ctx.cfg, history, None, None)
    c = app.test_client()
    for path in ('bulk-preview', 'bulk-apply'):
        assert c.post('/api/double-chapters/' + path).status_code == 401
        assert c.post('/api/double-chapters/' + path, headers={'Origin': 'https://another.example'}).status_code == 403
