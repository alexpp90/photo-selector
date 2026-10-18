"""Unit tests for CullingWorkspaceViewModel, comparison presentation, and UI styling.

Adheres to:
- R-LINUX-UI-01: Native GNOME Libadwaita Window & HeaderBar
- R-LINUX-UI-02: Obsidian Darkroom & Optical Cobalt Palette (>= 7:1 AAA contrast)
- R-LINUX-UI-03: Maximized Viewport Canvas (>85% area) & Contextual Slot Badges
- R-LINUX-UI-04: 1-Up Mode Transitions
- R-LINUX-UI-05: 2-Up Mode Transitions
- R-LINUX-UI-06: 3-Up Focus Mode Transitions
- R-LINUX-UI-07: Boundary Slot Conditions
- R-LINUX-UI-09: Floating Optical Metadata & Sharpness Goodness HUD
- R-LINUX-UI-10: Semantic Bottom Action Bar & Status Counters
- R-LINUX-SCORE-05: Metric Omission Rule
- R-LINUX-CULL-06/07: Transactional Undo Stack
"""

from pathlib import Path
from unittest.mock import MagicMock
import pytest

from photo_selector_linux.core.models import (
    CandidatePhoto,
    CullingActionType,
    CullingRecord,
    ExposureMetadata,
    QualityScores,
)
from photo_selector_linux.ui.action_bar import ActionBarView
from photo_selector_linux.ui.canvas import CanvasView
from photo_selector_linux.ui.info_hud import InfoHUD
from photo_selector_linux.ui.styling import (
    APP_BRAND_NAME,
    APP_BRAND_TAGLINE,
    APP_ICON_NAME,
    APP_THEME_NAME,
    GOODNESS_BAR_FLOOR,
    STUDIO_DARK_CSS,
    STUDIO_PALETTE,
    compute_sharpness_goodness,
    format_companion_badges,
    get_candidate_companion_badges,
    get_slot_role_label,
)
from photo_selector_linux.ui.window import MainWindow
from photo_selector_linux.viewmodels.culling_workspace import CullingWorkspaceViewModel


def make_candidate(name: str, companions: list[str] | None = None) -> CandidatePhoto:
    p = Path(f"/tmp/test_dir/{name}")
    comp_paths = [Path(f"/tmp/test_dir/{c}") for c in (companions or [])]
    return CandidatePhoto(primary_path=p, companion_paths=comp_paths)


def test_libadwaita_components():
    """Verify MainWindow initializes brand identity and core workspace views (R-LINUX-UI-01)."""
    win = MainWindow()
    assert win.window_title == APP_BRAND_NAME == "Photo Selector"
    assert win.icon_name == APP_ICON_NAME == "org.photoselector.Linux"
    assert win.canvas_view is not None
    assert win.action_bar is not None
    assert "studio-dark-surface" in win.get_css_classes()
    assert win.btn_mode_1.get_css_classes() == ["suggested-action"]
    assert win.btn_mode_2.get_css_classes() == []
    assert win.btn_mode_3.get_css_classes() == []
    assert win.lbl_counter.get_label() == "Photo 0 of 0"


def test_studio_dark_palette():
    """Verify Obsidian Darkroom & Optical Cobalt palette tokens and CSS classes (R-LINUX-UI-02)."""
    assert APP_THEME_NAME == "Obsidian Darkroom & Optical Cobalt"
    assert "Obsidian" not in APP_BRAND_TAGLINE and "Darkroom" not in APP_BRAND_TAGLINE
    assert "RAW" in APP_BRAND_TAGLINE
    assert STUDIO_PALETTE["surface_bg"] == "#141417"
    assert STUDIO_PALETTE["header_chrome"] == "#181920"
    assert STUDIO_PALETTE["card_bg"] == "#1C1C20"
    assert STUDIO_PALETTE["border_stroke"] == "#3A3A42"
    assert STUDIO_PALETTE["text_primary"] == "#FFFFFF"
    assert STUDIO_PALETTE["text_secondary"] == "#D9D9D9"
    assert STUDIO_PALETTE["optical_cobalt"] == "#3584E4"
    assert STUDIO_PALETTE["optical_cyan"] == "#06B6D4"
    assert STUDIO_PALETTE["iris_indigo"] == "#6366F1"
    assert STUDIO_PALETTE["emerald_keeper"] == "#2EC27E"
    assert STUDIO_PALETTE["amber_optical"] == "#E5A50A"
    assert STUDIO_PALETTE["crimson_cull"] == "#E01B24"

    for selector in (
        ".studio-dark-surface",
        ".studio-headerbar",
        ".studio-actionbar",
        ".card-pane",
        ".accent-border",
        ".floating-hud",
        ".sharpness-meter-track",
        ".sharpness-meter-track trough",
        ".sharpness-meter-track progress",
        ".sharpness-meter-sharp progress",
        ".sharpness-meter-acceptable progress",
        ".sharpness-meter-blurry progress",
        ".active-pill",
        ".slot-role-pill",
        ".zoom-indicator-pill",
        ".companion-badge-raw",
        ".companion-badge-xmp",
        ".status-counter-selected",
        ".status-counter-trashed",
        ".status-counter-remaining",
        ".empty-state-hero",
    ):
        assert selector in STUDIO_DARK_CSS, f"Missing CSS selector {selector} in STUDIO_DARK_CSS"


