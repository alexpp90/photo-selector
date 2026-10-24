"""Zero-latency keyboard routing engine for GTK4 root window event capture.

Adheres to:
- R-LINUX-KEY-01: Root Window Key Event Capture, Modifier Masking & Case Normalization
- R-LINUX-KEY-02: Navigation Hotkeys (←, →, H, L, J, K)
- R-LINUX-KEY-03: Culling Hotkeys (M, C, Delete, Backspace)
- R-LINUX-KEY-04: Comparison Mode Switchers (1, 2, 3)
- R-LINUX-KEY-05: Inspection Hotkeys (Space, Esc, Tab, I, F, S, B)
- R-LINUX-KEY-06: System & Utility Hotkeys (Ctrl+Z, Ctrl+O, etc.)
- R-LINUX-KEY-07: Editable Text Entry Exception
"""

from __future__ import annotations

from typing import Any

try:
    import gi
    gi.require_version("Gtk", "4.0")
    from gi.repository import Gtk, Gdk
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = Gdk = None


class KeyboardRouterEngine:
    """Decoupled key matching and dispatching engine.

    Operates in pure Python without GTK dependencies for headless testing.
    """
    # Keyval constants mirroring Gdk
    KEY_LEFT = 0xFF51
    KEY_RIGHT = 0xFF53
    KEY_DELETE = 0xFFFF
    KEY_BACKSPACE = 0xFF08
    KEY_SPACE = 0x0020
    KEY_ESCAPE = 0xFF1B
    KEY_TAB = 0xFF09
    KEY_QUESTION = 0x003F
    KEY_COMMA = 0x002C

    KEY_0 = 0x0030
    KEY_1 = 0x0031
    KEY_2 = 0x0032
    KEY_3 = 0x0033
    KEY_KP_1 = 0xFFB1
    KEY_KP_2 = 0xFFB2
    KEY_KP_3 = 0xFFB3

    KEY_A = 0x0061
    KEY_B = 0x0062
    KEY_C = 0x0063
    KEY_D = 0x0064
    KEY_F = 0x0066
    KEY_H = 0x0068
    KEY_I = 0x0069
    KEY_J = 0x006A
    KEY_K = 0x006B
    KEY_L = 0x006C
    KEY_M = 0x006D
    KEY_O = 0x006F
    KEY_S = 0x0073
    KEY_Z = 0x007A

    # Modifier constants
    SHIFT_MASK = 1 << 0
    LOCK_MASK = 1 << 1    # CapsLock
    CONTROL_MASK = 1 << 2
    MOD1_MASK = 1 << 3    # Alt
    ALT_MASK = 1 << 3     # Alt alias
    MOD2_MASK = 1 << 4    # NumLock
    MOD4_MASK = 1 << 6    # Super / Windows key on X11
    SUPER_MASK = 1 << 26  # Super / Windows key on GDK / Wayland
    HYPER_MASK = 1 << 27  # Hyper key
    META_MASK = 1 << 28   # Meta key

    ALT_SUPER_MASK = (
        MOD1_MASK
        | MOD4_MASK
        | SUPER_MASK
        | HYPER_MASK
        | META_MASK
    )

    def __init__(self, handler_target: Any):
        self.target = handler_target

    @classmethod
    def normalize_keyval(cls, keyval: int) -> int:
        """Case-insensitively normalizes uppercase ASCII letters to lowercase (R-LINUX-KEY-01)."""
        if 0x0041 <= keyval <= 0x005A:  # 'A' to 'Z'
            return keyval + 0x20
        return keyval

    @classmethod
    def clean_modifiers(cls, state: int) -> int:
        """Mask out CapsLock (LOCK_MASK) and NumLock (MOD2_MASK) (R-LINUX-KEY-01)."""
        return state & ~(cls.LOCK_MASK | cls.MOD2_MASK)

    def dispatch(self, keyval: int, state: int, is_text_editing: bool = False) -> bool:
        """Dispatch keypress to target handler.

        Returns True if handled, False otherwise.
        """
        if is_text_editing:
            return False  # Editable text entry exception (R-LINUX-KEY-07)

        norm_key = self.normalize_keyval(keyval)
        eff_state = self.clean_modifiers(state)
        has_ctrl = bool(eff_state & self.CONTROL_MASK)
        has_alt_or_super = bool(eff_state & self.ALT_SUPER_MASK)

        # 1. Control Combinations (R-LINUX-KEY-06)
        if has_ctrl and not has_alt_or_super:
            if norm_key == self.KEY_Z:
                self.target.on_undo()
                return True
            elif norm_key == self.KEY_O:
                self.target.on_open_folder()
                return True
            elif norm_key == self.KEY_D:
                self.target.on_duplicate_finder()
                return True
            elif norm_key == self.KEY_L:
                self.target.on_statistics()
                return True
            elif norm_key == self.KEY_COMMA:
                self.target.on_preferences()
                return True
            elif norm_key == self.KEY_QUESTION:
                self.target.on_shortcuts()
                return True
            return False

        # Disallow accelerator modifiers from bleeding into single-key actions
        if has_ctrl or has_alt_or_super:
            return False

        # 2. Single-Key Culling Actions (R-LINUX-KEY-03)
        if norm_key == self.KEY_M:
            self.target.on_cull_move()
            return True
        elif norm_key == self.KEY_C:
            self.target.on_cull_copy()
            return True
        elif norm_key in (self.KEY_DELETE, self.KEY_BACKSPACE):
            self.target.on_cull_trash()
            return True

        # 3. Single-Key Navigation (R-LINUX-KEY-02)
        if norm_key in (self.KEY_LEFT, self.KEY_H, self.KEY_K):
            self.target.on_nav_previous()
            return True
        elif norm_key in (self.KEY_RIGHT, self.KEY_L, self.KEY_J):
            self.target.on_nav_next()
            return True

        # 4. View Mode Switchers (R-LINUX-KEY-04)
        if norm_key in (self.KEY_1, self.KEY_KP_1):
            self.target.on_set_mode(1)
            return True
        elif norm_key in (self.KEY_2, self.KEY_KP_2):
            self.target.on_set_mode(2)
            return True
        elif norm_key in (self.KEY_3, self.KEY_KP_3):
            self.target.on_set_mode(3)
            return True

        # 5. Inspection Toggles (R-LINUX-KEY-05)
        if norm_key == self.KEY_SPACE:
            self.target.on_toggle_zoom()
            return True
        elif norm_key == self.KEY_ESCAPE:
            self.target.on_reset_zoom()
            return True
        elif norm_key == self.KEY_TAB:
            self.target.on_cycle_slot()
            return True
        elif norm_key == self.KEY_I:
            self.target.on_toggle_hud()
            return True
        elif norm_key == self.KEY_F:
            self.target.on_toggle_filmstrip()
            return True
        elif norm_key == self.KEY_S:
            self.target.on_toggle_sync_lock()
            return True
        elif norm_key == self.KEY_QUESTION:
            self.target.on_shortcuts()
            return True
        elif norm_key == self.KEY_B:
            self.target.on_toggle_sort_order()
            return True

        return False


