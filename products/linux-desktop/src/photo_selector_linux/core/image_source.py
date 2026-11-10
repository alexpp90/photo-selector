"""Display sources for candidate photographs, RAW included — the single decode entry.

Neither GdkPixbuf nor Pillow can decode camera RAW containers (``.ARW``, ``.NEF``, ``.CR3``, …)
on a stock Debian install, so a RAW file is shown through the largest JPEG preview its camera
embedded, rotated by the RAW file's own EXIF orientation (the preview carries none). When that
preview is smaller than ``min(1024 px, native long edge)`` — phone DNGs embed ~672 px previews, or
none in JPEG form — the file is developed at half size by LibRaw's ``dcraw_emu`` (Debian
``libraw-bin``), which is still larger than the display minimum for every supported camera.
Bitmap formats are passed through unchanged and keep their embedded orientation; HEIC/HEIF decode
through GdkPixbuf's ``heif-gdk-pixbuf`` loader (or ``pillow_heif`` when installed).

The contract (docs/shared/SUPPORTED_FORMATS.md) is checked by tests/unit/test_format_conformance.py.

Adheres to:
- R-LINUX-META-01: Supported File Formats (RAW display through the embedded preview)
"""

from __future__ import annotations

import io
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional, Tuple

from photo_selector_linux.core import metadata as _metadata
from photo_selector_linux.core.formats import RAW_EXTENSIONS, extension_of

# GExiv2 is loaded (namespace 0.10 or 0.16) and initialized once, by the metadata module.
HAS_GEXIV2 = _metadata._HAVE_GEXIV2
GExiv2 = _metadata.GExiv2 if HAS_GEXIV2 else None

# A display decode must reach min(MIN_DISPLAY_LONG_EDGE, native long edge).
MIN_DISPLAY_LONG_EDGE = 1024
RAW_DEVELOP_TIMEOUT_S = 60
# A preview this close to the sensor's long edge already is 1:1 (NEF 5568 of 5600 px).
FULL_SIZE_PREVIEW_RATIO = 0.9


@dataclass(frozen=True)
class DisplaySource:
    """Image bytes ready for a decoder, plus how to orient the decoded pixels.

    ``data`` is an encoded image (JPEG, PNG, HEIC, …) — or, when ``rgb_size`` is set, packed 8-bit
    RGB pixels of that (width, height), e.g. a LibRaw development. ``orientation`` is an EXIF
    orientation (1–8) to apply explicitly, or ``None`` when the bytes carry their own (or are
    already upright).
    """
    data: bytes
    orientation: Optional[int]
    is_raw_preview: bool
    rgb_size: Optional[Tuple[int, int]] = None


Developer = Callable[[Path, Optional[bytes], str], Optional[DisplaySource]]


def is_raw_name(name: str) -> bool:
    """True when ``name`` (a user-facing filename) has a camera RAW extension."""
    return extension_of(name) in RAW_EXTENSIONS


def orientation_ops(orientation: Optional[int]) -> Tuple[bool, int]:
    """EXIF orientation → (flip horizontally first, then rotate counter-clockwise by degrees).

    Unknown or missing values map to the identity.
    """
    return {
        1: (False, 0),
        2: (True, 0),
        3: (False, 180),
        4: (True, 180),
        5: (True, 90),
        6: (False, 270),
        7: (True, 270),
        8: (False, 90),
    }.get(orientation or 1, (False, 0))


def _raw_preview_and_native_edge(
    path: Path, data: Optional[bytes]
) -> Tuple[Optional[DisplaySource], int, int]:
    """(largest JPEG preview or None, its long edge, the native long edge or 0)."""
    if not HAS_GEXIV2:
        return None, 0, 0
    try:
        meta = GExiv2.Metadata()
        if data is not None:
            meta.open_buf(data)
        else:
            meta.open_path(str(path))
        try:
            native = max(int(meta.get_pixel_width()), int(meta.get_pixel_height()))
        except Exception:
            native = 0
        previews = [
            p for p in (meta.get_preview_properties() or [])
            if (p.get_mime_type() or "").lower() == "image/jpeg"
        ]
        if not previews:
            return None, 0, native
        best = max(previews, key=lambda p: p.get_width() * p.get_height())
        getter = getattr(meta, "try_get_preview_image", None) or meta.get_preview_image
        preview = getter(best).get_data()
        orientation_getter = getattr(meta, "try_get_orientation", None) or meta.get_orientation
        try:
            orientation = int(orientation_getter())
        except Exception:
            orientation = 1
    except Exception:
        return None, 0, 0
    if not preview:
        return None, 0, native
    source = DisplaySource(data=bytes(preview), orientation=orientation, is_raw_preview=True)
    return source, max(best.get_width(), best.get_height()), native


