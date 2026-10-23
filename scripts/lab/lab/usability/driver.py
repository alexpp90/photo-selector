"""Drive an Android app through a usability scenario and capture every screen a user sees.

Deliberately not Maestro: a scenario is a list of plain user actions ("tap 'Folder or
Cloud'", "swipe left") whose wording doubles as the action log the personas read, and each
`screen` step captures a screenshot, the view tree and the text metrics in one place.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..android.adb import AdbClient
from ..locks import DeviceBusy, device_lock  # noqa: F401 - re-exported for android_test_device.py
from ..paths import ensure_android_lab_path, repo_root
from .metrics import screen_metrics

PNG_1PX = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde"
    b"\x00\x00\x00\x0cIDATx\x9cc\xf8\xcf\xc0\x00\x00\x03\x01\x01\x00\xc9\xfe\x92\xef\x00\x00\x00\x00IEND\xaeB`\x82"
)


class BaseDriver:
    """Abstract base class for product usability scenario drivers."""

    def __init__(self, app_id: str, out_dir: Path, *, settle_ms: int = 450) -> None:
        self.app_id = app_id
        self.out_dir = out_dir
        self.settle_ms = settle_ms
        self.screens: list[dict[str, Any]] = []
        self._actions: list[str] = []

    def run(self, scenario: dict[str, Any]) -> dict[str, Any]:
        """Execute a scenario's steps and write scenario.json."""
        self.out_dir.mkdir(parents=True, exist_ok=True)
        status, error = "pass", None
        try:
            for step in scenario.get("steps", []):
                self._step(step)
        except StepFailed as exc:
            status, error = "broken", str(exc)
            try:
                self._capture({"screen": "broken", "intent": "(the scenario could not continue)"})
            except Exception:
                pass
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
        raise NotImplementedError

    def _capture(self, step: dict[str, Any]) -> None:
        raise NotImplementedError

    def close(self) -> None:
        pass


class StepFailed(RuntimeError):
    """A scenario step could not be carried out (element missing, timeout, device taken over)."""


_TOP = re.compile(r"(?:topResumedActivity|mResumedActivity)[=:]\s*\S+\s+\S+\s+([\w.]+)/")
# Packages a walkthrough may legitimately show besides the app: the system folder picker and
# its permission dialog, and the launcher after Back or Home.
SYSTEM_PACKAGES = ("com.android.documentsui", "com.google.android.documentsui", "android", "com.android.systemui")


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


def describe_target(target: dict[str, Any] | str) -> str:
    if isinstance(target, str):
        return f"'{target}'"
    if not isinstance(target, dict):
        return f"'{target}'"
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
    if "key" in step:
        k = step["key"]
        key_str = k if isinstance(k, str) else f"{k.get('code')}+{'+'.join(k.get('modifiers', []))}"
        return f"Pressed '{key_str}'"
    return None


class ScenarioDriver(BaseDriver):
    """Execute one scenario on an Android device; writes `<out_dir>/<NN>_<screen>.{png,xml,json}`."""

    def __init__(
        self, adb: AdbClient, app_id: str, out_dir: Path, *, settle_ms: int = 900, app_locale: str | None = None
    ) -> None:
        super().__init__(app_id, out_dir, settle_ms=settle_ms)
        self.adb = adb
        self.app_locale = app_locale
        self.size = (1080, 1920)
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
                return text[start:end + len("</hierarchy>")]
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

    def key(self, key_name: str) -> None:
        key_map = {
            "Right": "KEYCODE_DPAD_RIGHT",
            "Left": "KEYCODE_DPAD_LEFT",
            "Up": "KEYCODE_DPAD_UP",
            "Down": "KEYCODE_DPAD_DOWN",
            "Delete": "KEYCODE_DEL",
            "Backspace": "KEYCODE_DEL",
            "Space": "KEYCODE_SPACE",
            "Escape": "KEYCODE_ESCAPE",
            "Tab": "KEYCODE_TAB",
            "Enter": "KEYCODE_ENTER",
        }
        if key_name in key_map:
            self.adb.key(key_map[key_name])
        elif len(key_name) == 1 and key_name.isalpha():
            self.adb.key(f"KEYCODE_{key_name.upper()}")
        elif len(key_name) == 1 and key_name.isdigit():
            self.adb.key(f"KEYCODE_{key_name}")
        else:
            self.adb.key(key_name)

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
        elif "key" in step:
            k = step["key"]
            key_name = k if isinstance(k, str) else k.get("code", "")
            self.key(key_name)
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