class KeyboardRouter:
    """GTK4 EventControllerKey bridge attaching to the root window in CAPTURE phase."""

    def __init__(self, window: Any, viewmodel: Any):
        self.window = window
        self.viewmodel = viewmodel
        self.engine = KeyboardRouterEngine(handler_target=self)

    def attach(self, window: Any) -> None:
        """Attach key controller in CAPTURE phase to root window."""
        if not HAS_GI:
            return
        controller = Gtk.EventControllerKey.new()
        controller.set_propagation_phase(Gtk.PropagationPhase.CAPTURE)
        controller.connect("key-pressed", self._on_key_pressed)
        window.add_controller(controller)

    def _on_key_pressed(self, controller, keyval, keycode, state) -> bool:
        focus_widget = self.window.get_focus() if hasattr(self.window, "get_focus") else None
        is_text_editing = False
        if focus_widget is not None and isinstance(
            focus_widget, (Gtk.Editable, Gtk.Entry, Gtk.SearchEntry, Gtk.TextView)
        ):
            is_text_editing = True

        handled = self.engine.dispatch(keyval, int(state), is_text_editing=is_text_editing)
        return Gdk.EVENT_STOP if handled else Gdk.EVENT_PROPAGATE

    # Delegated actions
    def on_undo(self) -> None:
        self.viewmodel.undo()

    def on_open_folder(self) -> None:
        if hasattr(self.window, "show_open_folder_dialog"):
            self.window.show_open_folder_dialog()

    def on_duplicate_finder(self) -> None:
        if hasattr(self.window, "show_duplicate_finder"):
            self.window.show_duplicate_finder()

    def on_statistics(self) -> None:
        if hasattr(self.window, "show_library_statistics"):
            self.window.show_library_statistics()

    def on_preferences(self) -> None:
        if hasattr(self.window, "show_preferences"):
            self.window.show_preferences()

    def on_shortcuts(self) -> None:
        if hasattr(self.window, "show_shortcuts_guide"):
            self.window.show_shortcuts_guide()

    def on_cull_move(self) -> None:
        self.viewmodel.cull_move()

    def on_cull_copy(self) -> None:
        self.viewmodel.cull_copy()

    def on_cull_trash(self) -> None:
        self.viewmodel.cull_trash()

    def on_nav_previous(self) -> None:
        self.viewmodel.nav_previous()

    def on_nav_next(self) -> None:
        self.viewmodel.nav_next()

    def on_set_mode(self, mode: int) -> None:
        self.viewmodel.set_comparison_mode(mode)

    def on_toggle_zoom(self) -> None:
        if hasattr(self.window, "canvas_view") and hasattr(self.window.canvas_view, "toggle_zoom"):
            self.window.canvas_view.toggle_zoom()

    def on_reset_zoom(self) -> None:
        if hasattr(self.window, "canvas_view") and hasattr(self.window.canvas_view, "reset_zoom"):
            self.window.canvas_view.reset_zoom()

    def on_cycle_slot(self) -> None:
        pass

    def on_toggle_hud(self) -> None:
        if hasattr(self.window, "canvas_view") and hasattr(self.window.canvas_view, "toggle_hud"):
            self.window.canvas_view.toggle_hud()

    def on_toggle_sort_order(self) -> None:
        if hasattr(self.window, "toggle_view_order"):
            self.window.toggle_view_order()
        else:
            self.viewmodel.set_sort_order(
                "natural" if self.viewmodel.sort_order == "lowest_sharpness_first"
                else "lowest_sharpness_first"
            )

    def on_toggle_filmstrip(self) -> None:
        pass

    def on_toggle_sync_lock(self) -> None:
        pass
