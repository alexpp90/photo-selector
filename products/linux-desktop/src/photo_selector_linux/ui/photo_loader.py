"""Asynchronous photo decoding into GDK textures with a bounded cache.

Decoding never runs on the GTK main loop: a full-size JPEG takes hundreds of milliseconds, and a
file on a GVFS cloud mount (Google Drive) must first be downloaded. Workers decode
``DisplaySource`` bytes into a ``GdkPixbuf`` (thread-safe), the main loop wraps it in a texture.

Adheres to:
- R-LINUX-META-01: Supported File Formats (RAW display through the embedded preview or LibRaw)
- REQ-LINUX-CACHE.01: Bounded In-Memory LRU Cache
"""

from __future__ import annotations

import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

try:
    import gi
    gi.require_version("Gdk", "4.0")
    gi.require_version("GdkPixbuf", "2.0")
    from gi.repository import Gdk, GdkPixbuf, GLib
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gdk = GdkPixbuf = GLib = None

from photo_selector_linux.core.cache import ThumbnailCache
from photo_selector_linux.core.gvfs_names import is_gvfs_fuse_path
from photo_selector_linux.core.image_source import (
    DisplaySource,
    decode_source,
    orientation_ops,
    read_display_source,
    read_full_resolution_source,
)
from photo_selector_linux.core.metadata import read_metadata
from photo_selector_linux.core.scoring import compute_quality_scores

# Longest edge decoded for fit-to-window display (covers 4K panels); 1:1 zoom decodes full size.
FIT_MAX_EDGE: int = 3840
# Photos are scored on a 1080p-class proxy of the displayed pixels (R-LINUX-SCORE-01), so a RAW
# preview, a developed RAW and a JPEG of the same scene land on the same scale.
SCORE_MAX_EDGE: int = 1920

TextureCallback = Callable[[Optional[object]], None]


def fit_size(width: int, height: int, max_edge: Optional[int]) -> Tuple[int, int]:
    """Scale (width, height) down so the longest edge is at most ``max_edge``; never upscale."""
    if not max_edge or width <= 0 or height <= 0 or max(width, height) <= max_edge:
        return width, height
    scale = max_edge / float(max(width, height))
    return max(1, round(width * scale)), max(1, round(height * scale))


def _pixbuf_from_rgb(data: bytes, size: Tuple[int, int], max_edge: Optional[int]):
    """Wrap packed upright RGB pixels in a ``GdkPixbuf``, downscaled to ``max_edge``."""
    width, height = size
    pixbuf = GdkPixbuf.Pixbuf.new_from_bytes(
        GLib.Bytes.new(data), GdkPixbuf.Colorspace.RGB, False, 8, width, height, width * 3
    )
    w, h = fit_size(width, height, max_edge)
    if (w, h) != (width, height):
        pixbuf = pixbuf.scale_simple(w, h, GdkPixbuf.InterpType.BILINEAR)
    return pixbuf


def decode_pixbuf(source: DisplaySource, max_edge: Optional[int] = None):
    """Decode a source into an upright ``GdkPixbuf``, downscaled during decode if asked."""
    if source.rgb_size is not None:
        # Developed RAW (LibRaw): packed upright RGB pixels, no encoding to parse.
        return _pixbuf_from_rgb(source.data, source.rgb_size, max_edge)
    loader = GdkPixbuf.PixbufLoader()
    if max_edge:
        def _on_size_prepared(ldr, width, height):
            w, h = fit_size(width, height, max_edge)
            if (w, h) != (width, height):
                ldr.set_size(w, h)
        loader.connect("size-prepared", _on_size_prepared)
    pixbuf = None
    try:
        loader.write(source.data)
    except GLib.Error:
        pass  # unknown format; close() below still releases the loader
    try:
        loader.close()
        pixbuf = loader.get_pixbuf()
    except GLib.Error:
        pass
    if pixbuf is None:
        # No GdkPixbuf loader for the format (WebP without webp-pixbuf-loader): decode with
        # Pillow, as scoring does, so display never depends on an optional loader package.
        image = decode_source(source)
        if image is None:
            return None
        return _pixbuf_from_rgb(image.tobytes(), image.size, max_edge)
    if source.orientation is None:
        return pixbuf.apply_embedded_orientation() or pixbuf
    flip, degrees = orientation_ops(source.orientation)
    if flip:
        pixbuf = pixbuf.flip(True)
    if degrees:
        pixbuf = pixbuf.rotate_simple(GdkPixbuf.PixbufRotation(degrees))
    return pixbuf


def pixbuf_to_rgb_array(pixbuf):
    """Packed (H, W, 3) uint8 copy of a pixbuf's RGB channels (rowstride padding and alpha dropped)."""
    import numpy as np

    width, height = pixbuf.get_width(), pixbuf.get_height()
    channels, stride = pixbuf.get_n_channels(), pixbuf.get_rowstride()
    raw = np.frombuffer(pixbuf.get_pixels(), dtype=np.uint8)
    rows = np.lib.stride_tricks.as_strided(raw, shape=(height, width * channels), strides=(stride, 1))
    return np.ascontiguousarray(rows.reshape(height, width, channels)[:, :, :3])


