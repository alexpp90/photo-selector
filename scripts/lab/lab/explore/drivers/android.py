"""Android driver: Jackpix and Frameduel over direct adb (the hub lock is held because Maestro opens every
device on the hub, see `locks.py`)."""

from __future__ import annotations

import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from contextlib import ExitStack, contextmanager
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, Iterator, List, Optional, Tuple

from ...android.adb import AdbClient
from ...android.configs import ConfigNotRunError, resize_app_window, set_verified_rotation
from ...android.runner import ENSURE_FOLDER_FLOW, PICKER_PACKAGES, REMOTE_WORK_DIR, SHADE_SETTLE_S, ROTATION_SETTLE_S, _maestro_failure_line, default_maestro_runner
from ...android.system_bars import SystemBar, bars_from_dump, bars_from_insets
from ...android.tree_android import foreground_packages, parse_uiautomator_xml
from ...fixtures import generate_fixtures
from ...locks import device_lock
from ...model import Tree
from ...paths import ensure_android_lab_path, ensure_under, repo_root
from ...usability.driver import ANDROID_MODIFIER_CODES, android_keycode, home_packages
from ..actions import Action, ScreenState
from ..discovery import SYSTEM_PICKER_MODE, classify_screen
from .base import ActionUnavailableError, AppCrashedError, AppLeftError, DeviceDriver, DriverError, DriverExecutionError, DriverSettlingError, DriverTimeoutError, TargetUnderSystemBarError, _key_spec, _parse_coord, _scratch_path
from .hublock import _HUB_LOCK


