"""Text scaling x window size over every Blitzlens window (class check, real GTK4 under Xvfb).

One check for the whole class "text that does not fit": every window and dialog is shown at the
GNOME text-scaling factors 1.0, 1.5 and 2.0 and at the smallest size GTK can lay it out at, at
1366x768 and at 1920x1080. At each combination no visible label may be clipped or broken in the
middle of a word, no two controls may overlap and nothing may stick out of the window.

Adheres to:
- R-LINUX-UI-01: Native GNOME Libadwaita window
- R-LINUX-UI-03: Maximized viewport canvas
- R-LINUX-UI-10: Semantic bottom action bar (the status text keeps its bounded slot)

The probe (``gtk_layout_probe.py``) reads allocated geometry, so the check is only as good as it
can go red: the ``test_probe_*`` tests plant one defect each and demand a finding.
"""

from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.ui import window as window_module

STUBBED = isinstance(window_module.Gtk, MagicMock) or not window_module.HAS_GI
pytestmark = pytest.mark.skipif(STUBBED, reason="needs real Gtk/Adw under a display server")

if not STUBBED:
    import gtk_layout_probe as probe
    from gi.repository import Adw, Gdk, Gtk
    from photo_selector_linux.core.settings import SettingsStore
    from photo_selector_linux.ui import styling, tools_dialogs
    from photo_selector_linux.ui.preferences import PreferencesDialog
    from photo_selector_linux.ui.shortcuts_dialog import ShortcutsDialog
    from photo_selector_linux.ui.window import MainWindow

    #: The smallest size GTK can lay the window out at, then the two common screens.
    SIZES = (None,) + probe.SIZES

#: GNOME text-scaling factors the class check runs at.
SCALES = (1.0, 1.5, 2.0)

#: The status text of the longest outcome the view model produces (a bundle with companions).
LONG_OUTCOME = "Moved IMG_…_064000 +12 · Selection"