def score_pixbuf(pixbuf, blurry_threshold: float = 35.0, sharp_threshold: float = 70.0):
    """Focus and clipping scores of a decoded photo, or None."""
    w, h = fit_size(pixbuf.get_width(), pixbuf.get_height(), SCORE_MAX_EDGE)
    if (w, h) != (pixbuf.get_width(), pixbuf.get_height()):
        pixbuf = pixbuf.scale_simple(w, h, GdkPixbuf.InterpType.BILINEAR)
    return compute_quality_scores(
        pixbuf_to_rgb_array(pixbuf),
        blurry_threshold=blurry_threshold,
        sharp_threshold=sharp_threshold,
    )


class PhotoTextureLoader:
    """Loads candidate photos as textures off the main loop; callbacks run on the main loop."""

    def __init__(
        self,
        max_mb: float = 512,
        workers: int = 2,
        blurry_threshold: float = 35.0,
        sharp_threshold: float = 70.0,
    ) -> None:
        self._cache = ThumbnailCache(max_mb=max_mb)
        self._pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="photo-decode")
        self._pending: Dict[Tuple[Path, Optional[int]], List[TextureCallback]] = {}
        self._lock = threading.Lock()
        self.blurry_threshold: float = float(blurry_threshold)
        self.sharp_threshold: float = float(sharp_threshold)

    @property
    def cache(self) -> ThumbnailCache:
        return self._cache

    @staticmethod
    def _cache_key(candidate, full_size: bool = False) -> Tuple[Path, Optional[int]]:
        max_edge = None if full_size else FIT_MAX_EDGE
        path = candidate.primary_path if hasattr(candidate, "primary_path") else Path(candidate)
        return (path, max_edge)

    def set_limit_mb(self, max_mb: float) -> int:
        """Update cache ceiling in MB and evict excess entries immediately (R-LINUX-CACHE-01)."""
        return self._cache.set_limit_mb(max_mb)

    def evict_outside_window(self, keep_paths) -> int:
        """Evict cached entries whose path is outside keep_paths (R-LINUX-CACHE-03)."""
        paths = {
            p.primary_path if hasattr(p, "primary_path") else Path(p)
            for p in keep_paths
        }
        return self._cache.evict_outside_window(paths)

    def evict_full_resolution(self, keep_paths=None, keep_photos=None) -> int:
        """Evict full-resolution 1:1 textures outside keep_paths/keep_photos (R-LINUX-CACHE-03)."""
        items = keep_photos if keep_photos is not None else keep_paths
        if items is None:
            return self._cache.evict_full_resolution(None)
        paths = {
            p.primary_path if hasattr(p, "primary_path") else Path(p)
            for p in items
        }
        return self._cache.evict_full_resolution(paths)

    def request(self, candidate, callback: TextureCallback, full_size: bool = False) -> None:
        """Deliver the candidate's texture (or ``None`` if undecodable) to ``callback``."""
        key = self._cache_key(candidate, full_size=full_size)
        cached = self._cache.get(key)
        if cached is not None:
            callback(cached)
            return
        with self._lock:
            if key in self._pending:
                self._pending[key].append(callback)
                return
            self._pending[key] = [callback]
        self._pool.submit(self._work, key, candidate)

    def prefetch(self, candidates) -> None:
        """Warm the cache for a single candidate or an iterable of neighbouring candidates."""
        if hasattr(candidates, "primary_path"):
            self.request(candidates, lambda _texture: None)
            return
        for candidate in candidates:
            self.request(candidate, lambda _texture: None)

    def clear(self) -> None:
        self._cache.clear()

    def shutdown(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)
        self._cache.clear()

    def _work(self, key, candidate) -> None:
        path, max_edge = key
        pixbuf = None
        try:
            # On cloud mounts download once; every seek of a path-based parser is a round trip.
            data = path.read_bytes() if is_gvfs_fuse_path(path) else None
            if candidate.exif is None:
                candidate.exif = read_metadata(path, data)
            if max_edge is None:  # 1:1 zoom: full sensor resolution for RAW
                source = read_full_resolution_source(path, candidate.filename, data)
            else:
                source = read_display_source(path, candidate.filename, data)
            if source is not None:
                pixbuf = decode_pixbuf(source, max_edge)
            if pixbuf is not None and candidate.scores is None:
                candidate.scores = score_pixbuf(
                    pixbuf,
                    blurry_threshold=self.blurry_threshold,
                    sharp_threshold=self.sharp_threshold,
                )
        except Exception:
            pixbuf = None
        if GLib is not None:
            # Above the redraw priority (120) so animating spinners cannot starve it (LIN-4).
            GLib.idle_add(self._deliver, key, pixbuf, priority=GLib.PRIORITY_DEFAULT)

    def _deliver(self, key, pixbuf) -> bool:
        texture = None
        if pixbuf is not None:
            texture = Gdk.Texture.new_for_pixbuf(pixbuf)
            self._cache.put(key, texture, size_bytes=pixbuf.get_width() * pixbuf.get_height() * 4)
        with self._lock:
            callbacks = self._pending.pop(key, [])
        for callback in callbacks:
            try:
                callback(texture)
            except Exception:
                pass
        return False