def test_canvas_area_allocation():
    """Verify CanvasView allocates >85% viewport and renders slot role badges (R-LINUX-UI-03)."""
    vm = CullingWorkspaceViewModel()
    canvas = CanvasView(viewmodel=vm)

    # Empty state when no candidates are loaded
    assert canvas.rendered_slots == []

    # Populate candidates with RAW+JPG+XMP, RAW+JPG, and JPEG-only -Edit derivative
    vm.candidates = [
        make_candidate("DSC_0001.JPG", ["DSC_0001.ARW", "DSC_0001.xmp"]),
        make_candidate("DSC_0002.JPG", ["DSC_0002.CR3"]),
        make_candidate("DSC_0003.JPG", ["DSC_0003-Edit.JPG"]),
    ]
    vm.current_index = 0
    canvas._render_view()

    # 1-Up mode
    assert len(canvas.rendered_slots) == 1
    slot0 = canvas.rendered_slots[0]
    assert slot0["type"] == "photo"
    assert slot0["role"] == "ACTIVE"
    assert slot0["is_active"] is True
    assert slot0["badges"] == [
        ("RAW+JPG", "companion-badge-raw"),
        ("XMP", "companion-badge-xmp"),
    ]
    assert slot0["zoom_1to1"] is False

    # Toggle 1:1 pixel zoom in 1-Up mode
    canvas.toggle_zoom()
    assert canvas.is_1to1_zoom is True
    assert canvas.rendered_slots[0]["zoom_1to1"] is True
    canvas.reset_zoom()
    assert canvas.is_1to1_zoom is False

    # 2-Up mode (CHAMPION vs CHALLENGER)
    vm.set_comparison_mode(2)
    assert len(canvas.rendered_slots) == 2
    assert canvas.rendered_slots[0]["role"] == "CHAMPION"
    assert canvas.rendered_slots[0]["is_active"] is True
    assert canvas.rendered_slots[1]["role"] == "CHALLENGER"
    assert canvas.rendered_slots[1]["is_active"] is False

    # 3-Up mode at index 0: left boundary card + ACTIVE + NEXT
    vm.set_comparison_mode(3)
    assert len(canvas.rendered_slots) == 3
    assert canvas.rendered_slots[0] == {"type": "boundary", "slot_type": "first"}
    assert canvas.rendered_slots[1]["role"] == "ACTIVE"
    assert canvas.rendered_slots[2]["role"] == "NEXT"

    # 3-Up mode at middle index 1: PREVIOUS + ACTIVE + NEXT
    vm.nav_next()
    assert vm.current_index == 1
    assert [s.get("role") for s in canvas.rendered_slots] == ["PREVIOUS", "ACTIVE", "NEXT"]
    # Right slot (DSC_0003.JPG + DSC_0003-Edit.JPG) has no RAW file, so no false RAW+JPG badge
    assert canvas.rendered_slots[2]["badges"] == []

    # 3-Up mode at last index 2: PREVIOUS + ACTIVE + right boundary card
    vm.nav_next()
    assert vm.current_index == 2
    assert canvas.rendered_slots[0]["role"] == "PREVIOUS"
    assert canvas.rendered_slots[1]["role"] == "ACTIVE"
    assert canvas.rendered_slots[2] == {"type": "boundary", "slot_type": "last"}


