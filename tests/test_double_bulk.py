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
    cfg = Config(data_dir=tmp_path / 'data', config_dir=tmp_path / 'config', scan_delay=0)
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


def test_host_manga_root_does_not_override_container_mount(tool, monkeypatch):
    from manga_manager.settings import Settings
    root = tool.manga_root
    monkeypatch.setenv('MANGA_ROOT', '/host/nas/manga')
    monkeypatch.setenv('DC_MANGA_ROOT', root)
    monkeypatch.setenv('KAVITA_MANGA_ROOT', '/manga')
    tool.ctx.db.settings = lambda: {}
    tool.ctx.settings = Settings(tool.ctx.db)
    data = tool.load('series', {})
    for series in data.values():
        for chapter in series['chapters']:
            for file in chapter['files']:
                file['filePath'] = '/manga/' + Path(file['filePath']).name
    tool.save('series', data)
    assert tool.manga_root == root
    plan = tool.bulk.preview()
    assert plan['chapterCount'] == 2 and plan['skipped'] == []
    tool.bulk.start(plan['fingerprint'])
    wait_finished(tool)
    assert tool.bulk.status()['moved'] == 3


def test_default_container_path_ignores_compose_host_variable(tool, monkeypatch):
    from manga_manager.settings import Settings
    monkeypatch.setenv('MANGA_ROOT', '/host/nas/manga')
    monkeypatch.delenv('DC_MANGA_ROOT', raising=False)
    tool.ctx.db.settings = lambda: {}
    tool.ctx.settings = Settings(tool.ctx.db)
    assert tool.manga_root == '/manga'


def test_legacy_saved_host_path_recovers_only_the_known_mount(tool, monkeypatch):
    host = '/host/legacy/manga'
    tool.ctx.settings = SimpleNamespace(get=lambda key, default=None: host if key == 'dc.manga_root' else default)
    monkeypatch.setenv('MANGA_ROOT', host)
    monkeypatch.setenv('DC_MANGA_ROOT', '/manga')
    real_isdir, real_exists = os.path.isdir, os.path.exists
    monkeypatch.setattr(os.path, 'isdir', lambda p: True if p == '/manga' else real_isdir(p))
    monkeypatch.setattr(os.path, 'exists', lambda p: False if p == host else real_exists(p))
    assert tool.manga_root == '/manga'
    # Existing explicitly configured folders must still win over defaults.
    monkeypatch.setattr(os.path, 'exists', lambda p: True if p == host else real_exists(p))
    assert tool.manga_root == host


def test_path_mapping_requires_a_full_directory_prefix(tool):
    original = tool.ctx.settings
    tool.ctx.settings = SimpleNamespace(get=lambda key, default=None: '/kavita' if key == 'dc.kavita_manga_root' else original.get(key, default))
    assert tool.is_safe_path(tool.kavita_path_to_local('/kavita/official-new.cbz'))
    assert not tool.is_safe_path(tool.kavita_path_to_local('/kavita-backup/official-new.cbz'))


def test_apply_honors_reviewed_file_selections(api, tool):
    plan = api.post('/api/double-chapters/bulk-preview').json
    choices = [{'seriesId': p['seriesId'], 'chapterId': p['chapterId'], 'keepFileId': p['keep']['id']} for p in plan['chapters']]
    choices[0]['keepFileId'] = 3  # Newest fan file, rather than the default official file.
    assert api.post('/api/double-chapters/bulk-apply', json={'token': plan['token'], 'selections': choices}).status_code == 202
    wait_finished(tool)
    assert (Path(tool.manga_root) / 'fan-newest.cbz').exists()
    assert not (Path(tool.manga_root) / 'official-new.cbz').exists()
    assert tool.bulk.status()['failed'] == 0


def test_unreviewed_file_choice_cannot_move_anything(api, tool):
    plan = api.post('/api/double-chapters/bulk-preview').json
    choices = [{'seriesId': p['seriesId'], 'chapterId': p['chapterId'], 'keepFileId': p['keep']['id']} for p in plan['chapters']]
    choices[0]['keepFileId'] = 9999
    assert api.post('/api/double-chapters/bulk-apply', json={'token': plan['token'], 'selections': choices}).status_code == 400
    assert not tool.trash_list()


def test_cover_cache_and_conditional_request_avoid_repeated_kavita_calls(api, tool):
    calls = []
    def cover(sid):
        calls.append(sid)
        return b'cover bytes', 'image/png'
    tool.client.get_cover = cover
    first = api.get('/api/double-chapters/cover/1')
    assert first.status_code == 200 and first.data == b'cover bytes'
    assert 'private' in first.headers['Cache-Control']
    again = api.get('/api/double-chapters/cover/1', headers={'If-None-Match': first.headers['ETag']})
    assert again.status_code == 304
    assert calls == [1]