def read_raw_preview(path: Path, data: Optional[bytes] = None) -> Optional[DisplaySource]:
    """Extract the largest embedded JPEG preview of a RAW file, or ``None`` if it has none.

    ``data`` is the file's content when already in memory (parsed instead of reopening ``path``).
    """
    return _raw_preview_and_native_edge(path, data)[0]


def raw_developer_path() -> Optional[str]:
    """LibRaw's ``dcraw_emu`` (Debian ``libraw-bin``), when installed."""
    return shutil.which("dcraw_emu")


def develop_raw(
    path: Path,
    data: Optional[bytes] = None,
    name: str = "",
    runner: Callable[..., "subprocess.CompletedProcess[bytes]"] = subprocess.run,
    decoder: Optional[str] = None,
    half_size: bool = True,
) -> Optional[DisplaySource]:
    """Develop a RAW file with camera white balance; None when impossible.

    Half size (the default) is the display fallback for small previews; full size serves 1:1
    zoom, where the embedded preview (often ~1600 px for a 6000 px sensor) is not 100%.

    With ``data`` (a file already downloaded from a cloud mount) LibRaw reads a local temporary
    copy instead of seeking through the network path. LibRaw applies the EXIF orientation itself.
    """
    decoder = decoder or raw_developer_path()
    if decoder is None:
        return None
    try:
        from PIL import Image
    except ImportError:  # pragma: no cover — Pillow is a hard dependency
        return None
    tmp = None
    try:
        source_path = path
        if data is not None:
            suffix = "." + (extension_of(name or path.name) or "raw")
            tmp = tempfile.NamedTemporaryFile(prefix="pst-raw-", suffix=suffix, delete=False)
            tmp.write(data)
            tmp.close()
            source_path = Path(tmp.name)
        # -h half size, -w camera white balance, -Z - write the 8-bit PPM to stdout.
        size_args = ["-h"] if half_size else []
        proc = runner(
            [decoder, *size_args, "-w", "-Z", "-", str(source_path)],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=RAW_DEVELOP_TIMEOUT_S,
            check=False,
        )
        if proc.returncode != 0 or not proc.stdout:
            return None
        img = Image.open(io.BytesIO(proc.stdout))
        img.load()
        img = img.convert("RGB")
    except Exception:
        return None
    finally:
        if tmp is not None:
            Path(tmp.name).unlink(missing_ok=True)
    return DisplaySource(data=img.tobytes(), orientation=None, is_raw_preview=False, rgb_size=img.size)


def read_full_resolution_source(
    path: Path,
    name: Optional[str] = None,
    data: Optional[bytes] = None,
    developer: Optional[Developer] = None,
) -> Optional[DisplaySource]:
    """Bytes for 1:1 zoom: a RAW developed at full sensor resolution, else the display source.

    Many cameras (Nikon, Pentax, Canon CR2) embed a preview within a few pixels of the sensor
    size; it is used as is, since developing costs seconds. Without LibRaw a RAW falls back to its
    preview, so 1:1 then shows the preview's resolution.
    """
    name = name or path.name
    if is_raw_name(name):
        preview, preview_edge, native = _raw_preview_and_native_edge(path, data)
        if preview is not None and native and preview_edge >= FULL_SIZE_PREVIEW_RATIO * native:
            return preview
        develop = developer or (lambda p, d, n: develop_raw(p, d, n, half_size=False))
        developed = develop(path, data, name)
        if developed is not None:
            return developed
        if preview is not None:
            return preview
    return read_display_source(path, name, data)


