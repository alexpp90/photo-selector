"""Device drivers for exploratory testing: Android over adb, Linux in a private session, a mock.

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

Reused from the rest of the lab instead of copied: the key parsing and Android key codes of
`usability/driver.py`, the Android runner's fixture folder, picker packages and
`_ensure_folder.yaml` flow, the config module's verified rotation and window resize, and the
Linux runner's interpreter and `session.sh` environment.

- Android: `AndroidDeviceDriver`, direct adb (the hub lock is held because Maestro opens every
  device on the hub, see `locks.py`).
- Linux: `LinuxDeviceDriver`, Blitzlens in its own Xvfb (`-displayfd`) and private D-Bus.
- macOS: `MacOSDeviceDriver` refuses; the app's scenario hook cannot be driven interactively.
- Mock: `MockDeviceDriver`, a deterministic offline state machine for tests and `--driver mock`.
"""

from __future__ import annotations

import ctypes
import ctypes.util
import json
import math
import os
import re
import select
import shutil
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET
from abc import ABC, abstractmethod
from contextlib import ExitStack, contextmanager
from pathlib import Path
from typing import Any, Callable, Dict, Iterator, List, Optional, Tuple

from ..android.adb import AdbClient
from ..android.configs import ConfigNotRunError, resize_app_window, set_verified_rotation
from ..android.runner import (
    ENSURE_FOLDER_FLOW,
    PICKER_PACKAGES,
    REMOTE_WORK_DIR,
    SHADE_SETTLE_S,
    ROTATION_SETTLE_S,
    _maestro_failure_line,
    default_maestro_runner,
)
from ..android.tree_android import foreground_packages, parse_uiautomator_xml
from ..fixtures import generate_fixtures
from ..fs_oracle import snapshot_directory
from ..linux.keys import normalize_xdotool_key
from ..linux.runner import preflight_linux, session_env
from ..locks import device_lock, hub_lock_path, waiting_lock
from ..model import Node, Tree
from ..paths import ensure_android_lab_path, ensure_under, lab_tmp_root, repo_root
from ..usability.driver import ANDROID_MODIFIER_CODES, android_keycode, parse_key
from .actions import Action, ScreenState
from .discovery import SYSTEM_PICKER_MODE, classify_screen


# ==============================================================================
# Error Hierarchy
# ==============================================================================

class DriverError(Exception):
    """Base class for all device driver errors."""


class DriverTimeoutError(DriverError):
    """Action or state capture exceeded its timeout budget."""


class DriverExecutionError(DriverError):
    """Command failed, returned a non-zero exit code, or the action cannot be performed."""


class DriverSettlingError(DriverError):
    """UI state did not become idle after max retry attempts."""


class AppCrashedError(DriverError):
    """Application process died or unexpectedly vanished."""


class AppLeftError(DriverError):
    """Another app (or the launcher, or a system window) is in front of the app under test."""


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

    def settle(self, custom_settle_ms: Optional[int] = None) -> None:
        """Wait for UI transitions, layout passes, and animations to complete."""
        ms = custom_settle_ms if custom_settle_ms is not None else self.settle_ms
        if ms > 0:
            time.sleep(ms / 1000.0)


# ==============================================================================
# Android (direct adb)
# ==============================================================================

