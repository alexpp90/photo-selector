"""Data models for Linux Desktop photographic culling and metadata engine.

Adheres to:
- R-LINUX-META-02: Standardized ExposureMetadata data class
- R-LINUX-META-03: Formatted Optical Exposure Strip & Zero-Placeholder Invariant
- R-LINUX-SCORE-03: Compressive Sigmoid Score Mapping & Categorization
- R-LINUX-SCORE-05: Metric Omission Rule
- R-LINUX-CULL-06: Transactional Copy-Undo Safety Invariant
"""

from __future__ import annotations

import math
import time
import uuid
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Dict, List, Optional, Tuple


class SharpnessCategory(str, Enum):
    """Standardized sharpness classification categories (R-LINUX-SCORE-03)."""
    BLURRY = "blurry"
    ACCEPTABLE = "acceptable"
    SHARP = "sharp"

    @classmethod
    def from_score(
        cls,
        score: float,
        blurry_threshold: float = 35.0,
        sharp_threshold: float = 70.0,
    ) -> SharpnessCategory:
        """Derive category from normalized 0.0–100.0 sharpness score."""
        eff_sharp = 70.0 if sharp_threshold >= 100.0 else float(sharp_threshold)
        eff_blurry = min(float(blurry_threshold), eff_sharp)
        if score < eff_blurry:
            return cls.BLURRY
        elif score < eff_sharp:
            return cls.ACCEPTABLE
        else:
            return cls.SHARP


class PhotoStatus(str, Enum):
    """Culling lifecycle status of a candidate photo."""
    CANDIDATE = "candidate"
    SELECTED = "selected"  # Moved to Selection/
    COPIED = "copied"      # Copied to Selection/
    TRASHED = "trashed"    # Sent to FreeDesktop Trash


class CullingActionType(str, Enum):
    """Type of culling disk operation."""
    MOVE = "move"
    COPY = "copy"
    TRASH = "trash"


@dataclass(frozen=True)
class ExposureMetadata:
    """Standardized optical EXIF metadata (R-LINUX-META-02)."""
    shutter_speed: Optional[float] = None
    aperture: Optional[float] = None
    iso: Optional[int] = None
    focal_length: Optional[float] = None
    lens: Optional[str] = None
    camera_make: Optional[str] = None
    camera_model: Optional[str] = None
    is_fallback: bool = False

    @property
    def formatted_shutter_speed(self) -> Optional[str]:
        """Format exposure duration.

        Guards against division-by-zero, non-finite values, and bulb mode (R-LINUX-META-03).
        """
        if self.shutter_speed is None:
            return None
        if not math.isfinite(self.shutter_speed) or self.shutter_speed <= 0.0:
            return None

        t = self.shutter_speed
        if 0.0 < t < 1.0:
            denominator = int(round(1.0 / t))
            return f"1/{denominator}s"
        elif t.is_integer():
            return f"{int(t)}s"
        else:
            return f"{t:.1f}s"

    @property
    def formatted_aperture(self) -> Optional[str]:
        """Format aperture as f-number (e.g. f/2.8)."""
        if self.aperture is None or not math.isfinite(self.aperture) or self.aperture <= 0.0:
            return None
        return f"f/{self.aperture:.1f}"

    @property
    def formatted_iso(self) -> Optional[str]:
        """Format ISO sensitivity (e.g. ISO 100)."""
        if self.iso is None or self.iso <= 0:
            return None
        return f"ISO {self.iso}"

    @property
    def formatted_focal_length(self) -> Optional[str]:
        """Format focal length in millimeters (e.g. 85mm)."""
        if self.focal_length is None or not math.isfinite(self.focal_length) or self.focal_length <= 0.0:
            return None
        return f"{int(round(self.focal_length))}mm"

    @property
    def formatted_summary(self) -> str:
        """Concise optical exposure strip (R-LINUX-META-03).

        Omits missing values completely without 'Unknown' or 'N/A' placeholders.
        Returns empty string if all optical parameters are missing.
        """
        parts: List[str] = []
        if self.formatted_shutter_speed:
            parts.append(self.formatted_shutter_speed)
        if self.formatted_aperture:
            parts.append(self.formatted_aperture)
        if self.formatted_iso:
            parts.append(self.formatted_iso)
        if self.formatted_focal_length:
            parts.append(self.formatted_focal_length)
        return " · ".join(parts)


