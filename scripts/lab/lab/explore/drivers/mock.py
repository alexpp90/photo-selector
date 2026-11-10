"""Mock driver: a deterministic offline state machine for tests and `--driver mock`."""

from __future__ import annotations

import shutil
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from ...model import Node, Tree
from ..actions import Action, ScreenState
from .base import DeviceDriver, DriverExecutionError, DriverSettlingError, DriverTimeoutError, _scratch_path


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
