"""Wrapper around `adb` with lab-root path guards and injectable command execution."""

from __future__ import annotations

import re
import subprocess
import time
from pathlib import Path
from typing import Callable

from ..paths import ensure_android_lab_path


# uiautomator writes its dump to a device file; it lives in the lab root (never the user's
# storage root) and outside the work folder, so file-system snapshots never see it.
REMOTE_DUMP = "/sdcard/PSTLab/pst_dump.xml"
DUMP_ATTEMPTS = 3
DUMP_RETRY_S = 0.5


ExecFn = Callable[[list[str], int], subprocess.CompletedProcess[bytes]]


def _default_exec(cmd: list[str], timeout: int = 30) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(cmd, capture_output=True, timeout=timeout, check=True)


class AdbClient:
    """Client for one Android device serial (or general adb commands when `serial` is None)."""

    def __init__(self, serial: str | None = None, exec_fn: ExecFn | None = None) -> None:
        self.serial = serial
        self._exec = exec_fn or _default_exec

    def _cmd(self, *args: str) -> list[str]:
        base = ["adb"]
        if self.serial:
            base.extend(["-s", self.serial])
        base.extend(args)
        return base

    def run(self, *args: str, timeout: int = 30) -> str:
        res = self._exec(self._cmd(*args), timeout)
        return res.stdout.decode("utf-8", errors="replace")

    def run_bytes(self, *args: str, timeout: int = 30) -> bytes:
        res = self._exec(self._cmd(*args), timeout)
        return res.stdout

    def devices(self) -> list[tuple[str, str]]:
        """Return `(serial, state)` pairs from `adb devices`."""
        res = self._exec(["adb", "devices"], 15)
        out = res.stdout.decode("utf-8", errors="replace")
        found: list[tuple[str, str]] = []
        for line in out.splitlines()[1:]:
            parts = line.strip().split()
            if len(parts) >= 2:
                found.append((parts[0], parts[1]))
        return found

    def connect(self, host_port: str) -> str:
        res = self._exec(["adb", "connect", host_port], 15)
        return res.stdout.decode("utf-8", errors="replace").strip()

    def ensure_ready(self) -> None:
        """Wake device, dismiss keyguard, keep screen on, and verify lockscreen is not active."""
        self.run("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        self.run("shell", "wm", "dismiss-keyguard")
        self.run("shell", "svc", "power", "stayon", "true")
        win_dump = self.run("shell", "dumpsys", "window")
        if "mDreamingLockscreen=true" in win_dump:
            raise RuntimeError(f"Device {self.serial} is still locked (mDreamingLockscreen=true)")

    def in_use_by(self) -> str | None:
        """The app a person is using right now, or None when the device is idle.

        Idle means: screen off or dozing, lock screen showing, or the top activity is the home
        screen or a lab app. Personal devices are checked before a run takes them over.
        """
        power = self.run("shell", "dumpsys", "power")
        if "mWakefulness=Awake" not in power:
            return None
        window = self.run("shell", "dumpsys", "window")
        if "isKeyguardShowing=true" in window or "mDreamingLockscreen=true" in window:
            return None
        acts = self.run("shell", "dumpsys", "activity", "activities")
        m = re.search(r"topResumedActivity=\S+\s+\S+\s+([\w.]+)/", acts) or re.search(
            r"mResumedActivity: \S+\s+\S+\s+([\w.]+)/", acts
        )
        if not m:
            return None
        top = m.group(1)
        home = self.run(
            "shell", "cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
        )
        home_pkgs = {ln.split("/")[0].strip() for ln in home.splitlines() if "/" in ln}
        if top in home_pkgs or top.endswith(".lab") or "launcher" in top:
            return None
        return top

    def app_task(self, app_id: str) -> tuple[int, str, tuple[int, int, int, int] | None, tuple[int, int] | None]:
        """`(task id, windowing mode, bounds px, max size px)` of the app's top task.

        Bounds exist only for freeform windows (Samsung DeX, ChromeOS ARC); the max size is the
        area a window may cover (`mMaxBounds`).
        """
        out = self.run("shell", "dumpsys", "activity", "activities")
        head = re.search(r"Task\{\w+ #(\d+) [^}]*A=\d+:" + re.escape(app_id) + r"[^}]*mode=(\w+)", out)
        if not head:
            raise RuntimeError(f"{app_id} has no task on {self.serial} (not running?)")
        rest = out[head.end():]
        nxt = re.search(r"\n\s*\* Task\{", rest)
        block = rest[: nxt.start()] if nxt else rest
        b = re.search(r"mBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)", block)
        mx = re.search(r"mMaxBounds=Rect\(0, 0 - (\d+), (\d+)\)", block)
        bounds = tuple(int(x) for x in b.groups()) if b else None
        max_px = (int(mx.group(1)), int(mx.group(2))) if mx else None
        return int(head.group(1)), head.group(2), bounds, max_px  # type: ignore[return-value]

    def resize_task(self, task_id: int, left: int, top: int, right: int, bottom: int) -> str:
        return self.run("shell", "am", "task", "resize", str(task_id), str(left), str(top), str(right), str(bottom))

    def current_user(self) -> int:
        out = self.run("shell", "am", "get-current-user").strip()
        return int(out.splitlines()[-1].strip())

    def switch_user(self, user_id: int, max_polls: int = 20, poll_interval: float = 0.25) -> None:
        self.run("shell", "am", "switch-user", str(user_id))
        for _ in range(max_polls):
            if self.current_user() == user_id:
                return
            if poll_interval > 0:
                time.sleep(poll_interval)
        raise RuntimeError(f"Timed out waiting for user {user_id} to become active on {self.serial}")

    def package_path(self, app_id: str, user: int = 0) -> str | None:
        """The installed `base.apk` of `app_id` (`pm path`), or None when it is not installed."""
        try:
            out = self.run("shell", "pm", "path", "--user", str(user), app_id)
        except subprocess.CalledProcessError:  # `pm path` exits 1 for a package that is not installed
            return None
        for line in out.splitlines():
            if line.strip().startswith("package:") and line.strip().endswith("base.apk"):
                return line.strip()[len("package:"):]
        return None

    def sha256(self, remote_path: str) -> str | None:
        """`sha256sum` of a device file (toybox, Android 8+), or None when it cannot be read."""
        try:
            out = self.run("shell", "sha256sum", remote_path)
        except subprocess.CalledProcessError:
            return None
        m = re.match(r"\s*([0-9a-f]{64})\s", out)
        return m.group(1) if m else None

    def install(self, apk: Path | str, user: int = 0) -> str:
        return self.run("install", "-r", "--user", str(user), str(apk), timeout=120)

    def clear(self, app_id: str, user: int = 0) -> str:
        return self.run("shell", "pm", "clear", "--user", str(user), app_id)

    def force_stop(self, app_id: str) -> str:
        return self.run("shell", "am", "force-stop", app_id)

    def start(self, app_id: str) -> str:
        """Launch the app's launcher activity with `am start`.

        Not `monkey`: on ChromeOS ARC it aborts ("SYS_KEYS has no physical keys").
        """
        out = self.run(
            "shell", "cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", app_id,
        )
        component = next((ln.strip() for ln in reversed(out.splitlines()) if "/" in ln), "")
        if not component:
            raise RuntimeError(f"{app_id} has no launcher activity on {self.serial} (not installed?)")
        return self.run("shell", "am", "start", "-W", "-n", component, timeout=60)

    def home(self) -> str:
        return self.run("shell", "input", "keyevent", "KEYCODE_HOME")

    def screencap(self, dest: Path | str) -> Path:
        target = Path(dest)
        target.parent.mkdir(parents=True, exist_ok=True)
        png_bytes = self.run_bytes("exec-out", "screencap", "-p", timeout=30)
        target.write_bytes(png_bytes)
        return target

    def dump_tree(self, dest_xml: Path | str) -> Path:
        """Pull a uiautomator dump of the foreground window into `dest_xml`.

        The device file is `REMOTE_DUMP` and is removed after the pull (2026-10-17: the dump
        was left in the storage root of personal devices). `uiautomator dump` exits 0 and
        prints "ERROR: could not get idle state." while the screen animates; that is retried.
        """
        target = Path(dest_xml)
        target.parent.mkdir(parents=True, exist_ok=True)
        remote = ensure_android_lab_path(REMOTE_DUMP)
        try:
            out = ""
            for attempt in range(DUMP_ATTEMPTS):
                out = self.run("shell", "uiautomator", "dump", remote, timeout=30)
                if "ERROR" not in out:
                    break
                if attempt + 1 < DUMP_ATTEMPTS and DUMP_RETRY_S > 0:
                    time.sleep(DUMP_RETRY_S)
            else:
                raise RuntimeError(f"uiautomator dump failed on {self.serial}: {out.strip()}")
            self.run("pull", remote, str(target), timeout=30)
        finally:
            self.run("shell", "rm", "-f", remote)
        return target

    def collapse_shade(self) -> str:
        """Close the notification shade and quick settings (`cmd statusbar collapse`).

        An open shade covers the app with other apps' notifications: on 2026-10-17 it turned a
        phone-landscape journey into SystemUI findings and put personal notifications into the
        screenshots.
        """
        return self.run("shell", "cmd", "statusbar", "collapse")

    def rm_rf(self, remote_dir: str) -> str:
        guarded = ensure_android_lab_path(remote_dir)
        if guarded == "/sdcard/PSTLab":
            raise ValueError("refusing to delete /sdcard/PSTLab root itself; specify a subdirectory")
        return self.run("shell", "rm", "-rf", guarded)

    def push_dir(self, src: Path | str, dest: str) -> str:
        guarded = ensure_android_lab_path(dest)
        self.run("shell", "mkdir", "-p", guarded)
        return self.run("push", f"{Path(src)}/.", guarded, timeout=120)

    def list_files(self, root: str) -> list[dict[str, int | str]]:
        guarded = ensure_android_lab_path(root)
        cmd_str = f"cd {guarded} && find . -type f -exec stat -c '%s %n' {{}} +"
        out = self.run("shell", cmd_str).strip()
        files: list[dict[str, int | str]] = []
        for line in out.splitlines():
            line = line.strip()
            if not line:
                continue
            parts = line.split(" ", 1)
            if len(parts) != 2:
                continue
            try:
                size = int(parts[0])
            except ValueError:
                continue
            rel = parts[1]
            if rel.startswith("./"):
                rel = rel[2:]
            files.append({"path": rel, "size": size})
        files.sort(key=lambda e: str(e["path"]))
        return files

    def dir_exists(self, remote_dir: str) -> bool:
        """Whether `remote_dir` is a directory on the device. Read-only, so any path is allowed."""
        return self.run("shell", f"test -d '{remote_dir}' && echo yes || echo no").strip() == "yes"

    def logcat_clear(self) -> str:
        return self.run("logcat", "-c")

    def logcat_dump(self, dest: Path | str) -> Path:
        target = Path(dest)
        target.parent.mkdir(parents=True, exist_ok=True)
        out = self.run("logcat", "-d", "-b", "main,crash", timeout=30)
        target.write_text(out, encoding="utf-8")
        return target

    def density(self) -> int:
        out = self.run("shell", "wm", "density")
        m_over = re.search(r"Override density:\s*(\d+)", out)
        if m_over:
            return int(m_over.group(1))
        m_phys = re.search(r"Physical density:\s*(\d+)", out)
        if m_phys:
            return int(m_phys.group(1))
        raise RuntimeError(f"Could not parse wm density output: {out!r}")

    def physical_density(self) -> int:
        out = self.run("shell", "wm", "density")
        m_phys = re.search(r"Physical density:\s*(\d+)", out)
        if m_phys:
            return int(m_phys.group(1))
        return self.density()

    def window_dp(self) -> tuple[int, int]:
        """Current display size in dp, as rotated.

        Read from the display (`cur=WxH` at the `base=… <dpi>dpi` density), not from the first
        `w…dp h…dp` configuration in `dumpsys window`: that one belongs to the top app and keeps
        its old orientation while the display rotates (seen on the Pixel Tablet).
        """
        out = self.run("shell", "dumpsys", "window", "displays")
        # "init=1600x2560 320dpi [base=2960x1848 320dpi] cur=2960x1848"; base only with overrides.
        m = re.search(r"init=\d+x\d+\s+(\d+)dpi(?:.*?base=\d+x\d+\s+(\d+)dpi)?.*?cur=(\d+)x(\d+)", out)
        if m:
            dpi = int(m.group(2) or m.group(1))
            w_px, h_px = int(m.group(3)), int(m.group(4))
            return round(w_px * 160 / dpi), round(h_px * 160 / dpi)
        out = self.run("shell", "dumpsys", "window")
        m = re.search(r"sw\d+dp\s+w(\d+)dp\s+h(\d+)dp", out)
        if m:
            return int(m.group(1)), int(m.group(2))
        raise RuntimeError("Could not parse the display size from dumpsys window")

    def key(self, code: str) -> str:
        norm = code if code.startswith("KEYCODE_") else f"KEYCODE_{code}"
        return self.run("shell", "input", "keyboard", "keyevent", norm)

    def key_combo(self, *codes: str) -> str:
        if len(codes) < 2:
            raise ValueError("key_combo requires at least 2 key codes")
        norms = [c if c.startswith("KEYCODE_") else f"KEYCODE_{c}" for c in codes]
        out = self.run("shell", "input", "keyboard", "keycombination", *norms)
        if "Unknown command" in out or "Error" in out:
            raise RuntimeError(f"keycombination not supported on device {self.serial}: {out.strip()}")
        return out
