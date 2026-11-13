"""The application's startup, activation and global actions drive the real GTK objects.

Real ``Adw.Application`` under ``xvfb-run`` (never on the desktop; see ``tests/_display_env.py``):
startup installs the dark theme and registers every ``app.*`` action with its accelerators,
activation creates one window, an action reaches the window method it names, and files handed
over by a file manager open their folder.
"""

import itertools
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from photo_selector_linux.ui import application as application_module

REAL = application_module.HAS_GI and not isinstance(application_module.Gtk, MagicMock)
pytestmark = pytest.mark.skipif(not REAL, reason="needs real Gtk/Adw under a display server")

_IDS = itertools.count()  # a bus owns one object per id: every test gets its own


def _app_id() -> str:
    """A fresh id per test, spelled with letters only.

    Harness trap (2026-11-12): on this stack (GTK 4.18, libadwaita 1.7) ``Adw.ApplicationWindow(
    application=app)`` segfaults in its constructor for ids ending in a single digit
    (``org.example.BlitzlensTest0``, ``...5``) when it is the first window of the process, and the
    same id with a letter, or ``...Test10``, does not. The crash looks like load flakiness because
    earlier tests in the full suite open a window first. Letters keep every test independent of its
    order; ``test_the_application_id_suffix_never_ends_in_one_digit`` pins the spelling.
    """
    number, letters = next(_IDS), ""
    while True:
        number, remainder = divmod(number, 26)
        letters = chr(ord("A") + remainder) + letters
        if number == 0:
            return f"org.example.BlitzlensTest{letters}"
        number -= 1


ACTIONS = {
    "open_folder": ("show_open_folder_dialog", ["<Control>o"]),
    "preferences": ("show_preferences", ["<Control>comma"]),
    "about": ("show_about", []),
    "shortcuts": ("show_shortcuts_guide", ["<Control>question", "question"]),
    "statistics": ("show_library_statistics", ["<Control>l"]),
}


@pytest.fixture
def app(monkeypatch):
    from photo_selector_linux.ui import window as window_module

    calls = []
    for _name, (method, _accels) in ACTIONS.items():
        monkeypatch.setattr(window_module.MainWindow, method, lambda self, m=method: calls.append(m))
    application = application_module.PhotoSelectorApplication(_app_id())
    application.calls = calls
    application.register(None)  # runs do_startup: theme and actions
    yield application
    if application.window is not None:
        application.window.destroy()


def test_the_application_id_suffix_never_ends_in_one_digit():
    ids = [_app_id() for _ in range(60)]
    assert len(set(ids)) == 60
    assert not any(i[-1].isdigit() for i in ids)


def test_startup_registers_every_action_with_its_accelerators(app):
    for name, (_method, accels) in ACTIONS.items():
        assert app.lookup_action(name) is not None, name
        assert list(app.get_accels_for_action(f"app.{name}")) == accels, name
    assert app.lookup_action("quit") is not None


def test_activate_creates_one_window_and_reuses_it(app):
    app.do_activate()
    first = app.window
    assert first is not None
    app.do_activate()
    assert app.window is first


def test_each_action_reaches_the_window_method_it_names(app):
    app.do_activate()
    for name, (method, _accels) in ACTIONS.items():
        app.lookup_action(name).activate(None)
        assert app.calls[-1] == method, name


def test_an_action_without_a_window_is_a_no_op(app):
    assert app.window is None
    for name in ACTIONS:
        app.lookup_action(name).activate(None)  # must not raise
    assert app.calls == []


def test_a_file_from_the_file_manager_opens_its_folder(app, tmp_path: Path):
    from gi.repository import Gio

    folder = tmp_path / "shoot"
    folder.mkdir()
    photo = folder / "IMG_0001.JPG"
    photo.write_bytes(b"x")
    app.do_open([Gio.File.new_for_path(str(photo))], 1, "")
    assert app.window is not None
    assert Path(app.window.viewmodel.current_folder).name == "shoot"
    app.do_open([Gio.File.new_for_path(str(tmp_path / "missing.jpg"))], 1, "")  # a gone file is ignored


def test_the_lab_checkpoint_action_exists_only_in_the_lab(monkeypatch):
    monkeypatch.setenv("PST_LAB", "1")
    lab = application_module.PhotoSelectorApplication(_app_id())
    lab.register(None)
    assert lab.lookup_action("lab-checkpoint") is not None
    monkeypatch.delenv("PST_LAB")
    plain = application_module.PhotoSelectorApplication(_app_id())
    plain.register(None)
    assert plain.lookup_action("lab-checkpoint") is None
