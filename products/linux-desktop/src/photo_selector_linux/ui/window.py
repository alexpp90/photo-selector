"""Main Libadwaita application window.

Adheres to:
- R-LINUX-UI-01: Pure GNOME HIG / Libadwaita Window Architecture
- R-LINUX-UI-03: Viewport Canvas Area Allocation (>85% of total window area)
"""

from __future__ import annotations

import logging
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

from photo_selector_linux.core.settings import SettingsStore
from photo_selector_linux.ui.action_bar import ActionBarView
from photo_selector_linux.ui.canvas import CanvasView
from photo_selector_linux.ui.canvas_states import announcement
from photo_selector_linux.ui.file_worker import FileOperationWorker, run_on_thread, schedule_on_main_loop
from photo_selector_linux.ui.keyboard_router import KeyboardRouter
from photo_selector_linux.ui.styling import APP_BRAND_NAME, APP_ICON_NAME
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

logger = logging.getLogger(__name__)

# Menu label -> view model sort order (R-LINUX-CULL-02).
VIEW_ORDER_LABELS = {
    "Newest first": "newest",
    "Oldest first": "oldest",
    "File name": "natural",
    "Lowest sharpness first": "lowest_sharpness_first",
}
#: The order a new install opens in; the order indicator is shown for any other.
DEFAULT_VIEW_ORDER = "newest"
VIEW_ORDER_TITLES = {order: label for label, order in VIEW_ORDER_LABELS.items()}
#: The always-visible word beside "Photo N of M" (plan 1.7).
VIEW_ORDER_WORDS = {"newest": "Newest", "oldest": "Oldest", "natural": "Name", "lowest_sharpness_first": "Blurry"}


