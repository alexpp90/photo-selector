"""Unit tests for KeyboardRouterEngine, modifier masking, and text bypass.

Adheres to:
- R-LINUX-KEY-01: Root Window Key Event Capture, Modifier Masking & Case Normalization
- R-LINUX-KEY-02: Navigation Hotkeys (←, →, H, L, J, K)
- R-LINUX-KEY-03: Culling Hotkeys (M, C, Delete, Backspace)
- R-LINUX-KEY-04: Comparison Mode Switchers (1, 2, 3)
- R-LINUX-KEY-05: Inspection Hotkeys (Space, Esc, Tab, I, F, S)
- R-LINUX-KEY-06: System & Utility Hotkeys (Ctrl+Z, Ctrl+O, etc.)
- R-LINUX-KEY-07: Editable Text Entry Exception
"""

from unittest.mock import MagicMock

from photo_selector_linux.ui.keyboard_router import KeyboardRouterEngine


def test_root_key_capture_and_modifier_masking():
    """Verify CapsLock (0x2) and NumLock (0x10) do not inhibit hotkey matching (R-LINUX-KEY-01)."""
    target = MagicMock()
    engine = KeyboardRouterEngine(handler_target=target)

    # Key 'm' (0x6d) with CapsLock active (state = 2)
    handled = engine.dispatch(keyval=0x6D, state=2)
    assert handled is True
    target.on_cull_move.assert_called_once()

    # Key 'm' (0x6d) with NumLock active (state = 16)
    target.reset_mock()
    handled = engine.dispatch(keyval=0x6D, state=16)
    assert handled is True
    target.on_cull_move.assert_called_once()

    # Key 'm' with both CapsLock and NumLock active (state = 18)
    target.reset_mock()
    handled = engine.dispatch(keyval=0x6D, state=18)
    assert handled is True
    target.on_cull_move.assert_called_once()


def test_navigation_hotkeys_case_insensitivity():
    """Verify Left/Right, H/L, J/K trigger navigation case-insensitively (R-LINUX-KEY-02)."""
    target = MagicMock()
    engine = KeyboardRouterEngine(handler_target=target)

    # Left Arrow (0xff51)
    assert engine.dispatch(keyval=0xFF51, state=0) is True
    target.on_nav_previous.assert_called_once()

    # Uppercase 'H' (0x48)
    target.reset_mock()
    assert engine.dispatch(keyval=0x48, state=0) is True
    target.on_nav_previous.assert_called_once()

    # Uppercase 'K' (0x4b)
    target.reset_mock()
    assert engine.dispatch(keyval=0x4B, state=0) is True
    target.on_nav_previous.assert_called_once()

    # Right Arrow (0xff53)
    target.reset_mock()
    assert engine.dispatch(keyval=0xFF53, state=0) is True
    target.on_nav_next.assert_called_once()

    # Uppercase 'L' (0x4c)
    target.reset_mock()
    assert engine.dispatch(keyval=0x4C, state=0) is True
    target.on_nav_next.assert_called_once()

    # Lowercase 'j' (0x6a)
    target.reset_mock()
    assert engine.dispatch(keyval=0x6A, state=0) is True
    target.on_nav_next.assert_called_once()


def test_culling_hotkeys_case_insensitivity_and_locks():
    """Verify M, C, Del, Backspace execute culling actions reliably (R-LINUX-KEY-03)."""
    target = MagicMock()
    engine = KeyboardRouterEngine(handler_target=target)

    # Uppercase 'M' (0x4d)
    assert engine.dispatch(keyval=0x4D, state=0) is True
    target.on_cull_move.assert_called_once()

    # Uppercase 'C' (0x43)
    target.reset_mock()
    assert engine.dispatch(keyval=0x43, state=0) is True
    target.on_cull_copy.assert_called_once()

    # Delete (0xffff)
    target.reset_mock()
    assert engine.dispatch(keyval=0xFFFF, state=0) is True
    target.on_cull_trash.assert_called_once()

    # Backspace (0xff08)
    target.reset_mock()
    assert engine.dispatch(keyval=0xFF08, state=0) is True
    target.on_cull_trash.assert_called_once()