_BOUNDS_ATTR = re.compile(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"')
_NAV_BAR = re.compile(r'resource-id="[^"]*navigationBarBackground"[^>]*?bounds="\[(-?\d+),(-?\d+)\]')
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


class _SharedHubLock:
    """The hub lock for this process: one `flock` however many Android drivers are active.

    `locks.py`: a lab run holds the hub lock for as long as it drives Android devices, because
    every Maestro and adb call shares one adb server. Two drivers of one run (two devices) must
    not wait for each other on it, so the first takes the flock and the last releases it.
    """

    def __init__(self) -> None:
        self._guard = threading.Lock()
        self._held: Dict[Path, Tuple[int, ExitStack]] = {}

    def acquire(self, holder: str, lock_dir: Optional[Path], wait_s: float) -> Path:
        path = hub_lock_path(lock_dir)
        with self._guard:
            if path in self._held:
                count, stack = self._held[path]
                self._held[path] = (count + 1, stack)
                return path
            stack = ExitStack()
            stack.enter_context(waiting_lock(path, holder, wait_s))  # DeviceBusy when it stays held
            self._held[path] = (1, stack)
            return path

    def release(self, path: Path) -> None:
        with self._guard:
            if path not in self._held:
                return
            count, stack = self._held[path]
            if count > 1:
                self._held[path] = (count - 1, stack)
                return
            del self._held[path]
            stack.close()


_HUB_LOCK = _SharedHubLock()


class AndroidDeviceDriver(DeviceDriver):
    """Direct adb driver for Jackpix and Frameduel on one device."""

    def __init__(
        self,
        serial: str,
        app_id: str,
        product: str = "phototok",
        working_dir: str = REMOTE_WORK_DIR,
        out_dir: Optional[Path] = None,
        adb: Optional[AdbClient] = None,
        settle_ms: int = 500,
        dump_retries: int = 5,
        holder_name: str = "explore-worker",
        fixture_set: str = "smoke",
        lock_dir: Optional[Path] = None,
        hub_wait_s: float = 60.0,
        maestro_fn: Optional[Callable[[Optional[str], Path, Path], int]] = None,
        shade_settle_s: float = SHADE_SETTLE_S,
    ) -> None:
        super().__init__(
            product, app_id, ensure_android_lab_path(str(working_dir)),
            out_dir or _scratch_path(f"explore-android-{serial}"), settle_ms,
        )
        self._owns_out_dir = out_dir is None
        self.serial = serial
        self.adb = adb or AdbClient(serial=serial)
        self.dump_retries = dump_retries
        self.holder_name = holder_name
        self.fixture_set = fixture_set
        self.lock_dir = lock_dir
        self.hub_wait_s = hub_wait_s
        self.shade_settle_s = shade_settle_s
        self._maestro = maestro_fn or default_maestro_runner
        self._device_lock: Optional[ExitStack] = None
        self._hub_path: Optional[Path] = None
        self._density: Optional[int] = None
        self._origin_px: Tuple[int, int] = (0, 0)
        self._window_px: Tuple[int, int] = (1080, 1920)
        self._safe_bottom_px: Optional[int] = None
        self._in_picker = False  # the system folder picker, not the app, is in front
        self._log_since: Optional[str] = None
        self._last_pid: Optional[int] = None
        self._launches = 0
        self.initial_fs: Dict[str, Any] = {}

    # -- errors --------------------------------------------------------------------
    @contextmanager
    def _adb_errors(self, what: str, error: type = DriverExecutionError) -> Iterator[None]:
        """Turn an adb/subprocess failure into a driver error that says what was attempted."""
        try:
            yield
        except subprocess.CalledProcessError as exc:
            tail = (exc.stderr or exc.stdout or b"")
            tail = tail.decode("utf-8", errors="replace") if isinstance(tail, bytes) else str(tail)
            raise error(f"{what}: adb exited {exc.returncode}: {tail.strip()[:200]}") from exc
        except subprocess.TimeoutExpired as exc:
            raise DriverTimeoutError(f"{what}: timed out after {exc.timeout}s") from exc
        except (RuntimeError, OSError, ValueError) as exc:
            raise error(f"{what}: {exc}") from exc

    # -- life cycle ---------------------------------------------------------------
    def setup(self) -> None:
        """Take the hub and device locks and check the device answers. Changes nothing on it."""
        self.out_dir.mkdir(parents=True, exist_ok=True)
        stack = ExitStack()
        try:
            self._hub_path = _HUB_LOCK.acquire(f"explore {self.holder_name} {self.serial}", self.lock_dir, self.hub_wait_s)
            stack.callback(self._release_hub)
            stack.enter_context(device_lock(self.serial, self.holder_name, self.lock_dir))
            self._device_lock = stack
            with self._adb_errors(f"device {self.serial} is not reachable", DriverError):
                state = self.adb.run("get-state").strip()
                if state != "device":
                    raise DriverError(f"device {self.serial} is not ready (adb state {state!r})")
                self._density = self.adb.density()
        except BaseException:
            self._device_lock = None
            stack.close()
            raise

    def _release_hub(self) -> None:
        if self._hub_path is not None:
            _HUB_LOCK.release(self._hub_path)
            self._hub_path = None

    def teardown(self) -> None:
        """Release the device and hub locks."""
        stack, self._device_lock = self._device_lock, None
        if stack is not None:
            stack.close()
        if self._owns_out_dir:
            shutil.rmtree(self.out_dir, ignore_errors=True)

    def prepare(self) -> None:
        """Wake the device, clear the log, stage the lab fixture and open the app on it."""
        with self._adb_errors("cannot ready the device", DriverError):
            self.adb.ensure_ready()
            self.adb.logcat_clear()
        self._stage_fixture()
        self._launch_into_folder()

    def _stage_fixture(self) -> None:
        stage = ensure_under(self.out_dir / "_fixture_stage", self.out_dir)
        shutil.rmtree(stage, ignore_errors=True)
        try:
            generate_fixtures(self.fixture_set, stage / self.fixture_set, root=repo_root())
            with self._adb_errors("cannot stage the fixture on the device", DriverError):
                # The app's one persisted folder grant is to this folder: replace its content
                # in place, never the folder's parent.
                self.adb.force_stop(self.app_id)
                for picker in PICKER_PACKAGES:
                    self.adb.force_stop(picker)
                self.adb.rm_rf(str(self.working_dir))
                self.adb.push_dir(stage / self.fixture_set, str(self.working_dir))
                self.initial_fs = self._snapshot_fs()
        finally:
            shutil.rmtree(stage, ignore_errors=True)

    def _launch_into_folder(self) -> None:
        """Force-stop, launch, and run the product's `_ensure_folder.yaml` so the app shows the fixture."""
        flow = repo_root() / "products" / "android" / self.product / "tests" / "e2e" / ENSURE_FOLDER_FLOW
        if not flow.is_file():
            raise DriverError(f"{flow} does not exist: cannot point {self.app_id} at {self.working_dir}")
        with self._adb_errors(f"cannot launch {self.app_id}", DriverError):
            # A share sheet or browser the app opened runs in a task of its own and survives the
            # force-stop; Home takes it out of the foreground.
            self.adb.key("HOME")
            self.adb.force_stop(self.app_id)
            for picker in PICKER_PACKAGES:
                self.adb.force_stop(picker)
            self.adb.start(self.app_id)
        self._launches += 1
        junit = self.out_dir / f"maestro_ensure_folder_{self._launches:02d}.xml"
        try:
            rc = self._maestro(self.serial, flow, junit)
        except OSError as exc:
            raise DriverError(f"cannot run Maestro for {ENSURE_FOLDER_FLOW}: {exc}") from exc
        if rc != 0:
            raise DriverError(f"{ENSURE_FOLDER_FLOW} failed (rc={rc}): {_maestro_failure_line(junit.with_suffix('.log'))}")
        self.last_state = None
        tree = self._observe()
        if not tree.nodes:
            raise DriverError(f"{self.app_id} shows an empty tree after launch")
        self._log_since = None

    def restart_app(self) -> None:
        """Force-stop and relaunch the app on the lab folder."""
        self._launch_into_folder()

    # -- observation --------------------------------------------------------------
    def _app_pid(self) -> Optional[int]:
        try:
            out = self.adb.run("shell", "pidof", self.app_id)
        except subprocess.CalledProcessError as exc:
            # pidof exits 1 with no output when there is no such process; adb itself failing
            # (device offline, unauthorized) is not a dead app.
            said = "".join(
                part.decode("utf-8", "replace") if isinstance(part, bytes) else str(part)
                for part in (exc.stderr or b"", exc.output or b"")
            ).strip()
            if said:
                raise DriverError(f"adb could not look for {self.app_id}: {said}") from exc
            return None
        m = re.search(r"\d+", out)
        if m:
            self._last_pid = int(m.group(0))
            return self._last_pid
        return None

    def _require_app_process(self) -> None:
        with self._adb_errors("cannot look for the app process", DriverError):
            if self._app_pid() is not None:
                return
            try:
                crash = self.adb.run("logcat", "-d", "-b", "crash", "-t", "20", timeout=10).strip()
            except (subprocess.SubprocessError, OSError):
                crash = ""
        raise AppCrashedError(f"{self.app_id} is not running" + (f"; crash log: {crash[-400:]}" if crash else ""))

    def _dump(self) -> Tuple[str, Tree]:
        """UiAutomator XML and its tree, retried while the screen animates ("could not get idle state")."""
        last_error = ""
        for attempt in range(self.dump_retries):
            try:
                raw = self.adb.run_bytes("exec-out", "uiautomator", "dump", "/dev/tty", timeout=30)
                text = raw.decode("utf-8", errors="replace")
                start, end = text.find("<hierarchy"), text.rfind("</hierarchy>")
                if start >= 0 and end > start:
                    xml = text[start : end + len("</hierarchy>")]
                    return xml, parse_uiautomator_xml(xml, density_dpi=self._density or 320)
                last_error = text.strip()[:160]
            except ET.ParseError as exc:
                last_error = f"ParseError: {exc}"
            except (subprocess.TimeoutExpired, subprocess.SubprocessError, OSError) as exc:
                last_error = str(exc)
            time.sleep(0.3 * (attempt + 1))
        self._require_app_process()  # a dead app explains a dump that never comes
        raise DriverSettlingError(f"UiAutomator dump failed after {self.dump_retries} attempts: {last_error}")

    def _observe(self) -> Tree:
        """Tree of the app's window; remembers where the window sits and where taps must stop."""
        self._require_app_process()
        allowed = {self.app_id, *PICKER_PACKAGES}
        xml = tree = None
        front: set = set()
        for attempt in range(2):
            with self._adb_errors("cannot collapse the notification shade", DriverError):
                self.adb.collapse_shade()  # an open shade covers the app with other apps' notifications
            if attempt:
                time.sleep(self.shade_settle_s)
            xml, tree = self._dump()
            front = foreground_packages(xml)
            if front & allowed:
                self._in_picker = self.app_id not in front
                break
        else:
            self._require_app_process()
            raise AppLeftError(f"{', '.join(sorted(front)) or 'an unknown window'} is in front of {self.app_id}")
        assert xml is not None and tree is not None
        scale = (self._density or 320) / 160.0
        window = next(
            (m for m in _BOUNDS_ATTR.finditer(xml) if int(m.group(3)) > int(m.group(1)) and int(m.group(4)) > int(m.group(2))),
            None,
        )
        if window is not None:  # uiautomator reports screen pixels; the tree is relative to this window
            x1, y1, x2, y2 = (int(window.group(i)) for i in range(1, 5))
            self._origin_px = (x1, y1)
            self._window_px = (x2 - x1, y2 - y1)
        nav = _NAV_BAR.search(xml)
        # Lowest y a gesture may touch: the top of the navigation bar, else 92 % of the window
        # (a tap on the bar is Home or Back, and leaves the app).
        self._safe_bottom_px = int(nav.group(2)) if nav else self._origin_px[1] + int(self._window_px[1] * 0.92)
        self._mark_focus(xml, tree)
        return tree

    @staticmethod
    def _mark_focus(xml: str, tree: Tree) -> None:
        """Tell the tree which text input has the keyboard (`focused`), which `Node` has no field for."""
        elements = list(ET.fromstring(xml).iter("node"))
        if len(elements) != len(tree.nodes):
            return
        for element, node in zip(elements, tree.nodes):
            if element.get("focused") == "true" and node.role == "input":
                node.focused = True  # type: ignore[attr-defined]

    def _snapshot_fs(self) -> Dict[str, Any]:
        files = self.adb.list_files(str(self.working_dir))
        return {"root": str(self.working_dir).replace("/sdcard/", "", 1), "files": files, "trash": []}

    def capture_state(self) -> ScreenState:
        """Tree, screenshot and file listing of the app as it is now."""
        with self._adb_errors("cannot capture the screen", DriverError):
            tree = self._observe()
            png = self.adb.run_bytes("exec-out", "screencap", "-p", timeout=30)
            if not png.startswith(PNG_MAGIC):
                raise DriverError("screencap did not return a PNG")
            fs_snapshot = self._snapshot_fs()
        state = ScreenState(
            tree=tree,
            screenshot_bytes=png,
            screen_mode=SYSTEM_PICKER_MODE if self._in_picker else classify_screen(tree, self.product),
            fs_snapshot=fs_snapshot,
            logs=[],  # the app's log is read with read_app_log(), once, by the log oracle
        )
        self.last_state = state
        return state

    @staticmethod
    def _logcat_epoch(line: str) -> Optional[float]:
        m = re.match(r"\s*(\d+\.\d+)\s", line)
        return float(m.group(1)) if m else None

    def read_app_log(self) -> List[str]:
        """The app process's log lines since the last call (`logcat --pid`, from the last line's time)."""
        pid = self._app_pid() or self._last_pid
        if pid is None:
            return []
        args = ["logcat", "-d", f"--pid={pid}", "-v", "epoch"]
        if self._log_since is not None:
            args += ["-T", self._log_since]
        with self._adb_errors("cannot read the app log", DriverError):
            out = self.adb.run(*args, timeout=30)
        lines = [ln for ln in out.splitlines() if ln.strip() and not ln.startswith("---")]
        stamps = [t for t in (self._logcat_epoch(ln) for ln in lines) if t is not None]
        if stamps:
            self._log_since = f"{max(stamps) + 0.001:.3f}"  # the next read starts after the last line
        return lines

    # -- actions ------------------------------------------------------------------
    def _px(self, x_dp: Any, y_dp: Any) -> Tuple[int, int]:
        """Window-relative dp -> screen pixels: add the window origin, stay above the navigation bar."""
        scale = (self._density or 320) / 160.0
        x = self._origin_px[0] + int(round(_parse_coord(x_dp, "x") * scale))
        y = self._origin_px[1] + int(round(_parse_coord(y_dp, "y") * scale))
        if self._safe_bottom_px is not None:
            y = min(y, self._safe_bottom_px - 2)
        return x, y

    def _target_center(self, target_id: Optional[str]) -> Tuple[float, float]:
        if target_id and self.last_state:
            node = next((n for n in self.last_state.tree.nodes if n.id == target_id), None)
            if node is not None:
                return node.x + node.w / 2.0, node.y + node.h / 2.0
        raise DriverExecutionError(f"tap has no coordinates and no known target {target_id!r}")

    def execute_action(self, action: Action) -> None:
        """Perform `action` with adb input; raises `DriverExecutionError` when it cannot."""
        self.step_count += 1
        t = action.action_type
        handlers: Dict[str, Callable[[Action], None]] = {
            "tap": self._tap, "click": self._tap, "swipe": self._swipe, "key": self._key,
            "back": self._back, "background": self._background, "resize": self._resize, "rotate": self._rotate,
        }
        handler = handlers.get(t)
        if handler is None:
            raise DriverExecutionError(f"Unsupported action type for Android: {t}")
        with self._adb_errors(f"{action.summary()} failed"):
            handler(action)

    def _tap(self, action: Action) -> None:
        x_dp, y_dp = action.params.get("x"), action.params.get("y")
        if (x_dp is None) != (y_dp is None):
            raise DriverExecutionError(f"Incomplete tap coordinates in {action.params}")
        if x_dp is None:
            x_dp, y_dp = self._target_center(action.target_id)
        x, y = self._px(x_dp, y_dp)
        taps = 2 if action.params.get("double_tap") or action.params.get("count") == 2 else 1
        for i in range(taps):
            if i:
                time.sleep(0.08)
            self.adb.run("shell", "input", "tap", str(x), str(y))

    def _swipe(self, action: Action) -> None:
        params = action.params
        try:
            duration = int(params.get("duration_ms", 250))
        except (ValueError, TypeError) as exc:
            raise DriverExecutionError(f"Invalid swipe duration: {params.get('duration_ms')!r}") from exc

        def pick(*names: str) -> Any:
            return next((params[n] for n in names if params.get(n) is not None), None)

        x1, y1, x2, y2 = pick("x1", "start_x"), pick("y1", "start_y"), pick("x2", "end_x"), pick("y2", "end_y")
        if all(v is None for v in (x1, y1, x2, y2)):
            vec = {"up": (0, -1), "down": (0, 1), "left": (-1, 0), "right": (1, 0)}.get(str(params.get("direction", "up")))
            if vec is None:
                raise DriverExecutionError(f"Unknown swipe direction {params.get('direction')!r}")
            scale = (self._density or 320) / 160.0
            w_dp, h_dp = self._window_px[0] / scale, self._window_px[1] / scale
            cx, cy = w_dp / 2.0, h_dp / 2.0
            dx, dy = w_dp * 0.35 * vec[0], h_dp * 0.28 * vec[1]
            x1, y1, x2, y2 = cx - dx, cy - dy, cx + dx, cy + dy
        elif any(v is None for v in (x1, y1, x2, y2)):
            raise DriverExecutionError(f"Incomplete swipe coordinates in {params}")
        sx, sy = self._px(x1, y1)
        ex, ey = self._px(x2, y2)
        self.adb.run("shell", "input", "swipe", str(sx), str(sy), str(ex), str(ey), str(duration))

    def _key(self, action: Action) -> None:
        key, mods = _key_spec(action)
        if key.lower() == "delete":
            key = "forward_delete"  # Delete deletes the photo; Android's KEYCODE_DEL is Backspace
        elif key == "?":
            key, mods = "/", tuple(dict.fromkeys((*mods, "shift")))
        try:
            code = android_keycode(key)
            mod_codes = [ANDROID_MODIFIER_CODES[m] for m in mods]
        except (ValueError, KeyError) as exc:
            raise DriverExecutionError(f"cannot press {action.params.get('key')!r}: {exc}") from exc
        if mod_codes:
            self.adb.key_combo(*mod_codes, code)
        else:
            self.adb.key(code)

    def _back(self, action: Action) -> None:
        self.adb.key("KEYCODE_BACK")

    def _background(self, action: Action) -> None:
        try:
            seconds = min(float(action.params.get("duration_s", 1.0)), 30.0)
        except (ValueError, TypeError) as exc:
            raise DriverExecutionError(f"Invalid background duration: {action.params.get('duration_s')!r}") from exc
        self.adb.home()
        time.sleep(seconds)
        self.adb.start(self.app_id)

    def _resize(self, action: Action) -> None:
        w = action.params.get("width", action.params.get("w"))
        h = action.params.get("height", action.params.get("h"))
        if w is None or h is None:
            raise DriverExecutionError(f"resize needs width and height: {action.params}")
        try:
            resize_app_window(self.adb, self.app_id, f"{int(_parse_coord(w, 'width'))}x{int(_parse_coord(h, 'height'))}")
        except ConfigNotRunError as exc:
            raise DriverExecutionError(str(exc)) from exc

    def _rotate(self, action: Action) -> None:
        params = action.params
        if params.get("toggle"):
            w_dp, h_dp = self.adb.window_dp()
            want = "portrait" if w_dp > h_dp else "landscape"
        elif str(params.get("orientation", "")).lower() in ("portrait", "landscape"):
            want = str(params["orientation"]).lower()
        else:
            raise DriverExecutionError(f"rotate needs toggle or orientation portrait/landscape: {params}")
        try:
            set_verified_rotation(self.adb, want)  # the settings it changes are restored by the orchestrator
        except ConfigNotRunError as exc:
            raise DriverExecutionError(str(exc)) from exc
        time.sleep(ROTATION_SETTLE_S)


# ==============================================================================
# Linux (Blitzlens in a private Xvfb + D-Bus session)
# ==============================================================================

class LinuxDeviceDriver(DeviceDriver):
    """Blitzlens in its own Xvfb display and private D-Bus, driven with xdotool and `lab-checkpoint`.

    The session is what `tests/e2e/session.sh` builds for a scripted journey, held open for an
    interactive run: a display from `Xvfb -displayfd` (never a fixed number), a session bus of
    its own (the user's bus is never inherited), no portals, the cairo renderer, and XDG
    directories under the lab root. Windows sit at the screen origin (there is no window
    manager), so tree coordinates are screen coordinates.
    """

    def __init__(
        self,
        app_id: str = "app.alpatix.Blitzlens",
        fixture_dir: Path | str | None = None,
        out_dir: Optional[Path] = None,
        size: str = "1920x1080",
        settle_ms: int = 300,
        fixture_set: str = "smoke",
        exec_fn: Optional[Callable[[List[str], Dict[str, str]], Any]] = None,
        start_timeout_s: float = 30.0,
        checkpoint_timeout_s: float = 10.0,
    ) -> None:
        root = lab_tmp_root()
        self.lab_dir = ensure_under(root / f"explore-linux-{os.getpid()}-{uuid.uuid4().hex[:8]}", root)
        work = ensure_under(fixture_dir, root) if fixture_dir is not None else self.lab_dir / "work"
        super().__init__("linux-desktop", app_id, work, out_dir or _scratch_path("explore-linux-out"), settle_ms)
        self._owns_out_dir = out_dir is None
        self.size = size
        self.fixture_set = fixture_set
        self.exec_fn = exec_fn  # runs xdotool / gapplication commands; the session itself is always real
        self.start_timeout_s = start_timeout_s
        self.checkpoint_timeout_s = checkpoint_timeout_s
        self.artifacts_dir = self.out_dir / "artifacts"
        self._env: Dict[str, str] = {}
        self._procs: List[subprocess.Popen[Any]] = []
        self._app_proc: Optional[subprocess.Popen[Any]] = None
        self._app_log: Optional[Any] = None
        self._log_offset = 0
        self._captures = 0

    # -- life cycle ---------------------------------------------------------------
    def setup(self) -> None:
        """Check the host can run the session. Starts nothing."""
        ok, reason = preflight_linux()
        if not ok:
            raise DriverError(reason or "the Linux host cannot run Blitzlens headless")

    def prepare(self) -> None:
        try:
            self._start_session()
            self._launch_app()
            tree = self._wait_for_window()
        except BaseException:
            self.teardown()
            raise
        if not tree.nodes:
            self.teardown()
            raise DriverError(f"{self.app_id} shows a tree with no nodes after launch")

    def _read_line(self, fd: int, proc: subprocess.Popen[Any], what: str) -> str:
        """The first line a child writes to `fd` (Xvfb's display number, dbus-daemon's address)."""
        buf = b""
        deadline = time.monotonic() + self.start_timeout_s
        while b"\n" not in buf:
            if time.monotonic() > deadline:
                raise DriverTimeoutError(f"no {what} after {self.start_timeout_s:.0f}s")
            ready, _, _ = select.select([fd], [], [], 0.1)
            if ready:
                chunk = os.read(fd, 256)
                if not chunk:
                    break
                buf += chunk
            elif proc.poll() is not None:
                break
        line = buf.decode("utf-8", errors="replace").strip()
        if not line:
            raise DriverError(f"{what} did not start (exit {proc.poll()})")
        return line

    def _spawn_reporting(self, cmd: List[str], what: str) -> Tuple[subprocess.Popen[Any], str]:
        """Start `cmd` with `{fd}` replaced by a pipe it reports on; return it and what it reported."""
        r, w = os.pipe()
        try:
            proc = subprocess.Popen(
                [c.replace("{fd}", str(w)) for c in cmd],
                pass_fds=(w,), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
        finally:
            os.close(w)
        self._procs.append(proc)
        try:
            return proc, self._read_line(r, proc, what)
        finally:
            os.close(r)

    def _start_session(self) -> None:
        xvfb = shutil.which("Xvfb")
        dbus = "/usr/bin/dbus-daemon" if Path("/usr/bin/dbus-daemon").is_file() else shutil.which("dbus-daemon")
        if not xvfb or not dbus:
            raise DriverError("Xvfb and dbus-daemon are needed (install xvfb dbus)")
        for sub in ("xdg-data", "xdg-config"):
            _ensure_dir(self.lab_dir / sub)
        _ensure_dir(self.artifacts_dir)
        work = ensure_under(self.working_dir, lab_tmp_root())
        if not work.is_dir() or not any(work.iterdir()):
            generate_fixtures(self.fixture_set, work, root=repo_root())
        self.working_dir = work

        _, display = self._spawn_reporting([xvfb, "-displayfd", "{fd}", "-screen", "0", f"{self.size}x24", "-nolisten", "tcp"], "Xvfb display number")
        _, bus = self._spawn_reporting([dbus, "--session", "--nofork", "--print-address={fd}"], "D-Bus session address")
        if not display.isdigit() or not bus.startswith(("unix:", "tcp:")):
            raise DriverError(f"unexpected session answers: display {display!r}, bus {bus!r}")

        env = session_env()  # the app's interpreter (PST_LAB_PYTHON) and PYTHONPATH of the source tree
        for inherited in ("WAYLAND_DISPLAY", "DBUS_SESSION_BUS_ADDRESS", "DBUS_SYSTEM_BUS_ADDRESS", "XDG_SESSION_TYPE"):
            env.pop(inherited, None)
        env.update({
            "DISPLAY": f":{display}",
            "DBUS_SESSION_BUS_ADDRESS": bus,  # the private bus; never the user's
            "GDK_BACKEND": "x11",
            "GDK_SCALE": "1",
            "GSK_RENDERER": "cairo",
            "GDK_DEBUG": "no-portals",
            "GTK_USE_PORTAL": "0",
            "ADW_DISABLE_PORTAL": "1",
            "GSETTINGS_BACKEND": "memory",  # settings the app writes stay in the process
            "PST_LAB": "1",
            "PST_LAB_ARTIFACTS": str(self.artifacts_dir),
            "XDG_DATA_HOME": str(self.lab_dir / "xdg-data"),
            "XDG_CONFIG_HOME": str(self.lab_dir / "xdg-config"),
            "PYTHONDONTWRITEBYTECODE": "1",
        })
        self._env = env

    def _launch_app(self) -> None:
        self._close_app()
        self._app_log = open(self.artifacts_dir / "app.log", "ab")
        self._log_offset = self._app_log.tell()
        self._app_proc = subprocess.Popen(
            [self._env["PST_LAB_PYTHON"], "-m", "photo_selector_linux", str(self.working_dir)],
            env=self._env, stdout=self._app_log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
        )

    def _app_log_tail(self) -> str:
        path = self.artifacts_dir / "app.log"
        return path.read_text(encoding="utf-8", errors="replace")[-400:].strip() if path.is_file() else ""

    def _wait_for_window(self) -> Tree:
        """Wait for Blitzlens' window, fill the screen as a maximised window would, return its first tree."""
        deadline = time.monotonic() + self.start_timeout_s
        while True:
            self._require_app_alive()
            found = self._run(["xdotool", "search", "--onlyvisible", "--name", "Blitzlens"])
            if found.returncode == 0 and (found.stdout or "").strip():
                break
            if time.monotonic() > deadline:
                raise DriverTimeoutError(f"no Blitzlens window after {self.start_timeout_s:.0f}s: {self._app_log_tail()}")
            time.sleep(0.2)
        self._check(
            self._run(["xdotool", "search", "--onlyvisible", "--name", "Blitzlens",
                       "windowmove", "--sync", "0", "0", "windowsize", "--sync", "100%", "100%"]),
            "size the window",
        )
        self.settle(1000)
        return self.capture_state().tree

    def _close_app(self) -> None:
        proc, self._app_proc = self._app_proc, None
        if proc is not None and proc.poll() is None:
            self._stop(proc, 3)
        if self._app_log is not None:
            self._app_log.close()
            self._app_log = None

    @staticmethod
    def _stop(proc: subprocess.Popen[Any], wait_s: float) -> None:
        proc.terminate()
        try:
            proc.wait(timeout=wait_s)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=wait_s)

    def teardown(self) -> None:
        """Stop the app, the bus and the display; remove the session directory."""
        self._close_app()
        for proc in reversed(self._procs):
            if proc.poll() is None:
                try:
                    self._stop(proc, 2)
                except (OSError, subprocess.TimeoutExpired):
                    pass
        self._procs.clear()
        self._env = {}
        shutil.rmtree(ensure_under(self.lab_dir, lab_tmp_root()), ignore_errors=True)
        if self._owns_out_dir:
            shutil.rmtree(self.out_dir, ignore_errors=True)

    def restart_app(self) -> None:
        """Stop the app and start it again on the same folder, in the same session."""
        self._launch_app()
        self.last_state = None
        if not self._wait_for_window().nodes:
            raise DriverError(f"{self.app_id} shows a tree with no nodes after restart")

    # -- commands -----------------------------------------------------------------
    def _run(self, cmd: List[str], timeout: float = 10.0) -> Any:
        if self.exec_fn is not None:
            proc = self.exec_fn(cmd, self._env)
            if proc is not None:
                return proc
            return subprocess.CompletedProcess(cmd, 0, "", "")
        try:
            return subprocess.run(cmd, env=self._env, capture_output=True, text=True, timeout=timeout, check=False)
        except subprocess.TimeoutExpired as exc:
            raise DriverTimeoutError(f"{cmd[0]} timed out after {timeout:.0f}s") from exc
        except OSError as exc:
            raise DriverExecutionError(f"cannot run {cmd[0]}: {exc}") from exc

    @staticmethod
    def _check(proc: Any, what: str) -> None:
        """xdotool exits 0 with only a warning for a key name it does not know: read the output too."""
        text = f"{getattr(proc, 'stdout', '') or ''}{getattr(proc, 'stderr', '') or ''}".strip()
        if proc.returncode != 0:
            raise DriverExecutionError(f"{what}: exit {proc.returncode}: {text[:200]}")
        if "No such key name" in text:
            raise DriverExecutionError(f"{what}: {text[:200]}")

    def _require_app_alive(self) -> None:
        if self._app_proc is None:
            raise DriverError("the app has not been started")
        code = self._app_proc.poll()
        if code is not None:
            raise AppCrashedError(f"{self.app_id} exited with {code}: {self._app_log_tail()}")

    # -- observation --------------------------------------------------------------
    def capture_state(self) -> ScreenState:
        """Ask the app for a checkpoint (`lab-checkpoint` over the private bus) and read what it wrote."""
        self._require_app_alive()
        self._captures += 1
        name = f"s{self._captures}"
        self._check(
            self._run(["gapplication", "action", self.app_id, "lab-checkpoint", f"'{name}'"]),
            f"lab-checkpoint {name}",
        )
        deadline = time.monotonic() + self.checkpoint_timeout_s
        while True:
            timing = sorted(self.artifacts_dir.glob(f"*_{name}.timing.json"))  # written last
            if timing:
                break
            self._require_app_alive()
            if time.monotonic() > deadline:
                raise DriverTimeoutError(f"checkpoint {name} was not written within {self.checkpoint_timeout_s:.0f}s")
            time.sleep(0.05)
        stem = timing[0].name[: -len(".timing.json")]
        png_path, tree_path = self.artifacts_dir / f"{stem}.png", self.artifacts_dir / f"{stem}.tree.json"
        if not png_path.is_file() or png_path.stat().st_size == 0:
            raise DriverError(f"checkpoint {stem} has no screenshot")
        try:
            tree = Tree.from_dict(json.loads(tree_path.read_text(encoding="utf-8")))
        except (OSError, ValueError, KeyError) as exc:
            raise DriverError(f"checkpoint {stem} has no readable tree: {exc}") from exc
        position = self._read_position(self.artifacts_dir / f"{stem}.state.json")
        try:
            fs_snapshot = snapshot_directory(
                self.working_dir, lab_tmp_root(), trash_dir=self.lab_dir / "xdg-data" / "Trash" / "files", root_label="work"
            )
        except (OSError, ValueError) as exc:
            raise DriverError(f"cannot list {self.working_dir}: {exc}") from exc
        state = ScreenState(
            tree=tree,
            screenshot_bytes=png_path.read_bytes(),
            screen_mode=classify_screen(tree, self.product),
            fs_snapshot=fs_snapshot,
            logs=[],
            position=position,
        )
        self.last_state = state
        return state

    @staticmethod
    def _read_position(path: Path) -> Tuple[int, int]:
        """`(current_index, total)` from the checkpoint's `state.json`, the app's own position.

        The tree has no counter node ("Photo 5 of 9" is not exported), so without this file no
        navigation step on Blitzlens is checked; a checkpoint without it is a driver error.
        """
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            return int(data["current_index"]), int(data["total"])
        except (OSError, ValueError, KeyError, TypeError) as exc:
            raise DriverError(f"checkpoint {path.name} has no readable position: {exc}") from exc

    def read_app_log(self) -> List[str]:
        """What the app wrote to stderr/stdout since the last call."""
        path = self.artifacts_dir / "app.log"
        if not path.is_file():
            return []
        with open(path, "rb") as fh:
            fh.seek(self._log_offset)
            data = fh.read()
        self._log_offset += len(data)
        return data.decode("utf-8", errors="replace").splitlines()

    # -- actions ------------------------------------------------------------------
    def execute_action(self, action: Action) -> None:
        """Perform `action` with xdotool; raises `DriverExecutionError` when it cannot."""
        self.step_count += 1
        t = action.action_type
        if t == "key":
            self._press(action)
        elif t in ("tap", "click"):
            self._click(action)
        elif t == "resize":
            w = int(_parse_coord(action.params.get("w", action.params.get("width")), "w"))
            h = int(_parse_coord(action.params.get("h", action.params.get("height")), "h"))
            self._check(
                self._run(["xdotool", "search", "--onlyvisible", "--name", "Blitzlens", "windowsize", "--sync", str(w), str(h)]),
                f"resize to {w}x{h}",
            )
        elif t == "back":
            self._back()
        else:
            raise DriverExecutionError(f"Unsupported action type for Linux: {t}")

    _XMODS = {"control": "ctrl", "shift": "shift", "option": "alt", "command": "super", "super": "super"}

    # The tree's window is the X window less its client-side shadow, which is at most this much.
    WINDOW_SIZE_TOLERANCE = 24

    def _window_size(self, wid: int) -> Tuple[int, int]:
        proc = self._run(["xdotool", "getwindowgeometry", "--shell", str(wid)])
        self._check(proc, f"read the size of window {wid}")
        values = dict(line.split("=", 1) for line in (proc.stdout or "").splitlines() if "=" in line)
        try:
            return int(values["WIDTH"]), int(values["HEIGHT"])
        except (KeyError, ValueError) as exc:
            raise DriverExecutionError(f"cannot read the size of window {wid}: {proc.stdout!r}") from exc

    def _visible_windows(self, name: str = ".") -> List[int]:
        found = self._run(["xdotool", "search", "--onlyvisible", "--name", name])
        if name == "." or found.returncode == 0:
            self._check(found, "look for the app's windows")
        return sorted(int(w) for w in (found.stdout or "").split() if w.isdigit())

    def _focus_frontmost(self) -> int:
        """Give the keyboard to the window the last tree described (a dialog over the main window).

        There is no window manager, so X focus follows the pointer: after a click the keys
        would reach whichever window sits under it, and a dialog the pointer is not over never
        hears Escape. The window is the newest visible one of the tree's size; tooltips and
        popovers are newer still, but they are not what the tree shows. Returns its id.
        """
        ids = self._visible_windows()
        if not ids:
            raise DriverExecutionError("no visible window to send the key to")
        target = ids[0]
        if self.last_state is not None:
            want_w, want_h = self.last_state.tree.window_w, self.last_state.tree.window_h
            for wid in reversed(ids):
                w, h = self._window_size(wid)
                if abs(w - want_w) <= self.WINDOW_SIZE_TOLERANCE and abs(h - want_h) <= self.WINDOW_SIZE_TOLERANCE:
                    target = wid
                    break
        self._check(self._run(["xdotool", "windowfocus", str(target)]), f"focus window {target}")
        return target

    def _back(self) -> None:
        """Escape; and when a dialog that ignores it stays in front, close it as a window manager's close button would.

        Several Blitzlens windows are plain `Adw.Window`s that Escape does not close, and there is
        no window manager here to close them: without this the run would be stuck behind the
        first such dialog.
        """
        target = self._focus_frontmost()
        self._check(self._run(["xdotool", "key", "--clearmodifiers", "Escape"]), "Escape")
        if target in self._visible_windows("Blitzlens"):
            return  # the main window: nothing to close
        time.sleep(0.4)
        if target in self._visible_windows():
            self._send_wm_delete(target)

    def _send_wm_delete(self, wid: int) -> None:
        """Ask window `wid` to close (the ICCCM `WM_DELETE_WINDOW` message a window manager sends)."""
        lib = ctypes.util.find_library("X11")
        if lib is None:
            raise DriverExecutionError("libX11 is needed to close a dialog that ignores Escape")
        x11 = ctypes.CDLL(lib)
        x11.XOpenDisplay.restype = ctypes.c_void_p
        x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
        x11.XInternAtom.restype = ctypes.c_ulong
        x11.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
        x11.XSendEvent.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_long, ctypes.c_void_p]
        x11.XFlush.argtypes = [ctypes.c_void_p]
        x11.XCloseDisplay.argtypes = [ctypes.c_void_p]

        class ClientMessage(ctypes.Structure):
            _fields_ = [("type", ctypes.c_int), ("serial", ctypes.c_ulong), ("send_event", ctypes.c_int),
                        ("display", ctypes.c_void_p), ("window", ctypes.c_ulong), ("message_type", ctypes.c_ulong),
                        ("format", ctypes.c_int), ("data", ctypes.c_long * 5)]

        class Event(ctypes.Union):
            _fields_ = [("xclient", ClientMessage), ("pad", ctypes.c_long * 24)]

        display = x11.XOpenDisplay(self._env["DISPLAY"].encode())
        if not display:
            raise DriverExecutionError(f"cannot open display {self._env['DISPLAY']}")
        try:
            event = Event()
            event.xclient.type = 33  # ClientMessage
            event.xclient.window = wid
            event.xclient.message_type = x11.XInternAtom(display, b"WM_PROTOCOLS", 0)
            event.xclient.format = 32
            event.xclient.data[0] = x11.XInternAtom(display, b"WM_DELETE_WINDOW", 0)
            if not x11.XSendEvent(display, wid, 0, 0, ctypes.byref(event)):
                raise DriverExecutionError(f"cannot send WM_DELETE_WINDOW to window {wid}")
            x11.XFlush(display)
        finally:
            x11.XCloseDisplay(display)

    def _press(self, action: Action) -> None:
        key, mods = _key_spec(action)
        if key.lower() in ("escape", "esc") and not mods:
            self._back()  # Escape also has to get out of a dialog that ignores it
            return
        combo = "+".join([*(self._XMODS[m] for m in mods), normalize_xdotool_key(key)])
        self._focus_frontmost()
        self._check(self._run(["xdotool", "key", "--clearmodifiers", combo]), f"key {combo}")

    def _click(self, action: Action) -> None:
        x_raw, y_raw = action.params.get("x"), action.params.get("y")
        if (x_raw is None) != (y_raw is None):
            raise DriverExecutionError(f"Incomplete click coordinates in {action.params}")
        if x_raw is None:
            node = self._known_node(action.target_id)
            x_raw, y_raw = node.x + node.w / 2.0, node.y + node.h / 2.0
        x, y = int(round(_parse_coord(x_raw, "x"))), int(round(_parse_coord(y_raw, "y")))
        double = bool(action.params.get("double_click") or action.params.get("double_tap") or action.params.get("count") == 2)
        click = ["click", "--repeat", "2", "--delay", "80", "1"] if double else ["click", "1"]
        # No `--sync`: xdotool then waits for the pointer to arrive, and never returns when it is already there.
        self._check(self._run(["xdotool", "mousemove", str(x), str(y), *click]), f"click at {x},{y}")

    def _known_node(self, target_id: Optional[str]) -> Node:
        if target_id and self.last_state:
            for node in self.last_state.tree.nodes:
                if node.id == target_id:
                    return node
        raise DriverExecutionError(f"click has no coordinates and no known target {target_id!r}")