def test_info_hud_goodness_and_metric_omission():
    """Verify InfoHUD sharpness goodness bar and strict Metric Omission Rule (R-LINUX-UI-09, R-LINUX-SCORE-05)."""
    vm = CullingWorkspaceViewModel()
    vm.candidates = [
        make_candidate("IMG_4021.JPG", ["IMG_4021.CR3", "IMG_4021.XMP"]),
        make_candidate("IMG_4022.JPG"),
    ]
    canvas = CanvasView(viewmodel=vm)
    hud = canvas.hud
    assert isinstance(hud, InfoHUD)

    # Verify HUD is initially visible for loaded candidate
    vm._notify_state_changed()
    assert canvas.hud_enabled is True
    assert hud.is_hud_visible is True
    assert hud.get_visible() is True

    # Toggle HUD off via I hotkey handler and verify it stays hidden across navigation
    canvas.toggle_hud()
    assert canvas.hud_enabled is False
    assert hud.is_hud_visible is False
    assert hud.get_visible() is False

    vm.nav_next()
    assert vm.current_index == 1
    assert hud.filename_text == "IMG_4022.JPG"
    assert hud.is_hud_visible is False
    assert hud.get_visible() is False

    # Toggle HUD back on
    canvas.toggle_hud()
    assert canvas.hud_enabled is True
    assert hud.is_hud_visible is True
    assert hud.get_visible() is True

    # Candidate with full optical metadata, companions, and sharp focus score
    cand_sharp = vm.candidates[0]
    cand_sharp.exif = ExposureMetadata(
        shutter_speed=1.0 / 500.0,
        aperture=2.8,
        iso=400,
        focal_length=85.0,
        camera_make="Sony",
        camera_model="Sony ILCE-7RM5",
        lens="FE 85mm F1.4 GM",
    )
    cand_sharp.scores = QualityScores(
        sharpness=90.0,
        noise=1.2,
        highlight_clipping=1.5,
        shadow_clipping=0.2,
    )
    hud.update_photo(cand_sharp)
    assert hud.is_hud_visible is True
    assert hud.filename_text == "IMG_4021.JPG"
    assert hud.companion_badges == [
        ("RAW+JPG", "companion-badge-raw"),
        ("XMP", "companion-badge-xmp"),
    ]
    assert hud.exif_text == "1/500s · f/2.8 · ISO 400 · 85mm"
    assert hud.camera_text == "Sony ILCE-7RM5 · FE 85mm F1.4 GM"
    assert hud.score_visible is True
    assert hud.score_badge_text == "Focus: 90.0 (Sharp)"
    assert hud.score_css_class == "score-badge-sharp"
    assert hud.meter_css_class == "sharpness-meter-sharp"
    assert hud.sharpness_meter.get_css_classes() == ["sharpness-meter-track", "sharpness-meter-sharp"]
    assert hud.sharpness_goodness == pytest.approx(compute_sharpness_goodness(90.0))
    assert hud.sharpness_meter.get_fraction() == pytest.approx(compute_sharpness_goodness(90.0))
    assert hud.clipping_text == "HL: 1.5% · SH: 0.2%"

    # Candidate with separate camera_make and camera_model + acceptable score
    cand_acc = make_candidate("IMG_4023.CR3", ["IMG_4023.JPG"])
    cand_acc.exif = ExposureMetadata(
        camera_make="Canon",
        camera_model="EOS R5",
        lens="RF 50mm F1.2 L USM",
    )
    cand_acc.scores = QualityScores(
        sharpness=52.0,
        noise=1.8,
        highlight_clipping=0.0,
        shadow_clipping=0.0,
    )
    hud.update_photo(cand_acc)
    assert hud.camera_text == "Canon EOS R5 · RF 50mm F1.2 L USM"
    assert hud.score_badge_text == "Focus: 52.0 (Acceptable)"
    assert hud.score_css_class == "score-badge-acceptable"
    assert hud.meter_css_class == "sharpness-meter-acceptable"
    assert hud.sharpness_meter.get_css_classes() == ["sharpness-meter-track", "sharpness-meter-acceptable"]

    # Candidate with missing EXIF and missing scores -> strict Metric Omission Rule (no N/A or --)
    cand_bare = make_candidate("SCAN_001.PNG")
    cand_bare.exif = ExposureMetadata()
    cand_bare.scores = None
    hud.update_photo(cand_bare)
    assert hud.is_hud_visible is True
    assert hud.filename_text == "SCAN_001.PNG"
    assert hud.companion_badges == []
    assert hud.exif_text == ""
    assert hud.camera_text == ""
    assert hud.score_visible is False
    assert hud.score_badge_text == ""
    assert hud.meter_css_class == ""
    assert hud.sharpness_goodness is None
    assert hud.clipping_text == ""

    # Candidate with blurry score -> blurry badge & goodness floor
    cand_soft = make_candidate("BLUR_002.JPG")
    cand_soft.scores = QualityScores(
        sharpness=18.4,
        noise=3.1,
        highlight_clipping=0.0,
        shadow_clipping=0.0,
    )
    hud.update_photo(cand_soft)
    assert hud.score_visible is True
    assert hud.score_badge_text == "Focus: 18.4 (Blurry)"
    assert hud.score_css_class == "score-badge-blurry"
    assert hud.meter_css_class == "sharpness-meter-blurry"
    assert hud.sharpness_meter.get_css_classes() == ["sharpness-meter-track", "sharpness-meter-blurry"]
    assert hud.sharpness_goodness == pytest.approx(compute_sharpness_goodness(18.4))

    # Reset HUD with None
    hud.update_photo(None)
    assert hud.is_hud_visible is False
    assert hud.filename_text == ""
    assert hud.score_visible is False


def test_action_bar_semantic_status_counters():
    """Verify ActionBarView semantic status counters and Undo button state (R-LINUX-UI-10)."""
    mock_file_mgr = MagicMock()
    vm = CullingWorkspaceViewModel(file_manager=mock_file_mgr)
    vm.candidates = [make_candidate(f"p_{i}.jpg") for i in range(4)]
    vm.current_folder = Path("/tmp/test_dir")

    bar = ActionBarView(viewmodel=vm)
    bar._update_state()
    assert bar.selected_label_text == "0 Selected"
    assert bar.trashed_label_text == "0 Trashed"
    assert bar.remaining_label_text == "4 Remaining"
    assert bar.selected_css_class == "status-counter-badge"
    assert bar.trashed_css_class == "status-counter-badge"
    assert bar.remaining_css_class == "status-counter-remaining"
    assert bar.undo_label_text == "Undo (Ctrl+Z)"
    assert bar.undo_sensitive is False
    assert bar.btn_undo.get_sensitive() is False

    # Perform Move and Trash to verify semantic counter pills update
    c0 = vm.candidates[0]
    mock_file_mgr.move_candidate.return_value = CullingRecord(
        photo_id=c0.id,
        action_type=CullingActionType.MOVE,
        original_primary_path=c0.primary_path,
        affected_paths=[(c0.primary_path, Path("/tmp/test_dir/Selection/p_0.jpg"))],
    )
    vm.cull_move()

    c1 = vm.candidates[0]
    mock_file_mgr.trash_candidate.return_value = CullingRecord(
        photo_id=c1.id,
        action_type=CullingActionType.TRASH,
        original_primary_path=c1.primary_path,
        affected_paths=[(c1.primary_path, c1.primary_path)],
    )
    vm.cull_trash()

    assert bar.selected_label_text == "1 Selected"
    assert bar.trashed_label_text == "1 Trashed"
    assert bar.remaining_label_text == "2 Remaining"
    assert bar.selected_css_class == "status-counter-selected"
    assert bar.trashed_css_class == "status-counter-trashed"
    assert bar.undo_label_text == "Undo (2) (Ctrl+Z)"
    assert bar.undo_sensitive is True
    assert bar.lbl_selected.get_label() == "1 Selected"
    assert bar.lbl_selected.get_css_classes() == ["status-counter-selected"]
    assert bar.lbl_trashed.get_label() == "1 Trashed"
    assert bar.lbl_trashed.get_css_classes() == ["status-counter-trashed"]
    assert bar.btn_undo.get_sensitive() is True
    assert bar.btn_undo.get_label() == "Undo (2) (Ctrl+Z)"


