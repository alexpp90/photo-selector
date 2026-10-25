"""Device interaction drivers for exploratory testing across Android, Linux, macOS, and Mock environments.

Executes candidate interactions directly per target:
- Android: direct adb per device (uiautomator stream dump, screencap, input tap/swipe/keys,
  bypassing Maestro lock, acquiring only device-<serial>.lock).
- Linux: isolated Xvfb + dbus-run-session with xdotool and D-Bus lab-checkpoint.
- macOS: headless PhotoSelectorApp --ui-scenario or remote pst-lab LaunchAgent.
- Mock: deterministic offline state machine for unit tests and local runs.
"""

from __future__ import annotations

import json
import math
import os
import re
import shlex
import shutil
import signal
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from ..android.adb import AdbClient
from ..android.tree_android import parse_uiautomator_xml
from ..fixtures import generate_fixtures
from ..fs_oracle import snapshot_directory
from ..locks import device_lock
from ..model import Node, Tree
from ..paths import ensure_under, lab_tmp_root, repo_root
from .actions import Action, ScreenState


# ==============================================================================
# Error Hierarchy
# ==============================================================================

class DriverError(Exception):
    """Base class for all device driver errors."""


class DriverTimeoutError(DriverError):
    """Action or state capture exceeded its timeout budget."""


class DriverExecutionError(DriverError):
    """Command failed or returned a non-zero exit code."""


class DriverSettlingError(DriverError):
    """UI state did not become idle after max retry attempts."""


class AppCrashedError(DriverError):
    """Application process died or unexpectedly vanished from foreground."""


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

    def __enter__(self) -> DeviceDriver:
        self.setup()
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        self.teardown()

    @abstractmethod
    def setup(self) -> None:
        """Initialize the target device, launch the app, and prepare working environment."""

    @abstractmethod
    def teardown(self) -> None:
        """Clean up processes, restore device settings, and release locks."""

    @abstractmethod
    def capture_state(self) -> ScreenState:
        """Capture the live screen state: UI tree, screenshot bytes, screen mode, fs snapshot, and logs."""

    @abstractmethod
    def execute_action(self, action: Action) -> bool:
        """Execute a candidate action on the target environment and wait for it to settle."""

    def settle(self, custom_settle_ms: Optional[int] = None) -> None:
        """Wait for UI transitions, layout passes, and animations to complete."""
        ms = custom_settle_ms if custom_settle_ms is not None else self.settle_ms
        if ms > 0:
            time.sleep(ms / 1000.0)


# ==============================================================================
# Direct Android Device Driver (No Maestro Lock)
# ==============================================================================