@pytest.fixture(scope="module", autouse=True)
def studio_css():
    """The stylesheet the shipped app loads: padding and font sizes change every measurement."""
    Adw.init()
    provider = Gtk.CssProvider()
    provider.load_from_data(styling.STUDIO_DARK_CSS.encode("utf-8"))
    display = Gdk.Display.get_default()
    Gtk.StyleContext.add_provider_for_display(display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
    yield
    Gtk.StyleContext.remove_provider_for_display(display, provider)


@pytest.fixture(autouse=True)
def reset_scale():
    yield
    probe.set_text_scale(1.0)


@pytest.fixture
def folder(tmp_path) -> Path:
    from PIL import Image

    root = tmp_path / "shoot"
    root.mkdir()
    for number, colour in enumerate(("red", "green", "blue", "grey")):
        Image.new("RGB", (240, 160), colour).save(root / f"IMG_20260814_06400{number}.jpg")
    return root


def _settings(tmp_path: Path) -> SettingsStore:
    return SettingsStore(path=tmp_path / "settings.json", auto_load=False)


def _loaded_main(tmp_path, folder, mode):
    win = MainWindow(settings=_settings(tmp_path))
    win.viewmodel.load_directory(folder)
    assert probe.pump(until=lambda: len(win.viewmodel.candidates) == 4 and not win.viewmodel.is_scanning)
    win.viewmodel.set_comparison_mode(mode)
    return win


# ── the screens: name -> (window factory, optional per-window steps, root widget of the check) ──


def _main_empty(tmp_path, folder):
    return MainWindow(settings=_settings(tmp_path))


def _main_mode(mode):
    def factory(tmp_path, folder):
        return _loaded_main(tmp_path, folder, mode)

    return factory


def _main_with_outcome(tmp_path, folder):
    win = _loaded_main(tmp_path, folder, 3)
    win.action_bar.show_outcome(LONG_OUTCOME)
    return win


def _preferences(tmp_path, folder):
    return PreferencesDialog(settings=_settings(tmp_path))


def _statistics(tmp_path, folder):
    win = _loaded_main(tmp_path, folder, 1)
    return tools_dialogs.LibraryStatisticsDialog(viewmodel=win.viewmodel)


def _statistics_remote(tmp_path, folder):
    win = _loaded_main(tmp_path, folder, 1)
    win.viewmodel.is_remote_folder = True
    return tools_dialogs.LibraryStatisticsDialog(viewmodel=win.viewmodel)


def _shortcuts(tmp_path, folder):
    return ShortcutsDialog()


def _main_state(state):
    """The canvas cards (plan 1.1, 1.2): reading and done, at every scale and size."""

    def factory(tmp_path, folder):
        win = MainWindow(settings=_settings(tmp_path))
        vm = win.viewmodel
        vm.current_folder, vm.current_folder_name = folder, folder.name
        vm.is_scanning = state == "reading"
        if state == "done":
            vm.selected_count, vm.trashed_count = 10, 1
        vm._notify_state_changed()
        return win

    return factory


SCREENS = {
    "main-empty": _main_empty,
    "main-reading": _main_state("reading"),
    "main-done": _main_state("done"),
    "main-1up": _main_mode(1),
    "main-2up": _main_mode(2),
    "main-3up": _main_mode(3),
    "main-3up-outcome": _main_with_outcome,
    "preferences": _preferences,
    "statistics": _statistics,
    "statistics-remote": _statistics_remote,
    "shortcuts": _shortcuts,
}


def _pages(window):
    return [w for w in probe.walk(window) if isinstance(w, Adw.PreferencesPage)]


def _findings_of(window, name):
    """Findings of the window as shown; Preferences is checked once per page."""
    if not isinstance(window, Adw.PreferencesWindow):
        return probe.layout_findings(window)
    found = []
    for page in _pages(window):
        window.set_visible_page(page)
        probe.pump(0.3)
        found.extend(probe.layout_findings(window))
    return found


@pytest.mark.parametrize("scale", SCALES)
@pytest.mark.parametrize("screen", sorted(SCREENS))
def test_no_text_is_clipped_broken_overlapping_or_outside(screen, scale, tmp_path, folder):
    """Every window x text scale x size: nothing clipped, no mid-word break, no overlap, nothing outside."""
    probe.set_text_scale(scale)
    failures = []
    for size in SIZES:
        window = SCREENS[screen](tmp_path, folder)
        try:
            probe.show_at(window, size)
            for finding in _findings_of(window, screen):
                failures.append(f"{screen} x{scale} at {size or 'minimum'} ({window.get_width()}x"
                                f"{window.get_height()}): {finding}")
        finally:
            window.destroy()
            probe.pump(0.1)
    assert not failures, "\n".join(sorted(set(failures)))


def test_the_about_dialog_fits_at_every_scale(tmp_path):
    failures = []
    for scale in SCALES:
        probe.set_text_scale(scale)
        for size in SIZES:
            win = MainWindow(settings=_settings(tmp_path))
            try:
                probe.show_at(win, size)
                win.show_about()
                probe.pump(0.6)
                # The dialog is a modal layer over the window: what lies behind it (the start card's
                # button) is not on the same layer, so the check reads the dialog itself.
                dialog = win.get_visible_dialog()
                assert dialog is not None, "the About dialog must be showing"
                failures.extend(
                    f"about x{scale} at {size or 'minimum'}: {f}"
                    for f in probe.layout_findings(dialog, root=win)
                )
            finally:
                win.destroy()
                probe.pump(0.1)
    assert not failures, "\n".join(sorted(set(failures)))


def test_the_go_to_dialog_and_the_order_button_fit_at_every_scale(tmp_path):
    """BL test gap: the order button beside "Photo N of M" and the Go to photo dialog (added in #915)."""
    failures = []
    folder = tmp_path / "shoot"
    folder.mkdir()
    for number in range(4):
        (folder / f"IMG_000{number}.JPG").write_bytes(b"x")
    for scale in SCALES:
        probe.set_text_scale(scale)
        for size in SIZES:
            win = _loaded_main(tmp_path, folder, 1)
            try:
                probe.show_at(win, size)
                probe.pump(0.3)
                order = win.btn_order
                assert order.get_mapped() and win.lbl_order.get_label() in ("Newest", "Oldest", "Name", "Blurry")
                failures.extend(f"order x{scale} at {size or 'minimum'}: {f}" for f in probe.layout_findings(win))
                win.show_goto_dialog()
                probe.pump(0.6)
                dialog = win.get_visible_dialog()
                assert dialog is not None, "the Go to photo dialog must be showing"
                failures.extend(
                    f"goto x{scale} at {size or 'minimum'}: {f}"
                    for f in probe.layout_findings(dialog, root=win)
                )
            finally:
                win.destroy()
                probe.pump(0.1)
    assert not failures, "\n".join(sorted(set(failures)))


def test_no_stylesheet_rule_sets_a_font_size_in_pixels():
    """Text follows the GNOME text-scaling factor: sizes are relative (%), never a fixed px."""
    import re

    assert not re.findall(r"font-size\s*:\s*[0-9.]+px", styling.STUDIO_DARK_CSS)


def test_the_scale_really_reaches_the_widgets():
    """Precondition of the class check: a 2.0 factor makes the text twice as tall."""
    heights = []
    for scale in (1.0, 2.0):
        probe.set_text_scale(scale)
        label = Gtk.Label(label="Photo 0 of 0")
        win = Gtk.Window()
        win.set_child(label)
        probe.show_at(win, (300, 100))
        heights.append(label.get_layout().get_pixel_size()[1])
        win.destroy()
    assert heights[1] >= heights[0] * 1.8, heights


# ── the probe goes red on a planted defect ───────────────────────────────────


if not STUBBED:

    class _CappedNoEllipsis(Gtk.Label):
        """A label that reports a hard-coded 40 px width (the shape of a fixed cap) and cannot ellipsize."""

        def do_measure(self, orientation, for_size):
            minimum, natural, min_baseline, nat_baseline = Gtk.Label.do_measure(self, orientation, for_size)
            if orientation == Gtk.Orientation.HORIZONTAL:
                return 40, 40, -1, -1
            return minimum, natural, min_baseline, nat_baseline


def _planted(tmp_path, folder, plant):
    win = _loaded_main(tmp_path, folder, 3)
    plant(win)
    probe.show_at(win, None)
    try:
        return [str(f) for f in probe.layout_findings(win)]
    finally:
        win.destroy()
        probe.pump(0.1)


def test_probe_reports_a_planted_wide_label_that_cannot_ellipsize(tmp_path, folder):
    def plant(win):
        label = _CappedNoEllipsis(label="A hard-coded status text that is far too long for this bar")
        label.set_halign(Gtk.Align.START)
        win.action_bar.pack_start(label)

    findings = _planted(tmp_path, folder, plant)
    assert any(f.startswith("clipped") and "hard-coded" in f for f in findings), findings


def test_probe_reports_a_planted_word_broken_in_the_middle(tmp_path, folder):
    def plant(win):
        label = Gtk.Label(label="Unbelievablylongwordthatdoesnotfit")
        label.set_wrap(True)
        label.set_wrap_mode(2)  # Pango.WrapMode.WORD_CHAR: the layout may break inside a word
        label.set_max_width_chars(8)
        label.set_halign(Gtk.Align.START)
        win.action_bar.pack_start(label)

    findings = _planted(tmp_path, folder, plant)
    assert any(f.startswith("broken-word") for f in findings), findings


def test_probe_reports_planted_overlapping_controls(tmp_path, folder):
    def plant(win):
        overlay = Gtk.Overlay()
        first = Gtk.Button(label="First")
        second = Gtk.Button(label="Second")
        for button in (first, second):
            button.set_size_request(80, 30)
            button.set_halign(Gtk.Align.START)
        overlay.set_child(first)
        overlay.add_overlay(second)
        win.action_bar.pack_start(overlay)

    findings = _planted(tmp_path, folder, plant)
    assert any(f.startswith("overlap") for f in findings), findings


def test_probe_reports_a_planted_widget_outside_the_window(tmp_path, folder):
    def plant(win):
        # An overlay's children are not measured: the window cannot grow to make room for them.
        overlay = Gtk.Overlay()
        overlay.set_child(Gtk.Box())
        label = Gtk.Label(label="Pushed out past the right edge")
        label.set_halign(Gtk.Align.START)
        label.set_margin_start(2000)
        overlay.add_overlay(label)
        win.action_bar.pack_start(overlay)

    findings = _planted(tmp_path, folder, plant)
    assert any(f.startswith("outside-window") for f in findings), findings
