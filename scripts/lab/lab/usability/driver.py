"""Drive an Android app through a usability scenario and capture every screen a user sees.

Deliberately not Maestro: a scenario is a list of plain user actions ("tap 'Folder or
Cloud'", "swipe left") whose wording doubles as the action log the personas read, and each
`screen` step captures a screenshot, the view tree and the text metrics in one place.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..android.adb import AdbClient
from ..locks import DeviceBusy, device_lock  # noqa: F401 - re-exported for android_test_device.py
from ..paths import ensure_android_lab_path, ensure_under, lab_tmp_root, repo_root
from .metrics import screen_metrics

PNG_1PX = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde"
    b"\x00\x00\x00\x0cIDATx\x9cc\xf8\xcf\xc0\x00\x00\x03\x01\x01\x00\xc9\xfe\x92\xef\x00\x00\x00\x00IEND\xaeB`\x82"
)


class BaseDriver:
    """What every product driver shares: the screen log a walkthrough is built from."""

    product = ""

    def __init__(self, app_id: str, out_dir: Path, *, settle_ms: int = 450) -> None:
        self.app_id = app_id
        self.out_dir = out_dir
        self.settle_ms = settle_ms
        self.screens: list[dict[str, Any]] = []
        self._actions: list[str] = []

    def validate(self, scenario: dict[str, Any]) -> list[str]:
        """Steps this driver cannot carry out, as messages; empty when the scenario runs as written.

        A step the driver cannot perform must never be replaced by a wait: the persona would be
        told an action happened that never did.
        """
        return []

    def run(self, scenario: dict[str, Any]) -> dict[str, Any]:
        """Execute a scenario's steps and write scenario.json."""
        self.out_dir.mkdir(parents=True, exist_ok=True)
        status, error = "pass", None
        problems = self.validate(scenario)
        if problems:
            return self._result(scenario, "broken", "cannot run: " + "; ".join(problems))
        try:
            for step in scenario.get("steps", []):
                self._step(step)
        except (StepFailed, subprocess.SubprocessError) as exc:
            # An adb call that times out or fails (`am start -W` hung for 60 s on the Pixel
            # Tablet) ends this scenario as broken, with its reason; it must not lose the walk.
            status, error = "broken", str(exc)
            try:
                self._capture({"screen": "broken", "intent": "(the scenario could not continue)"})
            except StepFailed:
                pass  # no screen either; the error above says why
        return self._result(scenario, status, error)

    def _result(self, scenario: dict[str, Any], status: str, error: str | None) -> dict[str, Any]:
        result = {
            "scenario": scenario["id"],
            "title": scenario.get("title", ""),
            "goal": scenario.get("goal", ""),
            "use_case": scenario.get("use_case", ""),
            "status": status,
            "error": error,
            "screens": self.screens,
        }
        self.out_dir.mkdir(parents=True, exist_ok=True)
        (self.out_dir / "scenario.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        return result

    def _screen_entry(self, idx: int, step: dict[str, Any], image: str, metrics: dict[str, Any]) -> dict[str, Any]:
        entry = {
            "index": idx,
            "name": str(step["screen"]),
            "image": image,
            "intent": step.get("intent", ""),
            "expected": step.get("expected", ""),
            "system_screen": bool(step.get("system")),
            "actions_before": list(self._actions),
            "metrics": metrics,
        }
        self._actions = []
        (self.out_dir / f"{Path(image).stem}.json").write_text(json.dumps(entry, indent=2), encoding="utf-8")
        self.screens.append(entry)
        return entry

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


_MODIFIERS = {
    "cmd": "command", "command": "command", "ctrl": "control", "control": "control",
    "shift": "shift", "alt": "option", "opt": "option", "option": "option", "super": "super", "meta": "super",
}
_MODIFIER_LABELS = {"command": "Cmd", "control": "Ctrl", "shift": "Shift", "option": "Alt", "super": "Super"}


def parse_key(spec: Any) -> tuple[str, tuple[str, ...]]:
    """`"Cmd+Shift+Z"` or `{code: z, modifiers: [cmd]}` -> `("Z", ("command", "shift"))`.

    The key keeps the scenario's spelling ("Right", "Delete", ","); modifiers are normalised,
    and an unknown one raises instead of being dropped (a dropped Ctrl turns Undo into Z).
    """
    if isinstance(spec, dict):
        key = str(spec.get("code", "")).strip()
        mods = [str(m) for m in spec.get("modifiers", []) or []]
    else:
        text = str(spec).strip()
        if text == "+":
            parts = ["+"]
        elif text.endswith("++"):  # "Cmd++" names the plus key itself
            parts = [*text[:-2].split("+"), "+"]
        else:
            parts = text.split("+")
        key, mods = parts[-1].strip(), [p for p in parts[:-1] if p.strip()]
    if not key:
        raise ValueError(f"empty key in {spec!r}")
    names: list[str] = []
    for m in mods:
        norm = _MODIFIERS.get(m.strip().lower())
        if norm is None:
            raise ValueError(f"unknown modifier {m!r} in {spec!r}")
        if norm not in names:
            names.append(norm)
    return key, tuple(names)


def format_key(spec: Any) -> str:
    """The key as a user names it: `Ctrl+Z`, `Cmd+,`, `Right`, `M`."""
    key, mods = parse_key(spec)
    shown = key.upper() if len(key) == 1 else key
    return "+".join([*(_MODIFIER_LABELS[m] for m in mods), shown])


ANDROID_KEY_CODES = {
    "right": "KEYCODE_DPAD_RIGHT", "left": "KEYCODE_DPAD_LEFT", "up": "KEYCODE_DPAD_UP", "down": "KEYCODE_DPAD_DOWN",
    "delete": "KEYCODE_DEL", "backspace": "KEYCODE_DEL", "forward_delete": "KEYCODE_FORWARD_DEL",
    "space": "KEYCODE_SPACE", "escape": "KEYCODE_ESCAPE", "esc": "KEYCODE_ESCAPE", "tab": "KEYCODE_TAB",
    "enter": "KEYCODE_ENTER", "return": "KEYCODE_ENTER", ",": "KEYCODE_COMMA", "comma": "KEYCODE_COMMA",
    "/": "KEYCODE_SLASH", "slash": "KEYCODE_SLASH",
}
ANDROID_MODIFIER_CODES = {
    "control": "KEYCODE_CTRL_LEFT", "shift": "KEYCODE_SHIFT_LEFT", "option": "KEYCODE_ALT_LEFT",
    "command": "KEYCODE_META_LEFT", "super": "KEYCODE_META_LEFT",
}


def android_keycode(key: str) -> str:
    """`Right` -> `KEYCODE_DPAD_RIGHT`, `m` -> `KEYCODE_M`; raises for a key Android has no code for."""
    if key.startswith("KEYCODE_"):
        return key
    if key.lower() in ANDROID_KEY_CODES:
        return ANDROID_KEY_CODES[key.lower()]
    if len(key) == 1 and key.isascii() and key.isalnum():
        return f"KEYCODE_{key.upper()}"
    raise ValueError(f"no Android key code for {key!r}")



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
        try:
            return f"Pressed '{format_key(step['key'])}'"
        except ValueError:
            return f"Pressed {step['key']!r}"
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

    def validate(self, scenario: dict[str, Any]) -> list[str]:
        problems = []
        for idx, step in enumerate(scenario.get("steps", [])):
            if "key" in step:
                try:
                    android_keycode(parse_key(step["key"])[0])
                except ValueError as exc:
                    problems.append(f"step {idx + 1}: {exc}")
        return problems

    def key(self, spec: Any) -> None:
        """Press a key, with modifiers as one key combination (Android 13+)."""
        try:
            key, mods = parse_key(spec)
            code = android_keycode(key)
        except ValueError as exc:
            raise StepFailed(str(exc)) from exc
        if not mods:
            self.adb.key(code)
            return
        try:
            self.adb.key_combo(*(ANDROID_MODIFIER_CODES[m] for m in mods), code)
        except RuntimeError as exc:
            raise StepFailed(str(exc)) from exc

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
            self.key(step["key"])
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


class UnsupportedStep(ValueError):
    """A scenario step the product's lab hook cannot perform."""


class DesktopDriver(BaseDriver):
    """A desktop app runs a whole scenario in one process and writes `NN_<checkpoint>` files.

    Every scenario is a fresh launch on the run's working copy of the fixture (the copy carries
    earlier scenarios' moves and deletions, as the Android walk does). Each step is translated
    into the app's own lab script before anything runs; a step the hook cannot perform fails
    validation, so a persona is only ever told about actions that happened.
    """

    def __init__(
        self,
        app_id: str,
        out_dir: Path,
        *,
        fixture_dir: Path | None = None,
        exec_fn: Any | None = None,
        timeout_s: float = 600.0,
    ) -> None:
        super().__init__(app_id, out_dir, settle_ms=0)
        self.fixture_dir = fixture_dir
        # Set by the capture: each scenario starts from a fresh copy of this roll. A scenario is
        # its own launch, and U-LIN-02 opened on another photo because U-LIN-01 had moved the first.
        self.fixture_master: Path | None = None
        self.exec_fn = exec_fn
        self.timeout_s = timeout_s

    def translate(self, step: dict[str, Any]) -> list[Any]:
        """The step as lab-hook commands; raises `UnsupportedStep`."""
        raise NotImplementedError

    def validate(self, scenario: dict[str, Any]) -> list[str]:
        problems = []
        if self.fixture_dir is None:
            problems.append("no fixture folder to open")
        for idx, step in enumerate(scenario.get("steps", [])):
            try:
                self.translate(step)
            except (UnsupportedStep, ValueError) as exc:
                problems.append(f"step {idx + 1} ({next(iter(step), '?')}): {exc}")
        return problems

    def _commands(self, scenario: dict[str, Any]) -> tuple[list[Any], list[tuple[dict[str, Any], list[str]]]]:
        """Native commands, and each checkpoint step with the actions the user took before it."""
        commands: list[Any] = []
        checkpoints: list[tuple[dict[str, Any], list[str]]] = []
        actions: list[str] = []
        steps = list(scenario.get("steps", []))
        if not steps or "launch" not in steps[0]:
            steps.insert(0, {"launch": {}})  # every scenario is a fresh process
        for step in steps:
            commands.extend(self.translate(step))
            if "screen" in step:
                checkpoints.append((step, actions))
                actions = []
            else:
                said = describe_action(step)
                if said:
                    actions.append(said)
        return commands, checkpoints

    def _execute(self, cmd: list[str], env: dict[str, str]) -> tuple[int, str]:
        if self.exec_fn is not None:
            proc = self.exec_fn(cmd, env)
            if proc is None:
                return 0, ""
            return int(proc.returncode), (proc.stdout or "") + (proc.stderr or "")
        try:
            proc = subprocess.run(cmd, env=env, capture_output=True, text=True, check=False, timeout=self.timeout_s)
        except subprocess.TimeoutExpired as exc:
            out = exc.stdout.decode() if isinstance(exc.stdout, bytes) else (exc.stdout or "")
            return 124, out + f"\ntimed out after {self.timeout_s:.0f} s"
        return proc.returncode, (proc.stdout or "") + (proc.stderr or "")

    def _launch(self, scenario: dict[str, Any], commands: list[Any]) -> tuple[int, str]:
        raise NotImplementedError

    def run(self, scenario: dict[str, Any]) -> dict[str, Any]:
        self.out_dir.mkdir(parents=True, exist_ok=True)
        problems = self.validate(scenario)
        if problems:
            return self._result(scenario, "broken", "cannot run: " + "; ".join(problems))
        commands, checkpoints = self._commands(scenario)
        if self.fixture_master is not None and self.fixture_dir is not None:
            shutil.rmtree(self.fixture_dir, ignore_errors=True)
            shutil.copytree(self.fixture_master, self.fixture_dir)
        code, log = self._launch(scenario, commands)
        (self.out_dir / "app.log").write_text(log, encoding="utf-8")
        status, error = "pass", None
        for idx, (step, actions) in enumerate(checkpoints, start=1):
            stem = f"{idx:02d}_{step['screen']}"
            if not (self.out_dir / f"{stem}.png").is_file():
                tail = " | ".join(line for line in log.strip().splitlines()[-3:])
                status, error = "broken", f"checkpoint {step['screen']!r} was not written (exit {code}): {tail}"
                break
            tree_path = self.out_dir / f"{stem}.tree.json"
            metrics = screen_metrics(json.loads(tree_path.read_text(encoding="utf-8"))) if tree_path.is_file() else {}
            self._actions = list(actions)
            self._screen_entry(idx, step, f"{stem}.png", metrics)
        if status == "pass" and code != 0:
            status, error = "broken", f"the app exited with {code}: " + " | ".join(log.strip().splitlines()[-3:])
        return self._result(scenario, status, error)


def _checkpoint_name(step: dict[str, Any]) -> str:
    name = str(step["screen"])
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", name):
        raise UnsupportedStep(f"checkpoint name {name!r} must be letters, digits, '-' or '_'")
    return name


# `UITestDriver.Key.from(name:)` in products/macos-desktop/src/PhotoSelectorApp/UITestDriver.swift;
# test_usability.py checks this set against the Swift source.
MACOS_KEY_NAMES = frozenset({
    "1", "one", "2", "two", "3", "three", "c", "d", "f", "h", "i", "j", "k", "l", "m", "s", "z",
    "tab", "space", "escape", "esc", "delete", "backspace", "/", "slash", "?", ",", "comma",
    "left", "leftarrow", "right", "rightarrow", "down", "downarrow", "up", "uparrow",
})


class MacOsDriver(DesktopDriver):
    """Lensjet with its test hooks: `--open <fixture> --ui-scenario <json> --artifacts <dir>`, headless."""

    product = "macos"

    def __init__(self, app_id: str, out_dir: Path, *, app_binary: Path | None = None, **kwargs: Any) -> None:
        super().__init__(app_id, out_dir, **kwargs)
        self.app_binary = app_binary

    def translate(self, step: dict[str, Any]) -> list[Any]:
        if "launch" in step:
            return [{"action": "waitLoaded"}]
        if "screen" in step:
            return [{"action": "checkpoint", "name": _checkpoint_name(step)}]
        if "key" in step:
            key, mods = parse_key(step["key"])
            if key.lower() not in MACOS_KEY_NAMES:
                raise UnsupportedStep(f"Lensjet's UI test driver has no key {key!r}")
            cmd: dict[str, Any] = {"action": "key", "key": key.lower()}
            if mods:
                cmd["modifiers"] = list(mods)
            return [cmd]
        if "tap" in step or "wait_for" in step:
            target = step.get("tap") or step.get("wait_for")
            if not isinstance(target, dict) or not target.get("id"):
                raise UnsupportedStep("Lensjet targets are frame ids: use {id: <frame id>}")
            if "tap" in step:
                return [{"action": "click", "target": str(target["id"])}]
            cmd = {"action": "waitFor", "target": str(target["id"])}
            if target.get("timeout_ms"):
                cmd["timeoutSeconds"] = int(target["timeout_ms"]) / 1000.0
            return [cmd]
        if "wait_ms" in step:
            return [{"action": "wait", "ms": int(step["wait_ms"])}]
        raise UnsupportedStep("not supported by Lensjet's UI scenario runner")

    def _launch(self, scenario: dict[str, Any], commands: list[Any]) -> tuple[int, str]:
        from ..macos.runner import default_app_binary

        doc = {"id": scenario["id"], "steps": commands}
        scenario_file = self.out_dir / "ui-scenario.json"
        scenario_file.write_text(json.dumps(doc, indent=2), encoding="utf-8")
        binary = self.app_binary or default_app_binary()
        cmd = [str(binary), "--open", str(self.fixture_dir), "--ui-scenario", str(scenario_file),
               "--artifacts", str(self.out_dir)]
        # The app reads UserDefaults.standard: pin the settings that decide where M and C write, so
        # an external destination set in this account never receives the lab's files.
        cmd += ["-cullingDestinationFolderName", "Selection", "-externalCullingDestinationURL", ""]
        env = dict(os.environ)
        env["PST_SHOW_GUI"] = "0"  # headless: never a window on the developer's desktop
        return self._execute(cmd, env)


class LinuxDriver(DesktopDriver):
    """Blitzlens in the lab's private Xvfb + D-Bus session (`tests/e2e/session.sh --script`)."""

    product = "linux"

    def __init__(self, app_id: str, out_dir: Path, *, session_script: Path | None = None,
                 text_scaling: float | None = None, **kwargs: Any) -> None:
        super().__init__(app_id, out_dir, **kwargs)
        self.session_script = session_script or (
            repo_root() / "products" / "linux-desktop" / "tests" / "e2e" / "session.sh"
        )
        self.text_scaling = text_scaling
        self.final_wait_s = 3.0

    def translate(self, step: dict[str, Any]) -> list[Any]:
        from ..linux.keys import normalize_xdotool_key

        if "launch" in step:
            # session.sh returns once the window is mapped; the first decode needs a moment more.
            return ["sleep 1.50"]
        if "screen" in step:
            return [f"checkpoint {_checkpoint_name(step)}", "sleep 0.50"]
        if "key" in step:
            key, mods = parse_key(step["key"])
            xmods = {"control": "ctrl", "shift": "shift", "option": "alt", "super": "super"}
            if "command" in mods:
                raise UnsupportedStep("Cmd is a macOS modifier; Blitzlens uses Ctrl")
            name = normalize_xdotool_key(key)
            return [f"key {'+'.join([*(xmods[m] for m in mods), name])}", "sleep 0.20"]
        if "wait_ms" in step:
            return [f"sleep {max(0.05, int(step['wait_ms']) / 1000.0):.2f}"]
        raise UnsupportedStep("the Linux lab session has no click, wait-for or swipe; use keys and waits")

    def _launch(self, scenario: dict[str, Any], commands: list[Any]) -> tuple[int, str]:
        from ..linux.runner import session_env

        script = self.out_dir / "session.script"
        # A checkpoint is written after the next frame has painted and settled; the session
        # stops the app when the script ends, which lost a scenario's last checkpoint.
        lines = [f"# {scenario['id']}", *commands, f"sleep {self.final_wait_s:.2f}"]
        script.write_text("\n".join(lines) + "\n", encoding="utf-8")
        lab_dir = ensure_under(lab_tmp_root() / f"usability-linux-{os.getpid()}-{scenario['id']}", lab_tmp_root())
        shutil.rmtree(lab_dir, ignore_errors=True)
        cmd = [str(self.session_script), "--fixture", str(self.fixture_dir), "--script", str(script),
               "--artifacts", str(self.out_dir), "--lab-dir", str(lab_dir)]
        if self.text_scaling:
            cmd += ["--text-scaling", f"{self.text_scaling:g}"]
        try:
            return self._execute(cmd, session_env())
        finally:
            shutil.rmtree(lab_dir, ignore_errors=True)


class MockDriver(BaseDriver):
    """Deterministic offline driver producing synthetic checkpoints for tests without hardware.

    `fail_on_step` fails the step with that 1-based number (int) or the `screen` with that name
    (str).
    """

    def __init__(
        self,
        product: str = "phototok",
        app_id: str = "mock.app",
        out_dir: Path | None = None,
        *,
        fail_on_step: str | int | None = None,
        settle_ms: int = 0,
    ) -> None:
        super().__init__(app_id, out_dir or Path(tempfile.mkdtemp(prefix="mock_run_", dir=lab_tmp_root())),
                         settle_ms=settle_ms)
        self.product = product
        self.fail_on_step = fail_on_step
        self.actions_log: list[tuple[str, Any]] = []
        self._step_count = 0

    def validate(self, scenario: dict[str, Any]) -> list[str]:
        problems = []
        for idx, step in enumerate(scenario.get("steps", [])):
            if "key" in step:
                try:
                    parse_key(step["key"])
                except ValueError as exc:
                    problems.append(f"step {idx + 1}: {exc}")
        return problems

    def _step(self, step: dict[str, Any]) -> None:
        self._step_count += 1
        if (isinstance(self.fail_on_step, int) and self.fail_on_step == self._step_count) or (
            isinstance(self.fail_on_step, str) and step.get("screen") == self.fail_on_step
        ):
            raise StepFailed(f"mock step failure on {self.fail_on_step}")
        if step.get("fail"):
            raise StepFailed(f"mock step failure configured in step: {step}")
        if "screen" in step:
            self._capture(step)
            return
        action = describe_action(step)
        if action:
            self._actions.append(action)
            self.actions_log.append((action, step))

    def _capture(self, step: dict[str, Any]) -> None:
        idx = len(self.screens) + 1
        name = str(step["screen"])
        stem = f"{idx:02d}_{name}"
        self.out_dir.mkdir(parents=True, exist_ok=True)
        (self.out_dir / f"{stem}.png").write_bytes(PNG_1PX)
        if self.product in ("phototok", "android-desktop"):
            xml_text = (
                "<?xml version='1.0' encoding='UTF-8'?><hierarchy rotation='0'>"
                f"<node bounds='[0,0][1080,1920]' text='{name}' resource-id='{name}' clickable='true'/>"
                "</hierarchy>"
            )
            (self.out_dir / f"{stem}.xml").write_text(xml_text, encoding="utf-8")
            metrics = screen_metrics(xml_text, density_dpi=440)
        else:
            tree_dict = {
                "platform": self.product,
                "window": {"w": 1440, "h": 900, "unit": "pt", "scale": 2.0},
                "nodes": [{
                    "id": f"node_{name}", "role": "window", "name": name, "text": name,
                    "bounds": [0, 0, 1440, 900], "interactive": True, "visible": True,
                    "overlay": False, "clipped": False,
                }],
            }
            (self.out_dir / f"{stem}.tree.json").write_text(json.dumps(tree_dict), encoding="utf-8")
            metrics = screen_metrics(tree_dict)
        self._screen_entry(idx, step, f"{stem}.png", metrics)


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
    text_scaling: float | None = None,
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
        return LinuxDriver(app_id, out_dir, fixture_dir=fixture_dir, exec_fn=exec_fn, text_scaling=text_scaling)
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