AndroidDriver = ScenarioDriver


class MacOsDriver(BaseDriver):
    """Driver for macOS Lensjet application using offscreen headless UI test hooks."""

    def __init__(
        self,
        app_id: str,
        out_dir: Path,
        *,
        app_binary: Path | None = None,
        fixture_dir: Path | None = None,
        settle_ms: int = 250,
        exec_fn: Any | None = None,
    ) -> None:
        super().__init__(app_id, out_dir, settle_ms=settle_ms)
        self.app_binary = app_binary or (
            repo_root() / "products" / "macos-desktop" / ".build" / "hooks" / "app" / "Lensjet.app" / "Contents" / "MacOS" / "PhotoSelectorApp"
        )
        self.fixture_dir = fixture_dir
        self.exec_fn = exec_fn

    _MACOS_LIVE_KEYS = {
        "1", "2", "3", "c", "d", "f", "h", "i", "j", "k", "l", "m",
        "s", "z", "tab", "space", "escape", "delete", "backspace",
        "left", "right", "down", "up", "comma", "slash",
    }
    _MACOS_LIVE_TARGETS = {
        "slot_current", "single_image_viewport", "dock", "toolbar_zoom",
        "toolbar_next", "toolbar_previous", "toolbar_mode_1",
        "toolbar_mode_2", "toolbar_mode_3", "dock_move", "dock_copy",
        "dock_trash", "dock_undo",
    }

    def _translate_step(self, step: dict[str, Any]) -> dict[str, Any] | None:
        if "launch" in step:
            return {"action": "waitLoaded"}
        if "key" in step:
            k = step["key"]
            if isinstance(k, str):
                key_lower = k.lower()
                if "+" in k:
                    parts = [p.strip() for p in k.split("+")]
                    mods = []
                    key_char = parts[-1].lower()
                    for m in parts[:-1]:
                        ml = m.lower()
                        if ml in ("cmd", "command"):
                            mods.append("command")
                        elif ml in ("shift",):
                            mods.append("shift")
                        elif ml in ("opt", "option", "alt"):
                            mods.append("option")
                        elif ml in ("ctrl", "control"):
                            mods.append("control")
                    if self.exec_fn is not None or key_char in self._MACOS_LIVE_KEYS:
                        res: dict[str, Any] = {"action": "key", "key": key_char}
                        if mods:
                            res["modifiers"] = mods
                        return res
                    return {"action": "wait", "ms": 150}
                elif key_lower in ("right", "left", "up", "down", "space", "escape"):
                    return {"action": "key", "key": key_lower}
                elif key_lower in ("delete", "backspace"):
                    return {"action": "key", "key": "delete"}
                elif key_lower in ("enter", "return"):
                    return {"action": "key", "key": "return"}
                else:
                    if self.exec_fn is not None or key_lower in self._MACOS_LIVE_KEYS:
                        return {"action": "key", "key": key_lower}
                    return {"action": "wait", "ms": 150}
            elif isinstance(k, dict):
                code = str(k.get("code", "")).lower()
                mods = [str(m).lower() for m in k.get("modifiers", [])]
                if self.exec_fn is not None or code in self._MACOS_LIVE_KEYS:
                    res = {"action": "key", "key": code}
                    if mods:
                        res["modifiers"] = mods
                    return res
                return {"action": "wait", "ms": 150}
        if "wait_for" in step:
            t = step["wait_for"]
            target = t.get("id") or t.get("text") or t.get("name") or str(t)
            if self.exec_fn is not None or target in self._MACOS_LIVE_TARGETS:
                return {"action": "waitFor", "target": str(target)}
            return {"action": "wait", "ms": 200}
        if "tap" in step:
            t = step["tap"]
            target = t.get("id") or t.get("text") or t.get("name") or str(t)
            if self.exec_fn is not None or target in self._MACOS_LIVE_TARGETS:
                return {"action": "click", "target": str(target)}
            return {"action": "wait", "ms": 150}
        if "wait_ms" in step:
            return {"action": "wait", "ms": int(step["wait_ms"])}
        if "screen" in step:
            return {"action": "checkpoint", "name": str(step["screen"])}
        return None

    def run(self, scenario: dict[str, Any]) -> dict[str, Any]:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        translated_steps: list[dict[str, Any]] = []
        screen_metas: list[dict[str, Any]] = []

        for step in scenario.get("steps", []):
            action = describe_action(step)
            if action:
                self._actions.append(action)
            tr = self._translate_step(step)
            if tr:
                translated_steps.append(tr)
            if "screen" in step:
                screen_metas.append({
                    "name": str(step["screen"]),
                    "intent": step.get("intent", ""),
                    "expected": step.get("expected", ""),
                    "system_screen": bool(step.get("system")),
                    "actions_before": list(self._actions),
                })
                self._actions = []

        scenario_doc = {
            "id": scenario["id"],
            "name": scenario["id"],
            "scenario": scenario["id"],
            "title": scenario.get("title", ""),
            "steps": translated_steps,
        }
        scenario_file = self.out_dir / "ui-scenario.json"
        scenario_file.write_text(json.dumps(scenario_doc, indent=2), encoding="utf-8")

        status, error = "pass", None
        try:
            cmd = [
                str(self.app_binary),
                "--open", str(self.fixture_dir or "/tmp/pstlab/usability"),
                "--ui-scenario", str(scenario_file),
                "--artifacts", str(self.out_dir),
            ]
            env = dict(os.environ)
            env["PST_SHOW_GUI"] = "0"
            if self.exec_fn is not None:
                self.exec_fn(cmd, env)
            else:
                subprocess.run(cmd, env=env, check=True, capture_output=True)
        except Exception as exc:
            status, error = "broken", str(exc)

        # Collect checkpoints
        for idx, meta in enumerate(screen_metas, start=1):
            name = meta["name"]
            stem = f"{idx:02d}_{name}"
            tree_path = self.out_dir / f"{stem}.tree.json"
            if not tree_path.is_file():
                tree_path = self.out_dir / f"{name}.tree.json"

            tree_dict: dict[str, Any] = {}
            if tree_path.is_file():
                try:
                    tree_dict = json.loads(tree_path.read_text(encoding="utf-8"))
                except Exception:
                    pass

            metrics = screen_metrics(tree_dict) if tree_dict else {
                "words": 0, "reading_seconds": 0.0, "longest_block_words": 0,
                "small_targets": 0, "unlabeled_controls": 0, "texts": [],
            }

            img_name = f"{stem}.png"
            if not (self.out_dir / img_name).is_file() and (self.out_dir / f"{name}.png").is_file():
                img_name = f"{name}.png"

            entry = {
                "index": idx,
                "name": name,
                "image": img_name,
                "intent": meta["intent"],
                "expected": meta["expected"],
                "system_screen": meta["system_screen"],
                "actions_before": meta["actions_before"],
                "metrics": metrics,
            }
            (self.out_dir / f"{stem}.json").write_text(json.dumps(entry, indent=2), encoding="utf-8")
            self.screens.append(entry)

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


