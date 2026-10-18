"""Responsive culling canvas supporting 1-Up, 2-Up, and 3-Up Focus Mode.

Adheres to:
- R-LINUX-UI-03: Viewport Canvas Area Allocation (>85% of total window area)
- R-LINUX-UI-04: 1-Up Single View Mode
- R-LINUX-UI-05: 2-Up Side-by-Side Comparison Mode
- R-LINUX-UI-06: 3-Up Focus Mode (One-over-Two)
- R-LINUX-UI-07: BoundarySlotPane Indicators
- R-LINUX-UI-08: 1:1 Actual-Pixel Zoom Toggle
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

try:
    import gi
    gi.require_version("Gtk", "4.0")
    from gi.repository import Gtk
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = None

from photo_selector_linux.ui.boundary_slot import BoundarySlotPane
from photo_selector_linux.ui.info_hud import InfoHUD
from photo_selector_linux.ui.layout import (
    Rect,
    can_fit_1to1_development,
    three_up_geometry,
)
from photo_selector_linux.ui.photo_loader import PhotoTextureLoader
from photo_selector_linux.ui.styling import (
    APP_BRAND_NAME,
    APP_BRAND_TAGLINE,
    APP_ICON_NAME,
    get_candidate_companion_badges,
    get_slot_role_label,
)


class CanvasView(Gtk.Box if HAS_GI else object):
    """Responsive culling canvas managing image viewports and comparison modes."""

    def __init__(self, viewmodel, loader=None):
        self.viewmodel = viewmodel
        self.is_1to1_zoom: bool = False
        self.hud_enabled: bool = True
        self.rendered_slots: List[Dict[str, Any]] = []
        self.viewport_width: int = 1440
        self.viewport_height: int = 810
        self.last_three_up_geometry: Optional[Tuple[Rect, Rect, Rect]] = None
        self.three_up_layout_rects: Dict[str, Rect] = {}
        self.linked_pan: Tuple[float, float] = (0.5, 0.5)
        self._neighbour_1to1_offset: Optional[int] = None
        self._last_index: int = getattr(viewmodel, "current_index", 0)
        self._last_mode: int = getattr(viewmodel, "comparison_mode", 1)
        self.last_prefetch_indices: List[int] = []
        self.hud = InfoHUD(viewmodel=self.viewmodel)
        self._active_image_area = None
        initial_max_mb = getattr(viewmodel, "cache_limit_mb", 512.0)
        initial_blurry = getattr(viewmodel, "blurry_threshold", 35.0)
        initial_sharp = getattr(viewmodel, "sharp_threshold", 70.0)
        if loader is not None:
            self.loader = loader
        else:
            self.loader = (
                PhotoTextureLoader(
                    max_mb=initial_max_mb,
                    blurry_threshold=initial_blurry,
                    sharp_threshold=initial_sharp,
                )
                if HAS_GI
                else None
            )
        self._loaded_folder = None

        if HAS_GI:
            super().__init__(orientation=Gtk.Orientation.VERTICAL)
            self.set_hexpand(True)
            self.set_vexpand(True)
            self.add_css_class("studio-dark-surface")

            self.overlay = Gtk.Overlay()
            self.overlay.set_hexpand(True)
            self.overlay.set_vexpand(True)
            self.append(self.overlay)

            # Container for slot panes
            self.slots_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
            self.slots_box.set_homogeneous(True)
            self.slots_box.set_hexpand(True)
            self.slots_box.set_vexpand(True)
            self.overlay.set_child(self.slots_box)

            # The Info HUD is re-parented into the active slot's image overlay on every render.

        self.viewmodel.connect("state-changed", self._on_state_changed)
        self._render_view()
        self._prefetch_neighbours()

    @property
    def pan_offset(self) -> Tuple[float, float]:
        return self.linked_pan

    def toggle_zoom(self) -> None:
        """Toggle 100% 1:1 pixel zoom across 1-Up, 2-Up, and 3-Up modes (Space) (R-LINUX-UI-08)."""
        self.is_1to1_zoom = not self.is_1to1_zoom
        if not self.is_1to1_zoom:
            self._neighbour_1to1_offset = None
            self.linked_pan = (0.5, 0.5)
            if self.loader is not None:
                self.loader.evict_full_resolution()
        self._render_view()

    def reset_zoom(self) -> None:
        """Reset zoom back to fit-to-window and reclaim full-res buffers (Escape) (R-LINUX-CACHE-03)."""
        if self.is_1to1_zoom:
            self.is_1to1_zoom = False
            self._neighbour_1to1_offset = None
            self.linked_pan = (0.5, 0.5)
            if self.loader is not None:
                self.loader.evict_full_resolution()
            self._render_view()

    def set_linked_pan(self, pan_x: float, pan_y: float) -> None:
        """Synchronize normalized (0.0..1.0) pan offsets across panes in 1:1 mode (U-07b)."""
        cx = max(0.0, min(1.0, float(pan_x)))
        cy = max(0.0, min(1.0, float(pan_y)))
        self.linked_pan = (cx, cy)
        for slot in self.rendered_slots:
            if slot.get("type") == "photo":
                slot["pan_offset"] = self.linked_pan

    def request_neighbour_1to1(
        self,
        slot_offset: int,
        width: int = 6000,
        height: int = 4000,
    ) -> bool:
        """Develop a 3-Up neighbour (-1 or +1) at 1:1 on demand within memory budget (U-07b).

        Ensures at most two full-resolution 1:1 panes (active top + one neighbour) are held at once.
        """
        if not self.is_1to1_zoom or slot_offset not in (-1, 1):
            return False
        candidates = self.viewmodel.candidates
        idx = self.viewmodel.current_index
        target_idx = idx + slot_offset
        if not (0 <= target_idx < len(candidates)):
            return False

        cache_max_bytes = (
            self.loader.cache.max_bytes
            if self.loader is not None
            else int(getattr(self.viewmodel, "cache_limit_mb", 512.0) * 1024 * 1024)
        )
        # Active top pane occupies 1 slot; if another neighbour had 1:1, evict it first so active_1to1_count == 1.
        if self._neighbour_1to1_offset is not None and self._neighbour_1to1_offset != slot_offset:
            self._neighbour_1to1_offset = None
            if self.loader is not None and 0 <= idx < len(candidates):
                self.loader.evict_full_resolution({candidates[idx].primary_path})

        active_count = 1 if self._neighbour_1to1_offset is None else 2
        if self._neighbour_1to1_offset != slot_offset and not can_fit_1to1_development(
            width=width,
            height=height,
            cache_max_bytes=cache_max_bytes,
            active_1to1_count=active_count,
        ):
            return False

        self._neighbour_1to1_offset = slot_offset
        if self.loader is not None and 0 <= idx < len(candidates):
            keep = {candidates[idx].primary_path, candidates[target_idx].primary_path}
            self.loader.evict_full_resolution(keep)
        self._render_view()
        return True

    def toggle_hud(self) -> None:
        """Toggle floating Info HUD visibility (I) (R-LINUX-UI-09)."""
        self.hud_enabled = not self.hud_enabled
        if hasattr(self, "hud"):
            self.hud.set_hud_enabled(self.hud_enabled)

    def _on_state_changed(self, vm) -> None:
        if self.loader is not None:
            self.loader.set_limit_mb(getattr(vm, "cache_limit_mb", 512.0))
            self.loader.blurry_threshold = float(getattr(vm, "blurry_threshold", 35.0))
            self.loader.sharp_threshold = float(getattr(vm, "sharp_threshold", 70.0))

        if vm.current_folder != self._loaded_folder:
            # A new folder invalidates every decoded texture of the previous one.
            self._loaded_folder = vm.current_folder
            self._last_index = 0
            self._neighbour_1to1_offset = None
            if self.loader is not None:
                self.loader.clear()
        elif vm.comparison_mode != self._last_mode or vm.current_index != self._last_index:
            self._neighbour_1to1_offset = None
            if self.loader is not None:
                if self.is_1to1_zoom and vm.current_photo is not None:
                    self.loader.evict_full_resolution({vm.current_photo.primary_path})
                else:
                    self.loader.evict_full_resolution()

        self._last_mode = vm.comparison_mode
        self._render_view()
        self._prefetch_neighbours()

    def _prefetch_neighbours(self) -> None:
        """Prefetch asymmetric sliding window: [i-2..i+3] forward, [i-3..i+2] backward (R-LINUX-CACHE-02)."""
        candidates = self.viewmodel.candidates
        count = len(candidates)
        if count == 0:
            self.last_prefetch_indices = []
            self._last_index = 0
            return

        idx = max(0, min(count - 1, self.viewmodel.current_index))
        moving_forward = idx >= self._last_index
        if moving_forward:
            start = max(0, idx - 2)
            stop = min(count, idx + 4)
        else:
            start = max(0, idx - 3)
            stop = min(count, idx + 3)

        self.last_prefetch_indices = list(range(start, stop))
        self._last_index = idx

        if self.loader is not None:
            keep_paths = [candidates[i].primary_path for i in self.last_prefetch_indices]
            self.loader.evict_outside_window(keep_paths)
            for i in self.last_prefetch_indices:
                if i != idx:
                    self.loader.prefetch(candidates[i])

    def _build_slot_descriptor(
        self,
        candidate,
        is_active: bool,
        slot_offset: int,
        allow_1to1: bool = False,
        position: Optional[str] = None,
        rect: Optional[Rect] = None,
    ) -> Dict[str, Any]:
        role = get_slot_role_label(
            self.viewmodel.comparison_mode,
            slot_offset,
            is_active=is_active,
        )
        badges = get_candidate_companion_badges(candidate)
        desc: Dict[str, Any] = {
            "type": "photo",
            "role": role,
            "is_active": is_active,
            "slot_offset": slot_offset,
            "filename": candidate.filename,
            "badges": badges,
            "zoom_1to1": allow_1to1,
            "pan_offset": self.linked_pan,
            "css_classes": ["card-pane", "accent-border"] if is_active else ["card-pane", "card-pane-inactive"],
        }
        if position is not None:
            desc["position"] = position
        if rect is not None:
            desc["rect"] = rect
        return desc

    def _detach_hud(self) -> None:
        """Unparent the single HUD from the slot that held it before slots are rebuilt."""
        parent = self.hud.get_parent()
        if parent is not None:
            parent.remove_overlay(self.hud)
        self._active_image_area = None

    def _attach_hud(self) -> None:
        """Anchor the HUD inside the active slot's image area (R-LINUX-UI-09)."""
        if self._active_image_area is not None:
            self._active_image_area.add_overlay(self.hud)

    def _render_view(self) -> None:
        mode = self.viewmodel.comparison_mode
        candidates = self.viewmodel.candidates
        idx = self.viewmodel.current_index
        count = len(candidates)
        self.rendered_slots = []

        if count == 0:
            self.hud.update_photo(None)
            if HAS_GI:
                self._detach_hud()
                while child := self.slots_box.get_first_child():
                    self.slots_box.remove(child)
                self.slots_box.append(self._create_empty_state())
            return

        self.hud.update_photo(candidates[idx])

        if mode == 1:
            self.slots_box.set_homogeneous(True) if HAS_GI else None
            desc = self._build_slot_descriptor(
                candidates[idx],
                is_active=True,
                slot_offset=0,
                allow_1to1=self.is_1to1_zoom,
            )
            self.rendered_slots.append(desc)
        elif mode == 2:
            self.slots_box.set_homogeneous(True) if HAS_GI else None
            self.rendered_slots.append(
                self._build_slot_descriptor(
                    candidates[idx],
                    is_active=True,
                    slot_offset=0,
                    allow_1to1=self.is_1to1_zoom,
                )
            )
            if idx + 1 < count:
                self.rendered_slots.append(
                    self._build_slot_descriptor(
                        candidates[idx + 1],
                        is_active=False,
                        slot_offset=1,
                        allow_1to1=self.is_1to1_zoom and self._neighbour_1to1_offset == 1,
                    )
                )
            else:
                self.rendered_slots.append({"type": "boundary", "slot_type": "last"})
        elif mode == 3:
            top_rect, bl_rect, br_rect = three_up_geometry(
                self.viewport_width,
                self.viewport_height,
            )
            self.last_three_up_geometry = (top_rect, bl_rect, br_rect)
            self.three_up_layout_rects = {
                "top": top_rect,
                "bottom_left": bl_rect,
                "bottom_right": br_rect,
            }

            if idx > 0:
                self.rendered_slots.append(
                    self._build_slot_descriptor(
                        candidates[idx - 1],
                        is_active=False,
                        slot_offset=-1,
                        allow_1to1=self.is_1to1_zoom and self._neighbour_1to1_offset == -1,
                        position="bottom-left",
                        rect=bl_rect,
                    )
                )
            else:
                self.rendered_slots.append({"type": "boundary", "slot_type": "first"})

            self.rendered_slots.append(
                self._build_slot_descriptor(
                    candidates[idx],
                    is_active=True,
                    slot_offset=0,
                    allow_1to1=self.is_1to1_zoom,
                    position="top",
                    rect=top_rect,
                )
            )

            if idx < count - 1:
                self.rendered_slots.append(
                    self._build_slot_descriptor(
                        candidates[idx + 1],
                        is_active=False,
                        slot_offset=1,
                        allow_1to1=self.is_1to1_zoom and self._neighbour_1to1_offset == 1,
                        position="bottom-right",
                        rect=br_rect,
                    )
                )
            else:
                self.rendered_slots.append({"type": "boundary", "slot_type": "last"})

        if not HAS_GI:
            return

        # Clear existing GTK slot children
        self._detach_hud()
        while child := self.slots_box.get_first_child():
            self.slots_box.remove(child)

        if mode == 1:
            slot = self._create_photo_slot(
                candidates[idx],
                is_active=True,
                slot_offset=0,
                allow_1to1=self.is_1to1_zoom,
            )
            self.slots_box.append(slot)

        elif mode == 2:
            champ_slot = self._create_photo_slot(
                candidates[idx],
                is_active=True,
                slot_offset=0,
                allow_1to1=self.is_1to1_zoom,
            )
            self.slots_box.append(champ_slot)

            if idx + 1 < count:
                challenger_slot = self._create_photo_slot(
                    candidates[idx + 1],
                    is_active=False,
                    slot_offset=1,
                    allow_1to1=self.is_1to1_zoom and self._neighbour_1to1_offset == 1,
                )
                self.slots_box.append(challenger_slot)
            else:
                end_slot = BoundarySlotPane(slot_type="last")
                end_slot.set_name("slot_next")
                self.slots_box.append(end_slot)

        elif mode == 3:
            # One-over-two layout (UD-4): a 60/40 split of the real canvas height. The grid has
            # five homogeneous rows; the active photo spans 2 columns x 3 rows, each neighbour
            # 1 column x 2 rows. No fixed size request, so the split follows the window.
            three_up_grid = Gtk.Grid()
            three_up_grid.set_row_spacing(12)
            three_up_grid.set_column_spacing(12)
            three_up_grid.set_row_homogeneous(True)
            three_up_grid.set_column_homogeneous(True)
            three_up_grid.set_hexpand(True)
            three_up_grid.set_vexpand(True)

            top_slot = self._create_photo_slot(
                candidates[idx],
                is_active=True,
                slot_offset=0,
                allow_1to1=self.is_1to1_zoom,
            )
            three_up_grid.attach(top_slot, 0, 0, 2, 3)

            if idx > 0:
                left_slot = self._create_photo_slot(
                    candidates[idx - 1],
                    is_active=False,
                    slot_offset=-1,
                    allow_1to1=self.is_1to1_zoom and self._neighbour_1to1_offset == -1,
                )
            else:
                left_slot = BoundarySlotPane(slot_type="first")
                left_slot.set_name("slot_previous")
            three_up_grid.attach(left_slot, 0, 3, 1, 2)

            if idx < count - 1:
                right_slot = self._create_photo_slot(
                    candidates[idx + 1],
                    is_active=False,
                    slot_offset=1,
                    allow_1to1=self.is_1to1_zoom and self._neighbour_1to1_offset == 1,
                )
            else:
                right_slot = BoundarySlotPane(slot_type="last")
                right_slot.set_name("slot_next")
            three_up_grid.attach(right_slot, 1, 3, 1, 2)

            self.slots_box.append(three_up_grid)

        self._attach_hud()

    def _create_empty_state(self):
        hero = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        hero.set_halign(Gtk.Align.CENTER)
        hero.set_valign(Gtk.Align.CENTER)
        hero.add_css_class("empty-state-hero")

        icon = Gtk.Image.new_from_icon_name(APP_ICON_NAME)
        icon.set_pixel_size(72)
        hero.append(icon)

        title = Gtk.Label(label=APP_BRAND_NAME)
        title.add_css_class("empty-state-title")
        hero.append(title)

        tagline = Gtk.Label(label=APP_BRAND_TAGLINE)
        tagline.add_css_class("empty-state-subtitle")
        hero.append(tagline)

        prompt = Gtk.Label(label="No photographs loaded. Press Ctrl+O to open a directory or SD card.")
        prompt.add_css_class("optical-strip-secondary")
        hero.append(prompt)

        hints_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        hints_row.set_halign(Gtk.Align.CENTER)
        for hint in (
            "Ctrl+O Open Folder",
            "1 / 2 / 3 View Modes",
            "M Move · C Copy · Del Trash",
            "I Optical HUD",
        ):
            pill = Gtk.Label(label=hint)
            pill.add_css_class("hotkey-hint-pill")
            hints_row.append(pill)
        hero.append(hints_row)

        return hero

    def _create_photo_slot(
        self,
        candidate,
        is_active: bool = False,
        slot_offset: int = 0,
        allow_1to1: bool = False,
    ):
        desc = self._build_slot_descriptor(
            candidate,
            is_active=is_active,
            slot_offset=slot_offset,
            allow_1to1=allow_1to1,
        )
        frame = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        slot_name = (
            "slot_previous" if slot_offset < 0 else "slot_current" if slot_offset == 0 else "slot_next"
        )
        frame.set_name(slot_name)
        for cls_name in desc["css_classes"]:
            frame.add_css_class(cls_name)

        # Header badge row (Role pill + 1:1 Zoom pill + Companion badges)
        badge_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        badge_box.set_halign(Gtk.Align.CENTER)

        role_pill = Gtk.Label(label=desc["role"])
        role_pill.add_css_class("active-pill" if is_active else "slot-role-pill")
        badge_box.append(role_pill)

        if allow_1to1:
            zoom_pill = Gtk.Label(label="1:1 PIXEL ZOOM")
            zoom_pill.add_css_class("zoom-indicator-pill")
            badge_box.append(zoom_pill)

        for badge_text, badge_css in desc["badges"]:
            comp_pill = Gtk.Label(label=badge_text)
            comp_pill.add_css_class(badge_css)
            badge_box.append(comp_pill)

        frame.append(badge_box)

        # Image view — decoded off the main loop (RAW via its embedded preview)
        picture = Gtk.Picture()
        picture.set_hexpand(True)
        picture.set_vexpand(True)
        picture.set_can_shrink(True)
        picture.set_keep_aspect_ratio(True)
        picture.set_name(f"{slot_name}_image")

        image_area = Gtk.Overlay()
        image_area.set_hexpand(True)
        image_area.set_vexpand(True)
        if is_active:
            self._active_image_area = image_area
        status = Gtk.Spinner()
        status.set_halign(Gtk.Align.CENTER)
        status.set_valign(Gtk.Align.CENTER)
        status.set_size_request(32, 32)
        status.start()
        image_area.add_overlay(status)

        if allow_1to1:
            scrolled = Gtk.ScrolledWindow()
            scrolled.set_hexpand(True)
            scrolled.set_vexpand(True)
            picture.set_can_shrink(False)
            scrolled.set_child(picture)
            image_area.set_child(scrolled)
        else:
            image_area.set_child(picture)
        frame.append(image_area)

        # Footer row: File name label + compact score badge (if computed)
        footer_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        footer_box.set_halign(Gtk.Align.CENTER)

        lbl = Gtk.Label(label=candidate.filename)
        lbl.set_ellipsize(3)  # PANGO_ELLIPSIZE_MIDDLE
        lbl.set_max_width_chars(36)
        lbl.add_css_class("optical-strip-secondary")
        footer_box.append(lbl)

        self._append_score_badge(footer_box, candidate)
        frame.append(footer_box)

        self.loader.request(
            candidate,
            lambda texture: self._on_texture_ready(candidate, picture, image_area, status, footer_box, texture),
            full_size=allow_1to1,
        )

        return frame

    @staticmethod
    def _append_score_badge(footer_box, candidate) -> None:
        """Compact focus score pill; scores are computed during the first decode."""
        if candidate.scores is None or footer_box.has_css_class("has-score-badge"):
            return
        cat = candidate.scores.focus_category.value
        score_pill = Gtk.Label(label=f"{candidate.scores.formatted_sharpness}")
        score_pill.add_css_class(f"score-badge-{cat}")
        footer_box.append(score_pill)
        footer_box.add_css_class("has-score-badge")

    def _on_texture_ready(self, candidate, picture, image_area, status, footer_box, texture) -> None:
        status.stop()
        image_area.remove_overlay(status)
        if texture is not None:
            picture.set_paintable(texture)
        else:
            unavailable = Gtk.Label(label=f"Cannot display {candidate.filename}")
            unavailable.add_css_class("optical-strip-secondary")
            unavailable.set_halign(Gtk.Align.CENTER)
            unavailable.set_valign(Gtk.Align.CENTER)
            image_area.add_overlay(unavailable)
        self._append_score_badge(footer_box, candidate)
        # EXIF and scores come with the first decode; refresh the HUD once they are known.
        if candidate is self.viewmodel.current_photo:
            self.hud.update_photo(candidate)