# ==============================================================================
# macOS (refused)
# ==============================================================================

class MacOSDeviceDriver(DeviceDriver):
    """Lensjet cannot be explored yet: its test hook runs a whole scenario per launch.

    A live exploration needs the app held open and driven step by step; replaying the history
    of earlier steps into a fresh launch every capture is not that (duplicate checkpoints,
    dropped modifiers, nothing observed on a remote Mac). Until such a driver exists this one
    refuses, so no run reports a Lensjet exploration it did not do.
    """

    REFUSAL = "Lensjet exploration needs a live-app driver; not implemented yet"

    def __init__(
        self,
        app_id: str = "app.alpatix.lensjet",
        fixture_dir: Path | str = "/tmp/pstlab/fixture",
        out_dir: Optional[Path] = None,
        settle_ms: int = 250,
        **_ignored: Any,
    ) -> None:
        super().__init__("macos-desktop", app_id, fixture_dir, out_dir or Path("."), settle_ms)

    def _refuse(self) -> DriverError:
        return DriverError(self.REFUSAL)

    def setup(self) -> None:
        raise self._refuse()

    def prepare(self) -> None:
        raise self._refuse()

    def teardown(self) -> None:
        pass

    def capture_state(self) -> ScreenState:
        raise self._refuse()

    def execute_action(self, action: Action) -> None:
        raise self._refuse()

    def restart_app(self) -> None:
        raise self._refuse()

    def read_app_log(self) -> List[str]:
        raise self._refuse()


