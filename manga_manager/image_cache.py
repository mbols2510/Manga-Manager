"""Small disk cache with bounded upstream work for proxied Kavita covers."""
from __future__ import annotations

import hashlib
import os
import threading
import time
from pathlib import Path


class ImageBusy(RuntimeError):
    pass


class ImageCache:
    def __init__(self, directory: Path, ttl=3600, concurrency=2):
        self.directory = directory
        self.ttl = ttl
        self.slots = threading.BoundedSemaphore(concurrency)
        self.lock = threading.Lock()
        self.inflight = set()

    def get(self, key, load):
        path = self.directory / (hashlib.sha256(key.encode()).hexdigest() + '.cover')
        cached = None
        try:
            raw = path.read_bytes()
            mime, _, data = raw.partition(b'\n')
            if data and mime.startswith(b'image/'):
                cached = (data, mime.decode('ascii'))
                if time.time() - path.stat().st_mtime < self.ttl:
                    return cached
        except (OSError, UnicodeError):
            pass
        with self.lock:
            if key in self.inflight or not self.slots.acquire(blocking=False):
                if cached:
                    return cached
                raise ImageBusy('Cover queue is busy; retry shortly')
            self.inflight.add(key)
        try:
            try:
                data, mime = load()
            except Exception:
                if cached:
                    return cached
                raise
            mime = mime.split(';', 1)[0].strip().lower()
            if not mime.startswith('image/'):
                raise ValueError('Cover response was not an image')
            try:
                self.directory.mkdir(parents=True, exist_ok=True)
                tmp = path.with_suffix('.tmp')
                tmp.write_bytes(mime.encode('ascii') + b'\n' + data)
                os.replace(tmp, path)
            except OSError:
                pass  # A read-only/full cache must not prevent showing a fetched image.
            return data, mime
        finally:
            with self.lock:
                self.inflight.discard(key)
                self.slots.release()