def test_view_mode_hotkeys():
    """Verify 1, 2, 3 switch comparison modes (R-LINUX-KEY-04)."""
    target = MagicMock()
    engine = KeyboardRouterEngine(handler_target=target)

    assert engine.dispatch(keyval=ord("1"), state=0) is True
    target.on_set_mode.assert_called_with(1)

    assert engine.dispatch(keyval=ord("2"), state=0) is True
    target.on_set_mode.assert_called_with(2)

    assert engine.dispatch(keyval=ord("3"), state=0) is True
    target.on_set_mode.assert_called_with(3)


def test_inspection_hotkeys():
    """Verify Space, Escape, Tab, I, F, S trigger inspection actions (R-LINUX-KEY-05)."""
    target = MagicMock()
    engine = KeyboardRouterEngine(handler_target=target)

    # Space (0x20)
    assert engine.dispatch(keyval=0x20, state=0) is True
    target.on_toggle_zoom.assert_called_once()

    # Escape (0xff1b)
    target.reset_mock()
    assert engine.dispatch(keyval=0xFF1B, state=0) is True
    target.on_reset_zoom.assert_called_once()

    # Tab (0xff09)
    target.reset_mock()
    assert engine.dispatch(keyval=0xFF09, state=0) is True
    target.on_cycle_slot.assert_called_once()

    # I / i (0x69)
    target.reset_mock()
    assert engine.dispatch(keyval=0x69, state=0) is True
    target.on_toggle_hud.assert_called_once()

    # F / f (0x66)
    target.reset_mock()
    assert engine.dispatch(keyval=0x66, state=0) is True
    target.on_toggle_filmstrip.assert_called_once()

    # S / s (0x73)
    target.reset_mock()
    assert engine.dispatch(keyval=0x73, state=0) is True
    target.on_toggle_sync_lock.assert_called_once()


def test_utility_hotkeys():
    """Verify Ctrl+Z, Ctrl+O, Ctrl+D, Ctrl+L, Ctrl+, trigger utilities (R-LINUX-KEY-06)."""
    target = MagicMock()
    engine = KeyboardRouterEngine(handler_target=target)
    ctrl = 1 << 2

    # Ctrl+Z
    assert engine.dispatch(keyval=ord("z"), state=ctrl) is True
    target.on_undo.assert_called_once()

    # Ctrl+O
    target.reset_mock()
    assert engine.dispatch(keyval=ord("o"), state=ctrl) is True
    target.on_open_folder.assert_called_once()

    # Ctrl+D
    target.reset_mock()
    assert engine.dispatch(keyval=ord("d"), state=ctrl) is True
    target.on_duplicate_finder.assert_called_once()

    # Ctrl+L
    target.reset_mock()
    assert engine.dispatch(keyval=ord("l"), state=ctrl) is True
    target.on_statistics.assert_called_once()

    # Ctrl+, (0x2c)
    target.reset_mock()
    assert engine.dispatch(keyval=0x2C, state=ctrl) is True
    target.on_preferences.assert_called_once()


def test_text_entry_bypass():
    """Verify hotkeys yield when focused on editable text entry (R-LINUX-KEY-07)."""
    target = MagicMock()
    engine = KeyboardRouterEngine(handler_target=target)

    # Pressing 'm' while is_text_editing=True must NOT trigger culling!
    handled = engine.dispatch(keyval=ord("m"), state=0, is_text_editing=True)
    assert handled is False
    target.on_cull_move.assert_not_called()

    # Pressing Left arrow while is_text_editing=True must yield for cursor navigation
    handled = engine.dispatch(keyval=0xFF51, state=0, is_text_editing=True)
    assert handled is False
    target.on_nav_previous.assert_not_called()


