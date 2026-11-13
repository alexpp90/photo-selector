"""Floating translucent metadata and optical quality score HUD.

Adheres to:
- R-LINUX-UI-09: Floating Translucent Metadata & Score HUD with normalized goodness bar
- R-LINUX-SCORE-05: Metric Omission Rule
"""

from __future__ import annotations

from typing import List, Optional, Tuple

try:
    import gi
    gi.require_version("Gtk", "4.0")
    from gi.repository import Gtk
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = None

from photo_selector_linux.core.bursts import burst_spoken, burst_text
from photo_selector_linux.core.rating import REJECTED, spoken as rating_spoken, stars
from photo_selector_linux.core.models import CandidatePhoto
from photo_selector_linux.ui.sharp_outline import area_text
from photo_selector_linux.ui.styling import (
    compute_sharpness_goodness,
    get_candidate_companion_badges,
)


#: What the score is (plan 2.3a): the colour of the chip carries the band, the number the measure.
SCORE_TOOLTIP = (
    "Sharpness of the centre of the frame. Compare frames of one burst, not different scenes. "
    "The sharpest of nine areas is named below and outlined on the photo."
)


STAR_ICON = "starred-symbolic"
NO_STAR_ICON = "non-starred-symbolic"
REJECTED_ICON = "action-unavailable-symbolic"
RATING_TOOLTIP = "Rating from the file"
BURST_ICON = "view-continuous-symbolic"
BURST_ICON_FALLBACK = "view-list-symbolic"
COPIED_ICON = "edit-copy-symbolic"
COPIED_TEXT = "Copied to Selection"


def build_copied_glyph():
    """The mark of a copied (decided) photo: ``edit-copy-symbolic`` 16 px, spoken "Copied to Selection"."""
    glyph = Gtk.Image.new_from_icon_name(COPIED_ICON)
    glyph.set_pixel_size(16)
    glyph.set_name("copied_mark")
    glyph.set_tooltip_text(COPIED_TEXT)
    glyph.update_property([Gtk.AccessibleProperty.LABEL], [COPIED_TEXT])
    return glyph


