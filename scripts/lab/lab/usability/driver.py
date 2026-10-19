"""Drive an Android app through a usability scenario and capture every screen a user sees.

Deliberately not Maestro: a scenario is a list of plain user actions ("tap 'Folder or
Cloud'", "swipe left") whose wording doubles as the action log the personas read, and each
`screen` step captures a screenshot, the view tree and the text metrics in one place.
"""

from __future__ import annotations

import fcntl
import json
import os
import re
import time
from contextlib import contextmanager
from typing import Iterator
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..android.adb import AdbClient
from ..paths import ensure_android_lab_path
from .metrics import screen_metrics


class StepFailed(RuntimeError):
    """A scenario step could not be carried out (element missing, timeout, device taken over)."""


class DeviceBusy(RuntimeError):
    """Another session or person is using the device; the walkthrough must not start."""


_TOP = re.compile(r"(?:topResumedActivity|mResumedActivity)[=:]\s*\S+\s+\S+\s+([\w.]+)/")
# Packages a walkthrough may legitimately show besides the app: the system folder picker and
# its permission dialog, and the launcher after Back or Home.
SYSTEM_PACKAGES = ("com.android.documentsui", "com.google.android.documentsui", "android", "com.android.systemui")


@contextmanager
def device_lock(serial: str, holder: str, lock_dir: Path | None = None) -> Iterator[Path]:
    """Hold `<lock_dir>/device-<serial>.lock` (fcntl.flock) for the walkthrough.

    `scripts/android_test_device.py with-lock` takes the same lock around instrumented runs
    (polling it until a timeout), so a test run and a walkthrough never drive the phone at
    once; the kernel drops the lock if we die.
    """
    from ..paths import lab_tmp_root

    base = lock_dir or lab_tmp_root()
    base.mkdir(parents=True, exist_ok=True)
    path = base / f"device-{serial}.lock"
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            held_by = os.pread(fd, 400, 0).decode("utf-8", errors="replace").strip() or "unknown holder"
            raise DeviceBusy(f"{path} is held by: {held_by}") from None
        os.ftruncate(fd, 0)
        os.pwrite(fd, f"{holder} pid={os.getpid()} since={time.strftime('%Y-%m-%dT%H:%M:%S')}\n".encode(), 0)
        yield path
    finally:
        os.close(fd)  # closing releases the flock


def foreground_package(adb: AdbClient) -> str | None:
    m = _TOP.search(adb.run("shell", "dumpsys", "activity", "activities"))
    return m.group(1) if m else None


def home_packages(adb: AdbClient) -> set[str]:
    out = adb.run(
        "shell", "cmd", "package", "resolve-activity", "--brief",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME",
    )
    return {ln.split("/")[0].strip() for ln in out.splitlines() if "/" in ln}


def check_device_free(adb: AdbClient, app_id: str) -> None:
    """Refuse a device on which instrumentation runs or a foreign app is in front."""
    procs = adb.run("shell", "ps", "-A", "-o", "NAME")
    tests = sorted({ln.strip() for ln in procs.splitlines() if ln.strip().endswith(".test")})
    if tests:
        raise DeviceBusy(f"instrumented tests are running ({', '.join(tests)}); wait until they finish")
    top = foreground_package(adb)
    allowed = {app_id, *SYSTEM_PACKAGES} | home_packages(adb)
    if top and top not in allowed and "launcher" not in top:
        raise DeviceBusy(f"{top} is in front; go to the home screen, then re-run")


@dataclass
class UiNode:
    res_id: str
    text: str
    desc: str
    bounds: tuple[int, int, int, int]
    clickable: bool

    @property
    def center(self) -> tuple[int, int]:
        return ((self.bounds[0] + self.bounds[2]) // 2, (self.bounds[1] + self.bounds[3]) // 2)


_BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def parse_nodes(xml_text: str) -> list[UiNode]:
    """Flatten a uiautomator dump into nodes with pixel bounds."""
    start = xml_text.find("<")
    root = ET.fromstring(xml_text[start:] if start > 0 else xml_text)
    nodes: list[UiNode] = []
    for el in root.iter("node"):
        m = _BOUNDS.match(el.get("bounds", ""))
        if not m:
            continue
        b = (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)))
        nodes.append(
            UiNode(
                res_id=el.get("resource-id", "").split("/")[-1],
                text=el.get("text", ""),
                desc=el.get("content-desc", ""),
                bounds=b,
                clickable=el.get("clickable") == "true",
            )
        )
    return nodes


def find_node(nodes: list[UiNode], target: dict[str, Any]) -> UiNode | None:
    """Match by `id` (exact), `text` or `desc` (exact, or case-insensitive substring with `contains`)."""
    contains = bool(target.get("contains"))

    def match(value: str, wanted: Any) -> bool:
        # A list gives alternatives, e.g. the English and German label: [Delete, Löschen].
        options = [str(w) for w in wanted] if isinstance(wanted, list) else [str(wanted)]
        if contains:
            return any(o.lower() in value.lower() for o in options)
        return value in options

    for node in nodes:
        if node.bounds[2] <= node.bounds[0] or node.bounds[3] <= node.bounds[1]:
            continue
        if "id" in target and node.res_id != target["id"]:
            continue
        if "text" in target and not match(node.text, target["text"]):
            continue
        if "desc" in target and not match(node.desc, target["desc"]):
            continue
        return node
    return None


