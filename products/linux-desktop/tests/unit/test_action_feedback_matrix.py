"""Every action gives visible feedback, through every way in (class check, real GTK4 under Xvfb).

One table: action x entry path (button, hotkey). Each row runs the action against real files in
a scratch folder through the real window, its real worker thread and the real main loop, and then
reads what the user can see: the status text in the action bar, mapped, not ellipsized, naming the
file and where it went. An error is said once, in the bar, and raises no toast over the photo.

Blitzlens has no star rating and no separate compare/keep step: "Keep" is the Move button
(M); compare is the 1-Up/2-Up/3-Up switcher, whose feedback is the pressed state (last table).
The window menu holds no culling action, so "menu" is no entry path here.

Adheres to:
- R-LINUX-UI-10: Tactile bottom action bar (the outcome of the last action is shown in it)
- R-LINUX-CULL-05: Trash; R-LINUX-CULL-06/07: transactional Undo
- R-LINUX-KEY-03: Culling hotkeys (M, C, Delete, Backspace); R-LINUX-KEY-06: Ctrl+Z
"""

from unittest.mock import MagicMock

import pytest

from photo_selector_linux.ui import window as window_module

STUBBED = isinstance(window_module.Gtk, MagicMock) or not window_module.HAS_GI
pytestmark = pytest.mark.skipif(STUBBED, reason="needs real Gtk/Adw under a display server")

if not STUBBED:
    import gtk_layout_probe as probe
    from gi.repository import Adw, Gdk, Gtk
    from photo_selector_linux.core.file_ops import FileCullingManager
    from photo_selector_linux.core.settings import SettingsStore
    from photo_selector_linux.ui import styling
    from photo_selector_linux.ui.window import MainWindow

KEY_M, KEY_C, KEY_Z = 0x6D, 0x63, 0x7A
KEY_DELETE, KEY_BACKSPACE = 0xFFFF, 0xFF08
CTRL = 1 << 2

#: name -> (how to trigger it). "button": attribute of the action bar; "key": (keyval, state).
ENTRY_PATHS = {
    "move-button": ("button", "btn_move"),
    "move-key-m": ("key", (KEY_M, 0)),
    "copy-button": ("button", "btn_copy"),
    "copy-key-c": ("key", (KEY_C, 0)),
    "trash-button": ("button", "btn_trash"),
    "trash-key-delete": ("key", (KEY_DELETE, 0)),
    "trash-key-backspace": ("key", (KEY_BACKSPACE, 0)),
}
#: action -> (text that must be in the outcome, ...): the file (with its companion) and the place.
EXPECTED = {
    # The first Move or Copy of an opened folder says where, in full (plan 1.6): no file name then.
    # Decision #33 (1): "+N" names the companions, " · " the destination.
    "move": ("Moved +1 · ", "Selection/"),
    "copy": ("Copied +1 · ", "Selection/"),
    "trash": ("Moved DSC_0001 +1 · Trash", "Trash"),
}


