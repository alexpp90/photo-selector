"""Main Libadwaita application controller.

Adheres to:
- R-LINUX-UI-01: AdwApplication Lifecycle and Global Action Registration
- R-LINUX-UI-02: Studio Dark Theme Installation
"""

from __future__ import annotations

import os
from pathlib import Path

try:
    import gi
    gi.require_version("Gtk", "4.0")
    gi.require_version("Adw", "1")
    from gi.repository import Gtk, Gdk, Adw, Gio, GLib
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = Gdk = Adw = Gio = GLib = None


class PhotoSelectorApplication(Adw.Application if HAS_GI else object):
    """Main Libadwaita Application controller managing window lifecycle and global actions."""

    def __init__(self, application_id: str = "org.photoselector.Linux"):
        if HAS_GI:
            super().__init__(
                application_id=application_id,
                flags=Gio.ApplicationFlags.HANDLES_OPEN,
            )
        self.app_id = application_id
        self.window = None
        self._lab_recorder = None

    def do_startup(self) -> None:
        if not HAS_GI:
            return
        Adw.Application.do_startup(self)
        self._setup_theme_and_styling()
        self._register_actions()

    def do_activate(self) -> None:
        if not HAS_GI:
            return
        if not self.window:
            from photo_selector_linux.ui.window import MainWindow
            self.window = MainWindow(application=self)
        self.window.present()

    def do_open(self, files, n_files, hint) -> None:
        self.do_activate()
        if n_files > 0 and files:
            target_path = Path(files[0].get_path())
            if target_path.exists() and self.window:
                self.window.open_directory(target_path)

    def _setup_theme_and_styling(self) -> None:
        from photo_selector_linux.ui.styling import APP_ICON_NAME, STUDIO_DARK_CSS

        if hasattr(Gtk.Window, "set_default_icon_name"):
            Gtk.Window.set_default_icon_name(APP_ICON_NAME)

        style_mgr = Adw.StyleManager.get_default()
        style_mgr.set_color_scheme(Adw.ColorScheme.FORCE_DARK)

        css_provider = Gtk.CssProvider()
        css_provider.load_from_data(STUDIO_DARK_CSS.encode("utf-8"))

        display = Gdk.Display.get_default()
        if display:
            Gtk.StyleContext.add_provider_for_display(
                display,
                css_provider,
                Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
            )

    def _register_actions(self) -> None:
        actions = [
            ("open_folder", self._on_action_open_folder, ["<Control>o"]),
            ("preferences", self._on_action_preferences, ["<Control>comma"]),
            ("shortcuts", self._on_action_shortcuts, ["<Control>question", "question"]),
            ("duplicate_finder", self._on_action_duplicate_finder, ["<Control>d"]),
            ("statistics", self._on_action_statistics, ["<Control>l"]),
            ("quit", lambda a, p: self.quit(), ["<Control>q"]),
        ]
        for name, callback, accels in actions:
            action = Gio.SimpleAction.new(name, None)
            action.connect("activate", callback)
            self.add_action(action)
            self.set_accels_for_action(f"app.{name}", accels)

        if os.environ.get("PST_LAB") == "1":
            from photo_selector_linux.ui.lab_hooks import LabCheckpointRecorder
            self._lab_recorder = LabCheckpointRecorder()
            lab_action = Gio.SimpleAction.new("lab-checkpoint", GLib.VariantType.new("s"))
            lab_action.connect("activate", self._on_action_lab_checkpoint)
            self.add_action(lab_action)

    def _on_action_lab_checkpoint(self, action, param) -> None:
        if self._lab_recorder is not None:
            name = param.get_string() if param is not None else "checkpoint"
            self._lab_recorder.record_checkpoint_after_frame(self.window, name)

    def _on_action_open_folder(self, action, param) -> None:
        if self.window:
            self.window.show_open_folder_dialog()

    def _on_action_preferences(self, action, param) -> None:
        if self.window:
            self.window.show_preferences()

    def _on_action_shortcuts(self, action, param) -> None:
        if self.window:
            self.window.show_shortcuts_guide()

    def _on_action_duplicate_finder(self, action, param) -> None:
        if self.window:
            self.window.show_duplicate_finder()

    def _on_action_statistics(self, action, param) -> None:
        if self.window:
            self.window.show_library_statistics()


# Alias for backward compatibility
PhotoSelectorApp = PhotoSelectorApplication
