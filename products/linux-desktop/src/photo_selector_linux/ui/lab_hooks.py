"""In-app laboratory checkpoint recorder and widget tree serializer.

When PST_LAB=1 is set in the environment, the application registers a
`lab-checkpoint` Gio.SimpleAction taking a string parameter. Activating the
action writes a four-file checkpoint quartet into PST_LAB_ARTIFACTS (default:
a private per-user directory under $XDG_RUNTIME_DIR). The screenshot shows
only the application window, never the rest of the desktop:
- NN_<name>.png
- NN_<name>.tree.json
- NN_<name>.state.json
- NN_<name>.timing.json
"""

from __future__ import annotations

import json
import os
import re
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

try:
    import gi
    gi.require_version("Gtk", "4.0")
    from gi.repository import Gtk
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = None


def format_checkpoint_stem(index: int, name: str) -> str:
    """Format a 1-based checkpoint index and name into `NN_<sanitized_name>`."""
    clean = re.sub(r"[^A-Za-z0-9_-]", "-", (name or "").strip()).strip("-")
    if not clean:
        clean = "checkpoint"
    return f"{index:02d}_{clean}"


def is_custom_widget_name(widget_name: Optional[str], type_name: Optional[str]) -> bool:
    """Return True when widget_name is explicitly assigned rather than the GTK class default."""
    if not widget_name:
        return False
    if type_name and widget_name == type_name:
        return False
    # Common GTK4/Adwaita default class names if type_name was stripped of prefix
    if widget_name in {
        "GtkBox",
        "GtkLabel",
        "GtkButton",
        "GtkImage",
        "GtkPicture",
        "GtkOverlay",
        "GtkActionBar",
        "GtkSpinner",
        "GtkMenuButton",
        "GtkScrolledWindow",
        "AdwApplicationWindow",
        "AdwHeaderBar",
        "AdwToolbarView",
        "AdwToastOverlay",
    }:
        return False
    return True


def infer_widget_role(type_name: str, widget_name: str) -> str:
    """Map a GTK widget class and custom widget name to the normalised tree schema role."""
    t_lower = (type_name or "").lower()
    w_lower = (widget_name or "").lower()
    if "button" in t_lower or w_lower.startswith("btn_"):
        return "button"
    if "picture" in t_lower or w_lower.endswith("_image") or w_lower == "single_image_viewport":
        return "image"
    if "label" in t_lower or w_lower == "info_hud" or w_lower.startswith("lbl_"):
        return "text"
    return "container"


def build_tree_from_records(
    records: List[Dict[str, Any]],
    window_width: int,
    window_height: int,
    scale: float = 1.0,
) -> Dict[str, Any]:
    """Build a normalised tree.schema.json dictionary from extracted widget records."""
    known_ids = {str(r["id"]) for r in records if r.get("id")}
    nodes: List[Dict[str, Any]] = []
    for rec in records:
        node_id = str(rec["id"])
        parent_id = rec.get("parent")
        if parent_id not in known_ids or parent_id == node_id:
            parent_id = None
        bounds_raw = rec.get("bounds", [0, 0, 0, 0])
        bounds = [
            int(round(float(bounds_raw[0]))),
            int(round(float(bounds_raw[1]))),
            max(0, int(round(float(bounds_raw[2])))),
            max(0, int(round(float(bounds_raw[3])))),
        ]
        role = str(rec.get("role") or infer_widget_role(str(rec.get("type_name", "")), node_id))
        name = str(rec.get("name") if rec.get("name") is not None else node_id)
        if not name and bool(rec.get("interactive", role == "button")):
            name = node_id
        nodes.append(
            {
                "id": node_id,
                "parent": parent_id,
                "role": role,
                "name": name,
                "bounds": bounds,
                "interactive": bool(rec.get("interactive", role == "button")),
                "visible": bool(rec.get("visible", True)),
                "overlay": bool(rec.get("overlay", node_id == "info_hud")),
                "text": str(rec.get("text", "")),
                "clipped": rec.get("clipped", None),
            }
        )
    return {
        "platform": "linux",
        "window": {
            "w": max(1, int(window_width)),
            "h": max(1, int(window_height)),
            "unit": "px",
            "scale": float(scale) if scale > 0 else 1.0,
        },
        "nodes": nodes,
    }