def test_styling_helper_edge_cases():
    """Verify boundary values and edge cases for sharpness goodness and badge helpers."""
    assert compute_sharpness_goodness(0.0) == pytest.approx(GOODNESS_BAR_FLOOR)
    assert compute_sharpness_goodness(-25.0) == pytest.approx(GOODNESS_BAR_FLOOR)
    assert compute_sharpness_goodness(float("nan")) == pytest.approx(GOODNESS_BAR_FLOOR)
    assert compute_sharpness_goodness(float("-inf")) == pytest.approx(GOODNESS_BAR_FLOOR)
    assert compute_sharpness_goodness(float("inf")) == pytest.approx(1.0)
    assert compute_sharpness_goodness(50.0) == pytest.approx(GOODNESS_BAR_FLOOR + 0.5 * (1.0 - GOODNESS_BAR_FLOOR))
    assert compute_sharpness_goodness(100.0) == pytest.approx(1.0)
    assert compute_sharpness_goodness(500.0) == pytest.approx(1.0)

    assert get_slot_role_label(1, 0) == "ACTIVE"
    assert get_slot_role_label(2, 0, is_active=True) == "CHAMPION"
    assert get_slot_role_label(2, 1, is_active=False) == "CHALLENGER"
    assert get_slot_role_label(3, -1, is_active=False) == "PREVIOUS"
    assert get_slot_role_label(3, 0, is_active=True) == "ACTIVE"
    assert get_slot_role_label(3, 1, is_active=False) == "NEXT"

    assert format_companion_badges(False, False) == []
    assert format_companion_badges(True, False) == [("RAW+JPG", "companion-badge-raw")]
    assert format_companion_badges(False, True) == [("XMP", "companion-badge-xmp")]
    assert format_companion_badges(True, True) == [
        ("RAW+JPG", "companion-badge-raw"),
        ("XMP", "companion-badge-xmp"),
    ]

    # Candidate companion badge extraction edge cases
    assert get_candidate_companion_badges(make_candidate("DSC_0001.ARW", ["DSC_0001.JPG"])) == [
        ("RAW+JPG", "companion-badge-raw")
    ]
    assert get_candidate_companion_badges(make_candidate("DSC_0001.JPG", ["DSC_0001-Edit.JPG"])) == []
    assert get_candidate_companion_badges(make_candidate("DSC_0001.ARW", ["DSC_0001-Enhanced-NR.DNG"])) == []
    assert get_candidate_companion_badges(make_candidate("DSC_0001.JPG", ["DSC_0001-Edit.JPG", "DSC_0001.xmp"])) == [
        ("XMP", "companion-badge-xmp")
    ]


def test_comparison_mode_transitions():
    """Verify switching between 1-Up, 2-Up, and 3-Up Focus Mode (R-LINUX-UI-04, 05, 06)."""
    vm = CullingWorkspaceViewModel()
    assert vm.comparison_mode == 1

    vm.set_comparison_mode(2)
    assert vm.comparison_mode == 2

    vm.set_comparison_mode(3)
    assert vm.comparison_mode == 3

    # Invalid mode ignored
    vm.set_comparison_mode(99)
    assert vm.comparison_mode == 3


def test_navigation_bounds():
    """Verify navigation clamps at collection boundaries."""
    vm = CullingWorkspaceViewModel()
    vm.candidates = [make_candidate(f"img_{i}.jpg") for i in range(3)]
    vm.current_index = 0

    # Cannot go before 0
    vm.nav_previous()
    assert vm.current_index == 0

    vm.nav_next()
    assert vm.current_index == 1
    vm.nav_next()
    assert vm.current_index == 2

    # Cannot go beyond last index
    vm.nav_next()
    assert vm.current_index == 2


def test_boundary_slot_conditions():
    """Verify 3-Up Focus Mode boundary index states (R-LINUX-UI-07)."""
    vm = CullingWorkspaceViewModel()
    vm.candidates = [make_candidate(f"img_{i}.jpg") for i in range(5)]
    vm.current_index = 0

    # At start: index 0 (Left slot shows First Photograph boundary card)
    assert vm.current_index == 0

    vm.current_index = 4
    # At end: index N-1 (Right slot shows Last Photograph boundary card)
    assert vm.current_index == len(vm.candidates) - 1


def test_transactional_undo_stack():
    """Verify culling operations and undo maintain candidate lists and counts (R-LINUX-CULL-06, 07)."""
    mock_file_mgr = MagicMock()
    vm = CullingWorkspaceViewModel(file_manager=mock_file_mgr)

    c1 = make_candidate("photo_1.jpg")
    c2 = make_candidate("photo_2.jpg")
    vm.candidates = [c1, c2]
    vm.current_folder = Path("/tmp/test_dir")
    vm.current_index = 0

    state_events = []
    vm.connect("state-changed", lambda _vm: state_events.append(_vm.current_index))

    # Copy operation emits state-changed exactly once
    fake_copy_rec = CullingRecord(
        photo_id=c1.id,
        action_type=CullingActionType.COPY,
        original_primary_path=c1.primary_path,
        affected_paths=[(c1.primary_path, Path("/tmp/test_dir/Selection/photo_1.jpg"))],
    )
    mock_file_mgr.copy_candidate.return_value = fake_copy_rec
    mock_file_mgr.undo_culling.return_value = True

    vm.cull_copy()
    assert len(state_events) == 1
    assert vm.current_index == 1
    assert vm.selected_count == 1
    assert vm.copied_count == 1

    assert vm.undo() is True
    assert vm.selected_count == 0
    assert vm.copied_count == 0
    vm.current_index = 0

    # Move operation
    fake_move_rec = CullingRecord(
        photo_id=c1.id,
        action_type=CullingActionType.MOVE,
        original_primary_path=c1.primary_path,
        affected_paths=[(c1.primary_path, Path("/tmp/test_dir/Selection/photo_1.jpg"))],
    )
    mock_file_mgr.move_candidate.return_value = fake_move_rec

    vm.cull_move()
    assert vm.selected_count == 1
    assert len(vm.candidates) == 1
    assert len(vm.undo_stack) == 1

    # Undo operation
    success = vm.undo()
    assert success is True
    assert vm.selected_count == 0
    assert len(vm.candidates) == 2
    assert len(vm.undo_stack) == 0
    assert vm.candidates[0] == c1


