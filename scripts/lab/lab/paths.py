"""Repository and lab-root path helpers with safety guards."""

from __future__ import annotations

import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


def repo_root(start: Path | None = None) -> Path:
    """Walk up from `start` (or this file) to the directory containing `AGENTS.md`."""
    cur = (start or Path(__file__)).resolve()
    if cur.is_file():
        cur = cur.parent
    for parent in [cur, *cur.parents]:
        if (parent / "AGENTS.md").is_file() and (parent / "products").is_dir():
            return parent
    for parent in [cur, *cur.parents]:
        if (parent / "AGENTS.md").is_file():
            return parent
    raise FileNotFoundError(f"Could not locate repository root containing AGENTS.md from {cur}")


def ensure_under(path: Path | str, root: Path | str) -> Path:
    """Resolve `path` and verify it is inside `root`, or raise ValueError."""
    resolved_root = Path(root).expanduser().resolve()
    resolved_path = Path(path).expanduser().resolve()
    try:
        resolved_path.relative_to(resolved_root)
    except ValueError as exc:
        raise ValueError(
            f"refusing to touch {resolved_path} outside lab root {resolved_root}"
        ) from exc
    return resolved_path


def ensure_android_lab_path(remote_path: str) -> str:
    """Verify an Android device path is strictly inside `/sdcard/PSTLab/`."""
    cleaned = remote_path.strip()
    if not (cleaned == "/sdcard/PSTLab" or cleaned.startswith("/sdcard/PSTLab/")):
        raise ValueError(f"refusing to touch {remote_path!r} outside lab root /sdcard/PSTLab/")
    parts = [p for p in cleaned.split("/") if p]
    if ".." in parts:
        raise ValueError(f"refusing to touch path with parent traversal {remote_path!r} outside lab root")
    return cleaned


# The only places outside /sdcard/PSTLab the lab may create (and, afterwards, remove) a directory
# on a device, for a journey that declares it under `stage_dirs` (J-TOK-06: the camera roll).
STAGEABLE_ANDROID_DIRS = ("/sdcard/DCIM/Camera",)


def ensure_android_stage_path(remote_dir: str) -> str:
    """Verify `remote_dir` is exactly one of `STAGEABLE_ANDROID_DIRS` (no children, no traversal)."""
    cleaned = remote_dir.strip().rstrip("/")
    if cleaned not in STAGEABLE_ANDROID_DIRS:
        raise ValueError(f"refusing to stage {remote_dir!r}: not one of {STAGEABLE_ANDROID_DIRS}")
    return cleaned


def lab_tmp_root() -> Path:
    """Scratch root for generated fixtures and private sessions: `/tmp/pstlab` by default.

    `/tmp` is shared between macOS accounts, so when `/tmp/pstlab` belongs to another user (an
    earlier run in the maintainer's own account) the lab account uses `/tmp/pstlab-<uid>`.
    `PST_LAB_TMP` overrides both; it must still start with `/tmp/pstlab` for the session guards.
    """
    explicit = os.environ.get("PST_LAB_TMP")
    if explicit:
        return Path(explicit)
    default = Path("/tmp/pstlab")
    try:
        if not default.exists() or default.stat().st_uid == os.getuid():
            return default
    except OSError:
        pass
    return Path(f"/tmp/pstlab-{os.getuid()}")


def default_runs_dir(root: Path | None = None) -> Path:
    """Where `runs_dir` would put lab runs, without creating anything."""
    r = root or repo_root()
    if (r / "private").is_dir():
        return r / "private" / "lab-runs"
    return Path(tempfile.gettempdir()) / "lab-runs"


def runs_dir(root: Path | None = None) -> Path:
    """Return the directory where lab runs live (`private/lab-runs` or `/tmp/lab-runs`)."""
    r = root or repo_root()
    if not (r / "private").is_dir():
        print("[lab] warning: private/ not present; writing runs under /tmp/lab-runs", file=sys.stderr)
    target = default_runs_dir(r)
    target.mkdir(parents=True, exist_ok=True)
    return target


def new_run_dir(sha: str, root: Path | None = None, now: datetime | None = None, label: str = "") -> Path:
    """Create and return `<runs_dir>/<YYYY-MM-DD>-<HHMM>-<shortsha>[-<label>]/`."""
    ts = (now or datetime.now(timezone.utc)).strftime("%Y-%m-%d-%H%M")
    short_sha = (sha or "unknown").strip()[:12]
    base = runs_dir(root)
    candidate = base / (f"{ts}-{short_sha}-{label}" if label else f"{ts}-{short_sha}")
    suffix = 1
    while candidate.exists():
        suffix += 1
        candidate = base / f"{ts}-{short_sha}-{suffix}"
    candidate.mkdir(parents=True, exist_ok=False)
    return candidate