def extract_state_dict(viewmodel: Any) -> Dict[str, Any]:
    """Extract a serializable workspace state snapshot from CullingWorkspaceViewModel."""
    if viewmodel is None:
        return {
            "current_index": 0,
            "total": 0,
            "tally": {"selected": 0, "trashed": 0, "remaining": 0},
            "mode": 1,
        }
    candidates = getattr(viewmodel, "candidates", None)
    if candidates is None:
        candidates = getattr(viewmodel, "assets", [])
    total = int(getattr(viewmodel, "candidate_count", len(candidates)))
    return {
        "current_index": int(getattr(viewmodel, "current_index", 0)),
        "total": total,
        "tally": {
            "selected": int(getattr(viewmodel, "selected_count", 0)),
            "trashed": int(getattr(viewmodel, "trashed_count", 0)),
            "remaining": int(getattr(viewmodel, "remaining_count", 0)),
        },
        "mode": int(getattr(viewmodel, "comparison_mode", 1)),
    }


class LabCheckpointRecorder:
    """Stateful checkpoint recorder attached to an application instance when PST_LAB=1."""

    def __init__(self, artifacts_dir: Optional[Path] = None, clock=time.monotonic):
        self._artifacts_dir = artifacts_dir
        self._clock = clock
        self._start_t = self._clock()
        self._last_t = self._start_t
        self._counter = 0

    @property
    def artifacts_dir(self) -> Path:
        if self._artifacts_dir is not None:
            return self._artifacts_dir
        env_dir = os.environ.get("PST_LAB_ARTIFACTS")
        if env_dir:
            return Path(env_dir)
        runtime = os.environ.get("XDG_RUNTIME_DIR")
        if runtime:
            return Path(runtime) / "pstlab-artifacts"
        # No per-user runtime directory: a directory of our own, owner-only, instead of a
        # predictable name in a world-writable /tmp.
        return Path(tempfile.gettempdir()) / f"pstlab-artifacts-{os.getuid()}"

    def record_checkpoint(self, window: Any, name: str) -> Dict[str, Path]:
        """Write NN_<name>.{png,tree.json,state.json,timing.json} to artifacts_dir."""
        self._counter += 1
        now = self._clock()
        t_ms = max(0, int(round((now - self._start_t) * 1000.0)))
        step_ms = max(0, int(round((now - self._last_t) * 1000.0)))
        self._last_t = now

        out_dir = self.artifacts_dir
        out_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        stem = format_checkpoint_stem(self._counter, name)

        png_path = out_dir / f"{stem}.png"
        tree_path = out_dir / f"{stem}.tree.json"
        state_path = out_dir / f"{stem}.state.json"
        timing_path = out_dir / f"{stem}.timing.json"

        self._write_screenshot(window, png_path)
        tree_dict = self._extract_window_tree(window)
        tree_path.write_text(json.dumps(tree_dict, indent=2), encoding="utf-8")

        vm = getattr(window, "viewmodel", None) if window is not None else None
        state_dict = extract_state_dict(vm)
        state_path.write_text(json.dumps(state_dict, indent=2), encoding="utf-8")

        timing_dict = {
            "checkpoint": name,
            "t_ms": t_ms,
            "step_ms": step_ms,
        }
        timing_path.write_text(json.dumps(timing_dict, indent=2), encoding="utf-8")

        return {
            "png": png_path,
            "tree": tree_path,
            "state": state_path,
            "timing": timing_path,
        }

    def _write_screenshot(self, window: Any, png_path: Path) -> None:
        """Capture the application window as a PNG via Gtk.WidgetPaintable.

        There is deliberately no fallback to a root-window screenshot: that would capture every
        other window on the desktop. When the snapshot fails the PNG is simply missing and the
        lab reports a missing screenshot.
        """
        if HAS_GI and window is not None:
            try:
                width = max(1, int(window.get_width() or 1920))
                height = max(1, int(window.get_height() or 1080))
                paintable = Gtk.WidgetPaintable.new(window)
                snapshot = Gtk.Snapshot.new()
                paintable.snapshot(snapshot, float(width), float(height))
                node = snapshot.to_node()
                native = window.get_native() if hasattr(window, "get_native") else None
                renderer = native.get_renderer() if native is not None else None
                if node is not None and renderer is not None:
                    texture = renderer.render_texture(node, None)
                    if texture is not None:
                        texture.save_to_png(str(png_path))
                        if png_path.exists() and png_path.stat().st_size > 0:
                            return
            except Exception:
                pass

    def _extract_window_tree(self, window: Any) -> Dict[str, Any]:
        """Walk the GTK4 widget hierarchy and return a normalised tree.schema.json dict."""
        if window is None:
            return build_tree_from_records([], 1920, 1080, 1.0)

        win_w = int(getattr(window, "get_width", lambda: 1920)() or 1920)
        win_h = int(getattr(window, "get_height", lambda: 1080)() or 1080)
        scale_fn = getattr(window, "get_scale_factor", lambda: 1)
        scale = float(scale_fn() or 1.0)

        records: List[Dict[str, Any]] = []
        self._walk_widget(window, root_window=window, parent_custom_id=None, out=records)
        return build_tree_from_records(records, win_w, win_h, scale)

    def _walk_widget(
        self,
        widget: Any,
        root_window: Any,
        parent_custom_id: Optional[str],
        out: List[Dict[str, Any]],
    ) -> None:
        if widget is None:
            return

        widget_name = getattr(widget, "get_name", lambda: "")() or ""
        gtype = getattr(widget, "__gtype__", None)
        type_name = getattr(gtype, "name", widget.__class__.__name__) if gtype else widget.__class__.__name__

        current_parent_id = parent_custom_id
        if widget is not root_window and is_custom_widget_name(widget_name, type_name):
            bounds = [0.0, 0.0, 0.0, 0.0]
            compute_bounds = getattr(widget, "compute_bounds", None)
            if callable(compute_bounds):
                try:
                    res = compute_bounds(root_window)
                    if isinstance(res, tuple) and len(res) == 2:
                        ok, rect = res
                        if ok and rect is not None:
                            bounds = [
                                float(rect.get_x()),
                                float(rect.get_y()),
                                float(rect.get_width()),
                                float(rect.get_height()),
                            ]
                except Exception:
                    pass

            role = infer_widget_role(type_name, widget_name)
            text_val = ""
            get_label = getattr(widget, "get_label", None)
            if callable(get_label):
                try:
                    lbl = get_label()
                    if lbl:
                        text_val = str(lbl)
                except Exception:
                    pass

            tooltip_val = ""
            get_tooltip = getattr(widget, "get_tooltip_text", None)
            if callable(get_tooltip):
                try:
                    tt = get_tooltip()
                    if tt:
                        tooltip_val = str(tt)
                except Exception:
                    pass

            clipped: Optional[bool] = None
            get_layout = getattr(widget, "get_layout", None)
            if callable(get_layout):
                try:
                    layout = get_layout()
                    if layout is not None and hasattr(layout, "is_ellipsized"):
                        clipped = bool(layout.is_ellipsized())
                except Exception:
                    pass

            visible = True
            get_mapped = getattr(widget, "get_mapped", None)
            if callable(get_mapped):
                try:
                    visible = bool(get_mapped())
                except Exception:
                    pass

            display_name = text_val or tooltip_val or widget_name
            out.append(
                {
                    "id": widget_name,
                    "parent": parent_custom_id,
                    "type_name": type_name,
                    "role": role,
                    "name": display_name,
                    "bounds": bounds,
                    "interactive": role == "button",
                    "visible": visible,
                    "overlay": widget_name == "info_hud",
                    "text": text_val,
                    "clipped": clipped,
                }
            )
            current_parent_id = widget_name

        get_first_child = getattr(widget, "get_first_child", None)
        if callable(get_first_child):
            child = get_first_child()
            while child is not None:
                self._walk_widget(child, root_window, current_parent_id, out)
                get_next_sibling = getattr(child, "get_next_sibling", None)
                child = get_next_sibling() if callable(get_next_sibling) else None