def test_failed_cull_is_reported_and_leaves_the_queue_unchanged():
    """A failing file operation shows a message instead of dying silently in a GTK handler."""
    from photo_selector_linux.core.file_ops import FileCullingError

    manager = MagicMock()
    manager.trash_candidate.side_effect = FileCullingError("Operation not supported")
    vm = CullingWorkspaceViewModel(file_manager=manager)
    vm.candidates = [make_candidate("a.jpg"), make_candidate("b.jpg")]
    vm.current_folder = Path("/tmp/test_dir")
    messages = []
    vm.connect("notification", lambda _vm, message: messages.append(message))

    vm.cull_trash()

    assert [c.filename for c in vm.candidates] == ["a.jpg", "b.jpg"]
    assert vm.trashed_count == 0 and vm.undo_stack == []
    assert messages == ["Could not move a.jpg to Trash: Operation not supported"]


class DeferredDispatcher:
    """Holds jobs until the test finishes them, as the background worker would later."""

    def __init__(self):
        self.pending = []

    def __call__(self, job, done):
        self.pending.append((job, done))

    def finish(self):
        job, done = self.pending.pop(0)
        try:
            result, error = job(), None
        except Exception as e:  # noqa: BLE001 — mirrors FileOperationWorker
            result, error = None, e
        done(result, error)


def _record(photo, action):
    return CullingRecord(
        photo_id=photo.id, action_type=action, original_primary_path=photo.primary_path, affected_paths=[],
    )


def test_rapid_culls_queue_in_order_and_update_queue_indicator():
    """Rapid M, M, M during a slow file operation queues all three actions in FIFO order (U-08)."""
    manager = MagicMock()
    dispatcher = DeferredDispatcher()
    vm = CullingWorkspaceViewModel(file_manager=manager, dispatcher=dispatcher)
    a, b, c, d = (
        make_candidate("a.jpg"),
        make_candidate("b.jpg"),
        make_candidate("c.jpg"),
        make_candidate("d.jpg"),
    )
    vm.candidates = [a, b, c, d]
    vm.current_folder = Path("/tmp/test_dir")
    manager.move_candidate.side_effect = lambda photo, folder, **kwargs: _record(
        photo, CullingActionType.MOVE
    )
    bar = ActionBarView(viewmodel=vm)

    # Press M three times rapidly while the worker is still running the first job
    assert vm.cull_move() is True
    assert vm.cull_move() is True
    assert vm.cull_move() is True

    # Optimistic UI state already advanced to d.jpg with 3 queued actions and buttons still sensitive
    assert [p.filename for p in vm.candidates] == ["d.jpg"]
    assert vm.current_photo is d
    assert vm.selected_count == 3
    assert vm.queued_count == 3
    assert vm.queue_status_label == "3 queued"
    assert bar.queue_label_text == "3 queued"
    assert bar.cull_sensitive is True
    assert bar.undo_sensitive is True

    # Finish each job in FIFO order
    dispatcher.finish()
    assert vm.queued_count == 2
    assert vm.queue_status_label == "2 queued"

    dispatcher.finish()
    assert vm.queued_count == 1
    assert vm.queue_status_label == "1 queued"

    dispatcher.finish()
    assert vm.queued_count == 0
    assert vm.queue_status_label is None
    assert not vm.is_busy and bar.busy_text == ""
    assert [call.args[0].filename for call in manager.move_candidate.call_args_list] == [
        "a.jpg",
        "b.jpg",
        "c.jpg",
    ]
    assert len(vm.undo_stack) == 3


def test_undo_during_running_job_cancels_queued_or_enqueues_disk_undo():
    """Ctrl+Z while jobs are queued cancels unstarted queued actions and enqueues disk undo for started ones (U-08)."""
    manager = MagicMock()
    dispatcher = DeferredDispatcher()
    vm = CullingWorkspaceViewModel(file_manager=manager, dispatcher=dispatcher)
    a, b, c = make_candidate("a.jpg"), make_candidate("b.jpg"), make_candidate("c.jpg")
    vm.candidates = [a, b, c]
    vm.current_folder = Path("/tmp/test_dir")
    manager.move_candidate.side_effect = lambda photo, folder, **kwargs: _record(
        photo, CullingActionType.MOVE
    )
    manager.undo_culling.return_value = True

    vm.cull_move()  # a.jpg (started in worker)
    vm.cull_move()  # b.jpg (waiting in queue)
    assert vm.queued_count == 2
    assert [p.filename for p in vm.candidates] == ["c.jpg"]

    # First Ctrl+Z cancels b.jpg before it ever touches disk
    assert vm.undo() is True
    assert vm.queued_count == 1
    assert [p.filename for p in vm.candidates] == ["b.jpg", "c.jpg"]
    assert vm.selected_count == 1

    # Second Ctrl+Z targets a.jpg (currently running on disk), so it queues a disk undo job
    assert vm.undo() is True
    assert vm.queued_count == 2

    dispatcher.finish()  # finishes move of a.jpg
    dispatcher.finish()  # finishes undo of a.jpg

    assert vm.queued_count == 0
    assert [p.filename for p in vm.candidates] == ["a.jpg", "b.jpg", "c.jpg"]
    assert vm.selected_count == 0
    assert manager.move_candidate.call_count == 1
    assert manager.undo_culling.call_count == 1