# ---- review 2026-11-09: M1 (integrity of the file kept) and M3 (admin only) --------------------------
def _rewrite(tool, name, data, pages=None):
    series = tool.load('series', {})
    for s in series.values():
        for c in s['chapters']:
            for f in c['files']:
                if Path(f['filePath']).name == name:
                    Path(f['filePath']).write_bytes(data)
                    if pages is not None:
                        f['pages'] = pages
    tool.save('series', series)


def test_empty_file_is_never_kept(tool):
    _rewrite(tool, 'newest.cbz', b'')            # interrupted download: newest by mtime, 0 bytes
    preview = tool.bulk.preview()
    assert [p['chapterId'] for p in preview['chapters']] == [11]
    assert any(s['chapterId'] == 21 and 'empty' in s['reason'] for s in preview['skipped'])


def test_much_smaller_or_shorter_newest_copy_needs_review(tool):
    _rewrite(tool, 'newest.cbz', b'x' * 4)       # 4 bytes against 16: truncated
    skipped = {s['chapterId']: s['reason'] for s in tool.bulk.preview()['skipped']}
    assert 'smaller' in skipped[21]
    _rewrite(tool, 'newest.cbz', b'chapter contents', pages=1)   # same size, fewer pages than the copy
    skipped = {s['chapterId']: s['reason'] for s in tool.bulk.preview()['skipped']}
    assert 'fewer pages' in skipped[21]


def test_official_copy_is_trusted_unless_empty(tool):
    _rewrite(tool, 'official-new.cbz', b'x' * 4, pages=1)   # small official copy: still the one to keep
    plan = {p['chapterId']: p for p in tool.bulk.preview()['chapters']}
    assert plan[11]['keep']['id'] == 2
    _rewrite(tool, 'official-new.cbz', b'')
    assert 11 not in [p['chapterId'] for p in tool.bulk.preview()['chapters']]


def test_bulk_and_remediate_endpoints_are_admin_only():
    from manga_manager.web import ADMIN_ENDPOINTS
    app = Flask('x')
    names = {f'double_chapters.{n}' for n in ('bulk_preview', 'bulk_apply', 'remediate')}
    assert names <= ADMIN_ENDPOINTS
    # guard against a renamed view silently dropping out of the gate
    import inspect
    src = inspect.getsource(dc.init)
    for n in names:
        assert f'def {n.split(".")[1]}(' in src


# ---------------------------------------------------------------- 3.3.2: same scan queue as Move Manga
def _record_sleeps(monkeypatch):
    sleeps, real = [], time.sleep
    monkeypatch.setattr(time, 'sleep', lambda s: (sleeps.append(s), real(0.001))[1])
    return sleeps


def test_bulk_rescans_wait_for_idle_and_are_spaced(tool, monkeypatch):
    sleeps = _record_sleeps(monkeypatch)
    tool.ctx.cfg.scan_delay = 7
    events, busy = [], [True, False, False]
    tool.client.is_scan_busy = lambda: (events.append('busy?'), busy.pop(0) if busy else False)[1]
    tool.client.scan_series = lambda sid, lib: (events.append(f'scan {sid}'), True)[1]
    tool.bulk.start(tool.bulk.preview()['fingerprint'])
    wait_finished(tool)
    assert events == ['busy?', 'busy?', 'scan 1', 'busy?', 'scan 2']   # waits while busy, one scan at a time
    assert 15 in sleeps and sleeps.count(7) == 1                      # poll interval, then one gap between scans
    assert tool.bulk.status()['failed'] == 0


def test_web_rescan_waits_only_briefly(tool, monkeypatch):
    sleeps = _record_sleeps(monkeypatch)
    tool.client.is_scan_busy = lambda: True            # Kavita never goes idle
    scans = []
    tool.client.scan_series = lambda sid, lib: (scans.append(sid), True)[1]
    assert tool.scan_series(5, 1, max_wait=dc.WEB_SCAN_WAIT) is True
    assert scans == [5] and sum(sleeps) == dc.WEB_SCAN_WAIT


def test_double_chapters_client_uses_shared_busy_check():
    client = dc.KavitaClient('http://k', 'key')
    seen = []

    class R:
        def __init__(self, status, body=None):
            self.status_code, self.ok, self._b, self.text = status, status < 400, body, ''

        def json(self):
            return self._b

    def fake(method, path, **kw):
        seen.append(path)
        if path == '/api/Server/activity':
            return R(404)
        return R(200, kw['params']['methodName'] == 'ScanLibrary')
    client._request = fake
    assert client.is_scan_busy() is True
    client._request = lambda m, p, **kw: R(500) if p != '/api/Server/activity' else R(404)
    with pytest.raises(dc.KavitaError):                 # the tool's own error type
        client.is_scan_busy()
