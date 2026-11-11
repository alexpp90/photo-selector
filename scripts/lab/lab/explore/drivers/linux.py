"""Linux driver: Blitzlens in its own Xvfb (`-displayfd`) and private D-Bus session."""

from __future__ import annotations

import ctypes
import ctypes.util
import json
import os
import select
import shutil
import subprocess
import time
import uuid
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

from ...fixtures import generate_fixtures
from ...fs_oracle import snapshot_directory
from ...linux.keys import normalize_xdotool_key
from ...linux.runner import preflight_linux, session_env
from ...model import Node, Tree
from ...paths import ensure_under, lab_tmp_root, repo_root
from ..actions import Action, ScreenState
from ..discovery import classify_screen
from .base import AppCrashedError, DeviceDriver, DriverError, DriverExecutionError, DriverTimeoutError, _ensure_dir, _key_spec, _parse_coord, _scratch_path


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

    def restage_fixture(self) -> None:
        """Replace the working folder with a fresh copy of the fixture and start the app on it.

        A run that culls the feed empty explored the empty-state screen for the rest of its time
        (2026-10-26: 324 of 684 checkpoints with `total: 0`). Only the folder the lab made is
        emptied (`ensure_under` the lab root); the app's Trash lives in the session's XDG dir and
        is cleared with it.
        """
        work = ensure_under(self.working_dir, lab_tmp_root())
        shutil.rmtree(work, ignore_errors=True)
        shutil.rmtree(ensure_under(self.lab_dir / "xdg-data" / "Trash", lab_tmp_root()), ignore_errors=True)
        generate_fixtures(self.fixture_set, work, root=repo_root())
        self.restart_app()

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