def test_accelerator_modifiers_blocked_from_single_key_actions():
    """Verify Alt and Super modifiers are strictly blocked from triggering single-key hotkeys."""
    target = MagicMock()
    engine = KeyboardRouterEngine(handler_target=target)

    alt = KeyboardRouterEngine.MOD1_MASK
    super_gdk = KeyboardRouterEngine.SUPER_MASK
    super_x11 = KeyboardRouterEngine.MOD4_MASK

    # 1. Alt modifier blocking
    assert engine.dispatch(keyval=0x6D, state=alt) is False
    target.on_cull_move.assert_not_called()

    assert engine.dispatch(keyval=0x63, state=alt) is False
    target.on_cull_copy.assert_not_called()

    assert engine.dispatch(keyval=0xFFFF, state=alt) is False
    target.on_cull_trash.assert_not_called()

    assert engine.dispatch(keyval=0xFF08, state=alt) is False
    target.on_cull_trash.assert_not_called()

    assert engine.dispatch(keyval=0xFF51, state=alt) is False
    target.on_nav_previous.assert_not_called()

    assert engine.dispatch(keyval=0xFF53, state=alt) is False
    target.on_nav_next.assert_not_called()

    assert engine.dispatch(keyval=0x20, state=alt) is False
    target.on_toggle_zoom.assert_not_called()

    assert engine.dispatch(keyval=ord("1"), state=alt) is False
    target.on_set_mode.assert_not_called()

    # 2. Super (Wayland / GDK) modifier blocking
    assert engine.dispatch(keyval=0x6D, state=super_gdk) is False
    target.on_cull_move.assert_not_called()

    assert engine.dispatch(keyval=0xFF53, state=super_gdk) is False
    target.on_nav_next.assert_not_called()

    assert engine.dispatch(keyval=0x6C, state=super_gdk) is False  # Super+L (screen lock)
    target.on_nav_next.assert_not_called()

    # 3. Super (X11 Mod4) modifier blocking
    assert engine.dispatch(keyval=0x6D, state=super_x11) is False
    target.on_cull_move.assert_not_called()

    assert engine.dispatch(keyval=0xFF53, state=super_x11) is False
    target.on_nav_next.assert_not_called()

    # 4. Alt/Super combined with CapsLock or NumLock
    caps = KeyboardRouterEngine.LOCK_MASK
    numlock = KeyboardRouterEngine.MOD2_MASK
    assert engine.dispatch(keyval=0x6D, state=alt | caps) is False
    target.on_cull_move.assert_not_called()

    assert engine.dispatch(keyval=0xFF53, state=super_gdk | numlock) is False
    target.on_nav_next.assert_not_called()

    # 5. Ctrl+Alt or Ctrl+Super multi-modifier combos
    ctrl = KeyboardRouterEngine.CONTROL_MASK
    assert engine.dispatch(keyval=ord("z"), state=ctrl | alt) is False
    target.on_undo.assert_not_called()

    assert engine.dispatch(keyval=ord("o"), state=ctrl | super_gdk) is False
    target.on_open_folder.assert_not_called()


def test_b_toggles_view_order_and_is_free_of_other_bindings():
    """B reaches the sort toggle; nothing else maps to it (R-LINUX-CULL-02)."""
    target = MagicMock()
    engine = KeyboardRouterEngine(handler_target=target)
    for key in (0x62, 0x42):
        target.reset_mock()
        assert engine.dispatch(keyval=key, state=0) is True
        target.on_toggle_sort_order.assert_called_once()
    target.reset_mock()
    assert engine.dispatch(keyval=0x62, state=0, is_text_editing=True) is False
    assert engine.dispatch(keyval=0x62, state=KeyboardRouterEngine.CONTROL_MASK) is False
    target.on_toggle_sort_order.assert_not_called()


def test_tab_is_left_to_focus_traversal_until_slots_can_cycle():
    """The router's own Tab handler cycles nothing, so it must not consume the key (BL-UX-3)."""
    from photo_selector_linux.ui.keyboard_router import KeyboardRouter

    router = KeyboardRouter(window=MagicMock(), viewmodel=MagicMock())
    assert router.engine.dispatch(keyval=0xFF09, state=0) is False


def test_space_presses_a_focused_button_instead_of_toggling_zoom():
    target = MagicMock()
    engine = KeyboardRouterEngine(handler_target=target)

    assert engine.dispatch(keyval=0x20, state=0, focus_on_button=True) is False
    target.on_toggle_zoom.assert_not_called()
    assert engine.dispatch(keyval=0x20, state=0) is True
    target.on_toggle_zoom.assert_called_once()
    # The other single keys still work with a button focused.
    assert engine.dispatch(keyval=0x6D, state=0, focus_on_button=True) is True


