"""Prefetch that the view has left behind is dropped, not decoded (BL-P-1).

Holding the arrow key queues prefetches faster than two workers decode; without cancelling,
the queue keeps decoding photos the user has passed and the photo on screen waits behind them.

Adheres to:
- R-LINUX-CACHE-02: Asymmetric prefetch window
- R-LINUX-CULL-08: Folder Size Contract
- R-LINUX-CACHE-03: Memory reclaimed outside the window
"""

import threading
from pathlib import Path

from photo_selector_linux.core.models import CandidatePhoto
from photo_selector_linux.ui import photo_loader


def _loader(monkeypatch):
    """A loader whose decode blocks until released and records which photos it decoded."""
    release = threading.Event()
    decoded = []
    lock = threading.Lock()

    def work(self, key, candidate, generation=None):
        release.wait(timeout=10)
        with lock:
            decoded.append(key[0].name)

    monkeypatch.setattr(photo_loader.PhotoTextureLoader, "_work", work)
    return photo_loader.PhotoTextureLoader(workers=2), release, decoded


def _photos(count):
    return [CandidatePhoto(primary_path=Path(f"/scale/IMG_{i:05d}.JPG")) for i in range(count)]


def test_prefetch_outside_the_window_is_cancelled_while_queued(monkeypatch):
    """Scale: pr. 60 photos prefetched while the user keeps moving; only the window is decoded."""
    loader, release, decoded = _loader(monkeypatch)
    photos = _photos(60)
    try:
        for photo in photos:  # the window slid over every photo in turn; two workers are busy
            loader.prefetch(photo)
        window = photos[56:60]
        loader.evict_outside_window([p.primary_path for p in window])
        release.set()
        loader._pool.shutdown(wait=True)
    finally:
        release.set()
    names = set(decoded)
    assert {p.primary_path.name for p in window} <= names
    # The two running decodes cannot be stopped; every other queued one outside the window is.
    assert len(names) <= len(window) + 2, f"{len(names)} decodes for a window of {len(window)}"


def test_a_photo_someone_waits_for_is_not_cancelled(monkeypatch):
    loader, release, decoded = _loader(monkeypatch)
    photos = _photos(10)
    got = []
    try:
        for photo in photos:
            loader.prefetch(photo)
        loader.request(photos[5], got.append)  # the viewer asks for it: no longer speculative
        loader.evict_outside_window([photos[9].primary_path])
        release.set()
        loader._pool.shutdown(wait=True)
    finally:
        release.set()
    assert photos[5].primary_path.name in decoded
