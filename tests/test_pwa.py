from types import SimpleNamespace

import pytest
from PIL import Image

from manga_manager.config import Config
from manga_manager.web import create_app


@pytest.fixture
def app(tmp_path):
    cfg = Config(data_dir=tmp_path, config_dir=tmp_path, auth_enabled=True)
    history = SimpleNamespace(db=None, aliases=lambda: {}, alias_ranks=lambda: {})
    return create_app(cfg, history, None, None)


def test_public_install_assets_without_session(app):
    c = app.test_client()
    manifest = c.get('/manifest.webmanifest')
    assert manifest.status_code == 200
    assert manifest.mimetype == 'application/manifest+json'
    assert manifest.json['start_url'] == manifest.json['scope'] == '/'
    assert manifest.json['display'] == 'standalone'
    for icon in manifest.json['icons']:
        response = c.get(icon['src'])
        from io import BytesIO
        image = Image.open(BytesIO(response.data))
        assert f'{image.width}x{image.height}' == icon['sizes']
    sw = c.get('/service-worker.js')
    assert sw.status_code == 200 and sw.mimetype == 'application/javascript'
    assert sw.headers['Cache-Control'] == 'no-store'
    assert c.get('/offline').status_code == 200
    assert c.get('/api/updates').status_code == 401


def test_manifest_respects_reverse_proxy_path(app):
    c = app.test_client()
    env = {'SCRIPT_NAME': '/manga'}
    manifest = c.get('/manifest.webmanifest', environ_overrides=env).json
    assert manifest['id'] == manifest['scope'] == manifest['start_url'] == '/manga/'
    assert all(icon['src'].startswith('/manga/static/') for icon in manifest['icons'])
    sw = c.get('/service-worker.js', environ_overrides=env).text
    assert '/manga/offline' in sw and '/manga/static/app.css' in sw
