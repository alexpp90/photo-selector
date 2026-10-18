"""Supported file formats — the Linux Desktop side of the shared format contract.

The contract itself is ``testdata/formats/manifest.json`` (docs/shared/SUPPORTED_FORMATS.md);
``tests/unit/test_format_conformance.py`` fails when these sets drift from it.

Adheres to:
- R-LINUX-META-01: Supported File Formats
"""

from __future__ import annotations

from pathlib import Path
from typing import FrozenSet, Union

# Required RAW formats: discovered, paired, displayed, EXIF read and scored.
REQUIRED_RAW_EXTENSIONS: FrozenSet[str] = frozenset({
    "arw", "cr2", "cr3", "nef", "nrw", "raf", "rw2", "orf", "pef", "dng", "srw",
})

# Recognized RAW formats: discovered and paired as RAW; display is best effort.
RECOGNIZED_RAW_EXTENSIONS: FrozenSet[str] = frozenset({
    "3fr", "fff", "iiq", "rwl", "raw", "sr2", "srf", "mrw", "x3f", "erf", "mef", "mos",
})

RAW_EXTENSIONS: FrozenSet[str] = REQUIRED_RAW_EXTENSIONS | RECOGNIZED_RAW_EXTENSIONS

BITMAP_EXTENSIONS: FrozenSet[str] = frozenset({
    "jpg", "jpeg", "heic", "heif", "png", "tif", "tiff", "webp",
})

SUPPORTED_EXTENSIONS: FrozenSet[str] = RAW_EXTENSIONS | BITMAP_EXTENSIONS


def extension_of(path: Union[str, Path]) -> str:
    """Lower-case extension without the dot (``DSC0001.ARW`` -> ``arw``); '' when there is none."""
    name = Path(path).name
    if "." not in name.lstrip("."):
        return ""
    return name.rsplit(".", 1)[-1].lower()


def is_raw(path: Union[str, Path]) -> bool:
    return extension_of(path) in RAW_EXTENSIONS


def is_supported(path: Union[str, Path]) -> bool:
    return extension_of(path) in SUPPORTED_EXTENSIONS


# Single source for the .desktop ``MimeType=`` line (tests/unit/test_packaging.py keeps them equal).
# Types follow shared-mime-info; extensions it does not register use the conventional x- name.
MIME_TYPE_BY_EXTENSION = {
    "arw": "image/x-sony-arw", "cr2": "image/x-canon-cr2", "cr3": "image/x-canon-cr3",
    "nef": "image/x-nikon-nef", "nrw": "image/x-nikon-nrw", "raf": "image/x-fuji-raf",
    "rw2": "image/x-panasonic-rw2", "orf": "image/x-olympus-orf", "pef": "image/x-pentax-pef",
    "dng": "image/x-adobe-dng", "srw": "image/x-samsung-srw",
    "3fr": "image/x-hasselblad-3fr", "fff": "image/x-kde-raw", "iiq": "image/x-phaseone-iiq",
    "rwl": "image/x-leica-rwl", "raw": "image/x-panasonic-rw", "sr2": "image/x-sony-sr2",
    "srf": "image/x-sony-srf", "mrw": "image/x-minolta-mrw", "x3f": "image/x-sigma-x3f",
    "erf": "image/x-kde-raw", "mef": "image/x-mamiya-mef", "mos": "image/x-kde-raw",
    "jpg": "image/jpeg", "jpeg": "image/jpeg", "heic": "image/heif", "heif": "image/heif",
    "png": "image/png", "tif": "image/tiff", "tiff": "image/tiff", "webp": "image/webp",
}

MIME_TYPES: FrozenSet[str] = frozenset(MIME_TYPE_BY_EXTENSION.values())
