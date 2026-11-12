"""Newest-first / oldest-first view orders, their persistence, and Go to photo (backlog 2026-11-02).

Adheres to:
- R-LINUX-CULL-02: View orders (newest first by default, oldest first, file name, lowest sharpness first)
- R-LINUX-NAV-01: Go to photo by number or name
"""

import json
import os
import time
from pathlib import Path

import pytest

from photo_selector_linux.core.goto import resolve_goto
from photo_selector_linux.core.scanner import DirectoryScanner
from photo_selector_linux.core.settings import SettingsStore
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel


def _folder(tmp_path: Path, count: int = 7) -> Path:
    """DSC_0000..: the higher the number, the newer the file."""
    tmp_path.mkdir(parents=True, exist_ok=True)
    for i in range(count):
        path = tmp_path / f"DSC_{i:04d}.JPG"
        path.write_bytes(b"img")
        os.utime(path, (1_700_000_000 + i * 100, 1_700_000_000 + i * 100))
    return tmp_path


def _vm(tmp_path: Path, *, order: str = "newest", threaded: bool = False) -> CullingWorkspaceViewModel:
    settings = SettingsStore(path=tmp_path / "cfg" / "settings.json", auto_load=False)
    settings.view_order = order
    kwargs = {"scan_idle_add": (lambda fn, *a: fn(*a))} if threaded else {}
    return CullingWorkspaceViewModel(settings=settings, **kwargs)


def _names(vm) -> list[str]:
    return [c.filename for c in vm.candidates]


def test_a_new_install_opens_newest_first_and_the_first_photo_is_the_newest(tmp_path: Path):
    settings = SettingsStore(path=tmp_path / "none.json")
    assert settings.view_order == "newest"
    vm = _vm(tmp_path)
    vm.load_directory(_folder(tmp_path / "photos"))
    assert _names(vm)[0] == "DSC_0006.JPG" and _names(vm)[-1] == "DSC_0000.JPG"
    assert vm.current_index == 0


def test_oldest_first_and_file_name_orders(tmp_path: Path):
    folder = _folder(tmp_path / "p")
    vm = _vm(tmp_path)
    vm.load_directory(folder)
    vm.set_sort_order("oldest")
    assert _names(vm)[0] == "DSC_0000.JPG"
    vm.set_sort_order("natural")
    assert _names(vm) == sorted(_names(vm))
    vm.set_sort_order("newest")
    assert _names(vm)[0] == "DSC_0006.JPG"


def test_an_existing_install_keeps_the_file_name_order_it_had(tmp_path: Path):
    path = tmp_path / "settings.json"
    path.write_text(json.dumps({"auto_advance": False}), encoding="utf-8")  # written by an older version
    assert SettingsStore(path=path).view_order == "natural"
    path.write_text(json.dumps({"view_order": "oldest"}), encoding="utf-8")
    assert SettingsStore(path=path).view_order == "oldest"
    path.write_text(json.dumps({"view_order": "nonsense"}), encoding="utf-8")
    assert SettingsStore(path=path).view_order == "natural"


def test_the_chosen_order_is_persisted_and_a_sharpness_sweep_is_not(tmp_path: Path):
    folder = _folder(tmp_path / "p")
    vm = _vm(tmp_path)
    vm.load_directory(folder)
    vm.set_sort_order("oldest")
    saved = json.loads((tmp_path / "cfg" / "settings.json").read_text(encoding="utf-8"))
    assert saved["view_order"] == "oldest"
    vm.set_sort_order("lowest_sharpness_first")
    saved = json.loads((tmp_path / "cfg" / "settings.json").read_text(encoding="utf-8"))
    assert saved["view_order"] == "oldest", "the sweep is a triage view, never the order a folder opens in"
    assert SettingsStore(path=tmp_path / "cfg" / "settings.json").view_order == "oldest"


class BatchScanner(DirectoryScanner):
    """Emits fixed batches in file-name (oldest-first) order, like a progressive scan."""

    def __init__(self, size: int):
        super().__init__()
        self._size = size

    def scan_stream(self, root_dir, batch_size=30, first_batch_size=1, **kwargs):
        batch = []
        for batch in super().scan_stream(root_dir, batch_size=self._size, first_batch_size=self._size, **kwargs):
            yield batch


def _streaming_vm(tmp_path: Path, order: str):
    settings = SettingsStore(path=tmp_path / "cfg" / "settings.json", auto_load=False)
    settings.view_order = order
    pending = []
    vm = CullingWorkspaceViewModel(
        settings=settings, scanner=BatchScanner(2), scan_idle_add=lambda fn, *a: pending.append((fn, a))
    )
    return vm, pending


