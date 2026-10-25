"""Shared fakes for the explore tests: screen states, PNGs and a scripted driver.

`ScriptedDriver` implements the `DeviceDriver` contract (setup / prepare / capture_state /
execute_action / restart_app / read_app_log / teardown) without a device, and records the order
of its calls, so the orchestrator is tested against the contract and not against a real driver.
"""

from __future__ import annotations

import io
import sys
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

LAB_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(LAB_DIR))

from PIL import Image  # noqa: E402

from lab.explore.actions import Action, ScreenState  # noqa: E402
from lab.explore.driver import DeviceDriver  # noqa: E402
from lab.model import Node, Tree  # noqa: E402


def png_bytes(color: tuple[int, int, int] = (40, 40, 40), size: tuple[int, int] = (64, 96)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", size, color).save(buf, format="PNG")
    return buf.getvalue()


def node(node_id: str, bounds: List[float], *, text: str = "", name: str = "", interactive: bool = True, parent: Optional[str] = None) -> Node:
    return Node(id=node_id, parent=parent, role="button" if interactive else "text", name=name or node_id,
                bounds=bounds, interactive=interactive, visible=True, text=text)


def make_state(
    nodes: Optional[List[Node]] = None,
    *,
    mode: str = "viewer",
    files: Optional[List[str]] = None,
    logs: Optional[List[str]] = None,
    color: tuple[int, int, int] = (40, 40, 40),
    platform: str = "android",
) -> ScreenState:
    tree = Tree(platform, {"w": 1080, "h": 1920, "unit": "dp", "scale": 1.0},
                nodes if nodes is not None else [node("root", [0, 0, 1080, 1920], interactive=False)])
    fs: Dict[str, Any] = {"root": "work", "files": [{"path": f, "size": 10} for f in (files or ["a.jpg", "b.jpg"])], "trash": []}
    return ScreenState(tree=tree, screenshot_bytes=png_bytes(color), screen_mode=mode, fs_snapshot=fs, logs=logs or [])


def default_state() -> ScreenState:
    """A screen with one big button: layout-clean, so the only findings come from the test."""
    return make_state([node("root", [0, 0, 1080, 1920], interactive=False), node("btn_ok", [100, 800, 400, 120], text="OK", parent="root")])


class ScriptedDriver(DeviceDriver):
    """A driver whose captures, action results and log lines are scripted by the test.

    `captures`: items returned in order by `capture_state`; an exception instance is raised.
    The last item repeats. `actions`: same for `execute_action` (None = success).
    `log`: lines handed out once by `read_app_log`.
    """

    def __init__(
        self,
        out_dir: Path,
        *,
        captures: Optional[List[Any]] = None,
        actions: Optional[List[Any]] = None,
        log: Optional[List[str]] = None,
        settle_ms: int = 0,
        on_setup: Optional[Callable[[], None]] = None,
        on_prepare: Optional[Callable[[], None]] = None,
        capture_delay_s: float = 0.0,
        adb: Any = None,
        serial: str = "FAKE1",
        calls: Optional[List[str]] = None,
    ) -> None:
        super().__init__("phototok", "com.phototok.lab", out_dir / "work", out_dir, settle_ms)
        self.captures = list(captures if captures is not None else [default_state()])
        self.actions = list(actions or [None])
        self.log = list(log or [])
        self.calls: List[str] = calls if calls is not None else []  # may be shared with the test
        self.executed: List[Action] = []
        self.on_setup = on_setup
        self.on_prepare = on_prepare
        self.capture_delay_s = capture_delay_s
        self.adb = adb
        self.serial = serial
        self.capture_count = 0

    def setup(self) -> None:
        self.calls.append("setup")
        if self.on_setup:
            self.on_setup()

    def prepare(self) -> None:
        self.calls.append("prepare")
        if self.on_prepare:
            self.on_prepare()

    def teardown(self) -> None:
        self.calls.append("teardown")

    def capture_state(self) -> ScreenState:
        import time

        self.calls.append("capture")
        self.capture_count += 1
        if self.capture_delay_s:
            time.sleep(self.capture_delay_s)
        item = self.captures[0] if len(self.captures) == 1 else self.captures.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item

    def execute_action(self, action: Action) -> None:
        self.calls.append("execute")
        self.executed.append(action)
        item = self.actions[0] if len(self.actions) == 1 else self.actions.pop(0)
        if isinstance(item, BaseException):
            raise item

    def restart_app(self) -> None:
        self.calls.append("restart")

    def read_app_log(self) -> List[str]:
        self.calls.append("read_log")
        lines, self.log = self.log, []
        return lines
