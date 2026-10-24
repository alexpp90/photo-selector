"""Pytest configuration and universal mocking harness for PyGObject / GTK4.

Tests never need a visible display: without PyGObject (macOS Poetry venv) or without a display
server they run against the stubs below; Linux CI runs the real GTK under ``xvfb-run``.
"""

import os
import sys
from unittest.mock import MagicMock

os.environ.setdefault("ADW_DISABLE_PORTAL", "1")


def _real_gtk_usable() -> bool:
    """True when real GTK widgets can be created in this process.

    GTK 4 has no headless GDK backend: creating a widget without a display server crashes the
    interpreter (SIGSEGV, exit code 139). Linux CI therefore runs under ``xvfb-run``; without any
    display (or without PyGObject, e.g. the macOS Poetry venv) the tests use the stubs below.
    """
    if sys.platform.startswith("linux") and not (
        os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")
    ):
        return False
    try:
        import gi  # noqa: F401
    except ImportError:
        return False
    return True


# Without a usable GTK, inject comprehensive test stubs
if "gi" not in sys.modules and not _real_gtk_usable():
    def mock_require_version(namespace, version):
        if namespace in ("GExiv2", "GdkPixbuf"):
            raise ValueError(f"Namespace {namespace} not available")
        return None

    mock_gi = MagicMock()
    mock_gi.require_version = mock_require_version

    class MockGtkWidget:
        def __init__(self, *args, **kwargs):
            self._visible = True
            self._css_classes = []
            self._children = []
            self._label = str(kwargs.get("label", ""))
            self._name = ""
            self._sensitive = True
            self._fraction = 0.0

        @classmethod
        def new_from_icon_name(cls, *args, **kwargs):
            return cls()

        def connect(self, *args, **kwargs):
            pass

        def set_name(self, name: str, *args, **kwargs):
            self._name = str(name)

        def get_name(self):
            return getattr(self, "_name", "")

        def set_visible(self, visible: bool = True, *args, **kwargs):
            self._visible = bool(visible)

        def get_visible(self):
            return getattr(self, "_visible", True)

        def set_label(self, label: str, *args, **kwargs):
            self._label = str(label)

        def get_label(self):
            return getattr(self, "_label", "")

        def set_sensitive(self, sensitive: bool = True, *args, **kwargs):
            self._sensitive = bool(sensitive)

        def get_sensitive(self):
            return getattr(self, "_sensitive", True)

        def set_fraction(self, fraction: float = 0.0, *args, **kwargs):
            self._fraction = float(fraction)

        def get_fraction(self):
            return getattr(self, "_fraction", 0.0)

        def set_tooltip_text(self, *args, **kwargs):
            pass

        def set_action_name(self, *args, **kwargs):
            pass

        def set_pixel_size(self, *args, **kwargs):
            pass

        def add_css_class(self, cls_name: str, *args, **kwargs):
            classes = getattr(self, "_css_classes", None)
            if classes is None:
                classes = []
                self._css_classes = classes
            if cls_name not in classes:
                classes.append(cls_name)

        def set_css_classes(self, classes, *args, **kwargs):
            self._css_classes = list(classes)

        def has_css_class(self, cls_name: str, *args, **kwargs) -> bool:
            return cls_name in getattr(self, "_css_classes", [])

        def get_css_classes(self):
            return list(getattr(self, "_css_classes", []))

        def set_halign(self, *args, **kwargs):
            pass

        def set_valign(self, *args, **kwargs):
            pass

        def set_hexpand(self, *args, **kwargs):
            pass

        def set_vexpand(self, *args, **kwargs):
            pass

        def set_ellipsize(self, *args, **kwargs):
            pass

        def set_max_width_chars(self, *args, **kwargs):
            pass

        def append(self, child, *args, **kwargs):
            children = getattr(self, "_children", None)
            if children is None:
                children = []
                self._children = children
            children.append(child)

        def remove(self, child, *args, **kwargs):
            children = getattr(self, "_children", None)
            if children and child in children:
                children.remove(child)

        def get_first_child(self):
            children = getattr(self, "_children", None)
            return children[0] if children else None

        def set_xalign(self, *args, **kwargs):
            pass

        def set_can_target(self, can_target: bool = True, *args, **kwargs):
            self._can_target = bool(can_target)

        def get_can_target(self):
            return getattr(self, "_can_target", True)

        def get_parent(self):
            return getattr(self, "_parent", None)

        def set_size_request(self, *args, **kwargs):
            pass

        def set_homogeneous(self, *args, **kwargs):
            pass

        def set_default_size(self, *args, **kwargs):
            pass

        def set_title(self, *args, **kwargs):
            pass

        def set_icon_name(self, *args, **kwargs):
            pass

        def set_content(self, *args, **kwargs):
            pass

        def set_margin_top(self, *args, **kwargs):
            pass

        def set_margin_bottom(self, *args, **kwargs):
            pass

        def set_margin_start(self, *args, **kwargs):
            pass

        def set_margin_end(self, *args, **kwargs):
            pass

        def pack_start(self, *args, **kwargs):
            pass

        def pack_end(self, *args, **kwargs):
            pass

        def set_center_widget(self, *args, **kwargs):
            pass

        def add_controller(self, *args, **kwargs):
            pass

        def set_transient_for(self, *args, **kwargs):
            pass

        def present(self, *args, **kwargs):
            pass

        def add(self, child, *args, **kwargs):
            self.append(child)

        def set_child(self, child, *args, **kwargs):
            self._children = [child]

        def set_subtitle(self, subtitle: str = "", *args, **kwargs):
            self._subtitle = str(subtitle)

        def get_subtitle(self):
            return getattr(self, "_subtitle", "")

        def set_text(self, text: str = "", *args, **kwargs):
            self._text = str(text)

        def get_text(self):
            return getattr(self, "_text", "")

        def set_active(self, active: bool = False, *args, **kwargs):
            self._active = bool(active)

        def get_active(self):
            return getattr(self, "_active", False)

        def set_value(self, value: float = 0.0, *args, **kwargs):
            self._value = float(value)

        def get_value(self):
            return getattr(self, "_value", 0.0)

        def set_digits(self, *args, **kwargs):
            pass

        def set_draw_value(self, *args, **kwargs):
            pass

        def add_suffix(self, child, *args, **kwargs):
            self.append(child)

        def add_tick_callback(self, *args, **kwargs):
            return 0

    # Mock Gtk
    mock_gtk = MagicMock()
    mock_gtk.Box = MockGtkWidget
    mock_gtk.ActionBar = MockGtkWidget
    mock_gtk.Widget = MockGtkWidget
    mock_gtk.Button = MockGtkWidget
    mock_gtk.Label = MockGtkWidget
    mock_gtk.ProgressBar = MockGtkWidget
    mock_gtk.ScrolledWindow = MockGtkWidget
    mock_gtk.Scale.new_with_range = lambda *args, **kwargs: MockGtkWidget()
    mock_gtk.Orientation.HORIZONTAL = 0
    mock_gtk.Orientation.VERTICAL = 1
    mock_gtk.Align.START = 1
    mock_gtk.Align.END = 2
    mock_gtk.Align.CENTER = 3
    mock_gtk.PropagationPhase.CAPTURE = 1
    mock_gtk.PropagationPhase.BUBBLE = 2
    mock_gtk.STYLE_PROVIDER_PRIORITY_APPLICATION = 600

    # Mock Gdk
    mock_gdk = MagicMock()
    mock_gdk.ModifierType.SHIFT_MASK = 1 << 0
    mock_gdk.ModifierType.LOCK_MASK = 1 << 1
    mock_gdk.ModifierType.CONTROL_MASK = 1 << 2
    mock_gdk.ModifierType.MOD1_MASK = 1 << 3
    mock_gdk.ModifierType.MOD2_MASK = 1 << 4
    mock_gdk.EVENT_STOP = True
    mock_gdk.EVENT_PROPAGATE = False
    mock_gdk.keyval_to_lower = lambda k: k + 0x20 if 0x0041 <= k <= 0x005A else k

    # Mock Adw
    mock_adw = MagicMock()
    mock_adw.Application = MockGtkWidget
    mock_adw.ApplicationWindow = MockGtkWidget
    mock_adw.PreferencesWindow = MockGtkWidget
    mock_adw.PreferencesPage = MockGtkWidget
    mock_adw.PreferencesGroup = MockGtkWidget
    mock_adw.EntryRow = MockGtkWidget
    mock_adw.SwitchRow = MockGtkWidget
    mock_adw.ActionRow = MockGtkWidget
    mock_adw.Window = MockGtkWidget
    mock_adw.ColorScheme.FORCE_DARK = 2

    # Mock Gio & GLib
    mock_gio = MagicMock()
    mock_glib = MagicMock()

    mock_repository = MagicMock()
    mock_repository.Gtk = mock_gtk
    mock_repository.Gdk = mock_gdk
    mock_repository.Adw = mock_adw
    mock_repository.Gio = mock_gio
    mock_repository.GLib = mock_glib

    sys.modules["gi"] = mock_gi
    sys.modules["gi.repository"] = mock_repository
    sys.modules["gi.repository.Gtk"] = mock_gtk
    sys.modules["gi.repository.Gdk"] = mock_gdk
    sys.modules["gi.repository.Adw"] = mock_adw
    sys.modules["gi.repository.Gio"] = mock_gio
    sys.modules["gi.repository.GLib"] = mock_glib