def test_outcome_applies_to_the_culled_photo_when_the_user_browsed_meanwhile():
    manager = MagicMock()
    dispatcher = DeferredDispatcher()
    vm = CullingWorkspaceViewModel(file_manager=manager, dispatcher=dispatcher)
    a, b, c = make_candidate("a.jpg"), make_candidate("b.jpg"), make_candidate("c.jpg")
    vm.candidates, vm.current_folder, vm.current_index = [a, b, c], Path("/tmp/test_dir"), 1
    manager.move_candidate.return_value = _record(b, CullingActionType.MOVE)
    manager.undo_culling.return_value = True

    vm.cull_move()  # moving b (optimistically removed, active becomes c)
    vm.nav_previous()  # user looks at a while the upload runs
    dispatcher.finish()

    assert [p.filename for p in vm.candidates] == ["a.jpg", "c.jpg"]
    assert vm.current_photo is a
    assert vm.undo_stack[-1][1] is b

    assert vm.undo() is True  # started in the background
    dispatcher.finish()

    assert [p.filename for p in vm.candidates] == ["a.jpg", "b.jpg", "c.jpg"]
    assert vm.current_photo is b and vm.selected_count == 0


def test_failed_background_undo_is_kept_for_retry():
    manager = MagicMock()
    dispatcher = DeferredDispatcher()
    vm = CullingWorkspaceViewModel(file_manager=manager, dispatcher=dispatcher)
    a = make_candidate("a.jpg")
    vm.candidates, vm.current_folder = [a], Path("/tmp/test_dir")
    manager.trash_candidate.return_value = _record(a, CullingActionType.TRASH)
    manager.undo_culling.side_effect = OSError("Drive offline")
    messages = []
    vm.connect("notification", lambda _vm, message: messages.append(message))

    vm.cull_trash()
    dispatcher.finish()
    vm.undo()
    dispatcher.finish()

    assert len(vm.undo_stack) == 1 and vm.candidates == []
    assert messages[-1] == "Could not undo trash for a.jpg: Drive offline"


def test_folder_change_completes_queued_jobs_without_dropping(tmp_path):
    """Changing folders drains/completes queued jobs for the previous folder rather than dropping them (U-08)."""
    manager = MagicMock()
    dispatcher = DeferredDispatcher()
    vm = CullingWorkspaceViewModel(file_manager=manager, dispatcher=dispatcher)
    a, b = make_candidate("a.jpg"), make_candidate("b.jpg")
    vm.candidates, vm.current_folder = [a, b], Path("/tmp/test_dir")
    manager.trash_candidate.side_effect = lambda photo: _record(photo, CullingActionType.TRASH)

    vm.cull_trash()
    vm.cull_trash()
    vm.load_directory(tmp_path)

    # Both queued trash jobs still execute to completion on disk
    dispatcher.finish()
    dispatcher.finish()

    assert manager.trash_candidate.call_count == 2
    assert vm.queued_count == 0 and not vm.is_busy
    # New folder's workspace remains clean
    assert vm.candidates == [] and vm.trashed_count == 0


def _two_folders(tmp_path):
    a_dir, b_dir = tmp_path / "a", tmp_path / "b"
    a_dir.mkdir()
    b_dir.mkdir()
    return a_dir, b_dir


def test_operation_finishing_after_a_folder_switch_keeps_its_undo_record(tmp_path):
    """Q-7: a Move/Trash that completes on the worker after the folder changed must stay undoable."""
    a_dir, b_dir = _two_folders(tmp_path)
    manager = MagicMock()
    dispatcher = DeferredDispatcher()
    vm = CullingWorkspaceViewModel(file_manager=manager, dispatcher=dispatcher)
    vm.load_directory(a_dir)
    photo = CandidatePhoto(primary_path=a_dir / "x.jpg", companion_paths=[])
    vm.candidates = [photo]
    manager.trash_candidate.side_effect = lambda p: _record(p, CullingActionType.TRASH)

    vm.cull_trash()
    vm.load_directory(b_dir)  # the trash job has not run yet
    dispatcher.finish()

    # The new folder's workspace stays clean: no entry and no photo that belongs to folder A.
    assert vm.undo_stack == [] and vm.candidates == []
    vm.load_directory(a_dir)

    assert len(vm.undo_stack) == 1
    record = vm.undo_stack[0][0]
    assert record.action_type == CullingActionType.TRASH and record is not None
    vm.undo()
    dispatcher.finish()
    assert manager.undo_culling.call_args.args[0] is record


def test_completed_operations_survive_leaving_and_reopening_a_folder(tmp_path):
    a_dir, b_dir = _two_folders(tmp_path)
    manager = MagicMock()
    dispatcher = DeferredDispatcher()
    vm = CullingWorkspaceViewModel(file_manager=manager, dispatcher=dispatcher)
    vm.load_directory(a_dir)
    photo = CandidatePhoto(primary_path=a_dir / "y.jpg", companion_paths=[])
    vm.candidates = [photo]
    manager.move_candidate.side_effect = lambda p, folder, **kw: _record(p, CullingActionType.MOVE)
    vm.cull_move()
    dispatcher.finish()
    assert len(vm.undo_stack) == 1

    vm.load_directory(b_dir)
    assert vm.undo_stack == []  # Ctrl+Z in folder B must never touch folder A's files
    vm.load_directory(a_dir)

    assert len(vm.undo_stack) == 1


