"""The canvas when there is no photo to show: start, reading, nothing to cull, done (plan 1.1, 1.2).

Each state is one hero card with a 72 px icon, one title and at most one line, named
``canvas_state_<state>`` for the lab tree. The texts are plain functions so a test can read them
without a display. Done says what happened (counts, the full destination path, whether the card
may be formatted); it appears after the last decision and goes on Undo or a new folder.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

try:
    import gi
    gi.require_version("Gtk", "4.0")
    from gi.repository import Gtk
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = None

from photo_selector_linux.core.destination import display_path
from photo_selector_linux.core.volumes import VERDICT_SAFE, VERDICT_SAME_VOLUME
from photo_selector_linux.ui.keyboard_router import EMPTY_STATE_HINTS
from photo_selector_linux.ui.styling import APP_BRAND_NAME, APP_ICON_NAME

SAFE_TO_FORMAT_TEXT = "Safe to format"
SAME_VOLUME_TEXT = "Selection is on this card"


def state_texts(vm) -> Dict[str, Any]:
    """Title, optional line and (for done) the cells of the state ``vm.canvas_state`` names."""
    state = vm.canvas_state
    folder = vm.current_folder_name or (vm.current_folder.name if vm.current_folder else "")
    if state == "start":
        return {"title": APP_BRAND_NAME, "line": "Drop a folder"}
    if state == "reading":
        return {"title": f"Reading {folder}", "line": ""}
    if state == "unreadable":
        return {"title": "Can't read folder", "line": folder}
    if state == "empty":
        return {"title": "No photos", "line": folder}
    summary = vm.card_summary()
    cells: List[Tuple[int, str]] = [(summary["kept"], "Selected")]
    if summary["trashed"]:
        cells.append((summary["trashed"], "Trashed"))
    verdict = summary["verdict"]
    return {
        "title": "Done ✓",
        "line": "",
        "cells": [(n, word) for n, word in cells if n or word == "Selected"],
        "path": summary["selection_path"],
        "verdict_text": SAFE_TO_FORMAT_TEXT if verdict == VERDICT_SAFE
        else SAME_VOLUME_TEXT if verdict == VERDICT_SAME_VOLUME else "",
    }


def announcement(vm) -> str:
    """What a screen reader hears when the done screen appears."""
    summary = vm.card_summary()
    text = f"Done. {summary['kept']} selected, {summary['trashed']} trashed."
    return text + (" Safe to format." if summary["verdict"] == VERDICT_SAFE else "")


def recent_folders(vm) -> List[Path]:
    """The recent folders the start card lists (at most five), newest first."""
    settings = getattr(vm, "settings", None)
    return [Path(f) for f in getattr(settings, "recent_folders", [])] if settings is not None else []


def state_key(vm) -> Tuple:
    """Changes exactly when the hero must be rebuilt."""
    texts = state_texts(vm)
    return (vm.canvas_state, texts["title"], texts["line"], tuple(texts.get("cells", ())),
            str(texts.get("path")), texts.get("verdict_text"),
            tuple(str(p) for p in recent_folders(vm)) if vm.canvas_state == "start" else ())


def build_state_hero(
    vm,
    on_open_folder: Optional[Callable[[], None]] = None,
    on_show_in_files: Optional[Callable[[], None]] = None,
    on_open_recent: Optional[Callable[[Path], None]] = None,
):
    """The hero widget for the current state."""
    state = vm.canvas_state
    texts = state_texts(vm)
    hero = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
    hero.set_name(f"canvas_state_{state}")
    hero.set_halign(Gtk.Align.CENTER)
    hero.set_valign(Gtk.Align.CENTER)
    hero.add_css_class("empty-state-hero")

    if state == "reading":
        spinner = Gtk.Spinner()
        spinner.set_size_request(72, 72)
        spinner.start()
        hero.append(spinner)
    else:
        icon = Gtk.Image.new_from_icon_name(
            APP_ICON_NAME if state == "start" else "emblem-ok-symbolic" if state == "done"
            else "dialog-warning-symbolic" if state == "unreadable" else "folder-symbolic"
        )
        icon.set_pixel_size(72)
        hero.append(icon)

    title = Gtk.Label(label=texts["title"])
    title.add_css_class("empty-state-title")
    hero.append(title)
    if texts["line"]:
        line = Gtk.Label(label=texts["line"])
        line.add_css_class("optical-strip-secondary")
        line.set_ellipsize(3)
        line.set_max_width_chars(48)
        hero.append(line)

    if state == "done":
        _append_done(hero, texts, on_show_in_files, on_open_folder)
    elif state in ("start", "empty", "unreadable") and on_open_folder is not None:
        hero.append(_open_button(on_open_folder, suggested=state == "start"))
    if state == "start":
        hints_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        hints_row.set_halign(Gtk.Align.CENTER)
        for _keyval, _state, keys, meaning in EMPTY_STATE_HINTS:
            pill = Gtk.Label(label=f"{keys} {meaning}")
            pill.add_css_class("hotkey-hint-pill")
            hints_row.append(pill)
        hero.append(hints_row)
        if on_open_recent is not None:
            _append_recent(hero, recent_folders(vm), on_open_recent)
    return hero


def _append_recent(hero, folders: List[Path], on_open_recent: Callable[[Path], None]) -> None:
    """Up to five recent folders: name, parent path dimmed; a folder that is gone says "Missing"."""
    if not folders:
        return
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
    box.set_name("recent_folders")
    for folder in folders:
        exists = folder.is_dir()
        row = Gtk.Button()
        row.add_css_class("flat")
        row.set_sensitive(exists)
        row.set_tooltip_text(str(folder))
        row.update_property([Gtk.AccessibleProperty.LABEL], [f"Recent folder: {folder.name}"])
        line = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        line.append(Gtk.Image.new_from_icon_name("folder-symbolic"))
        name = Gtk.Label(label=folder.name or str(folder))
        name.set_ellipsize(2)
        name.set_max_width_chars(24)
        line.append(name)
        detail = Gtk.Label(label="Missing" if not exists else display_path(folder.parent))
        detail.add_css_class("dim-label")
        detail.set_ellipsize(2)
        detail.set_max_width_chars(32)
        line.append(detail)
        row.set_child(line)
        row.connect("clicked", lambda _b, f=folder: on_open_recent(f))
        box.append(row)
    hero.append(box)


def _open_button(callback: Callable[[], None], suggested: bool):
    button = Gtk.Button(label="Open Folder…")
    button.set_name("btn_state_open_folder")
    button.set_tooltip_text("Open Folder (Ctrl+O)")
    button.set_halign(Gtk.Align.CENTER)
    button.set_focus_on_click(False)
    if suggested:
        button.add_css_class("suggested-action")
    button.connect("clicked", lambda _b: callback())
    return button


def _append_done(hero, texts, on_show_in_files, on_open_folder) -> None:
    cells = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=18)
    cells.set_halign(Gtk.Align.CENTER)
    for number, word in texts["cells"]:
        cell = Gtk.Label(label=f"{number} {word}")
        cell.add_css_class("optical-strip-secondary")
        cells.append(cell)
    hero.append(cells)

    path = texts["path"]
    if path is not None:
        shown = display_path(Path(path))
        destination = Gtk.Label(label=shown)
        destination.set_name("done_destination")
        destination.add_css_class("monospace")
        destination.set_selectable(True)
        destination.set_ellipsize(2)  # Pango.EllipsizeMode.MIDDLE
        destination.set_max_width_chars(48)
        destination.set_tooltip_text(str(path))
        hero.append(destination)
    if texts["verdict_text"]:
        verdict = Gtk.Label(label=texts["verdict_text"])
        verdict.set_name("done_verdict")
        verdict.add_css_class("optical-strip-secondary")
        hero.append(verdict)

    buttons = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
    buttons.set_halign(Gtk.Align.CENTER)
    if on_show_in_files is not None and path is not None:
        show = Gtk.Button(label="Show in Files")
        show.set_name("btn_state_show_in_files")
        show.add_css_class("suggested-action")
        show.set_focus_on_click(False)
        show.connect("clicked", lambda _b: on_show_in_files())
        buttons.append(show)
    if on_open_folder is not None:
        buttons.append(_open_button(on_open_folder, suggested=False))
    hero.append(buttons)
