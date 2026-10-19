"""Unit tests for photo_selector_linux.ui.lab_hooks."""

from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

from photo_selector_linux.ui.lab_hooks import (
    LabCheckpointRecorder,
    build_tree_from_records,
    extract_state_dict,
    format_checkpoint_stem,
    infer_widget_role,
    is_custom_widget_name,
)


def test_format_checkpoint_stem_sanitizes_and_zero_pads() -> None:
    assert format_checkpoint_stem(1, "opened") == "01_opened"
    assert format_checkpoint_stem(12, "after keep/move!") == "12_after-keep-move"
    assert format_checkpoint_stem(3, "   ") == "03_checkpoint"


def test_is_custom_widget_name_filters_default_gtk_classes() -> None:
    assert not is_custom_widget_name("", "GtkBox")
    assert not is_custom_widget_name("GtkBox", "GtkBox")
    assert not is_custom_widget_name("AdwHeaderBar", "AdwHeaderBar")
    assert is_custom_widget_name("slot_current", "GtkBox")
    assert is_custom_widget_name("btn_move", "GtkButton")


def test_infer_widget_role_maps_buttons_images_text_and_containers() -> None:
    assert infer_widget_role("GtkButton", "btn_move") == "button"
    assert infer_widget_role("GtkPicture", "slot_current_image") == "image"
    assert infer_widget_role("GtkBox", "info_hud") == "text"
    assert infer_widget_role("GtkBox", "slot_current") == "container"


def test_build_tree_from_records_matches_tree_schema_contract() -> None:
    records = [
        {
            "id": "slot_current",
            "parent": None,
            "role": "container",
            "name": "slot_current",
            "bounds": [20, 60, 1880, 920],
            "interactive": False,
            "visible": True,
            "overlay": False,
            "text": "",
            "clipped": None,
        },
        {
            "id": "slot_current_image",
            "parent": "slot_current",
            "role": "image",
            "name": "slot_current_image",
            "bounds": [24, 90, 1872, 860],
            "interactive": False,
            "visible": True,
            "overlay": False,
            "text": "",
            "clipped": None,
        },
        {
            "id": "btn_move",
            "parent": "action_bar",
            "role": "button",
            "name": "Move to Selection (M)",
            "bounds": [640, 1010, 200, 44],
            "interactive": True,
            "visible": True,
            "overlay": False,
            "text": "Move to Selection (M)",
            "clipped": False,
        },
    ]
    tree = build_tree_from_records(records, 1920, 1080, scale=1.0)
    assert tree["platform"] == "linux"
    assert tree["window"] == {"w": 1920, "h": 1080, "unit": "px", "scale": 1.0}
    by_id = {n["id"]: n for n in tree["nodes"]}
    assert by_id["slot_current_image"]["parent"] == "slot_current"
    # "action_bar" was not in records, so orphan parent normalizes to None
    assert by_id["btn_move"]["parent"] is None
    assert by_id["btn_move"]["interactive"] is True


def test_extract_state_dict_reads_viewmodel_fields() -> None:
    fake_vm = SimpleNamespace(
        current_index=2,
        candidate_count=6,
        selected_count=1,
        trashed_count=2,
        remaining_count=3,
        comparison_mode=3,
    )
    state = extract_state_dict(fake_vm)
    assert state == {
        "current_index": 2,
        "total": 6,
        "tally": {"selected": 1, "trashed": 2, "remaining": 3},
        "mode": 3,
    }


class _FakeRect:
    def __init__(self, x: float, y: float, w: float, h: float):
        self._x = x
        self._y = y
        self._w = w
        self._h = h

    def get_x(self) -> float:
        return self._x

    def get_y(self) -> float:
        return self._y

    def get_width(self) -> float:
        return self._w

    def get_height(self) -> float:
        return self._h


class _FakeWidget:
    def __init__(
        self,
        name: str,
        type_name: str,
        bounds=(0, 0, 100, 40),
        label: str = "",
        children=None,
    ):
        self._name = name
        self.__gtype__ = SimpleNamespace(name=type_name)
        self._bounds = bounds
        self._label = label
        self._children = list(children or [])
        for idx, child in enumerate(self._children):
            child._next_sibling = self._children[idx + 1] if idx + 1 < len(self._children) else None

    def get_name(self) -> str:
        return self._name

    def compute_bounds(self, _root):
        return True, _FakeRect(*self._bounds)

    def get_label(self) -> str:
        return self._label

    def get_mapped(self) -> bool:
        return True

    def get_first_child(self):
        return self._children[0] if self._children else None

    def get_next_sibling(self):
        return getattr(self, "_next_sibling", None)


def test_lab_checkpoint_recorder_writes_all_four_files(tmp_path: Path) -> None:
    times = iter([10.0, 10.25, 10.65])
    recorder = LabCheckpointRecorder(artifacts_dir=tmp_path, clock=lambda: next(times))

    btn_move = _FakeWidget("btn_move", "GtkButton", bounds=(500, 900, 180, 40), label="Move")
    action_bar = _FakeWidget("action_bar", "GtkActionBar", bounds=(0, 880, 1440, 60), children=[btn_move])
    slot_img = _FakeWidget("slot_current_image", "GtkPicture", bounds=(20, 80, 1400, 760))
    slot_cur = _FakeWidget("slot_current", "GtkBox", bounds=(16, 70, 1408, 780), children=[slot_img])
    anon_box = _FakeWidget("GtkBox", "GtkBox", children=[slot_cur, action_bar])

    window = _FakeWidget("AdwApplicationWindow", "AdwApplicationWindow", children=[anon_box])
    window.get_width = lambda: 1440
    window.get_height = lambda: 900
    window.get_scale_factor = lambda: 1
    window.viewmodel = SimpleNamespace(
        current_index=0,
        candidate_count=4,
        selected_count=0,
        trashed_count=0,
        remaining_count=4,
        comparison_mode=1,
    )

    out1 = recorder.record_checkpoint(window, "opened")
    assert out1["tree"].name == "01_opened.tree.json"
    assert out1["state"].name == "01_opened.state.json"
    assert out1["timing"].name == "01_opened.timing.json"

    tree = json.loads(out1["tree"].read_text(encoding="utf-8"))
    ids = [n["id"] for n in tree["nodes"]]
    assert ids == ["slot_current", "slot_current_image", "action_bar", "btn_move"]

    timing1 = json.loads(out1["timing"].read_text(encoding="utf-8"))
    assert timing1 == {"checkpoint": "opened", "t_ms": 250, "step_ms": 250}

    out2 = recorder.record_checkpoint(window, "after-move")
    timing2 = json.loads(out2["timing"].read_text(encoding="utf-8"))
    assert timing2 == {"checkpoint": "after-move", "t_ms": 650, "step_ms": 400}