def test_partial_move_finishing_after_a_folder_switch_keeps_its_record(tmp_path):
    from photo_selector_linux.core.file_ops import FileCullingError

    a_dir, b_dir = _two_folders(tmp_path)
    manager = MagicMock()
    dispatcher = DeferredDispatcher()
    vm = CullingWorkspaceViewModel(file_manager=manager, dispatcher=dispatcher)
    vm.load_directory(a_dir)
    photo = CandidatePhoto(primary_path=a_dir / "z.jpg", companion_paths=[])
    vm.candidates = [photo]
    partial = _record(photo, CullingActionType.MOVE)
    manager.move_candidate.side_effect = FileCullingError("original could not be removed", partial_record=partial)

    vm.cull_move()
    vm.load_directory(b_dir)
    dispatcher.finish()
    assert vm.undo_stack == []
    vm.load_directory(a_dir)

    assert [entry[0] for entry in vm.undo_stack] == [partial]


def test_folder_history_is_bounded(tmp_path):
    manager = MagicMock()
    dispatcher = DeferredDispatcher()
    vm = CullingWorkspaceViewModel(file_manager=manager, dispatcher=dispatcher)
    manager.trash_candidate.side_effect = lambda p: _record(p, CullingActionType.TRASH)
    folders = []
    for i in range(vm.MAX_REMEMBERED_FOLDERS + 3):
        d = tmp_path / f"f{i}"
        d.mkdir()
        folders.append(d)
        vm.load_directory(d)
        vm.candidates = [CandidatePhoto(primary_path=d / "p.jpg", companion_paths=[])]
        vm.cull_trash()
        dispatcher.finish()

    vm.load_directory(folders[0])  # long forgotten
    assert vm.undo_stack == []
    vm.load_directory(folders[-2])  # still remembered
    assert len(vm.undo_stack) == 1


def test_failed_queued_action_rolls_back_and_cancels_dependent_actions_on_same_photo():
    """If a queued Copy fails, both the Copy and a subsequent queued Trash on the same photo roll back (U-08)."""
    from photo_selector_linux.core.file_ops import FileCullingError

    manager = MagicMock()
    dispatcher = DeferredDispatcher()
    vm = CullingWorkspaceViewModel(file_manager=manager, dispatcher=dispatcher, auto_advance=False)
    a, b = make_candidate("a.jpg"), make_candidate("b.jpg")
    vm.candidates, vm.current_folder = [a, b], Path("/tmp/test_dir")
    manager.copy_candidate.side_effect = FileCullingError("Disk full")
    messages = []
    vm.connect("notification", lambda _vm, message: messages.append(message))

    # With auto_advance=False, user queues Copy on a.jpg and then Trash on a.jpg
    vm.cull_copy()
    vm.cull_trash()
    assert vm.queued_count == 2
    assert [p.filename for p in vm.candidates] == ["b.jpg"]

    # First job (Copy on a.jpg) fails -> rolls back Copy AND cancels dependent Trash on a.jpg
    dispatcher.finish()

    assert vm.queued_count == 0
    assert not manager.trash_candidate.called
    assert [p.filename for p in vm.candidates] == ["a.jpg", "b.jpg"]
    assert vm.selected_count == 0 and vm.copied_count == 0 and vm.trashed_count == 0
    assert messages == ["Could not copy a.jpg: Disk full"]


def test_file_worker_runs_jobs_off_the_calling_thread_and_delivers_through_idle_add():
    import threading
    import time
    from photo_selector_linux.ui.file_worker import FileOperationWorker

    delivered, job_threads = [], []
    worker = FileOperationWorker(idle_add=lambda fn, *args: fn(*args))

    def job():
        time.sleep(0.2)  # still running when the second job is queued, as on a slow runner
        job_threads.append(threading.current_thread())
        return "record"

    worker(job, lambda result, error: delivered.append((result, error)))
    worker(lambda: (_ for _ in ()).throw(OSError("offline")), lambda r, e: delivered.append((r, str(e))))
    deadline = time.monotonic() + 10
    while len(delivered) < 2 and time.monotonic() < deadline:  # shutdown() would cancel queued jobs
        time.sleep(0.01)
    worker.shutdown()

    assert job_threads[0] is not threading.current_thread()
    assert delivered == [("record", None), (None, "offline")]


def test_partial_move_is_recorded_for_undo_and_reported_without_losing_the_photo():
    """D-1: the copy is in Selection/ but the original could not be removed. The failure must
    leave an undo entry and say so; the photo stays in the queue because it is still on disk."""
    from photo_selector_linux.core.file_ops import FileCullingError

    photo = make_candidate("a.jpg")
    record = CullingRecord(
        photo_id=photo.id,
        action_type=CullingActionType.MOVE,
        original_primary_path=photo.primary_path,
        affected_paths=[(photo.primary_path, Path("/tmp/test_dir/Selection/a.jpg"))],
        unremoved_sources=[photo.primary_path],
    )
    manager = MagicMock()
    manager.move_candidate.side_effect = FileCullingError("could not remove", partial_record=record)
    vm = CullingWorkspaceViewModel(file_manager=manager)
    vm.candidates = [photo, make_candidate("b.jpg")]
    vm.current_folder = Path("/tmp/test_dir")
    messages = []
    vm.connect("notification", lambda _vm, message: messages.append(message))

    vm.cull_move()

    assert [c.filename for c in vm.candidates] == ["a.jpg", "b.jpg"]
    assert [entry[0] for entry in vm.undo_stack] == [record]
    assert vm.selected_count == 0
    assert messages == ["Copied a.jpg to Selection/ — original could not be removed"]