# ==============================================================================
# Offline Synthetic Mock Driver
# ==============================================================================

class MockDeviceDriver(DeviceDriver):
    """Deterministic offline driver producing synthetic state transitions and injecting test faults."""

    PNG_1PX = (
        b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde"
        b"\x00\x00\x00\x0cIDATx\x9cc\xf8\xcf\xc0\x00\x00\x03\x01\x01\x00\xc9\xfe\x92\xef\x00\x00\x00\x00IEND\xaeB`\x82"
    )
    SUPPORTED_ACTIONS = frozenset({"tap", "click", "swipe", "key", "back", "background", "resize", "rotate"})

    def __init__(
        self,
        product: str = "phototok",
        app_id: str = "com.phototok.lab",
        working_dir: Optional[Path] = None,
        out_dir: Optional[Path] = None,
        total_photos: int = 5,
        fail_on_action: Optional[str] = None,
        settle_failure_count: int = 0,
        timeout_on_capture: bool = False,
        capture_errors: Optional[List[BaseException]] = None,
        log_lines: Optional[List[str]] = None,
    ) -> None:
        super().__init__(
            product, app_id, working_dir or _scratch_path("mock-work"), out_dir or _scratch_path("mock-out"), settle_ms=0
        )
        self._owns_working_dir = working_dir is None
        self._owns_out_dir = out_dir is None
        self.total_photos = total_photos
        self.current_index = 0
        self.screen_mode = self._home_mode()
        self.selected_photos: List[str] = []
        self.deleted_photos: List[str] = []
        self.undo_stack: List[Tuple[str, str]] = []  # (action_type, photo_name)
        self.fail_on_action = fail_on_action
        self.settle_failure_count = settle_failure_count
        self.timeout_on_capture = timeout_on_capture
        # Errors the next captures raise, one per call, in order (an AppLeftError, an AppCrashedError, ...).
        self.capture_errors: List[BaseException] = list(capture_errors or [])
        self.pending_log: List[str] = list(log_lines or [])
        self.executed_actions: List[Action] = []
        self.restarts = 0

    def _home_mode(self) -> str:
        return "landing" if self.product == "phototok" else "selector"

    def setup(self) -> None:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        if isinstance(self.working_dir, Path):
            self.working_dir.mkdir(parents=True, exist_ok=True)
            for i in range(1, self.total_photos + 1):
                (self.working_dir / f"photo_{i:02d}.jpg").write_text("mock photo content", encoding="utf-8")

    def prepare(self) -> None:
        pass

    def teardown(self) -> None:
        if self._owns_working_dir and isinstance(self.working_dir, Path):
            shutil.rmtree(self.working_dir, ignore_errors=True)
        if self._owns_out_dir and self.out_dir:
            shutil.rmtree(self.out_dir, ignore_errors=True)

    def restart_app(self) -> None:
        self.restarts += 1
        self.screen_mode = self._home_mode()
        self.current_index = 0

    def read_app_log(self) -> List[str]:
        lines, self.pending_log = self.pending_log, []
        return lines

    def capture_state(self) -> ScreenState:
        if self.capture_errors:
            raise self.capture_errors.pop(0)

        if self.timeout_on_capture:
            raise DriverTimeoutError("Mock capture timed out after 30s")

        if self.settle_failure_count > 0:
            self.settle_failure_count -= 1
            raise DriverSettlingError("ERROR: could not get idle state.")

        tree = self._build_synthetic_tree()
        fs_snapshot = self._build_synthetic_fs()

        state = ScreenState(
            tree=tree,
            screenshot_bytes=self.PNG_1PX,
            screen_mode=self.screen_mode,
            fs_snapshot=fs_snapshot,
            logs=[],
        )
        self.last_state = state
        return state

    def execute_action(self, action: Action) -> None:
        self.step_count += 1
        self.executed_actions.append(action)

        if self.fail_on_action and action.action_type == self.fail_on_action:
            raise DriverExecutionError(f"Simulated execution failure for action '{action.action_type}'")

        t = action.action_type
        if t not in self.SUPPORTED_ACTIONS:
            raise DriverExecutionError(f"Unsupported action type for the mock: {t}")
        photo_name = f"photo_{self.current_index + 1:02d}.jpg"

        if t in ("tap", "click"):
            if self.screen_mode == "landing":
                self.screen_mode = "viewer"
            elif action.target_id == "inspect_tab":
                self.screen_mode = "inspect"
            elif action.target_id == "selector_tab":
                self.screen_mode = "selector"

        elif t == "swipe":
            direction = action.params.get("direction", "up")
            if direction in ("up", "left"):
                self.current_index = min(self.current_index + 1, self.total_photos - 1)
            elif direction in ("down", "right"):
                self.current_index = max(0, self.current_index - 1)

        elif t == "key":
            key = str(action.params.get("key", "")).upper()
            mods = [str(m).lower() for m in action.params.get("modifiers", [])]

            if ("ctrl" in mods or "cmd" in mods) and key == "Z":
                if self.undo_stack:
                    last_act, last_photo = self.undo_stack.pop()
                    if last_act == "move" and last_photo in self.selected_photos:
                        self.selected_photos.remove(last_photo)
                    elif last_act == "delete" and last_photo in self.deleted_photos:
                        self.deleted_photos.remove(last_photo)
            elif key == "M":
                if photo_name not in self.selected_photos:
                    self.selected_photos.append(photo_name)
                    self.undo_stack.append(("move", photo_name))
                    self.current_index = min(self.current_index + 1, self.total_photos - 1)
            elif key in ("DEL", "DELETE"):
                if photo_name not in self.deleted_photos:
                    self.deleted_photos.append(photo_name)
                    self.undo_stack.append(("delete", photo_name))
                    self.current_index = min(self.current_index + 1, self.total_photos - 1)
            elif key in ("RIGHT", "L", "J"):
                self.current_index = min(self.current_index + 1, self.total_photos - 1)
            elif key in ("LEFT", "H", "K"):
                self.current_index = max(0, self.current_index - 1)

        elif t == "back":
            if self.screen_mode in ("viewer", "inspect"):
                self.screen_mode = self._home_mode()

    def _build_synthetic_tree(self) -> Tree:
        nodes = []
        if self.screen_mode == "landing":
            nodes.append(Node(id="landing_root", parent=None, role="container", name="Landing", bounds=[0, 0, 1080, 1920], interactive=False, visible=True))
            nodes.append(Node(id="card_folder", parent="landing_root", role="button", name="Folder or Cloud", bounds=[100, 400, 880, 200], interactive=True, visible=True))
        elif self.screen_mode == "viewer":
            nodes.append(Node(id="viewer_root", parent=None, role="container", name="Viewer", bounds=[0, 0, 1080, 1920], interactive=False, visible=True))
            nodes.append(Node(id="viewer.photo", parent="viewer_root", role="image", name=f"Photo {self.current_index + 1}", bounds=[0, 120, 1080, 1600], interactive=True, visible=True))
            nodes.append(Node(id="counter_hud", parent="viewer_root", role="text", name=f"{self.current_index + 1} / {self.total_photos}", bounds=[40, 40, 200, 60], interactive=False, visible=True, text=f"{self.current_index + 1} / {self.total_photos}"))
        elif self.screen_mode == "selector":
            nodes.append(Node(id="selector_root", parent=None, role="container", name="Selector", bounds=[0, 0, 1480, 924], interactive=False, visible=True))
            nodes.append(Node(id="selector.frame.current", parent="selector_root", role="image", name="Current Frame", bounds=[200, 50, 1080, 500], interactive=True, visible=True))
            nodes.append(Node(id="selector.frame.previous", parent="selector_root", role="image", name="Previous Frame", bounds=[200, 580, 520, 300], interactive=True, visible=True))
            nodes.append(Node(id="selector.frame.next", parent="selector_root", role="image", name="Next Frame", bounds=[760, 580, 520, 300], interactive=True, visible=True))
        elif self.screen_mode == "inspect":
            nodes.append(Node(id="inspect_root", parent=None, role="container", name="Inspect", bounds=[0, 0, 1480, 924], interactive=False, visible=True))
            nodes.append(Node(id="inspect_tab", parent="inspect_root", role="button", name="Explore Tab", bounds=[40, 40, 200, 50], interactive=True, visible=True))

        return Tree(
            platform=self.product,
            window={"w": 1080 if self.product == "phototok" else 1480, "h": 1920 if self.product == "phototok" else 924, "unit": "dp", "scale": 2.0},
            nodes=nodes,
        )

    def _build_synthetic_fs(self) -> Dict[str, Any]:
        files = []
        for i in range(1, self.total_photos + 1):
            p = f"photo_{i:02d}.jpg"
            if p not in self.deleted_photos:
                if p in self.selected_photos:
                    files.append({"path": f"Selection/{p}", "size": 100})
                else:
                    files.append({"path": p, "size": 100})
        trash = [{"path": p, "size": 100} for p in self.deleted_photos]
        return {
            "root": "mock_work",
            "files": files,
            "trash": trash,
        }
