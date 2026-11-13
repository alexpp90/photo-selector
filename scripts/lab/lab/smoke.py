"""The E2E smoke campaign (`lab.py run --smoke [--since <ref>]`, maintainer decision 12, 2026-11-12).

A full journey x config pass costs about 95 minutes of Android time and found one APP defect in
its last two campaigns, so a routine campaign runs a smoke set and spends the time on usability
walks and exploration. The full pass stays for release campaigns (plain `lab.py run --all`).

The smoke set:

- **One config per device.** Each device has a reference config (`SMOKE_CONFIG_BY_ROLE`); a journey
  runs in it. A journey that does not offer it runs in the first config the device allows, so no
  journey of a changed product is left out.
- **Every journey of a changed product.** A product counts as changed when `git diff --name-only
  <ref>` (committed and uncommitted) touches its tree; `:core` and `:saf` change both Android
  products. Without `--since` every product counts as changed.
- **One journey of an unchanged product**: its `SMOKE-*` journey when it has one, else its first.
"""

from __future__ import annotations

import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterable

from .inventory import AndroidDevice

# First config a device of this role runs in a smoke pass.
SMOKE_CONFIG_BY_ROLE: dict[str, str] = {
    "phone": "phone-portrait",
    "tablet": "tab-landscape",
    "chromebook": "chromebook-window",
}

# A change under one of these prefixes changes these products. `scripts/lab/` changes no product:
# a tooling change is proven by the per-product baseline every smoke pass already runs.
PRODUCT_PATHS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("products/android/phototok/", ("phototok",)),
    ("products/android/android-desktop/", ("android-desktop",)),
    ("products/android/core/", ("phototok", "android-desktop")),
    ("products/android/saf/", ("phototok", "android-desktop")),
    ("products/macos-desktop/", ("macos",)),
    ("products/linux-desktop/", ("linux",)),
)
# Build files at the Android root change both Android products.
ANDROID_BUILD_FILES: tuple[str, ...] = (
    "products/android/build.gradle.kts",
    "products/android/settings.gradle.kts",
    "products/android/gradle/",
    "gradle/",
)


class SmokeError(ValueError):
    """The smoke selection cannot be made (an unknown git ref)."""


def products_of(paths: Iterable[str]) -> set[str]:
    """The products a list of repository-relative paths changes."""
    changed: set[str] = set()
    for path in paths:
        for prefix, products in PRODUCT_PATHS:
            if path.startswith(prefix):
                changed.update(products)
        if any(path == f or path.startswith(f) for f in ANDROID_BUILD_FILES):
            changed.update(("phototok", "android-desktop"))
    return changed


def changed_products(since: str, repo: Path, git: Callable[..., Any] = subprocess.run) -> set[str]:
    """Products touched since `since` (a git ref), the working tree included."""
    res = git(["git", "-C", str(repo), "diff", "--name-only", since, "--"], capture_output=True, text=True, check=False)
    if res.returncode != 0:
        raise SmokeError(f"git diff {since!r} failed: {(res.stderr or '').strip() or 'unknown ref'}")
    return products_of(line.strip() for line in (res.stdout or "").splitlines() if line.strip())


@dataclass
class SmokePlan:
    """Which journeys and configs the smoke pass runs. `changed=None` means every product changed."""

    changed: set[str] | None = None
    notes: list[str] = field(default_factory=list)

    def product_changed(self, product: str) -> bool:
        return self.changed is None or product in self.changed

    def journeys(self, product: str, journeys: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Every journey of a changed product; the baseline one of an unchanged product."""
        if self.product_changed(product) or not journeys:
            return list(journeys)
        for j in journeys:
            if str(j.get("id", "")).startswith("SMOKE-"):
                return [j]
        return [journeys[0]]

    def configs(self, journey: dict[str, Any], device: AndroidDevice | None = None) -> list[str]:
        """The one config this journey runs in (on this device); [] when the device allows none."""
        offered = [str(c) for c in journey.get("configs", [])]
        if device is None:
            return offered[:1]
        allowed = [c for c in offered if device.allows_config(c)]
        wanted = SMOKE_CONFIG_BY_ROLE.get(device.role)
        if wanted in allowed:
            return [wanted]
        return allowed[:1]

    def describe(self) -> str:
        if self.changed is None:
            return "smoke: every product treated as changed (no --since)"
        names = ", ".join(sorted(self.changed)) or "none"
        return f"smoke: products changed since the ref: {names}; the others run their baseline journey only"