_BOUNDS_ATTR = re.compile(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"')
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


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
        # Screen-pixel rectangles taps must not reach: read with the window geometry they belong to
        # (rotation moves the navigation bar to the side), kept until that geometry changes.
        self._bars: List[SystemBar] = []
        self._bars_key: Optional[Tuple[Any, ...]] = None
        self._dump_bars: List[SystemBar] = []
        self._insets: List[SystemBar] = []
        self._insets_fresh = False
        self._seen_bars: List[SystemBar] = []  # every inset bar of this window geometry, shown or not
        self._stale: set = set()  # packages seen in front of the app: stopped at the next relaunch
        self._home: Optional[frozenset] = None
        self._browsers: Optional[frozenset] = None
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
            # force-stop, and Recents can bring it back (2026-11-05: a Chrome task left at step 12
            # was in front again at step 34): stop the apps that were in front of ours, then
            # Home takes whatever remains out of the foreground.
            self._stop_stale_apps()
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

    def _stop_stale_apps(self) -> None:
        """Force-stop the apps that were in front of ours, unless they are the launcher or a persistent system process."""
        stale, self._stale = sorted(self._stale - {self.app_id}), set()
        if not stale:
            return
        keep = set(self.home_packages())
        for package in stale:
            if package not in keep and not self._persistent(package):
                self.adb.force_stop(package)

    def _persistent(self, package: str) -> bool:
        """`dumpsys package` lists PERSISTENT among the flags of System UI and of `android` itself."""
        try:
            out = self.adb.run("shell", "dumpsys", "package", package)
        except (subprocess.SubprocessError, OSError):
            return True  # cannot tell: do not stop what may be part of the system
        flags = re.search(r"^\s*flags=\[([^\]]*)\]", out, re.MULTILINE)
        return bool(flags and "PERSISTENT" in flags.group(1).split())

    def home_packages(self) -> frozenset:
        """Package of the launcher (`resolve-activity` for the HOME intent), read once."""
        if self._home is None:
            with self._adb_errors("cannot read the launcher package", DriverError):
                self._home = frozenset(home_packages(self.adb))
        return self._home

    def default_browser_packages(self) -> frozenset:
        """Package that opens `https://example.com` by default, read once.

        `resolve-activity --brief` prints the component last; with no default browser it is the
        system chooser (`android`), which is not a browser and is left out.
        """
        if self._browsers is None:
            with self._adb_errors("cannot read the default browser", DriverError):
                out = self.adb.run(
                    "shell", "cmd", "package", "resolve-activity", "--brief",
                    "-a", "android.intent.action.VIEW", "-d", "https://example.com",
                )
            component = next((ln.strip() for ln in reversed(out.splitlines()) if "/" in ln and " " not in ln.strip()), "")
            package = component.split("/")[0]
            self._browsers = frozenset({package} - {"", "android"})
        return self._browsers

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
            assert xml is not None and tree is not None
            self._stale.update(front - allowed)
            raise AppLeftError(
                f"{', '.join(sorted(front)) or 'an unknown window'} is in front of {self.app_id}",
                packages=front,
                top_task=self._top_task(),
                after=self._window_state(tree, front),
            )
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
        rotation = re.search(r'<hierarchy[^>]*\brotation="(\d+)"', xml)
        self._read_bars(xml, (rotation.group(1) if rotation else None, self._window_px, self._density))
        # Without any navigation bar read, the lowest y a gesture may touch is 92 % of the window
        # (a tap on the bar is Home or Back, and leaves the app).
        has_nav = any(bar.kind == "navigation" for bar in self._bars)
        self._safe_bottom_px = None if has_nav else self._origin_px[1] + int(self._window_px[1] * 0.92)
        self._mark_focus(xml, tree)
        return tree

    def _top_task(self) -> str:
        """The `topResumedActivity` line of `dumpsys activity activities`, empty when it cannot be read."""
        try:
            out = self.adb.run("shell", "dumpsys", "activity", "activities", timeout=30)
        except (subprocess.SubprocessError, OSError):
            return ""
        m = re.search(r"^.*(?:topResumedActivity|mResumedActivity)[=:].*$", out, re.MULTILINE)
        return m.group(0).strip() if m else ""

    def _window_state(self, tree: Tree, front: Iterable[str]) -> ScreenState:
        """The window in front of the app as a state, for the evidence of a finding (best effort)."""
        try:
            png = self.adb.run_bytes("exec-out", "screencap", "-p", timeout=30)
        except (subprocess.SubprocessError, OSError):
            png = b""
        return ScreenState(tree=tree, screenshot_bytes=png if png.startswith(PNG_MAGIC) else b"", screen_mode=", ".join(sorted(front)), fs_snapshot={})

    def _read_bars(self, xml: str, key: Tuple[Any, ...]) -> None:
        """System bars of the screen: the dump's own, plus the display's insets once per window geometry."""
        self._dump_bars = bars_from_dump(xml)
        self._insets_fresh = key != self._bars_key
        if self._insets_fresh:
            self._bars_key = key
            self._seen_bars = []
            self._insets = self._read_insets()
        self._bars = list(dict.fromkeys([*self._dump_bars, *self._insets]))
        self._seen_bars = list(dict.fromkeys([*self._seen_bars, *self._insets]))

    def _read_insets(self) -> List[SystemBar]:
        try:
            return bars_from_insets(self.adb.run("shell", "dumpsys", "window", timeout=30))
        except (subprocess.SubprocessError, OSError):
            return []

    @staticmethod
    def _mark_focus(xml: str, tree: Tree) -> None:
        """Tell the tree which text input has the keyboard (`Node.focused`)."""
        elements = list(ET.fromstring(xml).iter("node"))
        if len(elements) != len(tree.nodes):
            return
        for element, node in zip(elements, tree.nodes):
            if element.get("focused") == "true" and node.role == "input":
                node.focused = True

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
    def _screen_point(self, x_dp: Any, y_dp: Any) -> Tuple[int, int]:
        """Window-relative dp -> screen pixels: add the window origin."""
        scale = (self._density or 320) / 160.0
        return (
            self._origin_px[0] + int(round(_parse_coord(x_dp, "x") * scale)),
            self._origin_px[1] + int(round(_parse_coord(y_dp, "y") * scale)),
        )

    def _bar_at(self, point: Tuple[int, int]) -> Optional[SystemBar]:
        if not self._insets_fresh and any(b.contains(*point) for b in self._seen_bars):
            # The insets are read once per window geometry, but an immersive screen hides the bars,
            # and leaving it shows them, without changing it (2026-11-06, Frameduel's full-screen
            # viewer on the Pixel Tablet: "Close fullscreen" refused under a status bar that was not
            # shown). Where a bar has been, read them again.
            self._insets, self._insets_fresh = self._read_insets(), True
            self._bars = list(dict.fromkeys([*self._dump_bars, *self._insets]))
            self._seen_bars = list(dict.fromkeys([*self._seen_bars, *self._insets]))
        return next((b for b in self._bars if b.contains(*point)), None)

    def _px(self, x_dp: Any, y_dp: Any) -> Tuple[int, int]:
        """A gesture's point in screen pixels, moved out of every system bar (a swipe that starts or ends on one is the system's)."""
        x, y = self._screen_point(x_dp, y_dp)
        for _ in range(len(self._bars)):
            bar = self._bar_at((x, y))
            if bar is None:
                break
            left, top, right, bottom = bar.rect
            s_left, s_top = self._origin_px
            s_right, s_bottom = s_left + self._window_px[0], s_top + self._window_px[1]
            exits = [
                p for p in ((left - 2, y), (right + 1, y), (x, top - 2), (x, bottom + 1))
                if s_left <= p[0] < s_right and s_top <= p[1] < s_bottom
            ]  # the nearest way out that stays on the screen
            if not exits:
                break
            x, y = min(exits, key=lambda p: abs(p[0] - x) + abs(p[1] - y))
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
        x, y = self._screen_point(x_dp, y_dp)
        bar = self._bar_at((x, y))
        if bar is not None:
            raise self._under_bar(action, (x, y), bar)
        if self._safe_bottom_px is not None:  # no bar was read: keep clear of where the navigation bar usually is
            y = min(y, self._safe_bottom_px - 2)
        taps = 2 if action.params.get("double_tap") or action.params.get("count") == 2 else 1
        for i in range(taps):
            if i:
                time.sleep(0.08)
            self.adb.run("shell", "input", "tap", str(x), str(y))

    def _under_bar(self, action: Action, point: Tuple[int, int], bar: SystemBar) -> TargetUnderSystemBarError:
        """The error for a tap whose point lies under `bar`; says whether the target scrolls."""
        by_id = {n.id: n for n in self.last_state.tree.nodes} if self.last_state else {}
        node = by_id.get(action.target_id or "")
        in_scroll = False
        cur = by_id.get(node.parent) if node and node.parent else None
        seen: set = set()
        while cur is not None and cur.id not in seen:
            seen.add(cur.id)
            if cur.scrollable:
                in_scroll = True
                break
            cur = by_id.get(cur.parent) if cur.parent else None
        left, top, right, bottom = bar.rect
        return TargetUnderSystemBarError(
            f"{action.summary()} not performed: its centre ({point[0]}, {point[1]}) px lies under the {bar.kind} bar "
            f"[{left},{top}][{right},{bottom}]",
            target_id=action.target_id, point=point, bar=bar, in_scroll=in_scroll,
        )

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
            raise ActionUnavailableError(str(exc)) from exc
        time.sleep(ROTATION_SETTLE_S)
