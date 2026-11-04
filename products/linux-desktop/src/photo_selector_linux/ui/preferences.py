"""Modal preferences window isolating settings from main culling workspace.

Adheres to:
- R-LINUX-PREF-01: Decoupled AdwPreferencesWindow
- R-LINUX-PREF-02: Configurable Application Preferences backed by SettingsStore
- R-LINUX-PREF-03: Where photos go (working folder or custom folder, resolved path shown live)
"""

from __future__ import annotations

from pathlib import Path
from typing import Callable, Optional

try:
    import gi
    gi.require_version("Gtk", "4.0")
    gi.require_version("Adw", "1")
    from gi.repository import Gtk, Adw, Gio
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = Adw = Gio = None

from photo_selector_linux.core.destination import (
    MODE_CUSTOM,
    MODE_WORKING,
    DestinationResolution,
    describe_custom_folder,
    describe_destination,
    resolve_destination,
)
from photo_selector_linux.core.settings import SettingsStore

#: Receives the chosen folder, or ``None`` when the picker was cancelled.
FolderChosen = Callable[[Optional[Path]], None]
#: Opens a folder picker and reports back through the callback.
FolderChooser = Callable[[FolderChosen], None]


class PreferencesDialog(Adw.PreferencesWindow if HAS_GI else object):
    """Modal preferences window adhering to GNOME HIG standards and backed by SettingsStore."""

    def __init__(
        self,
        settings: Optional[SettingsStore] = None,
        viewmodel=None,
        folder_chooser: Optional[FolderChooser] = None,
        **kwargs,
    ):
        self.viewmodel = viewmodel
        #: Opens the folder picker for the custom destination; tests inject one that never
        #: opens a window.
        self._folder_chooser: FolderChooser = folder_chooser or self._open_folder_dialog
        self._closed = False
        self._destination_view: Optional[tuple] = None
        # True while a setter writes the (sanitised) value back into its widget: the widget's
        # change signal must not call the setter again (a feedback loop that hung GTK).
        self._syncing = False
        if settings is not None:
            self.settings = settings
        elif viewmodel is not None and getattr(viewmodel, "settings", None) is not None:
            self.settings = viewmodel.settings
        else:
            self.settings = SettingsStore()
            if viewmodel is not None:
                viewmodel.settings = self.settings
                self.settings.connect(viewmodel._on_settings_changed)

        self.row_folder_name = None
        self.row_sel_folder = None
        self.row_working = None
        self.row_custom = None
        self.row_destination = None
        self.check_working = None
        self.check_custom = None
        self.btn_custom_folder = None
        self.icon_destination_warning = None
        self.row_auto_advance = None
        self.row_blurry_cutoff = None
        self.row_sharp_cutoff = None
        self.row_cache_limit = None
        self.scale_blurry = None
        self.scale_sharp = None
        self.scale_cache = None

        if HAS_GI:
            super().__init__(**kwargs)
            self.set_title("Preferences")
            self.set_default_size(560, 480)
            self._build_pages()
            self.connect("close-request", self._on_close_request)
        else:
            self._build_headless_controls()
        self.refresh_destination()
        if viewmodel is not None:
            # The open folder decides the working-folder destination: follow it live.
            viewmodel.connect("state-changed", self._on_viewmodel_state_changed)

    def _build_headless_controls(self) -> None:
        class _EntryStub:
            def __init__(self, text: str):
                self._text = text

            def get_text(self) -> str:
                return self._text

            def set_text(self, text: str) -> None:
                self._text = text

        class _SwitchStub:
            def __init__(self, active: bool):
                self._active = active

            def get_active(self) -> bool:
                return self._active

            def set_active(self, active: bool) -> None:
                self._active = bool(active)

        class _ScaleStub:
            def __init__(self, value: float):
                self._value = float(value)

            def get_value(self) -> float:
                return self._value

            def set_value(self, value: float) -> None:
                self._value = float(value)

        class _RowStub:
            def __init__(self):
                self._subtitle = ""
                self._visible = True
                self._active = False

            def set_subtitle(self, text: str) -> None:
                self._subtitle = text

            def get_subtitle(self) -> str:
                return self._subtitle

            def set_active(self, active: bool) -> None:
                self._active = bool(active)

            def get_active(self) -> bool:
                return self._active

            def set_visible(self, visible: bool) -> None:
                self._visible = bool(visible)

            def get_visible(self) -> bool:
                return self._visible

            def add_css_class(self, *_args) -> None:
                pass

            def remove_css_class(self, *_args) -> None:
                pass

        self.row_working = _RowStub()
        self.row_custom = _RowStub()
        self.row_destination = _RowStub()
        self.check_working = _RowStub()
        self.check_custom = _RowStub()
        self.icon_destination_warning = _RowStub()
        self.row_folder_name = _EntryStub(self.settings.selection_folder_name)
        self.row_sel_folder = self.row_folder_name
        self.row_auto_advance = _SwitchStub(self.settings.auto_advance)
        self.scale_blurry = _ScaleStub(self.settings.blurry_threshold)
        self.scale_sharp = _ScaleStub(self.settings.sharp_threshold)
        self.scale_cache = _ScaleStub(self.settings.cache_limit_mb)

    def _build_pages(self) -> None:
        if not HAS_GI:
            return

        # Page 1: General Preferences
        page_gen = Adw.PreferencesPage()
        page_gen.set_title("General")
        page_gen.set_icon_name("preferences-other-symbolic")

        group_dest = Adw.PreferencesGroup()
        group_dest.set_title("Culling Destination")

        # Two-way exclusive choice: where Move and Copy put photos (R-LINUX-PREF-03).
        self.check_working = Gtk.CheckButton()
        self.check_working.set_valign(Gtk.Align.CENTER)
        self.check_custom = Gtk.CheckButton()
        self.check_custom.set_valign(Gtk.Align.CENTER)
        self.check_custom.set_group(self.check_working)

        self.row_working = Adw.ActionRow()
        self.row_working.set_title("Working folder")
        self.row_working.add_prefix(self.check_working)
        self.row_working.set_activatable_widget(self.check_working)
        group_dest.add(self.row_working)

        self.row_custom = Adw.ActionRow()
        self.row_custom.set_title("Custom folder")
        self.row_custom.add_prefix(self.check_custom)
        self.row_custom.set_activatable_widget(self.check_custom)
        self.row_custom.set_subtitle_selectable(True)
        self.btn_custom_folder = Gtk.Button.new_from_icon_name("folder-open-symbolic")
        self.btn_custom_folder.set_valign(Gtk.Align.CENTER)
        self.btn_custom_folder.set_tooltip_text("Choose folder\u2026")
        self.btn_custom_folder.update_property(
            [Gtk.AccessibleProperty.LABEL], ["Choose custom folder"]
        )
        self.btn_custom_folder.connect("clicked", lambda _btn: self.choose_custom_folder())
        self.row_custom.add_suffix(self.btn_custom_folder)
        group_dest.add(self.row_custom)

        self.row_folder_name = Adw.EntryRow()
        self.row_sel_folder = self.row_folder_name
        self.row_folder_name.set_title("Selection Subfolder Name")
        self.row_folder_name.set_text(self.settings.selection_folder_name)
        self.row_folder_name.connect(
            "notify::text",
            lambda row, _pspec=None: self._on_selection_folder_changed(row),
        )
        group_dest.add(self.row_folder_name)

        # The resolved destination, from the same resolution Move and Copy use.
        self.row_destination = Adw.ActionRow()
        self.row_destination.set_title("Photos go to")
        self.row_destination.set_subtitle_selectable(True)
        self.icon_destination_warning = Gtk.Image.new_from_icon_name("dialog-warning-symbolic")
        self.icon_destination_warning.set_tooltip_text("Warning")
        self.row_destination.add_prefix(self.icon_destination_warning)
        group_dest.add(self.row_destination)

        self.check_working.connect("toggled", self._on_destination_toggled, MODE_WORKING)
        self.check_custom.connect("toggled", self._on_destination_toggled, MODE_CUSTOM)

        self.row_auto_advance = Adw.SwitchRow()
        self.row_auto_advance.set_title("Auto-Advance on Culling")
        self.row_auto_advance.set_subtitle("Move to next candidate after Move, Copy, or Trash")
        self.row_auto_advance.set_active(self.settings.auto_advance)
        self.row_auto_advance.connect(
            "notify::active",
            lambda row, _pspec=None: self._on_auto_advance_changed(row),
        )
        group_dest.add(self.row_auto_advance)

        page_gen.add(group_dest)
        self.add(page_gen)

        # Page 2: Quality & Scoring
        page_score = Adw.PreferencesPage()
        page_score.set_title("Scoring")
        page_score.set_icon_name("image-filter-symbolic")

        group_thresh = Adw.PreferencesGroup()
        group_thresh.set_title("Focus Sharpness Thresholds")

        self.row_blurry_cutoff = Adw.ActionRow()
        self.row_blurry_cutoff.set_title(f"Blurry Threshold (< {self.settings.blurry_threshold:.1f})")
        self.scale_blurry = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 10.0, 50.0, 1.0)
        self.scale_blurry.set_value(self.settings.blurry_threshold)
        self.scale_blurry.set_hexpand(True)
        self.scale_blurry.connect("value-changed", self._on_blurry_changed)
        self.row_blurry_cutoff.add_suffix(self.scale_blurry)
        group_thresh.add(self.row_blurry_cutoff)

        self.row_sharp_cutoff = Adw.ActionRow()
        self.row_sharp_cutoff.set_title(f"Sharp Threshold (≥ {self.settings.sharp_threshold:.1f})")
        self.scale_sharp = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 50.0, 90.0, 1.0)
        self.scale_sharp.set_value(self.settings.sharp_threshold)
        self.scale_sharp.set_hexpand(True)
        self.scale_sharp.connect("value-changed", self._on_sharp_changed)
        self.row_sharp_cutoff.add_suffix(self.scale_sharp)
        group_thresh.add(self.row_sharp_cutoff)

        page_score.add(group_thresh)
        self.add(page_score)

        # Page 3: Performance & Cache
        page_cache = Adw.PreferencesPage()
        page_cache.set_title("Performance")
        page_cache.set_icon_name("system-run-symbolic")

        group_cache = Adw.PreferencesGroup()
        group_cache.set_title("Memory & Prefetch")

        self.row_cache_limit = Adw.ActionRow()
        self.row_cache_limit.set_title("In-Memory LRU Cache Ceiling (MB)")
        self.scale_cache = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 128.0, 2048.0, 64.0)
        self.scale_cache.set_value(self.settings.cache_limit_mb)
        self.scale_cache.set_hexpand(True)
        self.scale_cache.connect("value-changed", self._on_cache_changed)
        self.row_cache_limit.add_suffix(self.scale_cache)
        group_cache.add(self.row_cache_limit)

        page_cache.add(group_cache)
        self.add(page_cache)

    def _on_selection_folder_changed(self, row, *_args) -> None:
        if self._syncing:
            return
        self.set_selection_folder_name(row.get_text())

    def _on_destination_toggled(self, button, mode: str) -> None:
        if self._syncing or not button.get_active():
            return
        self.select_destination_mode(mode)

    def _on_close_request(self, *_args) -> bool:
        self._closed = True
        if self.viewmodel is not None:
            self.viewmodel.disconnect("state-changed", self._on_viewmodel_state_changed)
        return False

    def _on_viewmodel_state_changed(self, *_args) -> None:
        if not self._closed:
            self.refresh_destination()

    # -- Culling destination (R-LINUX-PREF-03) ------------------------------------------------

    def destination_resolution(self) -> DestinationResolution:
        """The resolution Move/Copy use right now (the workspace's when there is one)."""
        if self.viewmodel is not None and hasattr(self.viewmodel, "resolve_destination"):
            return self.viewmodel.resolve_destination()
        custom = self.settings.external_destination_folder
        return resolve_destination(
            self.settings.destination_mode,
            self.settings.selection_folder_name,
            None,
            Path(custom) if custom else None,
        )

    def destination_text(self) -> str:
        """The "Photos go to" line."""
        return describe_destination(self.destination_resolution())

    def custom_folder_text(self) -> str:
        """The Custom folder row's subtitle: the chosen folder, or why it cannot be used."""
        return describe_custom_folder(self.destination_resolution())

    def refresh_destination(self, force: bool = False) -> None:
        """Show the current choice and resolved destination; idempotent and signal-safe.

        ``force`` rewrites the widgets even when the model did not change: a click on a radio
        button moves it before the model decides, so the widgets must be put back.
        """
        resolution = self.destination_resolution()
        view = (
            resolution,
            describe_destination(resolution),
            describe_custom_folder(resolution),
        )
        if view == self._destination_view and not force:
            return
        self._destination_view = view
        is_custom = resolution.mode == MODE_CUSTOM
        warning = resolution.problem is not None

        def write() -> None:
            # Radio group: activating one deactivates the other (never deactivate directly).
            (self.check_custom if is_custom else self.check_working).set_active(True)
            self.row_custom.set_subtitle(view[2])
            self.row_destination.set_subtitle(view[1])
            self.icon_destination_warning.set_visible(warning)

        self._write_back(write)
        if HAS_GI:
            if warning:
                self.row_destination.add_css_class("error")
            else:
                self.row_destination.remove_css_class("error")

    def select_destination_mode(self, mode: str) -> None:
        """Switch between the working folder and the custom folder.

        Choosing Custom with no folder stored opens the picker instead; cancelling leaves the
        working folder selected, so there is never a Custom choice without a folder. Switching
        back to the working folder keeps the custom folder stored for later.
        """
        if mode == MODE_CUSTOM:
            if not self.settings.external_destination_folder:
                self.refresh_destination(force=True)  # stay on Working until a folder is chosen
                self.choose_custom_folder()
                return
            self.settings.update(destination_mode=MODE_CUSTOM)
        else:
            self.settings.update(destination_mode=MODE_WORKING)
        self.refresh_destination(force=True)

    def choose_custom_folder(self) -> None:
        """Open the folder picker; a chosen folder becomes the destination at once."""
        try:
            self._folder_chooser(self.set_custom_destination_folder)
        except Exception:
            self.refresh_destination(force=True)

    def set_custom_destination_folder(self, folder: Optional[Path]) -> None:
        """Use ``folder`` as the custom destination (``None``: the picker was cancelled)."""
        if folder is not None:
            self.settings.update(
                external_destination_folder=str(folder),
                destination_mode=MODE_CUSTOM,
            )
        self.refresh_destination(force=True)

    def _open_folder_dialog(self, on_chosen: FolderChosen) -> None:
        """Real picker (``Gtk.FileDialog``); every failure or dismissal reports ``None``."""
        if not HAS_GI:
            on_chosen(None)
            return
        dialog = Gtk.FileDialog()
        dialog.set_title("Custom Destination Folder")
        stored = self.settings.external_destination_folder
        if stored and Path(stored).is_dir():
            dialog.set_initial_folder(Gio.File.new_for_path(stored))

        def finished(dlg, result) -> None:
            try:
                gfile = dlg.select_folder_finish(result)
                path = gfile.get_path() if gfile is not None else None
            except Exception:
                path = None
            on_chosen(Path(path) if path else None)

        dialog.select_folder(self, None, finished)

    def _on_auto_advance_changed(self, row, *_args) -> None:
        if self._syncing:
            return
        self.set_auto_advance(bool(row.get_active()))

    def _on_blurry_changed(self, scale, *_args) -> None:
        if self._syncing:
            return
        self.set_blurry_threshold(float(scale.get_value()))

    def _on_sharp_changed(self, scale, *_args) -> None:
        if self._syncing:
            return
        self.set_sharp_threshold(float(scale.get_value()))

    def _on_cache_changed(self, scale, *_args) -> None:
        if self._syncing:
            return
        self.set_cache_limit_mb(float(scale.get_value()))

    def _write_back(self, write) -> None:
        """Run ``write`` (a widget setter) with this dialog's change handlers muted."""
        self._syncing = True
        try:
            write()
        finally:
            self._syncing = False

    def set_selection_folder_name(self, name: str) -> None:
        self.settings.update(selection_folder_name=name)
        self.refresh_destination()
        if HAS_GI and self.row_folder_name is not None:
            if self.row_folder_name.get_text() != self.settings.selection_folder_name:
                self._write_back(lambda: self.row_folder_name.set_text(self.settings.selection_folder_name))

    def set_auto_advance(self, active: bool) -> None:
        self.settings.update(auto_advance=bool(active))
        if HAS_GI and self.row_auto_advance is not None:
            if self.row_auto_advance.get_active() != self.settings.auto_advance:
                self._write_back(lambda: self.row_auto_advance.set_active(self.settings.auto_advance))

    def set_blurry_threshold(self, value: float) -> None:
        self.settings.update(blurry_threshold=float(value))
        if HAS_GI and self.row_blurry_cutoff is not None:
            self.row_blurry_cutoff.set_title(
                f"Blurry Threshold (< {self.settings.blurry_threshold:.1f})"
            )

    def set_sharp_threshold(self, value: float) -> None:
        self.settings.update(sharp_threshold=float(value))
        if HAS_GI and self.row_sharp_cutoff is not None:
            self.row_sharp_cutoff.set_title(
                f"Sharp Threshold (≥ {self.settings.sharp_threshold:.1f})"
            )

    def set_cache_limit_mb(self, value: float) -> None:
        self.settings.update(cache_limit_mb=float(value))
        if HAS_GI and self.scale_cache is not None:
            if self.scale_cache.get_value() != self.settings.cache_limit_mb:
                self._write_back(lambda: self.scale_cache.set_value(self.settings.cache_limit_mb))
