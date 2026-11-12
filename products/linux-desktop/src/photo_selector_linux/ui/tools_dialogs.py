"""Secondary tool dialog: Library Statistics.

Adheres to:
- R-LINUX-TOOLS-02: Library Statistics Dialog with Optical Histograms
"""

from __future__ import annotations

try:
    import gi
    gi.require_version("Gtk", "4.0")
    gi.require_version("Adw", "1")
    from gi.repository import Gtk, Adw, GLib
    HAS_GI = True
except (ImportError, ValueError):
    HAS_GI = False
    Gtk = Adw = GLib = None

from typing import Optional

from photo_selector_linux.core.statistics import LibraryStatistics, calculate_library_statistics
from photo_selector_linux.ui.secondary_window import install_chrome, show_scrollbar, size_window

REMOTE_NOTICE = "Remote / Cloud folder detected. Confirm before scanning all files."
CONFIRM_LABEL = "Scan anyway"


def _count(n: int, noun: str) -> str:
    """Singular for one: the statistics rows must not read "1 photos"."""
    return f"{n} {noun}" if n == 1 else f"{n} {noun}s"


class LibraryStatisticsDialog(Adw.Window if HAS_GI else object):
    """Dialog rendering optical EXIF and culling statistics distributions."""

    def __init__(
        self,
        transient_for=None,
        viewmodel=None,
        confirm_remote: bool = False,
    ):
        self.viewmodel = viewmodel
        self.remote_confirmed: bool = confirm_remote
        self.awaiting_remote_confirmation: bool = False
        self.stats: Optional[LibraryStatistics] = None
        if HAS_GI:
            super().__init__()
            self.set_title("Statistics")
            size_window(self, 680, 560, transient_for)
            if transient_for:
                self.set_transient_for(transient_for)
            self._toolbar = install_chrome(self)
            self._build_ui()

    @property
    def needs_remote_confirmation(self) -> bool:
        return bool(
            self.viewmodel
            and getattr(self.viewmodel, "is_remote_folder", False)
            and not self.remote_confirmed
        )

    def confirm_remote_scan(self) -> None:
        self.remote_confirmed = True
        self.awaiting_remote_confirmation = False
        self._build_ui()

    def _build_remote_placeholder(self):
        """What a remote folder shows until the user agrees to the full pass: never a blank window."""
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        box.set_valign(Gtk.Align.CENTER)
        box.set_halign(Gtk.Align.CENTER)
        box.set_margin_top(24)
        box.set_margin_bottom(24)
        box.set_margin_start(24)
        box.set_margin_end(24)
        label = Gtk.Label(label=REMOTE_NOTICE)
        label.set_wrap(True)
        label.set_justify(Gtk.Justification.CENTER)
        box.append(label)
        self.btn_confirm = Gtk.Button(label=CONFIRM_LABEL)
        self.btn_confirm.set_name("btn_confirm_remote")
        self.btn_confirm.add_css_class("suggested-action")
        self.btn_confirm.set_halign(Gtk.Align.CENTER)
        self.btn_confirm.connect("clicked", lambda _b: self.confirm_remote_scan())
        box.append(self.btn_confirm)
        return box

    def _build_ui(self) -> None:
        if self.needs_remote_confirmation:
            self.awaiting_remote_confirmation = True
            if HAS_GI:
                self._toolbar.set_content(self._build_remote_placeholder())
            return

        photos = self.viewmodel.candidates if self.viewmodel else []
        self.stats = calculate_library_statistics(photos)

        if not HAS_GI:
            return

        scrolled = Gtk.ScrolledWindow()
        show_scrollbar(scrolled)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        box.set_margin_top(16)
        box.set_margin_bottom(16)
        box.set_margin_start(16)
        box.set_margin_end(16)
        scrolled.set_child(box)

        stats = self.stats

        # 1. Summary Card
        summary_group = Adw.PreferencesGroup()
        summary_group.set_title("Summary")

        row_total = Adw.ActionRow()
        row_total.set_use_markup(False)
        row_total.set_title("Total Photos")
        row_total.set_subtitle(str(stats.total_photo_count))
        summary_group.add(row_total)

        row_storage = Adw.ActionRow()
        row_storage.set_use_markup(False)
        row_storage.set_title("Total Storage")
        row_storage.set_subtitle(f"{stats.total_storage_bytes / (1024 * 1024):.1f} MB")
        summary_group.add(row_storage)

        if stats.average_sharpness is not None:
            row_sharp = Adw.ActionRow()
            row_sharp.set_use_markup(False)
            row_sharp.set_title("Average Sharpness Score")
            row_sharp.set_subtitle(f"{stats.average_sharpness:.1f} / 100.0")
            summary_group.add(row_sharp)

        box.append(summary_group)

        # 2. Formats Group
        fmt_group = Adw.PreferencesGroup()
        fmt_group.set_title("File Formats")
        for fmt in stats.format_statistics:
            row = Adw.ActionRow()
            row.set_use_markup(False)  # buckets such as "< f/2.0" are text, not markup
            row.set_title(fmt.canonical_name)
            row.set_subtitle(f"{_count(fmt.count, 'file')} ({fmt.percentage_of_total:.1f}%)")
            fmt_group.add(row)
        box.append(fmt_group)

        # 3. Optical Histograms Group
        fl_group = Adw.PreferencesGroup()
        fl_group.set_title("Focal Length Distribution")
        for bucket, count in stats.focal_length_histogram.items():
            if count > 0:
                mean_sharp = stats.sharpness_by_focal_length.get(bucket)
                subtitle = _count(count, "photo")
                if mean_sharp is not None:
                    subtitle += f" · Avg sharpness {mean_sharp:.1f}"
                row = Adw.ActionRow()
                row.set_use_markup(False)
                row.set_title(bucket)
                row.set_subtitle(subtitle)
                fl_group.add(row)
        box.append(fl_group)

        # 4. Sharpness by Aperture Group (U-35)
        if stats.sharpness_by_aperture:
            ap_group = Adw.PreferencesGroup()
            ap_group.set_title("Sharpness by Aperture")
            for bucket, mean_sharp in stats.sharpness_by_aperture.items():
                count = stats.aperture_histogram.get(bucket, 0)
                row = Adw.ActionRow()
                row.set_use_markup(False)
                row.set_title(bucket)
                row.set_subtitle(f"{_count(count, 'photo')} · Avg sharpness {mean_sharp:.1f}")
                ap_group.add(row)
            box.append(ap_group)

        # 5. Sharpness by Shutter Speed Group (U-35)
        if stats.sharpness_by_shutter_speed:
            ss_group = Adw.PreferencesGroup()
            ss_group.set_title("Sharpness by Shutter Speed")
            for bucket, mean_sharp in stats.sharpness_by_shutter_speed.items():
                count = stats.shutter_speed_histogram.get(bucket, 0)
                row = Adw.ActionRow()
                row.set_use_markup(False)
                row.set_title(bucket)
                row.set_subtitle(f"{_count(count, 'photo')} · Avg sharpness {mean_sharp:.1f}")
                ss_group.add(row)
            box.append(ss_group)

        self._toolbar.set_content(scrolled)
