"""Move/Copy into Selection never overwrite (docs/shared/FILE_OPERATIONS.md section 1).

Each test drives the shipped ``execute_move_to_selection`` / ``execute_copy_to_selection``
with real files and the real ``find_related_files``.
"""
import errno
import os
import tkinter as tk
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest


pytestmark = pytest.mark.requirement("REQ-DESK-VIEW.07")


@pytest.fixture
def tk_root():
    """SharpnessTool builds Tk variables; give it a root instead of relying on earlier tests."""
    try:
        root = tk.Tk()
        root.withdraw()
    except tk.TclError as e:  # pragma: no cover - no display available
        pytest.skip(f"Tk unavailable: {e}")
    yield root
    try:
        root.destroy()
    except tk.TclError:  # pragma: no cover
        pass


def _make_tool(src_dir, parent):
    from photo_selector_toolbox.gui.sharpness_tool import SharpnessTool

    with (
        patch("photo_selector_toolbox.gui.sharpness_tool.SharpnessTool.setup_ui"),
        patch("photo_selector_toolbox.gui.sharpness_tool.SharpnessTool.setup_focus_ui"),
    ):
        tool = SharpnessTool(parent)
    tool.folder_var = MagicMock()
    tool.folder_var.get.return_value = str(src_dir)
    tool.candidate_listbox = MagicMock()
    tool.candidate_listbox.size.return_value = 1
    tool.candidates = []
    tool.panel_curr = MagicMock()
    tool.panel_prev = MagicMock()
    tool.panel_next = MagicMock()
    tool.meta_lbl = MagicMock()
    tool.log = MagicMock()
    return tool


def _run(tool, op, path, sorted_subfolders=False):
    cfg = {"selection_folder": "Selection", "separate_raw_jpeg": sorted_subfolders}
    with (
        patch("photo_selector_toolbox.gui.sharpness_tool.load_config", return_value=cfg),
        patch("photo_selector_toolbox.gui.sharpness_tool.messagebox") as box,
    ):
        getattr(tool, op)(path, 0)
    return box


@pytest.fixture
def shoot(tmp_path):
    """Two cards that both hold DSC_0001: `a/` is culled, `Selection/` already has one."""
    src = tmp_path / "a"
    sel = src / "Selection"
    sel.mkdir(parents=True)
    (src / "DSC_0001.JPG").write_bytes(b"A")
    (sel / "DSC_0001.JPG").write_bytes(b"B")
    return src, sel


def _bytes(path):
    return path.read_bytes()


def test_move_renames_instead_of_replacing(shoot, tk_root):
    src, sel = shoot
    tool = _make_tool(src, tk_root)
    box = _run(tool, "execute_move_to_selection", src / "DSC_0001.JPG")
    assert _bytes(sel / "DSC_0001.JPG") == b"B"
    assert _bytes(sel / "DSC_0001-2.JPG") == b"A"
    assert not (src / "DSC_0001.JPG").exists()
    box.showerror.assert_not_called()


def test_move_windows_style_file_exists_does_not_replace(shoot, tk_root):
    """Windows raises FileExistsError from rename; that used to fall through to os.replace."""
    src, sel = shoot
    real_rename = os.rename

    def windows_rename(self_path, target):
        if os.path.lexists(target):
            raise FileExistsError(errno.EEXIST, "exists", str(target))
        return real_rename(self_path, target)

    tool = _make_tool(src, tk_root)
    with patch.object(Path, "rename", windows_rename):
        _run(tool, "execute_move_to_selection", src / "DSC_0001.JPG")
    assert _bytes(sel / "DSC_0001.JPG") == b"B"
    assert _bytes(sel / "DSC_0001-2.JPG") == b"A"


def test_move_cross_device_fallback_does_not_replace(shoot, tk_root):
    src, sel = shoot

    def raise_exdev(self_path, target):
        raise OSError(errno.EXDEV, "Invalid cross-device link")

    tool = _make_tool(src, tk_root)
    with patch.object(Path, "rename", raise_exdev):
        _run(tool, "execute_move_to_selection", src / "DSC_0001.JPG")
    assert _bytes(sel / "DSC_0001.JPG") == b"B"
    assert _bytes(sel / "DSC_0001-2.JPG") == b"A"
    assert not (src / "DSC_0001.JPG").exists()
    assert not [p for p in sel.iterdir() if ".tmp." in p.name]


