"""Main Libadwaita application window.

Adheres to:
- R-LINUX-UI-01: Pure GNOME HIG / Libadwaita Window Architecture
- R-LINUX-UI-03: Viewport Canvas Area Allocation (>85% of total window area)
"""

from __future__ import annotations

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
from photo_selector_linux.ui.file_worker import FileOperationWorker
from photo_selector_linux.ui.keyboard_router import KeyboardRouter
from photo_selector_linux.ui.styling import APP_BRAND_NAME, APP_ICON_NAME
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

# Menu label -> view model sort order (R-LINUX-CULL-02).
VIEW_ORDER_LABELS = {
    "Natural order": "natural",
    "Lowest sharpness first": "lowest_sharpness_first",
}
VIEW_ORDER_TITLES = {order: label for label, order in VIEW_ORDER_LABELS.items()}


class MainWindow(Adw.ApplicationWindow if HAS_GI else object):
    """Main window housing the HeaderBar, Multi-View Canvas, and Bottom ActionBar."""

    def __init__(self, application=None, settings: SettingsStore | None = None, **kwargs):
        self.window_title: str = APP_BRAND_NAME
        self.icon_name: str = APP_ICON_NAME
        self.settings: SettingsStore = settings or SettingsStore()
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
        # The toast overlay wraps only the canvas so a toast can never cover the action bar (LIN-7).
        self.toast_overlay = Adw.ToastOverlay()
        self.toast_overlay.set_child(self.canvas_view)
        self.toolbar_view.set_content(self.toast_overlay)

        # Bottom Action Bar
        self.action_bar = ActionBarView(viewmodel=self.viewmodel)
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
        self.btn_mode_1 = Gtk.Button(label="1-Up (1)")
        self.btn_mode_1.set_name("btn_mode_1")
        self.btn_mode_2 = Gtk.Button(label="2-Up (2)")
        self.btn_mode_2.set_name("btn_mode_2")
        self.btn_mode_3 = Gtk.Button(label="Focus 3-Up (3)")
        self.btn_mode_3.set_name("btn_mode_3")

        self.btn_mode_1.connect("clicked", lambda b: self.viewmodel.set_comparison_mode(1))
        self.btn_mode_2.connect("clicked", lambda b: self.viewmodel.set_comparison_mode(2))
        self.btn_mode_3.connect("clicked", lambda b: self.viewmodel.set_comparison_mode(3))

        mode_box.append(self.btn_mode_1)
        mode_box.append(self.btn_mode_2)
        mode_box.append(self.btn_mode_3)
        title_box.append(mode_box)

        # Counter pill
        self.lbl_counter = Gtk.Label(label="Photo 0 of 0")
        self.lbl_counter.add_css_class("photo-counter-pill")
        title_box.append(self.lbl_counter)

        # Queue status pill (U-08)
        self.lbl_queue = Gtk.Label(label="")
        self.lbl_queue.add_css_class("photo-counter-pill")
        self.lbl_queue.set_visible(False)
        title_box.append(self.lbl_queue)

        self.header_bar.set_title_widget(title_box)

        # Menu Button (Primary Menu)
        menu_button = Gtk.MenuButton()
        menu_button.set_icon_name("open-menu-symbolic")
        menu_button.set_tooltip_text("Main Menu")
        menu = Gio.Menu()

        section_order = Gio.Menu()
        for label, target in VIEW_ORDER_LABELS.items():
            item = Gio.MenuItem.new(label, None)
            item.set_action_and_target_value("win.view_order", GLib.Variant.new_string(target))
            section_order.append_item(item)
        menu.append_section("View order (B)", section_order)

        section_tools = Gio.Menu()
        section_tools.append("Duplicate Finder (Ctrl+D)", "app.duplicate_finder")
        section_tools.append("Library Statistics (Ctrl+L)", "app.statistics")
        menu.append_section(None, section_tools)

        section_app = Gio.Menu()
        section_app.append("Preferences (Ctrl+,)", "app.preferences")
        section_app.append("Keyboard Shortcuts (Ctrl+?)", "app.shortcuts")
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

    def _on_view_order_action(self, action, param) -> None:
        self.set_view_order(param.get_string())

    def set_view_order(self, order: str) -> None:
        """Switch the view order and say so; the menu radio follows the view model."""
        if order not in VIEW_ORDER_LABELS.values():
            return
        self.viewmodel.set_sort_order(order)
        if HAS_GI:
            self.show_toast(f"Order: {VIEW_ORDER_TITLES[order]}")

    def toggle_view_order(self) -> None:
        """B: natural order <-> lowest sharpness first."""
        if self.viewmodel.sort_order == "lowest_sharpness_first":
            self.set_view_order("natural")
        else:
            self.set_view_order("lowest_sharpness_first")

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

        queue_label = getattr(vm, "queue_status_label", None)
        self.lbl_queue.set_label(queue_label or "")
        self.lbl_queue.set_visible(bool(queue_label))

        action = getattr(self, "_view_order_action", None)
        if action is not None:
            action.set_state(GLib.Variant.new_string(vm.sort_order))

        mode = vm.comparison_mode
        self.btn_mode_1.set_css_classes(["suggested-action"] if mode == 1 else [])
        self.btn_mode_2.set_css_classes(["suggested-action"] if mode == 2 else [])
        self.btn_mode_3.set_css_classes(["suggested-action"] if mode == 3 else [])

    def _on_viewmodel_notification(self, vm, message: str) -> None:
        """Cull/undo outcomes live in the action bar; load info and errors also get a toast."""
        if not HAS_GI:
            return
        if getattr(vm, "last_notification_severity", "info") == "status":
            return
        self.show_toast(message)

    def show_toast(self, message: str) -> None:
        """Show one toast at a time: the previous one is dismissed, never queued."""
        if self._toast is not None:
            self._toast.dismiss()
        toast = Adw.Toast.new(message)
        toast.set_timeout(3)
        self._toast = toast
        self.toast_overlay.add_toast(toast)

    def show_open_folder_dialog(self) -> None:
        if not HAS_GI:
            return
        dialog = Gtk.FileDialog()
        dialog.set_title("Select Folder with Photographs")
        dialog.select_folder(self, None, self._on_folder_dialog_finish)

    def _on_folder_dialog_finish(self, dialog, result) -> None:
        try:
            folder = dialog.select_folder_finish(result)
            if folder:
                path = Path(folder.get_path())
                self.open_directory(path)
        except Exception as e:
            if HAS_GI and GLib:
                GLib.idle_add(self.show_toast, f"Open folder cancelled: {e}")

    def open_directory(self, path: Path) -> None:
        self.viewmodel.load_directory(path)

    def show_duplicate_finder(self) -> None:
        from photo_selector_linux.ui.tools_dialogs import DuplicateFinderDialog
        dlg = DuplicateFinderDialog(transient_for=self, viewmodel=self.viewmodel)
        dlg.present()

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