def describe_target(target: dict[str, Any]) -> str:
    for key in ("label", "text", "desc"):
        value = target.get(key)
        if isinstance(value, list):
            value = value[0] if value else None
        if value:
            return f"'{value}'"
    return f"the control '{target.get('id', '?')}'"


def describe_action(step: dict[str, Any]) -> str | None:
    """Human wording of a step for the persona's action log; None for steps a user never sees."""
    if "say" in step:
        return str(step["say"])
    if "tap" in step:
        return f"Tapped {describe_target(step['tap'])}"
    if "long_press" in step:
        return f"Long-pressed {describe_target(step['long_press'])}"
    if "double_tap" in step:
        return "Double-tapped the photo"
    if "swipe" in step:
        return f"Swiped {step['swipe']}"
    if "scroll_to" in step:
        return f"Scrolled to {describe_target(step['scroll_to'])}"
    if "back" in step:
        return "Pressed Back"
    if "launch" in step:
        return "Opened the app"
    if "background" in step:
        return f"Left the app for {int(step['background']) // 1000} s and came back"
    return None


class ScenarioDriver:
    """Execute one scenario on a device; writes `<out_dir>/<NN>_<screen>.{png,xml,json}`."""

    def __init__(
        self, adb: AdbClient, app_id: str, out_dir: Path, *, settle_ms: int = 900, app_locale: str | None = None
    ) -> None:
        self.adb = adb
        self.app_id = app_id
        self.app_locale = app_locale
        self.out_dir = out_dir
        self.settle_ms = settle_ms
        self.size = (1080, 1920)
        self.screens: list[dict[str, Any]] = []
        self._actions: list[str] = []
        self._allowed: set[str] | None = None

    # -- device primitives -------------------------------------------------------------
    def _dump(self) -> str:
        # uiautomator answers "could not get idle state" while the screen animates: retry.
        last = ""
        for attempt in range(5):
            raw = self.adb.run_bytes("exec-out", "uiautomator", "dump", "/dev/tty", timeout=30)
            text = raw.decode("utf-8", errors="replace")
            start, end = text.find("<"), text.rfind("</hierarchy>")
            if start >= 0 and end > start:
                return text[start : end + len("</hierarchy>")]
            last = text.strip()[:120]
            time.sleep(0.5 * (attempt + 1))
        raise StepFailed(f"no UI dump from the device: {last!r}")

    def nodes(self) -> list[UiNode]:
        nodes = parse_nodes(self._dump())
        if nodes:
            self.size = (nodes[0].bounds[2], nodes[0].bounds[3])
        return nodes

    def _safe_bottom(self, nodes: list[UiNode]) -> int:
        """Lowest y a tap may target: the top of the navigation bar, else 92 % of the screen."""
        for n in nodes:
            if n.res_id == "navigationBarBackground":
                return n.bounds[1]
        return int(self.size[1] * 0.92)

    def _input(self, *args: str) -> None:
        self.adb.run("shell", "input", *args)

    def _swipe(self, direction: str, duration_ms: int = 250) -> None:
        w, h = self.size
        cx, cy = w // 2, h // 2
        dx, dy = int(w * 0.35), int(h * 0.28)
        vec = {"up": (0, -dy), "down": (0, dy), "left": (-dx, 0), "right": (dx, 0)}[direction]
        self._input("swipe", str(cx - vec[0]), str(cy - vec[1]), str(cx + vec[0]), str(cy + vec[1]), str(duration_ms))

    def _locate(self, target: dict[str, Any], *, timeout_ms: int = 6000, scroll: bool = False) -> UiNode:
        deadline = time.monotonic() + timeout_ms / 1000.0
        scrolls = 0
        while True:
            nodes = self.nodes()
            node = find_node(nodes, target)
            if node is not None and scroll and scrolls < 8 and node.bounds[3] > self._safe_bottom(nodes):
                node = None  # half under the navigation bar: its centre may be the Home button
            if node is not None:
                return node
            if scroll and scrolls < 8:
                scrolls += 1
                w, h = self.size
                self._input("swipe", str(w // 2), str(int(h * 0.7)), str(w // 2), str(int(h * 0.35)), "300")
                time.sleep(0.6)
                continue
            if time.monotonic() > deadline:
                raise StepFailed(f"not found: {target}")
            time.sleep(0.4)

    # -- scenario ------------------------------------------------------------------------
    def run(self, scenario: dict[str, Any]) -> dict[str, Any]:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        status, error = "pass", None
        try:
            for step in scenario.get("steps", []):
                self._step(step)
        except StepFailed as exc:
            status, error = "broken", str(exc)
            try:
                self._capture({"screen": "broken", "intent": "(the scenario could not continue)"})
            except StepFailed:
                pass  # the device gave no dump either; the error above says why
        result = {
            "scenario": scenario["id"],
            "title": scenario.get("title", ""),
            "goal": scenario.get("goal", ""),
            "status": status,
            "error": error,
            "screens": self.screens,
        }
        (self.out_dir / "scenario.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        return result

    def _step(self, step: dict[str, Any]) -> None:
        action = describe_action(step)
        if "screen" in step:
            # `when`: capture only if this element shows up (a dialog the app may or may not raise).
            when = step.get("when")
            if when is not None:
                try:
                    self._locate(when, timeout_ms=int(when.get("timeout_ms", 3000)))
                except StepFailed:
                    return
            self._capture(step)
            return
        if "launch" in step:
            if (step.get("launch") or {}).get("clear"):
                self.adb.run("shell", "pm", "clear", self.app_id)
                if self.app_locale:  # clearing the app's data also drops its per-app language
                    set_app_locale(self.adb, self.app_id, self.app_locale)
            self.adb.start(self.app_id)
        elif "tap" in step or "long_press" in step:
            target = step.get("tap") or step.get("long_press")
            node = self._locate(target, timeout_ms=int(target.get("timeout_ms", 6000)), scroll=bool(target.get("scroll")))
            x, y = node.center
            if "tap" in step:
                self._input("tap", str(x), str(y))
            else:
                self._input("swipe", str(x), str(y), str(x), str(y), "900")
        elif "optional_tap" in step:
            node = find_node(self.nodes(), step["optional_tap"])
            if node is None:
                return
            self._input("tap", *map(str, node.center))
            action = f"Tapped {describe_target(step['optional_tap'])}"
        elif "double_tap" in step:
            w, h = self.size
            for _ in range(2):
                self._input("tap", str(w // 2), str(h // 2))
                time.sleep(0.08)
        elif "swipe" in step:
            self._swipe(str(step["swipe"]))
        elif "scroll_to" in step:
            self._locate(step["scroll_to"], scroll=True)
        elif "wait_for" in step:
            self._locate(step["wait_for"], timeout_ms=int(step["wait_for"].get("timeout_ms", 10000)))
        elif "back" in step:
            self.adb.key("KEYCODE_BACK")
        elif "background" in step:
            self.adb.home()
            time.sleep(min(int(step["background"]) / 1000.0, 30.0))
            self.adb.start(self.app_id)
        elif "wait_ms" in step:
            time.sleep(min(int(step["wait_ms"]) / 1000.0, 30.0))
        else:
            raise StepFailed(f"unknown step: {step}")
        if action:
            self._actions.append(action)
        time.sleep(float(step.get("settle_ms", 450)) / 1000.0)

    def _capture(self, step: dict[str, Any]) -> None:
        time.sleep(self.settle_ms / 1000.0)
        top = foreground_package(self.adb)
        if self._allowed is None:
            self._allowed = {self.app_id, *SYSTEM_PACKAGES} | home_packages(self.adb)
        if top and top not in self._allowed and "launcher" not in top and step["screen"] != "broken":
            raise StepFailed(f"device taken over: {top} is in front")
        idx = len(self.screens) + 1
        stem = f"{idx:02d}_{step['screen']}"
        xml_text = self._dump()
        (self.out_dir / f"{stem}.xml").write_text(xml_text, encoding="utf-8")
        self.adb.screencap(self.out_dir / f"{stem}.png")
        density = self.adb.density()
        entry = {
            "index": idx,
            "name": str(step["screen"]),
            "image": f"{stem}.png",
            "intent": step.get("intent", ""),
            "expected": step.get("expected", ""),
            "system_screen": bool(step.get("system")),
            "actions_before": list(self._actions),
            "metrics": screen_metrics(xml_text, density_dpi=density, locale=self.app_locale or "en"),
        }
        self._actions = []
        (self.out_dir / f"{stem}.json").write_text(json.dumps(entry, indent=2), encoding="utf-8")
        self.screens.append(entry)


def set_app_locale(adb: AdbClient, app_id: str, locale: str | None) -> None:
    """Per-app language (Android 13+, `cmd locale`); None or "" resets it to the system's."""
    adb.run("shell", "cmd", "locale", "set-app-locales", app_id, "--locales", locale or "")


def push_fixture(adb: AdbClient, local_dir: Path, remote_dir: str) -> None:
    """Replace `remote_dir` (inside /sdcard/PSTLab/) with `local_dir` and index it for MediaStore."""
    remote = ensure_android_lab_path(remote_dir)
    adb.rm_rf(remote)
    adb.push_dir(local_dir, remote)
    for name in sorted(p.name for p in Path(local_dir).iterdir() if p.is_file()):
        adb.run(
            "shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d", f"file://{remote}/{name}",
        )
