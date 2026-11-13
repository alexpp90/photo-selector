"""The order button's panel and its sweep spinner (plan 1.7).

The panel is the View order radio group plus the "Stop at sharpness" number (0 = off) that used to
live in Preferences > Scoring; the stored key ``stop_sharpness_threshold`` is unchanged. While the
sharpness sweep scores photos, a spinner turns inside the button and its tooltip counts them.
"""

from __future__ import annotations

try:
    import gi
    gi.require_version("Gtk", "4.0")
    from gi.repository import Gtk, Gio, GLib
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = Gio = GLib = None

#: Tooltip of the number: what it does and what 0 means.
STOP_SHARPNESS_TOOLTIP = (
    "When sorting by lowest sharpness first, stop at the first photo at or above this score. "
    "0 turns it off."
)
STOP_AT_TITLE = "Stop at sharpness"
STOP_AT_SLOT = "stop_at"


def build_order_popover(radio_section, settings):
    """A ``PopoverMenu`` of the order radio group with the stop-at number below it.

    Returns ``(popover, spin)``. The spin writes ``settings.stop_sharpness_threshold`` (None for 0)
    and the settings notify the view model, as Preferences did.
    """
    menu = Gio.Menu()
    menu.append_section(None, radio_section)
    stop_section = Gio.Menu()
    item = Gio.MenuItem.new(None, None)
    item.set_attribute_value("custom", GLib.Variant.new_string(STOP_AT_SLOT))
    stop_section.append_item(item)
    menu.append_section(None, stop_section)

    popover = Gtk.PopoverMenu.new_from_model(menu)
    row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
    row.set_margin_start(12)
    row.set_margin_end(12)
    row.set_margin_top(6)
    row.set_margin_bottom(6)
    label = Gtk.Label(label=STOP_AT_TITLE)
    label.set_hexpand(True)
    label.set_xalign(0.0)
    row.append(label)
    spin = Gtk.SpinButton.new_with_range(0.0, 100.0, 1.0)
    spin.set_name("stop_at_sharpness")
    spin.set_value((settings.stop_sharpness_threshold or 0.0) if settings is not None else 0.0)
    spin.set_tooltip_text(STOP_SHARPNESS_TOOLTIP)
    spin.update_property([Gtk.AccessibleProperty.LABEL], [STOP_AT_TITLE])
    if settings is not None:
        spin.connect("value-changed", lambda s: set_stop_sharpness(settings, s.get_value()))
    row.append(spin)
    popover.add_child(row, STOP_AT_SLOT)
    return popover, spin


def set_stop_sharpness(settings, value: float) -> None:
    """0 (or less) turns the stop off; anything else is the score the sweep stops at."""
    settings.update(stop_sharpness_threshold=float(value) if value > 0 else None)


def sweep_tooltip(done: int, total: int) -> str:
    return f"Scoring {done} of {total}"
