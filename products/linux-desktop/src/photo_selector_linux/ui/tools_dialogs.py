"""Secondary tool dialogs: Duplicate Finder and Library Statistics.

Adheres to:
- R-LINUX-TOOLS-01: Streaming Cryptographic Duplicate Finder Dialog
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

from typing import Callable, List, Optional

from photo_selector_linux.core.duplicate_finder import DuplicateCluster, find_duplicates
from photo_selector_linux.core.statistics import LibraryStatistics, calculate_library_statistics
from photo_selector_linux.ui.file_worker import run_inline

REMOTE_NOTICE = "Remote / Cloud folder detected. Confirm before scanning all files."
SCAN_LABEL = "Scan for Duplicates"
CONFIRM_LABEL = "Scan anyway"


class DuplicateFinderDialog(Adw.Window if HAS_GI else object):
    """Dialog for cryptographic duplicate detection and safe file deduplication."""

    def __init__(
        self,
        transient_for=None,
        viewmodel=None,
        dispatcher: Optional[Callable] = None,
    ):
        self.viewmodel = viewmodel
        self._dispatcher = dispatcher or run_inline
        self.clusters: List[DuplicateCluster] = []
        self.status_text: str = "Ready to scan loaded library."
        self.awaiting_remote_confirmation: bool = False
        self.remote_confirmed: bool = False
        self.scan_button_label: str = SCAN_LABEL
        if HAS_GI:
            super().__init__()
            self.set_title("Duplicate Finder")
            self.set_default_size(700, 520)
            if transient_for:
                self.set_transient_for(transient_for)
            self._build_ui()

    @property
    def needs_remote_confirmation(self) -> bool:
        return bool(
            self.viewmodel
            and getattr(self.viewmodel, "is_remote_folder", False)
            and not self.remote_confirmed
        )

    def _build_ui(self) -> None:
        if not HAS_GI:
            return

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        box.set_margin_top(16)
        box.set_margin_bottom(16)
        box.set_margin_start(16)
        box.set_margin_end(16)

        header = Gtk.Label(label="Cryptographic Duplicate Finder (SHA-256)")
        header.add_css_class("title-2")
        box.append(header)

        self.lbl_status = Gtk.Label(label=self.status_text)
        self.lbl_status.add_css_class("dim-label")
        box.append(self.lbl_status)

        self.btn_scan = Gtk.Button(label=SCAN_LABEL)
        self.btn_scan.add_css_class("suggested-action")
        self.btn_scan.connect("clicked", self._on_scan_clicked)
        box.append(self.btn_scan)

        # Scrolled results list
        self.scrolled = Gtk.ScrolledWindow()
        self.scrolled.set_vexpand(True)
        self.scrolled.set_hexpand(True)
        self.results_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.scrolled.set_child(self.results_box)
        box.append(self.scrolled)

        self.set_content(box)

    def _set_status(self, text: str) -> None:
        self.status_text = text
        if HAS_GI and hasattr(self, "lbl_status"):
            self.lbl_status.set_label(text)

    def _set_scan_enabled(self, enabled: bool) -> None:
        if HAS_GI and hasattr(self, "btn_scan"):
            self.btn_scan.set_sensitive(enabled)

    def _set_scan_label(self, label: str) -> None:
        self.scan_button_label = label
        if HAS_GI and hasattr(self, "btn_scan"):
            self.btn_scan.set_label(label)

    def confirm_remote_scan(self) -> None:
        """Confirm full-folder duplicate pass over a remote/cloud mount (U-31)."""
        self.remote_confirmed = True
        self.awaiting_remote_confirmation = False
        self._on_scan_clicked(None)

    def _on_scan_clicked(self, btn) -> None:
        if btn is not None and self.awaiting_remote_confirmation:
            # The button read "Scan anyway": this click is the confirmation (U-31).
            self.confirm_remote_scan()
            return
        if not self.viewmodel or not self.viewmodel.candidates:
            self._set_status("No photographs loaded to scan.")
            return

        if self.needs_remote_confirmation:
            self.awaiting_remote_confirmation = True
            self._set_status(REMOTE_NOTICE)
            self._set_scan_label(CONFIRM_LABEL)
            return

        self.awaiting_remote_confirmation = False
        self._set_scan_label(SCAN_LABEL)
        self._set_status("Scanning files...")
        candidates_snapshot = list(self.viewmodel.candidates)

        def job():
            return find_duplicates(candidates_snapshot)

        self._set_scan_enabled(False)

        def done(clusters, error):
            self._set_scan_enabled(True)
            if error is not None:
                self._set_status(f"Error scanning files: {error}")
                return
            self._render_clusters(clusters or [])

        self._dispatcher(job, done)

    def _render_clusters(self, clusters: List[DuplicateCluster]) -> None:
        self.clusters = list(clusters)
        if HAS_GI and hasattr(self, "results_box"):
            while child := self.results_box.get_first_child():
                self.results_box.remove(child)

        if not clusters:
            self._set_status("No duplicate photographs detected.")
            return

        total_wasted = sum(c.wasted_bytes for c in clusters)
        mb_wasted = total_wasted / (1024 * 1024)
        self._set_status(
            f"Found {len(clusters)} duplicate clusters ({mb_wasted:.1f} MB potential space savings)."
        )

        if not HAS_GI or not hasattr(self, "results_box"):
            return

        for c in clusters:
            row = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
            size_kb = c.file_size / 1024
            title = Gtk.Label(
                label=f"SHA-256: {c.hash[:16]}... ({len(c.file_paths)} copies, {size_kb:.1f} KB each)"
            )
            title.set_halign(Gtk.Align.START)
            row.append(title)

            for p in c.file_paths:
                p_lbl = Gtk.Label(label=f"  • {p.name} ({p.parent.name}/)")
                p_lbl.set_halign(Gtk.Align.START)
                p_lbl.add_css_class("dim-label")
                row.append(p_lbl)

            self.results_box.append(row)


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
            self.set_title("Library Statistics")
            self.set_default_size(680, 560)
            if transient_for:
                self.set_transient_for(transient_for)
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
                self.set_content(self._build_remote_placeholder())
            return

        photos = self.viewmodel.candidates if self.viewmodel else []
        self.stats = calculate_library_statistics(photos)

        if not HAS_GI:
            return

        scrolled = Gtk.ScrolledWindow()
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        box.set_margin_top(16)
        box.set_margin_bottom(16)
        box.set_margin_start(16)
        box.set_margin_end(16)
        scrolled.set_child(box)

        stats = self.stats

        # 1. Summary Card
        summary_group = Adw.PreferencesGroup()
        summary_group.set_title("Library Summary")

        row_total = Adw.ActionRow()
        row_total.set_title("Total Photographs")
        row_total.set_subtitle(str(stats.total_photo_count))
        summary_group.add(row_total)

        row_storage = Adw.ActionRow()
        row_storage.set_title("Total Storage")
        row_storage.set_subtitle(f"{stats.total_storage_bytes / (1024 * 1024):.1f} MB")
        summary_group.add(row_storage)

        if stats.average_sharpness is not None:
            row_sharp = Adw.ActionRow()
            row_sharp.set_title("Average Sharpness Score")
            row_sharp.set_subtitle(f"{stats.average_sharpness:.1f} / 100.0")
            summary_group.add(row_sharp)

        box.append(summary_group)

        # 2. Formats Group
        fmt_group = Adw.PreferencesGroup()
        fmt_group.set_title("File Formats")
        for fmt in stats.format_statistics:
            row = Adw.ActionRow()
            row.set_title(fmt.canonical_name)
            row.set_subtitle(f"{fmt.count} files ({fmt.percentage_of_total:.1f}%)")
            fmt_group.add(row)
        box.append(fmt_group)

        # 3. Optical Histograms Group
        fl_group = Adw.PreferencesGroup()
        fl_group.set_title("Focal Length Distribution")
        for bucket, count in stats.focal_length_histogram.items():
            if count > 0:
                mean_sharp = stats.sharpness_by_focal_length.get(bucket)
                subtitle = f"{count} photos"
                if mean_sharp is not None:
                    subtitle += f" · Avg sharpness {mean_sharp:.1f}"
                row = Adw.ActionRow()
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
                row.set_title(bucket)
                row.set_subtitle(f"{count} photos · Avg sharpness {mean_sharp:.1f}")
                ap_group.add(row)
            box.append(ap_group)

        # 5. Sharpness by Shutter Speed Group (U-35)
        if stats.sharpness_by_shutter_speed:
            ss_group = Adw.PreferencesGroup()
            ss_group.set_title("Sharpness by Shutter Speed")
            for bucket, mean_sharp in stats.sharpness_by_shutter_speed.items():
                count = stats.shutter_speed_histogram.get(bucket, 0)
                row = Adw.ActionRow()
                row.set_title(bucket)
                row.set_subtitle(f"{count} photos · Avg sharpness {mean_sharp:.1f}")
                ss_group.add(row)
            box.append(ss_group)

        self.set_content(scrolled)