class InfoHUD(Gtk.Box if HAS_GI else object):
    """Floating translucent card displaying optical EXIF strip and quality metrics."""

    def __init__(self, viewmodel=None):
        self.viewmodel = viewmodel
        self.hud_enabled: bool = True
        self.is_hud_visible: bool = False
        self._current_photo: Optional[CandidatePhoto] = None
        self.filename_text: str = ""
        self.burst_text: str = ""
        self.rating_text: str = ""
        self.is_copied: bool = False
        self.companion_badges: List[Tuple[str, str]] = []
        self.exif_text: str = ""
        self.camera_text: str = ""
        self.score_visible: bool = False
        self.score_badge_text: str = ""
        self.score_css_class: str = ""
        self.meter_css_class: str = ""
        self.clipping_text: str = ""
        self.sharp_area_text: str = ""
        self.sharpness_goodness: Optional[float] = None

        if HAS_GI:
            super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6)
            self.add_css_class("floating-hud")
            self.set_halign(Gtk.Align.START)
            # Lives in the active slot's image overlay, bottom-left, clicks pass through (LIN-8).
            self.set_valign(Gtk.Align.END)
            self.set_margin_bottom(8)
            self.set_margin_start(8)
            self.set_can_target(False)
            self.set_name("info_hud")
            self._build_ui()

        if self.viewmodel:
            self.viewmodel.connect("state-changed", self._on_state_changed)
            self.update_photo(self.viewmodel.current_photo)

    def _build_ui(self) -> None:
        if not HAS_GI:
            return

        # First row, only inside a burst (decision #31): "3 of 10", spoken "Burst, photo 3 of 10".
        self.burst_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        self.burst_row.set_name("info_burst")
        self.burst_row.set_halign(Gtk.Align.START)
        self.burst_icon = Gtk.Image.new_from_icon_name(self._burst_icon_name())
        self.burst_icon.set_pixel_size(16)
        self.burst_row.append(self.burst_icon)
        self.lbl_burst = Gtk.Label(label="")
        self.lbl_burst.add_css_class("optical-strip-secondary")
        self.burst_row.append(self.lbl_burst)
        self.burst_row.set_visible(False)
        self.append(self.burst_row)

        # Top row: Filename + Companion Badges (RAW+JPG, XMP)
        self.header_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.header_row.set_halign(Gtk.Align.START)

        self.lbl_filename = Gtk.Label(label="")
        self.lbl_filename.set_halign(Gtk.Align.START)
        self.lbl_filename.set_ellipsize(3)  # PANGO_ELLIPSIZE_MIDDLE
        self.lbl_filename.set_max_width_chars(36)
        self.lbl_filename.add_css_class("optical-strip-primary")
        self.header_row.append(self.lbl_filename)
        self.copied_mark = build_copied_glyph()
        self.copied_mark.set_visible(False)
        self.header_row.append(self.copied_mark)

        self.companion_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=4)
        self.header_row.append(self.companion_box)
        self.append(self.header_row)

        # The rating the file already carries (decision #32): five 14 px stars, read-only, not
        # focusable; shown only for 1..5, a rejected photo shows the unavailable glyph.
        self.rating_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=2)
        self.rating_row.set_name("info_rating")
        self.rating_row.set_halign(Gtk.Align.START)
        self.rating_row.set_focusable(False)
        self.rating_row.set_tooltip_text(RATING_TOOLTIP)
        self.rating_row.set_visible(False)
        self.append(self.rating_row)

        # Optical EXIF strip (1/250s · f/8.0 · ISO 100 · 24mm)
        self.lbl_exif = Gtk.Label(label="")
        self.lbl_exif.set_halign(Gtk.Align.START)
        self.lbl_exif.add_css_class("optical-strip-secondary")
        self.append(self.lbl_exif)

        # Camera & Lens strip (Sony Alpha 7 IV · FE 24-70mm F2.8 GM II)
        self.lbl_camera = Gtk.Label(label="")
        self.lbl_camera.set_halign(Gtk.Align.START)
        self.lbl_camera.set_ellipsize(3)  # PANGO_ELLIPSIZE_MIDDLE
        self.lbl_camera.set_max_width_chars(48)
        self.lbl_camera.add_css_class("optical-strip-secondary")
        self.append(self.lbl_camera)

        # Score line + Normalized Sharpness Goodness Meter container
        self.score_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        self.score_box.set_halign(Gtk.Align.FILL)

        score_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        score_row.set_halign(Gtk.Align.START)

        self.lbl_score_badge = Gtk.Label(label="")
        score_row.append(self.lbl_score_badge)

        self.lbl_clipping = Gtk.Label(label="")
        self.lbl_clipping.add_css_class("optical-strip-secondary")
        score_row.append(self.lbl_clipping)
        self.score_box.append(score_row)

        self.lbl_sharp_area = Gtk.Label(label="")
        self.lbl_sharp_area.set_name("info_sharp_area")
        self.lbl_sharp_area.set_halign(Gtk.Align.START)
        self.lbl_sharp_area.add_css_class("optical-strip-secondary")
        self.score_box.append(self.lbl_sharp_area)

        self.sharpness_meter = Gtk.ProgressBar()
        self.sharpness_meter.add_css_class("sharpness-meter-track")
        self.sharpness_meter.set_hexpand(True)
        self.score_box.append(self.sharpness_meter)

        self.append(self.score_box)

    @staticmethod
    def _burst_icon_name() -> str:
        """``view-continuous-symbolic`` where the icon theme has it, else ``view-list-symbolic``."""
        try:
            from gi.repository import Gdk
            display = Gdk.Display.get_default()
            theme = Gtk.IconTheme.get_for_display(display) if display is not None else None
            if theme is not None and not theme.has_icon(BURST_ICON):
                return BURST_ICON_FALLBACK
        except Exception:  # noqa: BLE001 - an icon lookup never stops the card from building
            pass
        return BURST_ICON

    def _show_rating(self, rating) -> None:
        """Stars for 1..5, the unavailable glyph for a rejected photo, no row otherwise."""
        while child := self.rating_row.get_first_child():
            self.rating_row.remove(child)
        filled = stars(rating)
        if rating == REJECTED:
            icons = [REJECTED_ICON]
        elif filled:
            icons = [STAR_ICON] * filled + [NO_STAR_ICON] * (5 - filled)
        else:
            icons = []
        for name in icons:
            image = Gtk.Image.new_from_icon_name(name)
            image.set_pixel_size(14)
            self.rating_row.append(image)
        self.rating_row.set_visible(bool(icons))
        if icons:
            self.rating_row.update_property([Gtk.AccessibleProperty.LABEL], [self.rating_text])

    def set_hud_enabled(self, enabled: bool) -> None:
        """Enable or disable HUD visibility while preserving underlying photo state."""
        self.hud_enabled = bool(enabled)
        self.is_hud_visible = self.hud_enabled and (self._current_photo is not None)
        if HAS_GI:
            self.set_visible(self.is_hud_visible)

    def _on_state_changed(self, vm) -> None:
        self.update_photo(vm.current_photo)

    def update_photo(self, photo: Optional[CandidatePhoto]) -> None:
        """Update displayed metadata and scores according to Metric Omission Rule."""
        self._current_photo = photo
        if not photo:
            self.is_hud_visible = False
            self.filename_text = ""
            self.burst_text = ""
            self.rating_text = ""
            self.is_copied = False
            self.companion_badges = []
            self.exif_text = ""
            self.camera_text = ""
            self.score_visible = False
            self.score_badge_text = ""
            self.score_css_class = ""
            self.meter_css_class = ""
            self.clipping_text = ""
            self.sharp_area_text = ""
            self.sharpness_goodness = None
            if HAS_GI:
                self.set_visible(False)
            return

        self.is_hud_visible = bool(self.hud_enabled)
        self.filename_text = photo.filename
        cue = getattr(self.viewmodel, "burst_cue", None)
        cue = cue(photo) if callable(cue) else None
        spoken = burst_spoken(*cue) if isinstance(cue, tuple) else ""
        self.burst_text = burst_text(*cue) if isinstance(cue, tuple) else ""
        self.rating_text = rating_spoken(photo.rating)
        self.is_copied = bool(photo.copied)
        self.companion_badges = get_candidate_companion_badges(photo)

        # Optical EXIF Summary (Zero-Placeholder Invariant R-LINUX-META-03)
        exif_text = ""
        camera_text = ""
        if photo.exif:
            exif_text = photo.exif.formatted_summary
            camera_body = photo.exif.camera_model or photo.exif.camera_make
            if photo.exif.camera_make and photo.exif.camera_model:
                if not photo.exif.camera_model.lower().startswith(photo.exif.camera_make.lower()):
                    camera_body = f"{photo.exif.camera_make} {photo.exif.camera_model}"
            cam_parts = [p for p in (camera_body, photo.exif.lens) if p]
            camera_text = " · ".join(cam_parts)
        self.exif_text = exif_text
        self.camera_text = camera_text

        # Metric Omission Rule (R-LINUX-SCORE-05): Strictly omit if not yet computed
        if photo.scores is not None:
            self.score_visible = True
            if (
                photo.scores.category_override is None
                and self.viewmodel is not None
                and hasattr(self.viewmodel, "blurry_threshold")
                and hasattr(self.viewmodel, "sharp_threshold")
                and (photo.scores.blurry_threshold == 35.0 and photo.scores.sharp_threshold == 70.0)
            ):
                from photo_selector_linux.core.models import SharpnessCategory

                category = SharpnessCategory.from_score(
                    photo.scores.sharpness,
                    blurry_threshold=self.viewmodel.blurry_threshold,
                    sharp_threshold=self.viewmodel.sharp_threshold,
                ).value
            else:
                category = photo.scores.focus_category.value
            self.score_badge_text = f"Sharpness: {photo.scores.formatted_sharpness}"
            self.score_css_class = f"score-badge-{category}"
            self.meter_css_class = f"sharpness-meter-{category}"
            self.clipping_text = (
                f"HL: {photo.scores.formatted_highlight_clipping} · "
                f"SH: {photo.scores.formatted_shadow_clipping}"
            )
            self.sharpness_goodness = compute_sharpness_goodness(photo.scores.sharpness)
            cell = photo.scores.sharpest_cell
            self.sharp_area_text = area_text(cell) if cell is not None else ""
        else:
            self.sharp_area_text = ""
            self.score_visible = False
            self.score_badge_text = ""
            self.score_css_class = ""
            self.meter_css_class = ""
            self.clipping_text = ""
            self.sharpness_goodness = None

        if not HAS_GI:
            return

        self.set_visible(self.is_hud_visible)
        self.lbl_filename.set_label(self.filename_text)
        self._show_rating(photo.rating)
        self.lbl_burst.set_label(self.burst_text)
        self.burst_row.set_visible(bool(self.burst_text))
        if self.burst_text:
            self.burst_row.set_tooltip_text(spoken)
            self.lbl_burst.update_property([Gtk.AccessibleProperty.LABEL], [spoken])
        self.copied_mark.set_visible(bool(photo.copied))

        while child := self.companion_box.get_first_child():
            self.companion_box.remove(child)
        for badge_text, badge_css in self.companion_badges:
            pill = Gtk.Label(label=badge_text)
            pill.add_css_class(badge_css)
            self.companion_box.append(pill)

        self.lbl_exif.set_label(self.exif_text)
        self.lbl_exif.set_visible(bool(self.exif_text))

        self.lbl_camera.set_label(self.camera_text)
        self.lbl_camera.set_visible(bool(self.camera_text))

        if self.score_visible and self.sharpness_goodness is not None:
            self.score_box.set_visible(True)
            self.lbl_score_badge.set_label(self.score_badge_text)
            self.lbl_score_badge.set_tooltip_text(SCORE_TOOLTIP)
            self.sharpness_meter.set_tooltip_text(SCORE_TOOLTIP)
            self.lbl_score_badge.set_css_classes([self.score_css_class])
            self.lbl_clipping.set_label(self.clipping_text)
            self.lbl_sharp_area.set_label(self.sharp_area_text)
            self.lbl_sharp_area.set_visible(bool(self.sharp_area_text))
            self.sharpness_meter.set_css_classes(["sharpness-meter-track", self.meter_css_class])
            self.sharpness_meter.set_fraction(self.sharpness_goodness)
        else:
            self.score_box.set_visible(False)
