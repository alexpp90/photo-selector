"""Which volume a folder is on, and whether a card may be formatted (plan 1.2 and 1.6).

GTK-free. "Removable" is read from the mount point (udisks mounts cards and sticks under
``/run/media`` or ``/media``) or from the kernel's ``removable`` flag of the block device, so a
card in a laptop's built-in reader (``mmcblk``, flag 0) still counts. The seams are injectable:
tests pass fake ``stat`` and mount tables, never a real card.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Callable, Optional

REMOVABLE_MOUNT_PREFIXES = ("/run/media/", "/media/")

VERDICT_SAFE = "safe"
VERDICT_SAME_VOLUME = "same_volume"
VERDICT_NONE = "none"


def volume_id(path: Path) -> Optional[int]:
    """The device number of the volume holding ``path`` (``None`` when it cannot be read)."""
    try:
        return os.stat(path).st_dev
    except OSError:
        return None


def mount_point(path: Path, ismount: Callable[[str], bool] = os.path.ismount) -> Path:
    """The mount point containing ``path``."""
    current = Path(os.path.abspath(path))
    while not ismount(str(current)) and current != current.parent:
        current = current.parent
    return current


def _kernel_removable(device_number: int) -> bool:
    flag = Path(f"/sys/dev/block/{os.major(device_number)}:{os.minor(device_number)}")
    for candidate in (flag / "removable", flag.resolve().parent / "removable"):
        try:
            return candidate.read_text().strip() == "1"
        except OSError:
            continue
    return False


def is_removable_volume(
    path: Path,
    mount_of: Callable[[Path], Path] = mount_point,
    kernel_removable: Callable[[int], bool] = _kernel_removable,
    device_of: Callable[[Path], Optional[int]] = volume_id,
) -> bool:
    """True when ``path`` is on a card or stick that can be taken out."""
    mounted_at = str(mount_of(path))
    if any((mounted_at + "/").startswith(prefix) for prefix in REMOVABLE_MOUNT_PREFIXES):
        return True
    device = device_of(path)
    return bool(device is not None and kernel_removable(device))


def format_verdict(
    source: Optional[Path],
    selection: Optional[Path],
    queued: int,
    remaining: int,
    is_remote: bool,
    removable: Callable[[Path], bool] = is_removable_volume,
    device_of: Callable[[Path], Optional[int]] = volume_id,
) -> str:
    """``safe`` only for a removable source, a Selection on another volume, an empty queue and no
    photo left; ``same_volume`` when the Selection sits on the volume being culled (formatting
    would erase the keepers); ``none`` (no verdict) otherwise, remote folders included."""
    if source is None or selection is None or is_remote or queued or remaining:
        return VERDICT_NONE
    if not removable(source):
        return VERDICT_NONE
    selection_anchor = selection
    while not selection_anchor.exists() and selection_anchor != selection_anchor.parent:
        selection_anchor = selection_anchor.parent
    source_device, selection_device = device_of(source), device_of(selection_anchor)
    if source_device is None or selection_device is None:
        return VERDICT_NONE
    return VERDICT_SAME_VOLUME if source_device == selection_device else VERDICT_SAFE