def _deliver_next(pending) -> None:
    fn, args = pending.pop(0)
    fn(*args)


def test_streaming_with_several_batches_an_untouched_session_lands_on_the_newest(tmp_path: Path):
    folder = _folder(tmp_path / "p")
    vm, pending = _streaming_vm(tmp_path, "newest")
    vm.load_directory(folder)
    vm._scan_thread.join(timeout=5)
    shown = []
    while pending:
        _deliver_next(pending)
        shown.append(vm.current_photo.filename if vm.current_photo else None)
    # every batch brings newer files; the untouched view always shows the newest so far
    assert shown[-1] == "DSC_0006.JPG"
    assert _names(vm)[0] == "DSC_0006.JPG"


def test_streaming_the_photo_on_screen_survives_the_reorder_once_the_user_has_moved(tmp_path: Path):
    folder = _folder(tmp_path / "p")
    vm, pending = _streaming_vm(tmp_path, "newest")
    vm.load_directory(folder)
    vm._scan_thread.join(timeout=5)
    seen = []
    while pending and len(seen) < 3:
        _deliver_next(pending)
        seen.append(vm.current_photo)
    assert vm.candidate_count >= 4
    vm.nav_next()  # the user moves on
    held = vm.current_photo
    while pending:
        _deliver_next(pending)
        assert vm.current_photo is held, "a later batch moved the photo the user is looking at"
    assert _names(vm)[0] == "DSC_0006.JPG"


def test_undo_puts_a_photo_back_by_the_active_order_even_when_the_order_changed(tmp_path: Path):
    folder = _folder(tmp_path / "p")
    vm = _vm(tmp_path)
    vm.load_directory(folder)
    photo = vm.candidates[2]
    idx = vm._remove(photo)
    vm.set_sort_order("oldest")  # the order changes while the photo is away
    vm.current_index = vm._reinsert(photo, idx)
    assert _names(vm) == sorted(_names(vm))
    assert vm.current_photo is photo


@pytest.mark.parametrize(
    "text,expected",
    [("3", 2), ("1", 0), ("7", 6), ("8", None), ("0", None), ("dsc_0003", 3), ("dsc_0003.jpg", 3),
     ("0004", 3), ("zzz", None), ("", None), ("  5 ", 4)],
)
def test_go_to_resolves_a_position_or_a_file_name(text, expected):
    names = [f"DSC_{i:04d}.JPG" for i in range(7)]
    assert resolve_goto(text, names) == expected


def test_a_name_match_prefers_the_whole_name_then_the_start_then_any_part():
    names = ["a_sunset.jpg", "sunset.jpg", "sunset_2.jpg"]
    assert resolve_goto("sunset", names) == 1  # the whole stem beats the earlier partial match
    assert resolve_goto("sunset_", names) == 2
    assert resolve_goto("nset_2", names) == 2


def test_go_to_photo_shows_the_match_or_changes_nothing(tmp_path: Path):
    folder = _folder(tmp_path / "p")
    vm = _vm(tmp_path, order="natural")
    vm.load_directory(folder)
    assert vm.go_to_photo("4") is True
    assert vm.current_photo.filename == "DSC_0003.JPG"
    assert vm.go_to_photo("999") is False and vm.current_photo.filename == "DSC_0003.JPG"
    assert vm.go_to_photo("dsc_0006") is True and vm.current_index == 6


def test_preview_line_names_the_target_and_says_no_match():
    from photo_selector_linux.ui.goto_dialog import NO_MATCH, PREVIEW_HINT, preview_text

    names = ["a.jpg", "b.jpg"]
    find = lambda t: resolve_goto(t, names)  # noqa: E731
    assert preview_text("", find, names.__getitem__) == (PREVIEW_HINT, False)
    assert preview_text("2", find, names.__getitem__) == ("2: b.jpg", False)
    assert preview_text("9", find, names.__getitem__) == (NO_MATCH, True)


# ── The shipped window: menu radio, indicator, B and G (real GTK under xvfb-run) ──────────────────


def _real_gtk() -> bool:
    from photo_selector_linux.ui import window as window_module

    return window_module.HAS_GI and "Mock" not in type(window_module.Gtk).__name__


needs_gtk = pytest.mark.skipif(not _real_gtk(), reason="needs real Gtk/Adw under a display server")


def _window(tmp_path: Path, order: str = "newest"):
    from photo_selector_linux.ui.window import MainWindow

    settings = SettingsStore(path=tmp_path / "cfg" / "settings.json", auto_load=False)
    settings.view_order = order
    win = MainWindow(settings=settings)
    win.viewmodel.load_directory(_folder(tmp_path / "photos"))
    return win


