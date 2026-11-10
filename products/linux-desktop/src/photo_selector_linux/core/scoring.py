"""Focus quality scoring and exposure clipping engine in pure NumPy.

Adheres to:
- R-LINUX-SCORE-01: Vectorized Laplacian Focus Convolution & 3x3 Gaussian Pre-filtering
- R-LINUX-SCORE-02: Median Absolute Deviation (MAD) Noise Floor Subtraction
- R-LINUX-SCORE-03: Compressive Sigmoid Score Mapping & Categorization
- R-LINUX-SCORE-04: Exposure Histogram Clipping Analysis
- R-LINUX-SCORE-06: Compute Latency SLA (< 2.0 ms on 1080p via 50% ROI & Strided MAD)
"""

from __future__ import annotations

import os
from pathlib import Path
import threading
from typing import Any, Dict, Optional, Tuple
import numpy as np

from photo_selector_linux.core.models import QualityScores, SharpnessCategory

SAMPLE_LIMIT: int = 2048


class _ScoringWorkspace:
    """Thread-local pre-allocated workspace buffers to eliminate heap allocation churn during focus scoring."""

    def __init__(self, th: int, tw: int) -> None:
        self.shape: Tuple[int, int] = (th, tw)
        self.roi: np.ndarray = np.empty((th, tw, 3), dtype=np.uint8)
        self.target: np.ndarray = np.empty((th, tw), dtype=np.float32)
        self.scratch: np.ndarray = np.empty((th, tw), dtype=np.float32)
        self.gv: np.ndarray = np.empty((th - 2, tw), dtype=np.float32)
        self.smoothed: np.ndarray = np.empty((th - 2, tw - 2), dtype=np.float32)
        self.lap: np.ndarray = np.empty((th - 4, tw - 4), dtype=np.float32)
        self.c4_scratch: np.ndarray = np.empty((th - 4, tw - 4), dtype=np.float32)


_THREAD_LOCAL = threading.local()


def _get_workspace(th: int, tw: int) -> _ScoringWorkspace:
    """Retrieve or allocate thread-local scoring workspace for the target ROI dimensions."""
    ws = getattr(_THREAD_LOCAL, "workspace", None)
    if ws is None or ws.shape != (th, tw):
        ws = _ScoringWorkspace(th, tw)
        _THREAD_LOCAL.workspace = ws
    return ws


C_RED: np.float32 = np.float32(0.299)
C_GREEN: np.float32 = np.float32(0.587)
C_BLUE: np.float32 = np.float32(0.114)