class LinuxDriver(BaseDriver):
    """Driver for Linux Blitzlens application using session.sh and Xvfb."""

    def __init__(
        self,
        app_id: str,
        out_dir: Path,
        *,
        session_script: Path | None = None,
        fixture_dir: Path | None = None,
        settle_ms: int = 250,
        exec_fn: Any | None = None,
    ) -> None:
        super().__init__(app_id, out_dir, settle_ms=settle_ms)
        self.session_script = session_script or (
            repo_root() / "products" / "linux-desktop" / "tests" / "e2e" / "session.sh"
        )
        self.fixture_dir = fixture_dir
        self.exec_fn = exec_fn

    def _translate_step_line(self, step: dict[str, Any]) -> str | None:
        if "launch" in step:
            return "wait_loaded"
        if "key" in step:
            k = step["key"]
            key_name = k if isinstance(k, str) else k.get("code", "")
            return f"key {key_name}"
        if "wait_ms" in step:
            sec = int(step["wait_ms"]) / 1000.0
            return f"sleep {sec:.2f}"
        if "screen" in step:
            return f"checkpoint {step['screen']}"
        if "tap" in step:
            t = step["tap"]
            target = t.get("id") or t.get("text") or t.get("name") or str(t)
            return f"click {target}"
        return None

    def run(self, scenario: dict[str, Any]) -> dict[str, Any]:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        script_lines: list[str] = ["# Usability Scenario Script"]
        screen_metas: list[dict[str, Any]] = []

        for step in scenario.get("steps", []):
            action = describe_action(step)
            if action:
                self._actions.append(action)
            line = self._translate_step_line(step)
            if line:
                script_lines.append(line)
            if "screen" in step:
                screen_metas.append({
                    "name": str(step["screen"]),
                    "intent": step.get("intent", ""),
                    "expected": step.get("expected", ""),
                    "system_screen": bool(step.get("system")),
                    "actions_before": list(self._actions),
                })
                self._actions = []

        script_file = self.out_dir / "session.script"
        script_file.write_text("\n".join(script_lines) + "\n", encoding="utf-8")

        status, error = "pass", None
        try:
            cmd = [
                str(self.session_script),
                "--script", str(script_file),
                "--artifacts", str(self.out_dir),
            ]
            env = dict(os.environ)
            if self.exec_fn is not None:
                self.exec_fn(cmd, env)
            else:
                subprocess.run(cmd, env=env, check=True, capture_output=True)
        except Exception as exc:
            status, error = "broken", str(exc)

        # Collect checkpoints
        for idx, meta in enumerate(screen_metas, start=1):
            name = meta["name"]
            stem = f"{idx:02d}_{name}"
            tree_path = self.out_dir / f"{stem}.tree.json"
            if not tree_path.is_file():
                tree_path = self.out_dir / f"{name}.tree.json"

            tree_dict: dict[str, Any] = {}
            if tree_path.is_file():
                try:
                    tree_dict = json.loads(tree_path.read_text(encoding="utf-8"))
                except Exception:
                    pass

            metrics = screen_metrics(tree_dict) if tree_dict else {
                "words": 0, "reading_seconds": 0.0, "longest_block_words": 0,
                "small_targets": 0, "unlabeled_controls": 0, "texts": [],
            }

            img_name = f"{stem}.png"
            if not (self.out_dir / img_name).is_file() and (self.out_dir / f"{name}.png").is_file():
                img_name = f"{name}.png"

            entry = {
                "index": idx,
                "name": name,
                "image": img_name,
                "intent": meta["intent"],
                "expected": meta["expected"],
                "system_screen": meta["system_screen"],
                "actions_before": meta["actions_before"],
                "metrics": metrics,
            }
            (self.out_dir / f"{stem}.json").write_text(json.dumps(entry, indent=2), encoding="utf-8")
            self.screens.append(entry)

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