@needs_gtk
def test_the_menu_offers_the_four_orders_and_the_word_beside_the_counter_names_the_one_in_use(tmp_path: Path):
    from gi.repository import GLib

    from photo_selector_linux.ui.window import VIEW_ORDER_LABELS

    assert list(VIEW_ORDER_LABELS) == ["Newest first", "Oldest first", "File name", "Lowest sharpness first"]
    win = _window(tmp_path)
    assert win.lbl_order.get_label() == "Newest"  # the order in use is always in view (plan 1.7)
    win.lookup_action("view_order").activate(GLib.Variant.new_string("oldest"))
    assert win.viewmodel.sort_order == "oldest"
    assert win.lbl_order.get_label() == "Oldest"
    assert win.btn_order.get_tooltip_text() == "Order: Oldest first (S)"
    for order, word in (("natural", "Name"), ("lowest_sharpness_first", "Blurry"), ("newest", "Newest")):
        win.lookup_action("view_order").activate(GLib.Variant.new_string(order))
        assert win.lbl_order.get_label() == word
    assert win.btn_order.get_menu_model() is not None
    win.destroy()


@needs_gtk
def test_b_returns_from_the_sweep_to_the_persisted_order_not_to_file_name(tmp_path: Path):
    win = _window(tmp_path, order="oldest")
    win.toggle_view_order()
    assert win.viewmodel.sort_order == "lowest_sharpness_first"
    win.toggle_view_order()
    assert win.viewmodel.sort_order == "oldest"
    win.destroy()


@needs_gtk
def test_g_opens_the_dialog_enter_goes_and_a_miss_says_no_match(tmp_path: Path, monkeypatch):
    from gi.repository import GLib

    from photo_selector_linux.ui import goto_dialog
    from photo_selector_linux.ui.goto_dialog import NO_MATCH

    opened = []
    monkeypatch.setattr(goto_dialog.GoToPhotoDialog, "present", lambda self, parent=None: opened.append(self))
    win = _window(tmp_path, order="natural")
    context = GLib.MainContext.default()
    deadline = time.monotonic() + 5
    while win.viewmodel.is_scanning and time.monotonic() < deadline:
        context.iteration(False)
    assert win.viewmodel.candidate_count == 7
    assert win.router.engine.dispatch(0x67, 0) is True  # G
    assert opened, "G did not open the Go to photo dialog"
    dialog = opened[0]
    dialog.entry.set_text("99")
    assert dialog.preview == NO_MATCH and dialog.has_error
    dialog.entry.set_text("dsc_0005")
    assert dialog.preview == "6: DSC_0005.JPG"
    dialog.entry.emit("activate")
    assert win.viewmodel.current_photo.filename == "DSC_0005.JPG"
    win.destroy()


@needs_gtk
def test_f_toggles_full_screen_and_escape_leaves_zoom_before_full_screen(tmp_path: Path):
    win = _window(tmp_path)
    win.present()
    calls = []
    win.is_fullscreen = lambda: bool(calls) and calls[-1] == "fullscreen"
    win.fullscreen = lambda: calls.append("fullscreen")
    win.unfullscreen = lambda: calls.append("unfullscreen")
    engine = win.router.engine
    assert engine.dispatch(0x66, 0) is True  # F
    assert calls == ["fullscreen"]
    win.canvas_view.is_1to1_zoom = True
    engine.dispatch(0xFF1B, 0)  # Escape: zoom first
    assert not win.canvas_view.is_1to1_zoom and calls == ["fullscreen"]
    engine.dispatch(0xFF1B, 0)  # Escape again: full screen
    assert calls == ["fullscreen", "unfullscreen"]
    engine.dispatch(0xFFC8, 0)  # F11 enters again
    assert calls[-1] == "fullscreen"
    win.destroy()


@needs_gtk
def test_s_opens_the_order_menu(tmp_path: Path):
    win = _window(tmp_path)
    opened = []
    win.btn_order.popup = lambda: opened.append(True)
    assert win.router.engine.dispatch(0x73, 0) is True
    assert opened == [True]
    win.destroy()


def test_equal_modification_times_fall_back_to_the_file_name(tmp_path: Path):
    folder = tmp_path / "same"
    folder.mkdir()
    for name in ("DSC_0010.JPG", "DSC_0002.JPG", "DSC_0001.JPG"):
        path = folder / name
        path.write_bytes(b"x")
        os.utime(path, (1_700_000_000, 1_700_000_000))
    for order in ("newest", "oldest"):
        vm = _vm(tmp_path, order=order)
        vm.load_directory(folder)
        assert _names(vm) == ["DSC_0001.JPG", "DSC_0002.JPG", "DSC_0010.JPG"], order