def evaluate_focus_score(
    image: np.ndarray,
    crop_to_center: bool = True,
) -> Optional[Dict[str, Any]]:
    """Compute noise-corrected Laplacian focus score in pure NumPy.

    Args:
        image: 2D (H, W) or 3D (H, W, C) numpy array.
        crop_to_center: If True, evaluates center 50% ROI ([0.25H:0.75H, 0.25W:0.75W]).

    Returns:
        Dict with score (0.0-100.0), raw_variance, noise_sigma, corrected_variance, category,
        or None if image is empty or invalid.
    """
    if image is None or image.ndim < 2:
        return None

    h, w = image.shape[:2]
    if h < 5 or w < 5:
        return None

    # 1. Center 50% ROI crop: [0.25H : 0.75H, 0.25W : 0.75W]
    if crop_to_center:
        y1, y2 = h // 4, 3 * h // 4
        x1, x2 = w // 4, 3 * w // 4
        th, tw = y2 - y1, x2 - x1
        if th < 5 or tw < 5:
            return None
        ws = _get_workspace(th, tw)
        if image.ndim == 3 and image.shape[2] >= 3 and image.dtype == np.uint8:
            np.copyto(ws.roi, image[y1:y2, x1:x2])
            roi = ws.roi
        else:
            roi = np.ascontiguousarray(image[y1:y2, x1:x2])
    else:
        th, tw = h, w
        if th < 5 or tw < 5:
            return None
        ws = _get_workspace(th, tw)
        if image.ndim == 3 and image.shape[2] >= 3 and image.dtype == np.uint8:
            if image.flags.c_contiguous:
                roi = image
            else:
                np.copyto(ws.roi, image)
                roi = ws.roi
        else:
            roi = np.ascontiguousarray(image)

    # 2. Grayscale luminance conversion (BT.601) on cropped ROI
    target = ws.target
    if roi.ndim == 3:
        if roi.shape[2] >= 3:
            # Zero-allocation in-place linear combination (BT.601) in float32 (< 0.58 ms)
            np.multiply(roi[:, :, 0], C_RED, out=target)
            np.multiply(roi[:, :, 1], C_GREEN, out=ws.scratch)
            target += ws.scratch
            np.multiply(roi[:, :, 2], C_BLUE, out=ws.scratch)
            target += ws.scratch
        elif roi.shape[2] > 0:
            np.copyto(target, roi[..., 0])
        else:
            return None
    elif roi.ndim == 2:
        np.copyto(target, roi)
    else:
        return None

    # 3. Separable 3x3 Gaussian smoothing pre-filter: [1, 2, 1] / 4
    # Zero-allocation factored integer kernel passes (scaled by 16)
    gv = ws.gv
    np.add(target[:-2, :], target[2:, :], out=gv)
    m_v = target[1:-1, :]
    gv += m_v
    gv += m_v

    smoothed = ws.smoothed
    np.add(gv[:, :-2], gv[:, 2:], out=smoothed)
    m_h = gv[:, 1:-1]
    smoothed += m_h
    smoothed += m_h

    # 4. 3x3 Discrete 4-connected Laplacian convolution
    # [[ 0,  1,  0],
    #  [ 1, -4,  1],
    #  [ 0,  1,  0]]
    lap = ws.lap
    np.add(smoothed[:-2, 1:-1], smoothed[2:, 1:-1], out=lap)
    lap += smoothed[1:-1, :-2]
    lap += smoothed[1:-1, 2:]
    c = smoothed[1:-1, 1:-1]
    np.multiply(c, 4.0, out=ws.c4_scratch)
    lap -= ws.c4_scratch
    if lap.size == 0:
        return None

    # 5. Raw spatial variance via BLAS dot product: Var(X) = E[X^2] - (E[X])^2
    # Rescaling variance by 16^2 = 256 to account for factored Gaussian kernel
    f = lap.ravel()
    n = f.size
    mean = float(np.sum(f) / n)
    raw_variance = float(max(0.0, float(np.dot(f, f) / n - mean * mean))) / 256.0

    # 6. Strided low-discrepancy MAD noise floor sampling (<= 2048 samples)
    sample_count = min(SAMPLE_LIMIT, n)
    step = max(1, n // sample_count)
    samples = f[::step][:sample_count]

    med = float(np.median(samples))
    mad = float(np.median(np.abs(samples - med)))
    noise_sigma = float(1.4826 * mad) / 16.0

    # 7. Noise floor subtraction strictly adhering to R-LINUX-SCORE-02
    noise_floor = noise_sigma ** 2
    corrected_variance = float(max(0.0, raw_variance - noise_floor))

    # 8. Compressive exponential saturation mapping
    if corrected_variance <= 0.0:
        score = 0.0
    else:
        mapped = 100.0 * (1.0 - np.exp(-np.sqrt(corrected_variance) / 12.0))
        score = float(min(100.0, max(0.0, mapped)))

    score = round(score, 1)

    # 9. Categorization
    category = SharpnessCategory.from_score(score).value

    return {
        "score": score,
        "raw_variance": round(raw_variance, 1),
        "noise_sigma": round(noise_sigma, 1),
        "corrected_variance": round(corrected_variance, 1),
        "category": category,
    }


def evaluate_exposure_clipping(
    image: np.ndarray,
    highlight_min: int = 254,
    shadow_max: int = 2,
) -> Dict[str, float]:
    """Compute highlight and shadow clipping percentages over 256-bin luminance histogram (R-LINUX-SCORE-04).

    Highlights: luminance >= 254
    Shadows: luminance <= 2
    """
    if image is None or image.size == 0 or image.ndim < 2:
        return {"highlight_clipping": 0.0, "shadow_clipping": 0.0}

    if image.ndim == 3:
        if image.shape[2] >= 3:
            lum = (
                0.299 * image[..., 0]
                + 0.587 * image[..., 1]
                + 0.114 * image[..., 2]
            ).astype(np.uint8)
        elif image.shape[2] > 0:
            lum = image[..., 0].astype(np.uint8)
        else:
            return {"highlight_clipping": 0.0, "shadow_clipping": 0.0}
    elif image.ndim == 2:
        lum = image.astype(np.uint8)
    else:
        return {"highlight_clipping": 0.0, "shadow_clipping": 0.0}

    total_pixels = lum.size
    if total_pixels == 0:
        return {"highlight_clipping": 0.0, "shadow_clipping": 0.0}

    hist = np.bincount(lum.ravel(), minlength=256)

    # Highlights: luminance >= highlight_min (bins 254..255 by default)
    highlight_count = int(np.sum(hist[highlight_min:]))
    # Shadows: luminance <= shadow_max (bins 0..2 by default)
    shadow_count = int(np.sum(hist[: shadow_max + 1]))

    hl_pct = round((highlight_count / total_pixels) * 100.0, 2)
    sh_pct = round((shadow_count / total_pixels) * 100.0, 2)

    return {
        "highlight_clipping": hl_pct,
        "shadow_clipping": sh_pct,
    }


def _load_image_array(path_or_image: Any) -> Optional[np.ndarray]:
    """Load an image file path into an RGB uint8 NumPy array, or return ndarray directly.

    Files decode through ``image_source`` like the canvas, so RAW (embedded preview or LibRaw development) and
    HEIC files score the same pixels the user sees.
    """
    if isinstance(path_or_image, np.ndarray):
        return path_or_image
    if isinstance(path_or_image, (str, os.PathLike)):
        p = Path(path_or_image)
        if not p.is_file():
            return None
        try:
            from photo_selector_linux.core.image_source import decode_for_display

            rgb = decode_for_display(p)
            if rgb is None:
                return None
            w, h = rgb.size
            if max(w, h) > 2048:
                ratio = 2048.0 / max(w, h)
                rgb = rgb.resize((max(16, int(w * ratio)), max(16, int(h * ratio))))
            elif w < 10 or h < 10:
                rgb = rgb.resize((max(16, w), max(16, h)))
            return np.asarray(rgb, dtype=np.uint8)
        except Exception:
            return None
    return None


def compute_quality_scores(
    image: Any,
    crop_to_center: bool = True,
    sharp_threshold: float = 70.0,
    blurry_threshold: float = 35.0,
) -> Optional[QualityScores]:
    """Evaluate both focus sharpness and exposure clipping metrics into a typed QualityScores object."""
    arr = _load_image_array(image)
    if arr is None:
        return None

    focus = evaluate_focus_score(arr, crop_to_center=crop_to_center)
    if focus is None:
        return None

    clipping = evaluate_exposure_clipping(arr)
    cat = SharpnessCategory.from_score(
        focus["score"],
        blurry_threshold=blurry_threshold,
        sharp_threshold=sharp_threshold,
    )

    return QualityScores(
        sharpness=focus["score"],
        noise=focus["noise_sigma"],
        highlight_clipping=clipping["highlight_clipping"],
        shadow_clipping=clipping["shadow_clipping"],
        raw_variance=focus["raw_variance"],
        category_override=cat,
    )