class _FakeFrameClock:
    def __init__(self):
        self.handlers = {}
        self.disconnected = []

    def connect(self, signal, callback):
        self.handlers[signal] = callback
        return 7

    def disconnect(self, handler_id):
        self.disconnected.append(handler_id)


class _FakeGLib:
    def __init__(self):
        self.timeouts = []

    def timeout_add(self, ms, callback):
        self.timeouts.append((ms, callback))
        return 1


def _deferred_setup(monkeypatch):
    from photo_selector_linux.ui import lab_hooks

    glib = _FakeGLib()
    monkeypatch.setattr(lab_hooks, "GLib", glib)
    recorder = LabCheckpointRecorder()
    recorded = []
    monkeypatch.setattr(recorder, "record_checkpoint", lambda window, name: recorded.append(name))
    clock = _FakeFrameClock()
    draws = []
    window = SimpleNamespace(get_frame_clock=lambda: clock, queue_draw=lambda: draws.append(True))
    return recorder, recorded, clock, glib, window, draws


def test_checkpoint_is_recorded_after_the_next_painted_frame(monkeypatch) -> None:
    """The header lagged one step behind state.json when captured inside the action (lab)."""
    recorder, recorded, clock, glib, window, draws = _deferred_setup(monkeypatch)

    recorder.record_checkpoint_after_frame(window, "three-up")
    assert recorded == [] and draws == [True]

    clock.handlers["after-paint"](clock)
    assert recorded == [] and clock.disconnected == [7]
    settle_ms, record = glib.timeouts[-1]
    assert settle_ms >= 200  # outlasts Adwaita's CSS transitions (mode button highlight)
    record()
    assert recorded == ["three-up"]

    _ms, fallback = glib.timeouts[0]
    fallback()
    assert recorded == ["three-up"]  # the fallback never records a second time


def test_checkpoint_falls_back_when_no_frame_is_painted(monkeypatch) -> None:
    recorder, recorded, clock, glib, window, _draws = _deferred_setup(monkeypatch)

    recorder.record_checkpoint_after_frame(window, "hidden")
    ms, fallback = glib.timeouts[0]
    assert ms < 500  # inside the runner's 0.5 s pause after a checkpoint
    fallback()
    assert recorded == ["hidden"]


def test_checkpoint_without_frame_clock_records_immediately(monkeypatch) -> None:
    recorder, recorded, _clock, _glib, _window, _draws = _deferred_setup(monkeypatch)
    recorder.record_checkpoint_after_frame(None, "no-window")
    assert recorded == ["no-window"]


# ── Q-2: the hooks must not leak outside the app window or into a shared /tmp ────────────────

def test_default_artifacts_dir_is_private_to_the_user(tmp_path: Path, monkeypatch) -> None:
    runtime = tmp_path / "run" / "user" / "1000"
    runtime.mkdir(parents=True)
    monkeypatch.delenv("PST_LAB_ARTIFACTS", raising=False)
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(runtime))

    assert LabCheckpointRecorder().artifacts_dir == runtime / "pstlab-artifacts"


def test_default_artifacts_dir_without_runtime_dir_is_a_per_user_private_directory(monkeypatch, tmp_path) -> None:
    import os
    import stat
    import tempfile

    monkeypatch.delenv("PST_LAB_ARTIFACTS", raising=False)
    monkeypatch.delenv("XDG_RUNTIME_DIR", raising=False)
    monkeypatch.setattr(tempfile, "gettempdir", lambda: str(tmp_path))

    recorder = LabCheckpointRecorder()
    window = None
    recorder.record_checkpoint(window, "opened")

    directory = recorder.artifacts_dir
    assert directory != Path("/tmp/pstlab-artifacts")
    assert str(os.getuid()) in directory.name
    assert stat.S_IMODE(directory.stat().st_mode) == 0o700


def test_explicit_artifacts_dir_from_the_lab_is_honoured(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("PST_LAB_ARTIFACTS", str(tmp_path / "chosen"))

    assert LabCheckpointRecorder().artifacts_dir == tmp_path / "chosen"


def test_failed_window_snapshot_never_falls_back_to_a_root_window_screenshot(tmp_path: Path, monkeypatch) -> None:
    import subprocess

    def forbidden(*args, **kwargs):  # pragma: no cover - failing is the point
        raise AssertionError(f"must not run an external screenshot tool: {args}")

    monkeypatch.setattr(subprocess, "run", forbidden)
    recorder = LabCheckpointRecorder(artifacts_dir=tmp_path)

    recorder.record_checkpoint(None, "opened")  # no window: nothing may capture the desktop

    assert not (tmp_path / "01_opened.png").exists()