def test_real_router_passes_a_focused_button_to_the_engine():
    import pytest

    from photo_selector_linux.ui import keyboard_router as kr

    if not kr.HAS_GI or isinstance(kr.Gtk, MagicMock):
        pytest.skip("needs real Gtk")
    from gi.repository import Gtk

    window = MagicMock()
    window.get_focus.return_value = Gtk.Button()
    router = kr.KeyboardRouter(window=window, viewmodel=MagicMock())
    assert router._on_key_pressed(None, 0x20, 0, 0) is kr.Gdk.EVENT_PROPAGATE
    window.get_focus.return_value = Gtk.Label()
    router.on_toggle_zoom = MagicMock()
    router.engine.target = router
    assert router._on_key_pressed(None, 0x20, 0, 0) is kr.Gdk.EVENT_STOP


def test_every_empty_state_hint_names_a_key_the_router_handles():
    """The pills are generated from EMPTY_STATE_HINTS: each key shown must do something (BL-UX-8)."""
    from photo_selector_linux.ui.keyboard_router import EMPTY_STATE_HINTS

    for keyval, state, keys, meaning in EMPTY_STATE_HINTS:
        target = MagicMock()
        assert KeyboardRouterEngine(handler_target=target).dispatch(keyval=keyval, state=state) is True, keys
        assert meaning and keys


def test_the_empty_state_has_no_tagline_and_builds_its_pills_from_the_table():
    import pytest

    from photo_selector_linux.ui import canvas as canvas_module
    from photo_selector_linux.ui.keyboard_router import EMPTY_STATE_HINTS
    from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel

    if isinstance(canvas_module.Gtk, MagicMock):
        pytest.skip("needs real Gtk")
    view = canvas_module.CanvasView(viewmodel=CullingWorkspaceViewModel())
    labels = []

    def walk(widget):
        from gi.repository import Gtk

        if isinstance(widget, Gtk.Label):
            labels.append(widget.get_label())
        child = widget.get_first_child()
        while child is not None:
            walk(child)
            child = child.get_next_sibling()

    walk(view._create_empty_state())
    assert "Optical RAW & JPEG Triage" not in labels
    for _k, _s, keys, meaning in EMPTY_STATE_HINTS:
        assert f"{keys} {meaning}" in labels


def test_folder_load_moves_focus_off_the_header_button_so_space_zooms(tmp_path):
    """Regression (J-LIN-02): focus stayed on Open, so Space re-opened the picker, not 1:1."""
    import pytest

    from photo_selector_linux.ui import window as window_module

    if isinstance(window_module.Gtk, MagicMock) or not window_module.HAS_GI:
        pytest.skip("needs real Gtk (xvfb-run)")
    from gi.repository import Gtk

    from photo_selector_linux.core.models import CandidatePhoto

    win = window_module.MainWindow()
    win.present()
    win.btn_open.set_action_name(None)  # no Gtk.Application here: the action would disable it
    win.btn_open.set_sensitive(True)
    win.set_focus(win.btn_open)
    assert win.get_focus() is win.btn_open
    win.viewmodel.current_folder = tmp_path
    win.viewmodel.candidates = [CandidatePhoto(primary_path=tmp_path / "a.JPG")]
    win._on_viewmodel_state_changed(win.viewmodel)

    focus = win.get_focus()
    assert not isinstance(focus, Gtk.Button)
    assert focus is win.canvas_view
    win.router.on_toggle_zoom = MagicMock()
    win.router.engine.target = win.router
    assert win.router._on_key_pressed(None, 0x20, 0, 0) is window_module.Gdk.EVENT_STOP
    win.router.on_toggle_zoom.assert_called_once()
    # A button the user Tab-focused deliberately still gets Space.
    win.set_focus(win.btn_open)
    assert win.router._on_key_pressed(None, 0x20, 0, 0) is window_module.Gdk.EVENT_PROPAGATE
    win.destroy()