def read_display_source(
    path: Path,
    name: Optional[str] = None,
    data: Optional[bytes] = None,
    developer: Optional[Developer] = None,
) -> Optional[DisplaySource]:
    """Bytes to display for ``path``; ``name`` is its user-facing filename.

    The name decides the format, because on GVFS cloud mounts the on-disk name is an opaque ID.
    ``data`` is the file's content when the caller has already read it. ``developer`` replaces
    the LibRaw development (tests).
    """
    name = name or path.name
    if is_raw_name(name):
        preview, preview_edge, native = _raw_preview_and_native_edge(path, data)
        required = min(MIN_DISPLAY_LONG_EDGE, native) if native else MIN_DISPLAY_LONG_EDGE
        if preview is not None and preview_edge >= required:
            return preview
        developed = (developer or develop_raw)(path, data, name)
        if developed is not None and (preview is None or max(developed.rgb_size or (0,)) > preview_edge):
            return developed
        # Best effort: a small preview beats no image (recognized formats, no LibRaw).
        return preview
    if data is None:
        try:
            data = path.read_bytes()
        except OSError:
            return None
    return DisplaySource(data=data, orientation=None, is_raw_preview=False)


# ── Decoding to Pillow (scoring, conformance tests) ─────────────────────────────────────


def _gdkpixbuf():
    try:
        import gi

        gi.require_version("GdkPixbuf", "2.0")
        from gi.repository import GdkPixbuf

        return GdkPixbuf
    except Exception:
        return None


def heif_decoder_available() -> bool:
    """True when HEIC/HEIF can be decoded: ``pillow_heif`` or a GdkPixbuf ``heif`` loader."""
    try:
        import pillow_heif  # type: ignore[import-not-found]  # noqa: F401

        return True
    except Exception:
        # best-effort: pillow_heif is an optional decoder; without it the GdkPixbuf loaders are asked.
        pass
    GdkPixbuf = _gdkpixbuf()
    if GdkPixbuf is None:
        return False
    try:
        for fmt in GdkPixbuf.Pixbuf.get_formats():
            names = {str(fmt.get_name()).lower()}
            names.update(str(m).lower() for m in (fmt.get_mime_types() or []))
            if names & {"heif", "heic", "image/heif", "image/heic"}:
                return True
    except Exception:
        # best-effort: a loader list that cannot be read answers "no HEIF decoder", which makes
        # the viewer say "Cannot display" rather than hide the file.
        pass
    return False


def _pil_from_encoded(data: bytes):
    from PIL import Image

    try:
        img = Image.open(io.BytesIO(data))
        img.load()
        return img
    except Exception:
        # best-effort: Pillow cannot read this format; the HEIF opener and GdkPixbuf are tried next.
        pass
    try:
        import pillow_heif  # type: ignore[import-not-found]

        pillow_heif.register_heif_opener()
        img = Image.open(io.BytesIO(data))
        img.load()
        return img
    except Exception:
        # best-effort: no HEIF support here either; GdkPixbuf is the last resort and a failure
        # there is reported by the caller as "Cannot display <name>".
        pass
    # GdkPixbuf loaders cover what Pillow lacks on Debian (heif-gdk-pixbuf).
    GdkPixbuf = _gdkpixbuf()
    if GdkPixbuf is None:
        return None
    try:
        loader = GdkPixbuf.PixbufLoader()
        try:
            loader.write(data)
        finally:
            loader.close()
        pixbuf = loader.get_pixbuf()
        pixbuf = pixbuf.apply_embedded_orientation() or pixbuf
        mode = "RGBA" if pixbuf.get_has_alpha() else "RGB"
        return Image.frombuffer(
            mode, (pixbuf.get_width(), pixbuf.get_height()), pixbuf.get_pixels(),
            "raw", mode, pixbuf.get_rowstride(), 1,
        )
    except Exception:
        return None


def decode_source(source: DisplaySource):
    """Decode a ``DisplaySource`` into an upright RGB Pillow image, or None."""
    try:
        from PIL import Image, ImageOps
    except ImportError:  # pragma: no cover
        return None
    if source.rgb_size is not None:
        return Image.frombytes("RGB", source.rgb_size, source.data)
    img = _pil_from_encoded(source.data)
    if img is None:
        return None
    if source.orientation is None:
        img = ImageOps.exif_transpose(img)
    else:
        flip, degrees = orientation_ops(source.orientation)
        if flip:
            img = img.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        if degrees:
            img = img.rotate(degrees, expand=True)
    return img.convert("RGB")


def decode_for_display(path: Path, name: Optional[str] = None):
    """Upright RGB Pillow image of what the canvas shows for ``path``, or None."""
    source = read_display_source(Path(path), name)
    return decode_source(source) if source is not None else None