@dataclass(frozen=True)
class QualityScores:
    """Photographic optical quality metrics (R-LINUX-SCORE-01 to R-LINUX-SCORE-04)."""
    sharpness: float
    noise: float
    highlight_clipping: float
    shadow_clipping: float
    raw_variance: Optional[float] = None
    category_override: Optional[SharpnessCategory] = field(default=None, repr=False, compare=False)
    blurry_threshold: float = field(default=35.0, repr=False, compare=False)
    sharp_threshold: float = field(default=70.0, repr=False, compare=False)
    focus_category: SharpnessCategory = field(init=False)

    def __post_init__(self):
        cat = self.category_override or SharpnessCategory.from_score(
            self.sharpness,
            blurry_threshold=self.blurry_threshold,
            sharp_threshold=self.sharp_threshold,
        )
        object.__setattr__(self, "focus_category", cat)

    @property
    def formatted_sharpness(self) -> str:
        return f"{self.sharpness:.1f}"

    @property
    def formatted_noise(self) -> str:
        return f"{self.noise:.1f}"

    @property
    def formatted_highlight_clipping(self) -> str:
        return f"{self.highlight_clipping:.1f}%"

    @property
    def formatted_shadow_clipping(self) -> str:
        return f"{self.shadow_clipping:.1f}%"


@dataclass
class CandidatePhoto:
    """Represents a primary candidate image and its bound companion files."""
    primary_path: Path
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    companion_paths: List[Path] = field(default_factory=list)
    exif: Optional[ExposureMetadata] = None
    scores: Optional[QualityScores] = None
    status: PhotoStatus = PhotoStatus.CANDIDATE
    _file_size: Optional[int] = field(default=None, repr=False)
    # Human-readable names for paths whose on-disk name differs, e.g. Google Drive mounted
    # through GVFS exposes files under opaque IDs ("1aW85w…") without an extension.
    display_names: Dict[Path, str] = field(default_factory=dict, repr=False)

    def name_of(self, path: Path) -> str:
        """User-facing filename of one of this candidate's files."""
        return self.display_names.get(path, path.name)

    @property
    def path(self) -> Path:
        """Primary path alias for candidate photo."""
        return self.primary_path

    @property
    def filename(self) -> str:
        return self.name_of(self.primary_path)

    @property
    def stem(self) -> str:
        return Path(self.filename).stem

    @property
    def extension(self) -> str:
        """Lower-case extension of the user-facing filename, without the dot."""
        return Path(self.filename).suffix.lstrip(".").lower()

    @property
    def all_paths(self) -> List[Path]:
        """All files associated with this candidate (primary + companions)."""
        return [self.primary_path] + [p for p in self.companion_paths if p.resolve() != self.primary_path.resolve()]

    @property
    def companion_path(self) -> Optional[Path]:
        """First non-primary companion path (e.g. JPEG pair or first companion)."""
        for p in self.companion_paths:
            if p.resolve() != self.primary_path.resolve():
                return p
        return None

    @property
    def sidecar_path(self) -> Optional[Path]:
        """Bound .xmp sidecar metadata file if present."""
        for p in self.companion_paths:
            if Path(self.name_of(p)).suffix.lower() == ".xmp":
                return p
        return None

    @property
    def file_size(self) -> int:
        """Byte size of the primary candidate file on disk."""
        if self._file_size is not None:
            return self._file_size
        try:
            self._file_size = self.primary_path.stat().st_size
            return self._file_size
        except OSError:
            return 0

    @file_size.setter
    def file_size(self, val: int) -> None:
        self._file_size = val


@dataclass
class CullingRecord:
    """Audit record capturing affected files for atomic undo (R-LINUX-CULL-06/07)."""
    photo_id: str
    action_type: CullingActionType
    original_primary_path: Path
    affected_paths: List[Tuple[Path, Path]]  # [(original_source, target_destination), ...]
    previous_status: PhotoStatus = PhotoStatus.CANDIDATE
    trash_info_paths: Dict[Path, Path] = field(default_factory=dict)  # {original_path: trashinfo_path}
    # Sources of a MOVE whose verified copy exists in `affected_paths` but which could not be
    # removed (read-only card). Empty for a complete operation; non-empty marks it partial.
    unremoved_sources: List[Path] = field(default_factory=list)
    # Destination members a failed rollback or cleanup left behind in `Selection/` (D-12): they are
    # in `affected_paths`, so undo moves them back (MOVE) or deletes them (COPY). Non-empty marks
    # the record partial; the operation itself did not complete.
    stranded: List[Path] = field(default_factory=list)
    timestamp: float = field(default_factory=time.time)
    id: str = field(default_factory=lambda: str(uuid.uuid4()))