@pytest.fixture(scope="module", autouse=True)
def studio_css():
    Adw.init()
    provider = Gtk.CssProvider()
    provider.load_from_data(styling.STUDIO_DARK_CSS.encode("utf-8"))
    display = Gdk.Display.get_default()
    Gtk.StyleContext.add_provider_for_display(display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
    yield
    Gtk.StyleContext.remove_provider_for_display(display, provider)


@pytest.fixture
def ui(tmp_path):
    """A shown window on a folder of three photos; the first has a sidecar (so the text says +1)."""
    from PIL import Image

    root = tmp_path / "shoot"
    root.mkdir()
    for number in (1, 2, 3):
        Image.new("RGB", (240, 160), "red").save(root / f"DSC_000{number}.JPG")
    (root / "DSC_0001.xmp").write_text("<x/>")
    settings = SettingsStore(path=tmp_path / "settings.json", auto_load=False)
    settings.view_order = "natural"  # the test names the first photo; newest first is the app default
    win = MainWindow(settings=settings)
    win.viewmodel.file_manager = FileCullingManager(trash_dir=tmp_path / "Trash")
    win.viewmodel.load_directory(root)
    assert probe.pump(until=lambda: len(win.viewmodel.candidates) == 3 and not win.viewmodel.is_scanning)
    probe.show_at(win, (1366, 768))
    yield win, root
    win.destroy()
    probe.pump(0.1)


def _trigger(win, how):
    kind, target = how
    if kind == "button":
        getattr(win.action_bar, target).emit("clicked")
    else:
        keyval, state = target
        assert win.router.engine.dispatch(keyval, state) is True, "the key is not handled"


def _visible_outcome(win, prefix, whole=True):
    """The status text once it starts with ``prefix``: it must also be on screen (whole, or
    ellipsized with the full text in the tooltip)."""
    bar = win.action_bar
    assert probe.pump(until=lambda: bar.status_text.startswith(prefix)), (
        f"no outcome starting {prefix!r}; the bar says {bar.status_text!r}"
    )
    probe.pump(0.1)
    assert bar.busy_box.get_mapped(), "the outcome is not on screen"
    assert bar.lbl_busy.get_mapped() and bar.lbl_busy.get_text() == bar.status_text
    if whole:
        assert not bar.lbl_busy.get_layout().is_ellipsized(), f"the outcome is cut off: {bar.status_text!r}"
    else:
        assert bar.lbl_busy.get_tooltip_text() == bar.status_text
    return bar.status_text


@pytest.mark.parametrize("path", sorted(ENTRY_PATHS))
def test_move_copy_trash_show_the_file_and_the_place(path, ui):
    win, root = ui
    action = path.split("-")[0]
    _trigger(win, ENTRY_PATHS[path])
    text = _visible_outcome(win, EXPECTED[action][0])
    for part in EXPECTED[action]:
        assert part in text, f"{part!r} missing from {text!r}"
    # The action really happened: the photo left the queue the user works through.
    assert win.viewmodel.candidate_count == (3 if action == "copy" else 2)


@pytest.mark.parametrize("undo_path", ["undo-button", "undo-key-ctrl-z"])
@pytest.mark.parametrize("first", ["move-button", "trash-button", "copy-button"])
def test_undo_shows_what_was_undone(first, undo_path, ui):
    win, root = ui
    _trigger(win, ENTRY_PATHS[first])
    _visible_outcome(win, EXPECTED[first.split("-")[0]][0])
    assert probe.pump(until=lambda: win.action_bar.btn_undo.get_sensitive() and not win.viewmodel.is_busy)
    win.viewmodel.nav_previous() if win.viewmodel.current_index else None
    _trigger(win, ("button", "btn_undo") if undo_path == "undo-button" else ("key", (KEY_Z, CTRL)))
    text = _visible_outcome(win, "Undid ")
    assert f"Undid {first.split('-')[0]} for DSC_0001.JPG" == text


@pytest.mark.parametrize("path", ["move-button", "move-key-m", "copy-button", "copy-key-c"])
def test_a_refused_action_says_why_once_in_the_bar_and_raises_no_toast(path, ui, tmp_path):
    win, root = ui
    win.viewmodel.destination_mode = "custom"
    win.viewmodel.external_destination_root = tmp_path / "no-such-folder"
    _trigger(win, ENTRY_PATHS[path])
    verb = "move" if path.startswith("move") else "copy"
    text = _visible_outcome(win, f"Cannot {verb}", whole=False)
    assert win.viewmodel.candidate_count == 3, "a refused action must keep the photo"

    def toast_texts():
        toast = next((w for w in probe.walk(win) if type(w).__name__ == "AdwToastWidget" and w.get_mapped()), None)
        return [w.get_text() for w in probe.walk(toast) if isinstance(w, Gtk.Label)] if toast else []

    probe.pump(seconds=0.6)  # a toast, if one were raised, would be mapped by now
    assert not any(text in t for t in toast_texts()), "the failure must not be said a second time as a toast"


@pytest.mark.parametrize(
    "how,mode",
    [(("button", "btn_mode_2"), 2), (("button", "btn_mode_3"), 3), (("key", (0x32, 0)), 2), (("key", (0x33, 0)), 3)],
)
def test_switching_the_view_shows_the_pressed_mode(how, mode, ui):
    win, _root = ui
    if how[0] == "button":
        getattr(win, how[1]).set_active(True)
    else:
        _trigger(win, how)
    toggles = [win.btn_mode_1, win.btn_mode_2, win.btn_mode_3]
    assert win.viewmodel.comparison_mode == mode
    assert [t.get_active() for t in toggles] == [n == mode for n in (1, 2, 3)]
    assert [t.has_css_class("suggested-action") for t in toggles] == [n == mode for n in (1, 2, 3)]


def test_the_matrix_covers_every_action_button_of_the_bar(ui):
    """A new action button must get a row: every button of the bar is named in the table."""
    win, _root = ui
    in_table = {target for kind, target in list(ENTRY_PATHS.values()) if kind == "button"} | {"btn_undo"}
    in_table |= {"btn_selected"}  # its row: test_the_selected_pill_opens_the_selection_folder
    buttons = {
        w.get_name()
        for w in probe.walk(win.action_bar)
        if isinstance(w, Gtk.Button) and w.get_name().startswith("btn_")
    }
    assert buttons == in_table, f"action buttons without a feedback row: {sorted(buttons - in_table)}"


# ── Plan 0.5: a fact is shown in one place ───────────────────────────────────────────────────


def _label_texts(win):
    return [w.get_text() for w in probe.walk(win) if isinstance(w, Gtk.Label) and w.get_mapped()]


def _image_names(win):
    from gi.repository import Gtk as _Gtk

    return [w.get_tooltip_text() for w in probe.walk(win) if isinstance(w, _Gtk.Image) and w.get_mapped()]


def test_the_queue_count_lives_in_the_action_bar_not_in_the_header(ui):
    win, _root = ui
    assert not hasattr(win, "lbl_queue"), "the header has no second 'N queued' pill"
    assert hasattr(win.action_bar, "lbl_queue")


def test_one_up_has_no_active_pill_and_two_up_names_active_and_next(ui):
    win, _root = ui
    win.viewmodel.set_comparison_mode(1)
    probe.pump(seconds=0.3)
    assert "ACTIVE" not in _label_texts(win)
    win.viewmodel.set_comparison_mode(2)
    probe.pump(seconds=0.3)
    texts = _label_texts(win)
    assert "ACTIVE" in texts and "CHAMPION" not in texts
    assert "NEXT" not in texts and "PREVIOUS" not in texts  # arrows, decision #33 (3)
    assert "Next photo" in _image_names(win)


def test_the_caption_repeats_neither_name_nor_score_while_the_info_card_is_on(ui):
    win, _root = ui
    canvas = win.canvas_view
    probe.pump(seconds=0.3)
    assert canvas.hud_enabled and canvas._active_footer.get_opacity() == 0.0
    canvas.toggle_hud()  # I: card off, so the caption names the photo
    assert canvas._active_footer.get_opacity() == 1.0
    canvas.toggle_hud()
    assert canvas._active_footer.get_opacity() == 0.0


def test_the_selected_pill_opens_the_selection_folder(ui):
    """Plan 1.6: the pill is a button, named for what it opens; it is dead until something is selected."""
    win, _root = ui
    opened = []
    win.action_bar.on_open_selection = lambda: opened.append(1)
    button = win.action_bar.btn_selected
    assert button.get_tooltip_text() == "Photos moved or copied to Selection. Click to open it."
    assert not button.get_sensitive()
    _trigger(win, ENTRY_PATHS["copy-button"])
    assert probe.pump(until=button.get_sensitive)
    button.emit("clicked")
    assert opened == [1]


def test_previous_and_next_are_arrows_that_do_not_read_as_a_key(ui):
    """[USER 2026-11-12] decision #33 (3): no text on the PREVIOUS and NEXT tiles, a spoken name instead."""
    from gi.repository import Gtk

    win, _root = ui
    win.viewmodel.set_comparison_mode(3)
    win.viewmodel.current_index = 1
    win.viewmodel._notify_state_changed()
    probe.pump(seconds=0.3)
    pills = [w for w in probe.walk(win) if isinstance(w, Gtk.Image) and w.has_css_class("slot-role-pill")]
    assert sorted(p.get_icon_name() for p in pills) == ["go-next-symbolic", "go-previous-symbolic"]
    assert sorted(p.get_tooltip_text() for p in pills) == ["Next photo", "Previous photo"]
    assert not [t for t in _label_texts(win) if t in ("PREVIOUS", "NEXT", "K", "P")]
