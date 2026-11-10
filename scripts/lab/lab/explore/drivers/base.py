"""Device-driver contract for exploratory testing: errors, helpers and `DeviceDriver`.

A driver does what an action says and reports what it saw; it never reports success for
something it did not do. A command that fails raises, a tree it cannot read raises, and an
action it cannot perform raises `DriverExecutionError`.

Life cycle, in the order the orchestrator calls it:

1. `setup()` takes the locks and checks the device is there. It changes nothing on the device.
2. The orchestrator records the device's settings and persists them.
3. `prepare()` readies the device (wake, clear the log, stage the lab fixture, launch the app on
   it) and raises `DriverError` unless the app is running and shows a tree with nodes.
4. `capture_state()`, `execute_action()`, `read_app_log()` and `restart_app()` run the loop.
5. The orchestrator restores the device's settings; `teardown()` releases what `setup()` took.
"""

from __future__ import annotations

import math
import time
import uuid
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any, Iterable, List, Optional, Tuple

from ...android.system_bars import SystemBar
from ...paths import ensure_under, lab_tmp_root
from ...usability.driver import parse_key
from ..actions import Action, ScreenState


# ==============================================================================
# Error Hierarchy
# ==============================================================================

class DriverError(Exception):
    """Base class for all device driver errors."""


class DriverTimeoutError(DriverError):
    """Action or state capture exceeded its timeout budget."""


class DriverExecutionError(DriverError):
    """Command failed, returned a non-zero exit code, or the action cannot be performed."""


class ActionUnavailableError(DriverExecutionError):
    """The device cannot perform the action at all: an environment limit, not the app's defect.

    A docked Pixel Tablet locks the display rotation (2026-11-06: "rotate" was a major finding of
    the exploratory run). The explorer stops offering the action and names it in the target's detail.
    """


class DriverSettlingError(DriverError):
    """UI state did not become idle after max retry attempts."""


class AppCrashedError(DriverError):
    """Application process died or unexpectedly vanished."""


class AppLeftError(DriverError):
    """Another app (or the launcher, or a system window) is in front of the app under test.

    Carries what the driver saw of the window in front, so a finding can show it: `packages`
    (every package its dump names), `top_task` (the `topResumedActivity` line) and `after`
    (its tree and screenshot; the screenshot is empty when it could not be taken).
    """

    def __init__(
        self, message: str, *, packages: Iterable[str] = (), top_task: str = "", after: Optional[ScreenState] = None
    ) -> None:
        super().__init__(message)
        self.packages = frozenset(packages)
        self.top_task = top_task
        self.after = after


class TargetUnderSystemBarError(DriverExecutionError):
    """The centre of a tap's target lies under a system bar: the tap would reach the system, not the app.

    The target is the app's defect (a control the user cannot tap), so the explorer reports it as a
    finding instead of tapping. `in_scroll` says the target sits in a scrolling container, where
    content passing under a bar is normal while it scrolls.
    """

    def __init__(self, message: str, *, target_id: Optional[str], point: Tuple[int, int], bar: SystemBar, in_scroll: bool) -> None:
        super().__init__(message)
        self.target_id = target_id
        self.point = point
        self.bar = bar
        self.in_scroll = in_scroll


def _parse_coord(val: Any, name: str) -> float:
    """Parse and validate numeric coordinate, raising DriverExecutionError on NaN, Inf, or invalid value."""
    if val is None:
        raise DriverExecutionError(f"Coordinate '{name}' is None")
    try:
        f = float(val)
    except (ValueError, TypeError) as exc:
        raise DriverExecutionError(f"Coordinate '{name}' is not a valid number: {val!r}") from exc
    if math.isnan(f) or math.isinf(f):
        raise DriverExecutionError(f"Coordinate '{name}' is NaN or Inf: {val!r}")
    return f


def _key_spec(action: Action) -> Tuple[str, Tuple[str, ...]]:
    """The key and normalised modifiers of a key action; `DriverExecutionError` when malformed.

    `{"key": "o", "modifiers": ["ctrl"]}` and `{"key": "Ctrl+O"}` both work; a modifier the lab
    does not know raises instead of being dropped (a dropped Ctrl turns Undo into Z).
    """
    key = str(action.params.get("key", "")).strip()
    if not key:
        raise DriverExecutionError("Key action missing 'key' parameter")
    mods = action.params.get("modifiers") or []
    try:
        if mods:
            return parse_key({"code": key, "modifiers": list(mods)})
        return parse_key(key)
    except ValueError as exc:
        raise DriverExecutionError(str(exc)) from exc


def _ensure_dir(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    return path


def _scratch_path(prefix: str) -> Path:
    """A fresh directory name under the lab root. Nothing is created until the driver starts."""
    return ensure_under(lab_tmp_root() / f"{prefix}-{uuid.uuid4().hex[:10]}", lab_tmp_root())


# ==============================================================================
# Abstract Base Class: DeviceDriver
# ==============================================================================

class DeviceDriver(ABC):
    """Abstract base class for all exploratory interaction drivers."""

    def __init__(
        self,
        product: str,
        app_id: str,
        working_dir: Path | str,
        out_dir: Path,
        settle_ms: int = 450,
    ) -> None:
        self.product = product
        self.app_id = app_id
        self.working_dir = working_dir
        self.out_dir = out_dir
        self.settle_ms = settle_ms
        self.step_count = 0
        self.last_state: Optional[ScreenState] = None
        self._owns_out_dir = False

    def __enter__(self) -> DeviceDriver:
        self.setup()
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        self.teardown()

    @abstractmethod
    def setup(self) -> None:
        """Acquire the locks and check the target is reachable. Changes no device setting."""

    @abstractmethod
    def prepare(self) -> None:
        """Ready the target and start the app on the lab fixture.

        Called after the orchestrator recorded and persisted the device state. Raises
        `DriverError` unless the app is running and shows a tree with nodes.
        """

    @abstractmethod
    def teardown(self) -> None:
        """Stop what the driver started and release the locks. Device settings are the orchestrator's."""

    @abstractmethod
    def capture_state(self) -> ScreenState:
        """The live screen: tree, screenshot, screen mode and file-system snapshot.

        Raises `DriverError` when it cannot; `AppLeftError` when another app is in front,
        `AppCrashedError` when the app is gone. Never returns a made-up empty tree.
        """

    @abstractmethod
    def execute_action(self, action: Action) -> None:
        """Perform `action`. Raises `DriverExecutionError` for an unsupported action or a failed command."""

    @abstractmethod
    def restart_app(self) -> None:
        """Stop the app and start it again on the lab fixture (after `AppLeftError` / `AppCrashedError`)."""

    @abstractmethod
    def read_app_log(self) -> List[str]:
        """The app's own log lines since the previous call."""

    def home_packages(self) -> frozenset:
        """Packages of the device's launcher; none for a target that has no launcher."""
        return frozenset()

    def default_browser_packages(self) -> frozenset:
        """Package that opens a web link by default; none for a target with no such default."""
        return frozenset()

    def settle(self, custom_settle_ms: Optional[int] = None) -> None:
        """Wait for UI transitions, layout passes, and animations to complete."""
        ms = custom_settle_ms if custom_settle_ms is not None else self.settle_ms
        if ms > 0:
            time.sleep(ms / 1000.0)
