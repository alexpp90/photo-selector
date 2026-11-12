"""Which devices walk a product's usability scenarios, and in which order (decisions 12 and 39).

A campaign walks each product on a first and a second device, because a second phone found
geometry- and Android-version-specific findings a single walk cannot. The order is the
inventory's, with the preferred phone (`inventory.PREFERRED_PHONE`) first whenever it is attached,
dedicated devices before personal ones (personal ones only with the maintainer's opt-in).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

from .inventory import AndroidDevice, Inventory, order_by_preference, resolve_android_device

ORDINALS = ("first", "second", "third")


@dataclass
class Walk:
    ordinal: str  # first | second | ...
    device: AndroidDevice
    serial: str  # the adb transport id to pass as `usability-capture --serial`


def walk_devices(
    inv: Inventory,
    product: str,
    *,
    with_personal: bool = False,
    count: int = 2,
    resolve: Callable[[AndroidDevice], tuple[bool, str | None]] = resolve_android_device,
) -> tuple[list[Walk], list[tuple[AndroidDevice, str]]]:
    """`(walks, not_run)`: up to `count` reachable devices in walk order, and each device that is
    not reachable with its reason (a named *not run*, never a silent skip)."""
    candidates = [d for d in inv.android if d.runs(product) and (with_personal or not d.personal)]
    walks: list[Walk] = []
    not_run: list[tuple[AndroidDevice, str]] = []
    for dev in order_by_preference(candidates):
        ok, reason = resolve(dev)
        if not ok:
            not_run.append((dev, reason or "unreachable"))
            continue
        if len(walks) < count:
            walks.append(Walk(ORDINALS[min(len(walks), len(ORDINALS) - 1)], dev, dev.adb_serial or dev.serial))
    return walks, not_run
