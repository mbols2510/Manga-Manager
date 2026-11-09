import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from manga_manager.image_cache import ImageBusy, ImageCache


def test_repeat_cover_is_served_from_disk(tmp_path):
    cache = ImageCache(tmp_path)
    calls = []
    def load():
        calls.append(1)
        return b'image bytes', 'image/png'
    assert cache.get('series:1', load) == (b'image bytes', 'image/png')
    assert ImageCache(tmp_path).get('series:1', load) == (b'image bytes', 'image/png')
    assert len(calls) == 1


def test_cover_work_is_bounded_and_busy_requests_do_not_block(tmp_path):
    cache = ImageCache(tmp_path, concurrency=2)
    entered = threading.Barrier(3)
    release = threading.Event()
    def load():
        entered.wait(timeout=5)
        assert release.wait(5)
        return b'image', 'image/png'
    with ThreadPoolExecutor(max_workers=2) as pool:
        jobs = [pool.submit(cache.get, str(i), load) for i in range(2)]
        entered.wait(timeout=5)
        try:
            started = time.monotonic()
            with pytest.raises(ImageBusy):
                cache.get('third', lambda: None)
            with pytest.raises(ImageBusy):
                cache.get('0', lambda: None)
            assert time.monotonic() - started < .5
        finally:
            release.set()
        assert all(job.result()[0] == b'image' for job in jobs)


def test_stale_cover_is_available_when_kavita_is_offline(tmp_path):
    cache = ImageCache(tmp_path, ttl=-1)
    cache.get('series', lambda: (b'cached cover', 'image/jpeg'))
    def offline():
        raise RuntimeError('Kavita unavailable')
    assert cache.get('series', offline) == (b'cached cover', 'image/jpeg')