class MainWindow(Adw.ApplicationWindow if HAS_GI else object):
    """Main window housing the HeaderBar, Multi-View Canvas, and Bottom ActionBar."""

    def __init__(self, application=None, settings: SettingsStore | None = None, **kwargs):
        self.window_title: str = APP_BRAND_NAME
        self.icon_name: str = APP_ICON_NAME
        self.settings: SettingsStore = settings or SettingsStore()
        self._close_when_idle = False
        self._last_canvas_state = "start"
        self._close_forced = False
        self._focused_folder = None
        if HAS_GI:
            super().__init__(application=application, **kwargs)
            self.set_default_size(1440, 900)
            self.set_title(APP_BRAND_NAME)
            self.set_icon_name(APP_ICON_NAME)
            self.add_css_class("studio-dark-surface")

        # File operations run on a worker so a cloud upload does not freeze the window.
        self.file_worker = FileOperationWorker() if HAS_GI else None
        self.viewmodel = CullingWorkspaceViewModel(
            dispatcher=self.file_worker,
            settings=self.settings,
            # The folder scan runs on its own thread; its batches reach the main loop above the
            # frame clock's redraw priority (R-LINUX-CULL-01, LIN-4).
            scan_idle_add=schedule_on_main_loop if HAS_GI else None,
        )
        if HAS_GI:
            self._build_ui()
            self._attach_keyboard_router()
            self._bind_viewmodel()
            self.connect("close-request", self._on_close_request)
        else:
            self.canvas_view = CanvasView(viewmodel=self.viewmodel)
            self.action_bar = ActionBarView(viewmodel=self.viewmodel)

    def _on_close_request(self, *_args) -> bool:
        """Hold the close until every queued file operation ran (R-LINUX-UI-10).

        The view model hands the worker one job at a time, so shutting the worker down with
        culls still queued lost them (or raised in the next dispatch). A second close request
        while waiting quits anyway, for a mount that hangs.
        """
        pending = self.viewmodel.queued_count
        if pending > 0 and not self._close_forced:
            self._close_forced = True
            self._close_when_idle = True
            self.show_toast(f"Finishing {pending} file operation{'s' if pending != 1 else ''}…")
            return True
        if self.file_worker is not None:
            self.file_worker.shutdown()
        if getattr(self.canvas_view, "loader", None) is not None:
            self.canvas_view.loader.shutdown()
        return False

    def _build_ui(self) -> None:
        self.toolbar_view = Adw.ToolbarView()
        self.set_content(self.toolbar_view)
        self._toast = None

        # HeaderBar
        self.header_bar = Adw.HeaderBar()
        self.header_bar.add_css_class("studio-headerbar")
        self._build_header_bar()
        self.toolbar_view.add_top_bar(self.header_bar)

        # Main Canvas View
        self.canvas_view = CanvasView(viewmodel=self.viewmodel)
        self.canvas_view.set_state_card_actions(
            self.show_open_folder_dialog, self.show_selection_in_files, self.open_directory
        )
        self._attach_folder_drop_target()
        loader = getattr(self.canvas_view, "loader", None)
        if loader is not None:
            self.viewmodel.score_runner = loader.run_scoring_pass
        # The toast overlay wraps only the canvas so a toast can never cover the action bar (LIN-7).
        self.toast_overlay = Adw.ToastOverlay()
        self.toast_overlay.set_child(self.canvas_view)
        self.toolbar_view.set_content(self.toast_overlay)

        # Bottom Action Bar
        self.action_bar = ActionBarView(viewmodel=self.viewmodel)
        self.action_bar.on_open_selection = self.show_selection_in_files
        self.toolbar_view.add_bottom_bar(self.action_bar)

    def _build_header_bar(self) -> None:
        # Open Folder button
        self.btn_open = Gtk.Button.new_from_icon_name("folder-open-symbolic")
        self.btn_open.set_name("btn_open")
        self.btn_open.set_tooltip_text("Open Folder (Ctrl+O)")
        if hasattr(Gtk, "AccessibleProperty") and hasattr(self.btn_open, "update_property"):
            self.btn_open.update_property([Gtk.AccessibleProperty.LABEL], ["Open folder"])
        self.btn_open.set_action_name("app.open_folder")
        self.header_bar.pack_start(self.btn_open)

        # Path label
        self.lbl_path = Gtk.Label(label="No folder loaded")
        self.lbl_path.set_ellipsize(3)  # PANGO_ELLIPSIZE_MIDDLE
        self.lbl_path.add_css_class("dim-label")
        self.header_bar.pack_start(self.lbl_path)

        # Remote / Cloud filesystem badge (U-31)
        self.lbl_remote_badge = Gtk.Label(label="")
        self.lbl_remote_badge.add_css_class("status-counter-badge")
        self.lbl_remote_badge.set_visible(False)
        self.header_bar.pack_start(self.lbl_remote_badge)

        # Title widget: Mode Switcher & Counter Box
        title_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)

        # Segmented view mode buttons
        mode_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        mode_box.add_css_class("linked")
        # A toggle group, so assistive technology reads which view is pressed; keys in tooltips.
        self._syncing_modes = False
        self.btn_mode_1 = Gtk.ToggleButton(label="1-Up")
        self.btn_mode_2 = Gtk.ToggleButton(label="2-Up")
        self.btn_mode_3 = Gtk.ToggleButton(label="3-Up")
        for mode, button, tip in (
            (1, self.btn_mode_1, "Single photo (1)"),
            (2, self.btn_mode_2, "Two photos side by side (2)"),
            (3, self.btn_mode_3, "Three photos: one over two (3)"),
        ):
            button.set_name(f"btn_mode_{mode}")
            button.set_tooltip_text(tip)
            button.set_focus_on_click(False)
            if button is not self.btn_mode_1:
                button.set_group(self.btn_mode_1)
            button.connect("toggled", self._on_mode_toggled, mode)

        mode_box.append(self.btn_mode_1)
        mode_box.append(self.btn_mode_2)
        mode_box.append(self.btn_mode_3)
        title_box.append(mode_box)

        # Counter pill
        self.lbl_counter = Gtk.Label(label="Photo 0 of 0")
        self.lbl_counter.add_css_class("photo-counter-pill")
        title_box.append(self.lbl_counter)

        # Which order the photos are in, only when it is not the default. The slot is always
        # allocated (opacity, not visibility), so the buttons beside it never move when it appears.
        self.btn_order = Gtk.MenuButton()
        self.btn_order.set_name("order_indicator")
        self.btn_order.add_css_class("flat")
        self.btn_order.set_focus_on_click(False)
        order_content = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=4)
        order_content.append(Gtk.Image.new_from_icon_name("view-sort-ascending-symbolic"))
        self.lbl_order = Gtk.Label(label=VIEW_ORDER_WORDS[DEFAULT_VIEW_ORDER])
        order_content.append(self.lbl_order)
        self.btn_order.set_child(order_content)
        title_box.append(self.btn_order)

        self.header_bar.set_title_widget(title_box)

        # Menu Button (Primary Menu)
        menu_button = self.menu_button = Gtk.MenuButton()
        menu_button.set_name("btn_main_menu")
        menu_button.set_icon_name("open-menu-symbolic")
        menu_button.set_tooltip_text("Main Menu")
        menu = Gio.Menu()

        section_order = Gio.Menu()
        for label, target in VIEW_ORDER_LABELS.items():
            item = Gio.MenuItem.new(label, None)
            item.set_action_and_target_value("win.view_order", GLib.Variant.new_string(target))
            section_order.append_item(item)
        menu.append_section("Order", section_order)
        self.btn_order.set_menu_model(section_order)  # the same radio group, one tap from the counter

        # Accelerators are drawn by GTK from ``set_accels_for_action``, never typed into a label.
        section_view = Gio.Menu()
        section_view.append("Info", "win.toggle_info")
        section_view.append("Go to Photo…", "win.goto_photo")
        menu.append_section(None, section_view)

        section_tools = Gio.Menu()
        section_tools.append("Statistics", "app.statistics")
        menu.append_section(None, section_tools)

        section_app = Gio.Menu()
        section_app.append("Preferences", "app.preferences")
        section_app.append("Keyboard Shortcuts", "app.shortcuts")
        section_app.append("About Blitzlens", "app.about")
        menu.append_section(None, section_app)

        menu_button.set_menu_model(menu)
        self.header_bar.pack_end(menu_button)

        # Radio action behind the menu items and the B shortcut (R-LINUX-CULL-02).
        self._view_order_action = Gio.SimpleAction.new_stateful(
            "view_order",
            GLib.VariantType.new("s"),
            GLib.Variant.new_string(self.viewmodel.sort_order),
        )
        self._view_order_action.connect("activate", self._on_view_order_action)
        self.add_action(self._view_order_action)
        # The keys I and G stay with the keyboard router; the menu rows only run the same methods.
        for name, handler in (("toggle_info", self._toggle_info), ("goto_photo", self.show_goto_dialog)):
            action = Gio.SimpleAction.new(name, None)
            action.connect("activate", lambda _a, _p, fn=handler: fn())
            self.add_action(action)

    def _toggle_info(self) -> None:
        self.canvas_view.toggle_hud()

    def _on_mode_toggled(self, button, mode: int) -> None:
        if self._syncing_modes or not button.get_active():
            return
        self.viewmodel.set_comparison_mode(mode)

    def _on_view_order_action(self, action, param) -> None:
        self.set_view_order(param.get_string())

    def set_view_order(self, order: str) -> None:
        """Switch the view order and say so; the menu radio follows the view model."""
        if order not in VIEW_ORDER_LABELS.values():
            return
        if HAS_GI:
            # First, so the view model's own toast ("Scoring N photographs…") is the one that stays.
            self.show_toast(f"Order: {VIEW_ORDER_TITLES[order]}")
        self.viewmodel.set_sort_order(order)

    def _show_order_indicator(self, order: str) -> None:
        """The order in use, in a word, beside "Photo N of M": a button that opens the order menu."""
        title = VIEW_ORDER_TITLES.get(order, order)
        self.lbl_order.set_label(VIEW_ORDER_WORDS.get(order, title))
        self.btn_order.set_tooltip_text(f"Order: {title} (S)")
        self.btn_order.update_property([Gtk.AccessibleProperty.LABEL], [f"Order: {title}. Change"])

    def toggle_view_order(self) -> None:
        """B: lowest sharpness first <-> the order the folder opens in (newest first, by default)."""
        if self.viewmodel.sort_order == "lowest_sharpness_first" and not self.viewmodel.scoring_confirmation_pending:
            self.set_view_order(self.settings.view_order)
        else:
            self.set_view_order("lowest_sharpness_first")

    def toggle_fullscreen(self) -> None:
        """F / F11."""
        if self.is_fullscreen():
            self.unfullscreen()
        else:
            self.fullscreen()

    def exit_fullscreen(self) -> bool:
        """Escape, after zoom: leave full screen; False when the window was not in it."""
        if not self.is_fullscreen():
            return False
        self.unfullscreen()
        return True

    def show_view_order_menu(self) -> None:
        """S: open the order menu (the radio group of Newest / Oldest / Name / Blurry)."""
        self.btn_order.popup()

    def show_goto_dialog(self) -> None:
        """G: jump to a photo by its position or file name."""
        if not HAS_GI or self.viewmodel.candidate_count == 0:
            return
        from photo_selector_linux.ui.goto_dialog import GoToPhotoDialog

        GoToPhotoDialog(self.viewmodel).present(self)

    def _attach_keyboard_router(self) -> None:
        self.router = KeyboardRouter(window=self, viewmodel=self.viewmodel)
        self.router.attach(self)

    def _bind_viewmodel(self) -> None:
        self.viewmodel.connect("state-changed", self._on_viewmodel_state_changed)
        self.viewmodel.connect("notification", self._on_viewmodel_notification)
        self._on_viewmodel_state_changed(self.viewmodel)

    def _on_viewmodel_state_changed(self, vm) -> None:
        if not HAS_GI:
            return

        total = vm.candidate_count
        current = vm.current_index + 1 if total > 0 else 0
        self.lbl_counter.set_label(f"Photo {current} of {total}")

        if vm.current_folder:
            self.lbl_path.set_label(vm.current_folder_name or vm.current_folder.name)
        else:
            self.lbl_path.set_label("No folder loaded")

        remote_label = getattr(vm, "remote_badge_label", None)
        self.lbl_remote_badge.set_label(remote_label or "")
        self.lbl_remote_badge.set_visible(bool(remote_label))

        action = getattr(self, "_view_order_action", None)
        if action is not None:
            action.set_state(GLib.Variant.new_string(vm.sort_order))
        self._show_order_indicator(vm.sort_order)

        state = vm.canvas_state
        if state != self._last_canvas_state:
            if state == "done":
                self.action_bar._announce(announcement(vm))
            elif state == "reading":
                self.action_bar._announce(f"Reading {vm.current_folder_name}")
            self._last_canvas_state = state

        if self._close_when_idle and vm.queued_count == 0:
            self._close_when_idle = False
            self.close()

        # Once per folder, when its first photos are shown: focus moves from the header button
        # (Open) to the viewport, so Space toggles Fit / 1:1 instead of re-opening the picker.
        # Tab still reaches the buttons and Space/Enter then press them (R-LINUX-UI-08).
        if total > 0 and vm.current_folder != self._focused_folder:
            self._focused_folder = vm.current_folder
            self.canvas_view.grab_focus()

        mode = vm.comparison_mode
        self._syncing_modes = True  # the toggles follow the view model; do not echo it back
        try:
            for number, button in ((1, self.btn_mode_1), (2, self.btn_mode_2), (3, self.btn_mode_3)):
                button.set_active(mode == number)
                button.set_css_classes(["suggested-action"] if mode == number else [])
        finally:
            self._syncing_modes = False

    def _on_viewmodel_notification(self, vm, message: str) -> None:
        """Cull/undo outcomes and errors live in the action bar; load info gets a toast."""
        if not HAS_GI:
            return
        severity = getattr(vm, "last_notification_severity", "info")
        if severity == "status":
            return  # the action bar shows and announces it
        if severity == "error":
            return  # one sentence, in the action bar, never a second copy over the photo
        # Files left in an unexpected place: the toast stays until the user dismisses it.
        self.show_toast(message, persistent=severity == "stranded")
        if severity == "info":
            # "Loaded N photos" and the like: errors are announced by the action bar.
            self.action_bar._announce(message)

    def show_toast(self, message: str, persistent: bool = False) -> None:
        """Show one toast at a time: the previous one is dismissed, never queued."""
        if self._toast is not None:
            self._toast.dismiss()
        toast = Adw.Toast.new(message)
        toast.set_timeout(0 if persistent else 3)
        self._toast = toast
        self.toast_overlay.add_toast(toast)

    def show_selection_in_files(self) -> None:
        """Show in Files: open the folder the keepers went to in the file manager."""
        path = self.viewmodel.resolve_destination().path
        if path is None or not HAS_GI:
            return
        if not path.exists():
            self.show_toast("The Selection folder is not there.")
            return
        launcher = Gtk.FileLauncher.new(Gio.File.new_for_path(str(path)))
        launcher.launch(self, None, self._on_files_launched)

    def _on_files_launched(self, launcher, result) -> None:
        try:
            launcher.launch_finish(result)
        except GLib.Error as e:
            logger.warning("Show in Files failed: %s", e.message)
            self.show_toast("Can't open the file manager.")

    def show_open_folder_dialog(self) -> None:
        if not HAS_GI:
            return
        dialog = Gtk.FileDialog()
        dialog.set_title("Open Folder")
        recent = self.settings.recent_folders if self.settings is not None else []
        if recent and Path(recent[0]).is_dir():
            dialog.set_initial_folder(Gio.File.new_for_path(recent[0]))
        dialog.select_folder(self, None, self._on_folder_dialog_finish)

    def _on_folder_dialog_finish(self, dialog, result) -> None:
        try:
            folder = dialog.select_folder_finish(result)
        except GLib.Error as e:
            # Closing the picker is not an error: say nothing. Anything else is.
            if e.matches(Gtk.dialog_error_quark(), Gtk.DialogError.DISMISSED) or e.matches(
                Gtk.dialog_error_quark(), Gtk.DialogError.CANCELLED
            ):
                return
            self.show_toast(f"Could not open the folder: {e.message}")
            return
        if not folder or not folder.get_path():
            return
        path = Path(folder.get_path())
        try:
            self.open_directory(path)
        except Exception as e:
            self.show_toast(f"Could not open {path.name}: {e}")

    def open_directory(self, path: Path) -> None:
        """Open a folder; a photo opens its folder with that photo selected."""
        if path.is_file():
            self.viewmodel.load_directory(path.parent, select=path)
        else:
            self.viewmodel.load_directory(path)
        if self.settings is not None:
            self.settings.remember_folder(path.parent if path.is_file() else path)

    def _attach_folder_drop_target(self) -> None:
        """A folder dropped on the window opens (a file opens its folder)."""
        target = Gtk.DropTarget.new(Gio.File, Gdk.DragAction.COPY)
        target.connect("drop", self._on_folder_dropped)
        self.add_controller(target)

    def _on_folder_dropped(self, _target, value, _x, _y) -> bool:
        path = value.get_path() if value is not None else None
        if not path:
            return False
        self.open_directory(Path(path))
        return True

    def show_library_statistics(self) -> None:
        from photo_selector_linux.ui.tools_dialogs import LibraryStatisticsDialog
        dlg = LibraryStatisticsDialog(transient_for=self, viewmodel=self.viewmodel)
        dlg.present()

    def show_preferences(self) -> None:
        from photo_selector_linux.ui.preferences import PreferencesDialog
        prefs = PreferencesDialog(
            settings=self.settings,
            viewmodel=self.viewmodel,
            transient_for=self,
        )
        prefs.present()

    def show_about(self) -> None:
        from photo_selector_linux import __version__

        about = Adw.AboutDialog()
        about.set_application_name(APP_BRAND_NAME)
        about.set_application_icon(APP_ICON_NAME)
        about.set_version(__version__)
        about.set_developer_name("AlxPPa")
        about.set_comments("Photo culling for GNOME")
        about.set_website("https://github.com/alexpp90/photo-selector")
        about.set_license_type(Gtk.License.MIT_X11)
        about.set_copyright("\u00a9 AlxPPa")
        about.present(self)

    def show_shortcuts_guide(self) -> None:
        from photo_selector_linux.ui.shortcuts_dialog import ShortcutsDialog
        dlg = ShortcutsDialog(transient_for=self)
        dlg.present()