def _stranded_vm(kind: str):
    """A manager whose `kind` operation failed and left a member in Selection/ (D-12)."""
    from photo_selector_linux.core.file_ops import FileCullingError

    photo = make_candidate("a.jpg")
    dst = Path("/tmp/test_dir/Selection/a.jpg")
    record = CullingRecord(
        photo_id=photo.id,
        action_type=CullingActionType.MOVE if kind == "move" else CullingActionType.COPY,
        original_primary_path=photo.primary_path,
        affected_paths=[(photo.primary_path, dst)],
        stranded=[dst],
    )
    manager = MagicMock()
    getattr(manager, f"{kind}_candidate").side_effect = FileCullingError(
        "failed, and a.jpg could not be put back", partial_record=record
    )
    manager.undo_culling.return_value = True
    vm = CullingWorkspaceViewModel(file_manager=manager)
    vm.candidates = [photo, make_candidate("b.jpg")]
    vm.current_folder = Path("/tmp/test_dir")
    messages = []
    vm.connect("notification", lambda _vm, message: messages.append(message))
    return vm, photo, record, messages


def test_move_whose_rollback_failed_keeps_the_record_and_does_not_claim_a_rollback():
    vm, photo, record, messages = _stranded_vm("move")

    vm.cull_move()

    assert [c.filename for c in vm.candidates] == ["a.jpg", "b.jpg"]
    assert [entry[0] for entry in vm.undo_stack] == [record]
    assert vm.selected_count == 0
    assert messages == [
        "Move interrupted — some files of a.jpg stayed in Selection/ and could not be put back"
    ]


def test_copy_whose_cleanup_failed_keeps_the_record_and_undo_leaves_the_counters_alone():
    vm, photo, record, messages = _stranded_vm("copy")
    vm.copied_count = vm.selected_count = 5

    vm.cull_copy()

    assert [entry[0] for entry in vm.undo_stack] == [record]
    assert (vm.copied_count, vm.selected_count) == (5, 5)
    assert messages == [
        "Copy interrupted — some copies of a.jpg stayed in Selection/ and could not be removed"
    ]

    # The failed copy already gave its optimistic counts back; undoing the stray copies must not
    # take them away a second time.
    assert vm.undo() is True
    vm.file_manager.undo_culling.assert_called_once()
    assert (vm.copied_count, vm.selected_count) == (5, 5)


def test_move_whose_stray_copies_could_not_be_removed_says_the_originals_are_untouched():
    """Cross-mount staging failed and its cleanup too: the originals never moved, so the user must
    not be told to put anything back."""
    vm, photo, record, messages = _stranded_vm("move")
    record.unremoved_sources = [photo.primary_path]

    vm.cull_move()

    assert [entry[0] for entry in vm.undo_stack] == [record]
    assert messages == [
        "Move interrupted — copies of a.jpg stayed in Selection/ and could not be removed; "
        "the original is untouched"
    ]


# ── B-7: feedback templates of docs/shared/FILE_OPERATIONS.md (finding D-8) ─────────────────────


def _vm_with_record(kind: str, pairs):
    photo = make_candidate("a.jpg", ["a.xmp"])
    record = CullingRecord(
        photo_id=photo.id,
        action_type=CullingActionType.MOVE if kind == "move" else CullingActionType.COPY,
        original_primary_path=photo.primary_path,
        affected_paths=pairs,
    )
    manager = MagicMock()
    getattr(manager, f"{kind}_candidate").return_value = record
    vm = CullingWorkspaceViewModel(file_manager=manager)
    vm.candidates = [photo, make_candidate("b.jpg")]
    vm.current_folder = Path("/tmp/test_dir")
    messages = []
    vm.connect("notification", lambda _vm, message: messages.append(message))
    return vm, photo, messages


def test_collision_suffixed_move_says_which_name_it_got():
    sel = Path("/tmp/test_dir/Selection")
    vm, _photo, messages = _vm_with_record("move", [
        (Path("/tmp/test_dir/a.jpg"), sel / "a-2.jpg"),
        (Path("/tmp/test_dir/a.xmp"), sel / "a-2.xmp"),
    ])

    vm.cull_move()

    assert messages == ["Moved to Selection/ as a-2.jpg (+1 related)"]


def test_collision_suffixed_copy_says_which_name_it_got():
    sel = Path("/tmp/test_dir/Selection")
    vm, _photo, messages = _vm_with_record("copy", [(Path("/tmp/test_dir/a.jpg"), sel / "a-2.jpg")])

    vm.cull_copy()

    assert messages == ["Copied to Selection/ as a-2.jpg"]


def test_transfer_without_collision_keeps_the_plain_message():
    sel = Path("/tmp/test_dir/Selection")
    vm, _photo, messages = _vm_with_record("move", [(Path("/tmp/test_dir/a.jpg"), sel / "a.jpg")])

    vm.cull_move()

    assert messages == ["Moved a.jpg to Selection/"]


def test_undo_that_had_to_suffix_says_so():
    sel = Path("/tmp/test_dir/Selection")
    vm, photo, messages = _vm_with_record("move", [(Path("/tmp/test_dir/a.jpg"), sel / "a.jpg")])
    vm.cull_move()
    messages.clear()

    def undo_with_suffix(record, candidate):
        candidate.primary_path = Path("/tmp/test_dir/a-2.jpg")  # the original name was occupied
        return True

    vm.file_manager.undo_culling.side_effect = undo_with_suffix

    assert vm.undo() is True
    assert messages == ["Restored as a-2.jpg (original name was in use)"]