def test_move_never_replaces_a_file_that_appears_after_planning(shoot, tk_root):
    """A target taken between planning and the write is an error for that file, not an overwrite."""
    src, sel = shoot
    (sel / "DSC_0001.JPG").unlink()
    tool = _make_tool(src, tk_root)
    real_plan = __import__(
        "photo_selector_toolbox.gui.sharpness_tool", fromlist=["plan_selection_targets"]
    ).plan_selection_targets

    def plan_then_collide(*args, **kwargs):
        plan = real_plan(*args, **kwargs)
        (sel / "DSC_0001.JPG").write_bytes(b"LATE")
        return plan

    with patch(
        "photo_selector_toolbox.gui.sharpness_tool.plan_selection_targets", plan_then_collide
    ):
        box = _run(tool, "execute_move_to_selection", src / "DSC_0001.JPG")
    assert _bytes(sel / "DSC_0001.JPG") == b"LATE"
    assert _bytes(src / "DSC_0001.JPG") == b"A"
    box.showerror.assert_called_once()


def test_copy_renames_instead_of_replacing(shoot, tk_root):
    src, sel = shoot
    tool = _make_tool(src, tk_root)
    box = _run(tool, "execute_copy_to_selection", src / "DSC_0001.JPG")
    assert _bytes(sel / "DSC_0001.JPG") == b"B"
    assert _bytes(sel / "DSC_0001-2.JPG") == b"A"
    assert _bytes(src / "DSC_0001.JPG") == b"A"
    box.showerror.assert_not_called()


def test_copy_twice_takes_the_next_free_suffix(shoot, tk_root):
    src, sel = shoot
    tool = _make_tool(src, tk_root)
    _run(tool, "execute_copy_to_selection", src / "DSC_0001.JPG")
    _run(tool, "execute_copy_to_selection", src / "DSC_0001.JPG")
    assert _bytes(sel / "DSC_0001.JPG") == b"B"
    assert _bytes(sel / "DSC_0001-2.JPG") == b"A"
    assert _bytes(sel / "DSC_0001-3.JPG") == b"A"


def test_bundle_shares_one_suffix(tmp_path, tk_root):
    """One occupied member renames the whole bundle: RAW, JPEG, the sidecar and the edit."""
    src = tmp_path / "a"
    sel = src / "Selection"
    sel.mkdir(parents=True)
    members = {
        "IMG_1.CR3": b"raw",
        "IMG_1.JPG": b"jpg",
        "IMG_1.xmp": b"x2",
        "IMG_1-Edit.tif": b"edit",
    }
    for name, data in members.items():
        (src / name).write_bytes(data)
    (sel / "IMG_1.JPG").write_bytes(b"OLD")  # only the JPEG collides

    tool = _make_tool(src, tk_root)
    _run(tool, "execute_copy_to_selection", src / "IMG_1.CR3")

    assert _bytes(sel / "IMG_1.JPG") == b"OLD"
    assert sorted(p.name for p in sel.iterdir()) == sorted(
        ["IMG_1.JPG", "IMG_1-2.CR3", "IMG_1-2.JPG", "IMG_1-2.xmp", "IMG_1-2-Edit.tif"]
    )
    assert _bytes(sel / "IMG_1-2.JPG") == b"jpg"
    assert _bytes(sel / "IMG_1-2.CR3") == b"raw"


def test_collision_in_a_sorted_subfolder_renames_the_bundle(tmp_path, tk_root):
    src = tmp_path / "a"
    sel = src / "Selection"
    (sel / "RAW").mkdir(parents=True)
    (src / "IMG_2.CR3").write_bytes(b"new-raw")
    (src / "IMG_2.JPG").write_bytes(b"new-jpg")
    (sel / "RAW" / "IMG_2.CR3").write_bytes(b"old-raw")

    tool = _make_tool(src, tk_root)
    _run(tool, "execute_move_to_selection", src / "IMG_2.CR3", sorted_subfolders=True)

    assert _bytes(sel / "RAW" / "IMG_2.CR3") == b"old-raw"
    assert _bytes(sel / "RAW" / "IMG_2-2.CR3") == b"new-raw"
    assert _bytes(sel / "JPEG" / "IMG_2-2.JPG") == b"new-jpg"


def test_free_name_keeps_its_name(tmp_path, tk_root):
    src = tmp_path / "a"
    src.mkdir()
    (src / "IMG_3.JPG").write_bytes(b"x")
    tool = _make_tool(src, tk_root)
    _run(tool, "execute_copy_to_selection", src / "IMG_3.JPG")
    assert _bytes(src / "Selection" / "IMG_3.JPG") == b"x"