class AndroidDeviceDriver(DeviceDriver):
    """Direct adb driver for Android devices (Jackpix & Frameduel) without Maestro lock."""

    def __init__(
        self,
        serial: str,
        app_id: str,
        product: str = "phototok",
        working_dir: str = "/sdcard/PSTLab/work",
        out_dir: Optional[Path] = None,
        adb: Optional[AdbClient] = None,
        settle_ms: int = 500,
        dump_retries: int = 5,
        holder_name: str = "explore-worker",
    ) -> None:
        out = out_dir or Path(tempfile.mkdtemp(prefix=f"explore_android_{serial}_", dir=lab_tmp_root()))
        super().__init__(product, app_id, working_dir, out, settle_ms)
        self.serial = serial
        self.adb = adb or AdbClient(serial=serial)
        self.dump_retries = dump_retries
        self.holder_name = holder_name
        self._lock_ctx: Any = None
        self._density: Optional[int] = None
        self._window_size: Tuple[int, int] = (1080, 1920)

    def setup(self) -> None:
        """Acquire per-device lock, wake device, dismiss keyguard, and launch app."""
        # Hold ONLY device lock: NEVER android-hub.lock
        self._lock_ctx = device_lock(self.serial, self.holder_name)
        self._lock_ctx.__enter__()

        try:
            self.adb.ensure_ready()
            self._density = self.adb.density()
            self.adb.start(self.app_id)
            self.settle(1200)
        except BaseException:
            self.teardown()
            raise

    def teardown(self) -> None:
        """Release device lock and clean up."""
        if self._lock_ctx is not None:
            try:
                self._lock_ctx.__exit__(None, None, None)
            except Exception:
                pass
            self._lock_ctx = None

    def capture_state(self) -> ScreenState:
        """Capture live ScreenState using direct adb stream commands with Compose settling."""
        tree = self._dump_tree()
        screenshot_bytes = self.adb.run_bytes("exec-out", "screencap", "-p", timeout=30)
        mode = self._detect_screen_mode(tree)
        fs_snapshot = self._snapshot_fs()
        logs = self._collect_logs()

        state = ScreenState(
            tree=tree,
            screenshot_bytes=screenshot_bytes,
            screen_mode=mode,
            fs_snapshot=fs_snapshot,
            logs=logs,
        )
        self.last_state = state
        return state

    def _dump_tree(self) -> Tree:
        """Stream UiAutomator XML directly over adb with retry backoff for Compose animation settling."""
        last_error = ""
        for attempt in range(self.dump_retries):
            try:
                raw = self.adb.run_bytes("exec-out", "uiautomator", "dump", "/dev/tty", timeout=30)
                text = raw.decode("utf-8", errors="replace")
                start = text.find("<hierarchy")
                end = text.rfind("</hierarchy>")
                if start >= 0 and end > start:
                    xml_clean = text[start : end + len("</hierarchy>")]
                    density = self._density or 320
                    tree = parse_uiautomator_xml(xml_clean, density_dpi=density)
                    if tree.window_w > 0 and tree.window_h > 0:
                        self._window_size = (int(tree.window_w), int(tree.window_h))
                    return tree
                last_error = text.strip()[:160]
            except (subprocess.TimeoutExpired, subprocess.SubprocessError, ET.ParseError) as exc:
                last_error = f"ParseError: {exc}" if isinstance(exc, ET.ParseError) else str(exc)

            # Exponential backoff for animation settling
            time.sleep(0.3 * (attempt + 1))

        # Check if app crashed
        try:
            crashes = self.adb.run("logcat", "-d", "-b", "crash", "-t", "20", timeout=10)
            if crashes.strip():
                raise AppCrashedError(f"Application {self.app_id} crashed: {crashes.strip()}")
        except AppCrashedError:
            raise
        except Exception:
            pass

        raise DriverSettlingError(f"UiAutomator dump failed after {self.dump_retries} attempts: {last_error}")

    def _detect_screen_mode(self, tree: Tree) -> str:
        """Determine screen mode based on visible nodes."""
        node_ids = {n.id for n in tree.nodes if n.visible}
        if any("landing" in nid or "start" in nid for nid in node_ids):
            return "landing"
        if "selector.frame.current" in node_ids or "image_region" in node_ids:
            return "selector"
        if "viewer.photo" in node_ids or "vertical_pager" in node_ids:
            return "viewer"
        if any("inspect" in nid for nid in node_ids):
            return "inspect"
        if any("dialog" in nid or "alert" in nid for nid in node_ids):
            return "dialog"
        return "viewer" if self.product == "phototok" else "selector"

    def _snapshot_fs(self) -> Dict[str, Any]:
        """Inspect working directory on device via adb find & stat."""
        try:
            files = self.adb.list_files(str(self.working_dir))
        except Exception:
            files = []
        return {
            "root": str(self.working_dir).split("/")[-1],
            "files": files,
            "trash": [],
        }

    def _collect_logs(self) -> List[str]:
        """Collect recent logcat lines for the app."""
        try:
            raw = self.adb.run("logcat", "-d", "-b", "main,crash", "-t", "50", timeout=10)
            return raw.splitlines()[-50:]
        except Exception:
            return []

    def execute_action(self, action: Action) -> bool:
        """Dispatch action to direct adb input commands."""
        self.step_count += 1
        t = action.action_type

        if t in ("tap", "click"):
            self._handle_tap(action)
        elif t == "swipe":
            self._handle_swipe(action)
        elif t == "key":
            self._handle_key(action)
        elif t == "back":
            self.adb.key("KEYCODE_BACK")
        elif t == "background":
            dur = float(action.params.get("duration_s", 1.0))
            self.adb.home()
            time.sleep(dur)
            self.adb.start(self.app_id)
        elif t == "resize":
            self._handle_resize(action)
        elif t == "rotate":
            self._handle_rotate(action)
        else:
            raise DriverExecutionError(f"Unsupported action type: {t}")

        self.settle()
        return True

    def _handle_tap(self, action: Action) -> None:
        scale = (self._density or 320) / 160.0
        x = action.params.get("x")
        y = action.params.get("y")
        if x is None and y is None:
            if action.target_id and self.last_state:
                node = next((n for n in self.last_state.tree.nodes if n.id == action.target_id), None)
                if node:
                    nx = _parse_coord(node.x + node.w / 2.0, "node_x")
                    ny = _parse_coord(node.y + node.h / 2.0, "node_y")
                    x = int(nx * scale)
                    y = int(ny * scale)
        elif x is not None and y is not None:
            x_f = _parse_coord(x, "x")
            y_f = _parse_coord(y, "y")
            x = int(x_f * scale)
            y = int(y_f * scale)
        else:
            raise DriverExecutionError(f"Incomplete tap coordinates in {action.params}")

        if x is None or y is None:
            raise DriverExecutionError(f"Cannot tap without coordinates or valid target_id: {action}")
        self.adb.run("shell", "input", "tap", str(int(x)), str(int(y)))

    def _handle_swipe(self, action: Action) -> None:
        direction = action.params.get("direction", "up")
        duration = action.params.get("duration_ms", 250)
        try:
            dur_int = int(duration)
        except (ValueError, TypeError) as exc:
            raise DriverExecutionError(f"Invalid swipe duration: {duration!r}") from exc

        x1 = action.params.get("x1") if action.params.get("x1") is not None else action.params.get("start_x")
        y1 = action.params.get("y1") if action.params.get("y1") is not None else action.params.get("start_y")
        x2 = action.params.get("x2") if action.params.get("x2") is not None else action.params.get("end_x")
        y2 = action.params.get("y2") if action.params.get("y2") is not None else action.params.get("end_y")

        scale = (self._density or 320) / 160.0

        if x1 is not None or y1 is not None or x2 is not None or y2 is not None:
            if x1 is None or y1 is None or x2 is None or y2 is None:
                raise DriverExecutionError(f"Incomplete swipe coordinates in {action.params}")
            px1 = int(_parse_coord(x1, "x1") * scale)
            py1 = int(_parse_coord(y1, "y1") * scale)
            px2 = int(_parse_coord(x2, "x2") * scale)
            py2 = int(_parse_coord(y2, "y2") * scale)
            self.adb.run("shell", "input", "swipe", str(px1), str(py1), str(px2), str(py2), str(dur_int))
            return

        w_px = int(_parse_coord(self._window_size[0], "window_w") * scale)
        h_px = int(_parse_coord(self._window_size[1], "window_h") * scale)
        cx, cy = w_px // 2, h_px // 2
        dx, dy = int(w_px * 0.35), int(h_px * 0.28)

        vecs = {
            "up": (0, -dy),
            "down": (0, dy),
            "left": (-dx, 0),
            "right": (dx, 0),
        }
        vec = vecs.get(direction, (0, -dy))
        x1_c, y1_c = cx - vec[0], cy - vec[1]
        x2_c, y2_c = cx + vec[0], cy + vec[1]
        self.adb.run("shell", "input", "swipe", str(int(x1_c)), str(int(y1_c)), str(int(x2_c)), str(int(y2_c)), str(dur_int))

    def _handle_key(self, action: Action) -> None:
        key = str(action.params.get("key", ""))
        mods = action.params.get("modifiers", [])
        if not key:
            raise DriverExecutionError("Key action missing 'key' parameter")

        code = self._map_keycode(key)
        if mods:
            mod_codes = [self._map_modifier(m) for m in mods]
            self.adb.key_combo(*mod_codes, code)
        else:
            self.adb.key(code)

    def _handle_resize(self, action: Action) -> None:
        bounds = action.params.get("bounds")
        task_id = 0
        cur_bounds = None
        try:
            task_info = self.adb.app_task(self.app_id)
            task_id = task_info[0]
            cur_bounds = task_info[2]
        except Exception:
            pass

        if bounds:
            left = int(_parse_coord(bounds[0], "bounds[0]"))
            top = int(_parse_coord(bounds[1], "bounds[1]"))
            right = int(_parse_coord(bounds[2], "bounds[2]"))
            bottom = int(_parse_coord(bounds[3], "bounds[3]"))
        else:
            w = action.params.get("width")
            if w is None:
                w = action.params.get("w")
            h = action.params.get("height")
            if h is None:
                h = action.params.get("h")
            if w is None or h is None:
                return

            scale = (self._density or 320) / 160.0
            w_px = int(_parse_coord(w, "width") * scale)
            h_px = int(_parse_coord(h, "height") * scale)
            if cur_bounds:
                left, top = cur_bounds[0], cur_bounds[1]
            else:
                left, top = 0, 0
            right = left + w_px
            bottom = top + h_px

        self.adb.resize_task(task_id, left, top, right, bottom)

    def _handle_rotate(self, action: Action) -> None:
        if "rotation" in action.params:
            try:
                rot = int(action.params["rotation"])
            except (ValueError, TypeError) as exc:
                raise DriverExecutionError(f"Invalid rotation value: {action.params['rotation']!r}") from exc
        elif action.params.get("toggle"):
            try:
                out = self.adb.run("shell", "settings", "get", "system", "user_rotation")
                m = re.search(r"\d+", str(out))
                cur_rot = int(m.group(0)) if m else 0
            except Exception:
                cur_rot = 0
            rot = 1 if cur_rot == 0 else 0
        elif "orientation" in action.params:
            orient = str(action.params["orientation"]).lower()
            rot = 1 if orient == "landscape" else 0
        else:
            rot = 0

        self.adb.run("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        self.adb.run("shell", "settings", "put", "system", "user_rotation", str(rot))

    @staticmethod
    def _map_keycode(key: str) -> str:
        k = key.lower()
        mapping = {
            "right": "KEYCODE_DPAD_RIGHT", "left": "KEYCODE_DPAD_LEFT",
            "up": "KEYCODE_DPAD_UP", "down": "KEYCODE_DPAD_DOWN",
            "delete": "KEYCODE_DEL", "del": "KEYCODE_DEL", "backspace": "KEYCODE_DEL",
            "space": "KEYCODE_SPACE", "esc": "KEYCODE_ESCAPE", "escape": "KEYCODE_ESCAPE",
            "enter": "KEYCODE_ENTER", "return": "KEYCODE_ENTER", "tab": "KEYCODE_TAB",
            "m": "KEYCODE_M", "c": "KEYCODE_C", "z": "KEYCODE_Z",
            "1": "KEYCODE_1", "2": "KEYCODE_2", "3": "KEYCODE_3",
            "h": "KEYCODE_H", "j": "KEYCODE_J", "k": "KEYCODE_K", "l": "KEYCODE_L",
        }
        if k in mapping:
            return mapping[k]
        if len(k) == 1 and k.isalnum():
            return f"KEYCODE_{k.upper()}"
        return key if key.startswith("KEYCODE_") else f"KEYCODE_{key.upper()}"

    @staticmethod
    def _map_modifier(mod: str) -> str:
        m = mod.lower()
        mapping = {
            "ctrl": "KEYCODE_CTRL_LEFT", "control": "KEYCODE_CTRL_LEFT",
            "shift": "KEYCODE_SHIFT_LEFT",
            "alt": "KEYCODE_ALT_LEFT", "opt": "KEYCODE_ALT_LEFT",
            "cmd": "KEYCODE_META_LEFT", "meta": "KEYCODE_META_LEFT",
        }
        return mapping.get(m, "KEYCODE_CTRL_LEFT")


# ==============================================================================
# Direct Linux Headless Driver
# ==============================================================================

class LinuxDeviceDriver(DeviceDriver):
    """Driver for Blitzlens inside isolated Xvfb + dbus-run-session using xdotool and lab-checkpoint."""

    def __init__(
        self,
        app_id: str = "app.alpatix.Blitzlens",
        fixture_dir: Path | str = "/tmp/pstlab/fixture",
        out_dir: Optional[Path] = None,
        size: str = "1920x1080",
        text_scaling: float = 1.0,
        settle_ms: int = 300,
        session_script: Optional[Path] = None,
        exec_fn: Optional[Callable[..., Any]] = None,
        display: str = ":99",
    ) -> None:
        out = out_dir or Path(tempfile.mkdtemp(prefix="explore_linux_", dir=lab_tmp_root()))
        super().__init__("linux-desktop", app_id, fixture_dir, out, settle_ms)
        self.size = size
        self.text_scaling = text_scaling
        self.session_script = session_script or (
            repo_root() / "products" / "linux-desktop" / "tests" / "e2e" / "session.sh"
        )
        self.exec_fn = exec_fn
        safe_display = display.replace(":", "")
        unique_token = uuid.uuid4().hex[:8]
        self.lab_dir = ensure_under(
            lab_tmp_root() / f"explore-linux-{safe_display}-{os.getpid()}-{int(time.time())}-{unique_token}",
            lab_tmp_root(),
        )
        self.artifacts_dir = self.out_dir / "artifacts"
        self._env: Dict[str, str] = {}
        self._app_proc: Optional[subprocess.Popen[Any]] = None
        self._xvfb_proc: Optional[subprocess.Popen[Any]] = None
        self._dbus_pid: Optional[int] = None
        self._display: str = display

    def setup(self) -> None:
        """Launch isolated Xvfb and application with PST_LAB=1."""
        self.lab_dir.mkdir(parents=True, exist_ok=True)
        self.artifacts_dir.mkdir(parents=True, exist_ok=True)

        working_path = Path(self.working_dir).expanduser().resolve()
        working_path.mkdir(parents=True, exist_ok=True)
        image_exts = {".jpg", ".jpeg", ".png", ".webp", ".tif", ".tiff", ".cr2", ".nef", ".arw", ".dng"}
        has_images = any(f.suffix.lower() in image_exts for f in working_path.iterdir() if f.is_file())
        if not has_images:
            try:
                generate_fixtures("smoke", working_path)
            except Exception:
                pass
        self.working_dir = working_path

        try:
            self._env = dict(os.environ)
            self._env["DISPLAY"] = self._display
            self._env["GDK_BACKEND"] = "x11"
            self._env.pop("WAYLAND_DISPLAY", None)
            self._env["GSK_RENDERER"] = "cairo"
            self._env["ADW_DISABLE_PORTAL"] = "1"
            self._env["GDK_DEBUG"] = "no-portals"
            self._env["GTK_USE_PORTAL"] = "0"
            self._env["PST_LAB"] = "1"
            self._env["PST_LAB_ARTIFACTS"] = str(self.artifacts_dir)
            self._env["XDG_DATA_HOME"] = str(self.lab_dir / "xdg-data")
            self._env["XDG_CONFIG_HOME"] = str(self.lab_dir / "xdg-config")

            dbus_bin = shutil.which("dbus-daemon")
            if self.exec_fn is None and dbus_bin and "dbus-daemon" in os.path.basename(dbus_bin):
                try:
                    dbus_res = subprocess.run(
                        [dbus_bin, "--session", "--print-address", "--print-pid", "--fork"],
                        capture_output=True,
                        text=True,
                        check=True,
                        timeout=5,
                    )
                    lines = [line.strip() for line in dbus_res.stdout.splitlines() if line.strip()]
                    if len(lines) >= 2:
                        self._env["DBUS_SESSION_BUS_ADDRESS"] = lines[0]
                        self._dbus_pid = int(lines[1])
                except Exception:
                    pass

            xvfb_bin = shutil.which("Xvfb")
            if self.exec_fn is None and xvfb_bin and "xvfb" in os.path.basename(xvfb_bin).lower():
                try:
                    self._xvfb_proc = subprocess.Popen(
                        [xvfb_bin, self._display, "-screen", "0", f"{self.size}x24", "-nolisten", "tcp"],
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                    )
                    time.sleep(0.5)
                except Exception:
                    pass

            app_cmd = ["python3", "-m", "photo_selector_linux", str(self.working_dir)]
            if self.exec_fn is not None:
                self.exec_fn(app_cmd, self._env)
            elif self._xvfb_proc is not None:
                try:
                    self._app_proc = subprocess.Popen(app_cmd, env=self._env)
                    self.settle(1000)

                    xdotool_bin = shutil.which("xdotool")
                    if xdotool_bin and "xdotool" in os.path.basename(xdotool_bin):
                        for _ in range(50):
                            if self._app_proc.poll() is not None:
                                break
                            res = subprocess.run(
                                [xdotool_bin, "search", "--onlyvisible", "--name", "Blitzlens"],
                                env=self._env,
                                stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL,
                                check=False,
                            )
                            if res.returncode == 0 and res.stdout.strip():
                                subprocess.run(
                                    [
                                        xdotool_bin,
                                        "search",
                                        "--onlyvisible",
                                        "--name",
                                        "Blitzlens",
                                        "windowmove",
                                        "--sync",
                                        "0",
                                        "0",
                                        "windowsize",
                                        "--sync",
                                        "100%",
                                        "100%",
                                    ],
                                    env=self._env,
                                    stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL,
                                    check=False,
                                )
                                break
                            time.sleep(0.1)
                except Exception:
                    pass
        except BaseException:
            self.teardown()
            raise

    def teardown(self) -> None:
        """Terminate application, private dbus-daemon, and Xvfb display."""
        if self._app_proc and self._app_proc.poll() is None:
            try:
                self._app_proc.terminate()
                try:
                    self._app_proc.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    try:
                        self._app_proc.kill()
                    except (ProcessLookupError, OSError):
                        pass
            except (ProcessLookupError, OSError):
                pass

        if self._dbus_pid:
            try:
                os.kill(self._dbus_pid, signal.SIGTERM)
            except (ProcessLookupError, OSError):
                pass
            self._dbus_pid = None

        if self._xvfb_proc and self._xvfb_proc.poll() is None:
            try:
                self._xvfb_proc.terminate()
                try:
                    self._xvfb_proc.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    try:
                        self._xvfb_proc.kill()
                    except (ProcessLookupError, OSError):
                        pass
            except (ProcessLookupError, OSError):
                pass
        shutil.rmtree(self.lab_dir, ignore_errors=True)

    def capture_state(self) -> ScreenState:
        """Trigger lab-checkpoint via D-Bus and ingest the resulting tree and PNG."""
        cp_name = f"step_{self.step_count}"
        cmd = ["gapplication", "action", self.app_id, "lab-checkpoint", f"'{cp_name}'"]
        existing_trees = set(self.artifacts_dir.glob(f"*_{cp_name}.tree.json"))
        existing_pngs = set(self.artifacts_dir.glob(f"*_{cp_name}.png"))

        if self.exec_fn is not None:
            self.exec_fn(cmd, self._env)
        else:
            try:
                subprocess.run(cmd, env=self._env, check=False, timeout=10)
            except Exception:
                pass

        tree_file: Optional[Path] = None
        png_file: Optional[Path] = None
        max_wait = 0.05 if self.exec_fn is not None else 2.0
        t0 = time.monotonic()
        while time.monotonic() - t0 < max_wait:
            trees = sorted(p for p in self.artifacts_dir.glob(f"*_{cp_name}.tree.json") if p not in existing_trees)
            pngs = sorted(p for p in self.artifacts_dir.glob(f"*_{cp_name}.png") if p not in existing_pngs)
            if trees and pngs and trees[-1].stat().st_size > 0 and pngs[-1].stat().st_size > 0:
                tree_file = trees[-1]
                png_file = pngs[-1]
                break
            time.sleep(0.05)

        if tree_file is None:
            trees = sorted(self.artifacts_dir.glob(f"*_{cp_name}.tree.json"))
            if trees and trees[-1].stat().st_size > 0:
                tree_file = trees[-1]
        if png_file is None:
            pngs = sorted(self.artifacts_dir.glob(f"*_{cp_name}.png"))
            if pngs and pngs[-1].stat().st_size > 0:
                png_file = pngs[-1]

        if tree_file is not None and tree_file.is_file():
            try:
                tree_dict = json.loads(tree_file.read_text(encoding="utf-8"))
                tree = Tree.from_dict(tree_dict)
            except Exception:
                tree = Tree(platform="linux", window={"w": 1920, "h": 1080, "unit": "px", "scale": 1.0})
        else:
            stem = f"{self.step_count:02d}_{cp_name}"
            exact_tree = self.artifacts_dir / f"{stem}.tree.json"
            if exact_tree.is_file():
                try:
                    tree_dict = json.loads(exact_tree.read_text(encoding="utf-8"))
                    tree = Tree.from_dict(tree_dict)
                except Exception:
                    tree = Tree(platform="linux", window={"w": 1920, "h": 1080, "unit": "px", "scale": 1.0})
            else:
                tree = Tree(platform="linux", window={"w": 1920, "h": 1080, "unit": "px", "scale": 1.0})

        if png_file is not None and png_file.is_file():
            screenshot_bytes = png_file.read_bytes()
        else:
            stem = f"{self.step_count:02d}_{cp_name}"
            exact_png = self.artifacts_dir / f"{stem}.png"
            screenshot_bytes = exact_png.read_bytes() if exact_png.is_file() else b""

        trash_dir = self.lab_dir / "xdg-data" / "Trash" / "files"
        try:
            fs_snapshot = snapshot_directory(self.working_dir, lab_tmp_root(), trash_dir=trash_dir)
        except Exception:
            fs_snapshot = {"root": "work", "files": [], "trash": []}

        state = ScreenState(
            tree=tree,
            screenshot_bytes=screenshot_bytes,
            screen_mode="selector",
            fs_snapshot=fs_snapshot,
            logs=[],
        )
        self.last_state = state
        return state

    def execute_action(self, action: Action) -> bool:
        """Inject keys or mouse clicks using xdotool."""
        self.step_count += 1
        t = action.action_type

        if t == "key":
            raw_key = str(action.params.get("key", ""))
            x11_key_map = {
                "Space": "space",
                "SPACE": "space",
                "Del": "Delete",
                "DEL": "Delete",
                "Enter": "Return",
                "ENTER": "Return",
                "Esc": "Escape",
                "ESC": "Escape",
            }
            key = x11_key_map.get(raw_key, raw_key)
            mods = [str(m).lower() for m in action.params.get("modifiers", [])]
            mod_map = {
                "ctrl": "ctrl",
                "control": "ctrl",
                "alt": "alt",
                "shift": "shift",
                "super": "super",
                "meta": "super",
                "cmd": "super",
            }
            mapped_mods = [mod_map.get(m, m) for m in mods]
            if mapped_mods and key:
                key_combo = "+".join(mapped_mods + [key])
            else:
                key_combo = key
            cmd = ["xdotool", "key", "--clearmodifiers", key_combo]
            if self.exec_fn is not None:
                self.exec_fn(cmd, self._env)
            else:
                subprocess.run(cmd, env=self._env, check=False, timeout=10)
        elif t in ("tap", "click"):
            raw_x = action.params.get("x")
            raw_y = action.params.get("y")
            if raw_x is None or raw_y is None:
                tid = action.target_id or action.params.get("target_id")
                if tid and self.last_state and self.last_state.tree:
                    for node in self.last_state.tree.nodes:
                        if node.id == tid and len(node.bounds) >= 4:
                            if raw_x is None:
                                raw_x = node.bounds[0] + node.bounds[2] / 2.0
                            if raw_y is None:
                                raw_y = node.bounds[1] + node.bounds[3] / 2.0
                            break
            if raw_x is None:
                raw_x = 100
            if raw_y is None:
                raw_y = 100
            x = int(_parse_coord(raw_x, "x"))
            y = int(_parse_coord(raw_y, "y"))
            cmd = ["xdotool", "mousemove", "--sync", str(x), str(y), "click", "1"]
            if self.exec_fn is not None:
                self.exec_fn(cmd, self._env)
            else:
                subprocess.run(cmd, env=self._env, check=False, timeout=10)
        elif t == "resize":
            raw_w = action.params.get("w", action.params.get("width", 1920))
            raw_h = action.params.get("h", action.params.get("height", 1080))
            w = int(_parse_coord(raw_w, "w"))
            h = int(_parse_coord(raw_h, "h"))
            cmd = ["xdotool", "search", "--onlyvisible", "--name", "Blitzlens", "windowsize", "--sync", str(w), str(h)]
            if self.exec_fn is not None:
                self.exec_fn(cmd, self._env)
            else:
                subprocess.run(cmd, env=self._env, check=False, timeout=10)
        elif t == "back":
            cmd = ["xdotool", "key", "--clearmodifiers", "Escape"]
            if self.exec_fn is not None:
                self.exec_fn(cmd, self._env)
            else:
                subprocess.run(cmd, env=self._env, check=False, timeout=10)

        self.settle()
        return True


# ==============================================================================
# Direct macOS Desktop Driver
# ==============================================================================

class MacOSDeviceDriver(DeviceDriver):
    """Driver for Lensjet using headless --ui-scenario or remote pst-lab LaunchAgent."""

    def __init__(
        self,
        app_id: str = "app.alpatix.lensjet",
        fixture_dir: Path | str = "/tmp/pstlab/fixture",
        out_dir: Optional[Path] = None,
        app_binary: Optional[Path] = None,
        remote_host: Optional[str] = None,
        remote_user: str = "pst-lab",
        settle_ms: int = 250,
        exec_fn: Optional[Callable[..., Any]] = None,
    ) -> None:
        out = out_dir or Path(tempfile.mkdtemp(prefix="explore_macos_", dir=lab_tmp_root()))
        super().__init__("macos-desktop", app_id, fixture_dir, out, settle_ms)
        self.app_binary = app_binary
        self.remote_host = remote_host
        self.remote_user = remote_user
        self.exec_fn = exec_fn
        self.action_history: List[Dict[str, Any]] = [{"action": "waitLoaded"}]

    def setup(self) -> None:
        self.out_dir.mkdir(parents=True, exist_ok=True)

    def teardown(self) -> None:
        pass

    def capture_state(self) -> ScreenState:
        """Execute accumulated scenario up to checkpoint and ingest generated artifacts."""
        cp_name = f"step_{self.step_count}"
        self.action_history.append({"action": "checkpoint", "name": cp_name})

        scenario_doc = {"id": f"explore_{self.step_count}", "steps": list(self.action_history)}
        scenario_file = self.out_dir / "ui-scenario.json"
        scenario_file.write_text(json.dumps(scenario_doc, indent=2), encoding="utf-8")

        cmd = [
            str(self.app_binary or "PhotoSelectorApp"),
            "--open", str(self.working_dir),
            "--ui-scenario", str(scenario_file),
            "--artifacts", str(self.out_dir),
            "-cullingDestinationFolderName", "Selection",
            "-externalCullingDestinationURL", "",
        ]
        env = dict(os.environ)
        env["PST_SHOW_GUI"] = "0"

        if self.remote_host:
            target = f"{self.remote_user}@{self.remote_host}" if "@" not in self.remote_host else self.remote_host
            cmd = ["ssh", target] + [shlex.quote(str(arg)) for arg in cmd]

        if self.exec_fn is not None:
            self.exec_fn(cmd, env)
        elif self.remote_host or (self.app_binary and self.app_binary.is_file()):
            subprocess.run(cmd, env=env, check=False, timeout=60)

        stem = f"{len([s for s in self.action_history if s.get('action') == 'checkpoint']):02d}_{cp_name}"
        tree_file = self.out_dir / f"{stem}.tree.json"
        png_file = self.out_dir / f"{stem}.png"

        tree = Tree.from_dict(json.loads(tree_file.read_text(encoding="utf-8"))) if tree_file.is_file() else Tree(
            platform="macos", window={"w": 1440, "h": 900, "unit": "pt", "scale": 2.0}
        )
        screenshot_bytes = png_file.read_bytes() if png_file.is_file() else b""
        try:
            fs_snapshot = snapshot_directory(self.working_dir, lab_tmp_root())
        except Exception:
            fs_snapshot = {"root": "work", "files": [], "trash": []}

        state = ScreenState(
            tree=tree,
            screenshot_bytes=screenshot_bytes,
            screen_mode="selector",
            fs_snapshot=fs_snapshot,
            logs=[],
        )
        self.last_state = state
        return state

    def execute_action(self, action: Action) -> bool:
        """Append action to history."""
        self.step_count += 1
        t = action.action_type
        if t == "key":
            self.action_history.append({"action": "key", "key": str(action.params.get("key", "")).lower()})
        elif t in ("tap", "click"):
            target = action.target_id or action.params.get("target")
            self.action_history.append({"action": "click", "target": str(target)})
        elif t == "back":
            self.action_history.append({"action": "key", "key": "escape"})
        return True


# ==============================================================================
# Offline Synthetic Mock Driver
# ==============================================================================

class MockDeviceDriver(DeviceDriver):
    """Deterministic offline driver producing synthetic state transitions and injecting test faults."""

    PNG_1PX = (
        b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde"
        b"\x00\x00\x00\x0cIDATx\x9cc\xf8\xcf\xc0\x00\x00\x03\x01\x01\x00\xc9\xfe\x92\xef\x00\x00\x00\x00IEND\xaeB`\x82"
    )

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
    ) -> None:
        self._owns_working_dir = working_dir is None
        self._owns_out_dir = out_dir is None
        work = working_dir or Path(tempfile.mkdtemp(prefix="mock_work_", dir=lab_tmp_root()))
        out = out_dir or Path(tempfile.mkdtemp(prefix="mock_out_", dir=lab_tmp_root()))
        super().__init__(product, app_id, work, out, settle_ms=0)
        self.total_photos = total_photos
        self.current_index = 0
        self.screen_mode = "landing" if product == "phototok" else "selector"
        self.selected_photos: List[str] = []
        self.deleted_photos: List[str] = []
        self.undo_stack: List[Tuple[str, str]] = []  # (action_type, photo_name)
        self.fail_on_action = fail_on_action
        self.settle_failure_count = settle_failure_count
        self.timeout_on_capture = timeout_on_capture
        self.executed_actions: List[Action] = []

    def setup(self) -> None:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        if isinstance(self.working_dir, Path):
            self.working_dir.mkdir(parents=True, exist_ok=True)
            for i in range(1, self.total_photos + 1):
                (self.working_dir / f"photo_{i:02d}.jpg").write_text("mock photo content", encoding="utf-8")

    def teardown(self) -> None:
        if self._owns_working_dir and isinstance(self.working_dir, Path):
            shutil.rmtree(self.working_dir, ignore_errors=True)
        if self._owns_out_dir and self.out_dir:
            shutil.rmtree(self.out_dir, ignore_errors=True)

    def capture_state(self) -> ScreenState:
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
            logs=[f"Mock log at step {self.step_count}"],
        )
        self.last_state = state
        return state

    def execute_action(self, action: Action) -> bool:
        self.step_count += 1
        self.executed_actions.append(action)

        if self.fail_on_action and action.action_type == self.fail_on_action:
            raise DriverExecutionError(f"Simulated execution failure for action '{action.action_type}'")

        t = action.action_type
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
                self.screen_mode = "landing" if self.product == "phototok" else "selector"

        return True

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