class MockDriver(BaseDriver):
    """Deterministic offline driver producing synthetic checkpoints for tests without hardware."""

    def __init__(
        self,
        product: str = "phototok",
        app_id: str = "mock.app",
        out_dir: Path | None = None,
        *,
        scripted_screens: dict[str, Any] | None = None,
        fail_on_step: str | int | None = None,
        settle_ms: int = 0,
    ) -> None:
        super().__init__(app_id, out_dir or Path("/tmp/pstlab/mock_run"), settle_ms=settle_ms)
        self.product = product
        self.scripted_screens = scripted_screens or {}
        self.fail_on_step = fail_on_step
        self.actions_log: list[tuple[str, Any]] = []
        self._step_count = 0

    def _step(self, step: dict[str, Any]) -> None:
        self._step_count += 1
        if self.fail_on_step is not None:
            fail_str = str(self.fail_on_step)
            if (
                self.fail_on_step == self._step_count
                or fail_str in step
                or step.get("screen") == self.fail_on_step
                or fail_str in str(step)
            ):
                raise StepFailed(f"mock step failure on {self.fail_on_step}")
        if step.get("fail"):
            raise StepFailed(f"mock step failure configured in step: {step}")

        action = describe_action(step)
        if action:
            self._actions.append(action)
            self.actions_log.append((action, step))

        if "screen" in step:
            self._capture(step)

    def _capture(self, step: dict[str, Any]) -> None:
        idx = len(self.screens) + 1
        name = str(step["screen"])
        stem = f"{idx:02d}_{name}"
        png_path = self.out_dir / f"{stem}.png"
        png_path.write_bytes(PNG_1PX)

        if self.product in ("phototok", "android-desktop"):
            xml_text = (
                f"<?xml version='1.0' encoding='UTF-8'?><hierarchy rotation='0'>"
                f"<node bounds='[0,0][1080,1920]' text='{name}' resource-id='{name}' clickable='true'/>"
                f"</hierarchy>"
            )
            (self.out_dir / f"{stem}.xml").write_text(xml_text, encoding="utf-8")
            metrics = screen_metrics(xml_text, density_dpi=440)
        else:
            tree_dict = {
                "platform": self.product,
                "window": {"w": 1440, "h": 900, "unit": "pt", "scale": 2.0},
                "nodes": [
                    {
                        "id": f"node_{name}",
                        "role": "window",
                        "name": name,
                        "text": name,
                        "bounds": [0, 0, 1440, 900],
                        "interactive": True,
                        "visible": True,
                        "overlay": False,
                        "clipped": False,
                    }
                ],
            }
            (self.out_dir / f"{stem}.tree.json").write_text(json.dumps(tree_dict), encoding="utf-8")
            metrics = screen_metrics(tree_dict)

        entry = {
            "index": idx,
            "name": name,
            "image": f"{stem}.png",
            "intent": step.get("intent", ""),
            "expected": step.get("expected", ""),
            "system_screen": bool(step.get("system")),
            "actions_before": list(self._actions),
            "metrics": metrics,
        }
        self._actions = []
        (self.out_dir / f"{stem}.json").write_text(json.dumps(entry, indent=2), encoding="utf-8")
        self.screens.append(entry)


FakeDriver = MockDriver


def get_driver(
    product: str,
    app_id: str,
    out_dir: Path,
    *,
    serial: str | None = None,
    adb: AdbClient | None = None,
    fixture_dir: Path | None = None,
    driver_type: str = "auto",
    exec_fn: Any | None = None,
    app_locale: str | None = None,
) -> BaseDriver:
    """Factory creating the appropriate driver for a product and mode."""
    if driver_type == "mock":
        return MockDriver(product=product, app_id=app_id, out_dir=out_dir)
    if product in ("phototok", "android-desktop"):
        client = adb or AdbClient(serial=serial)
        return ScenarioDriver(client, app_id, out_dir, app_locale=app_locale)
    if product == "macos":
        return MacOsDriver(app_id, out_dir, fixture_dir=fixture_dir, exec_fn=exec_fn)
    if product == "linux":
        return LinuxDriver(app_id, out_dir, fixture_dir=fixture_dir, exec_fn=exec_fn)
    raise ValueError(f"unknown product: {product}")


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
