#if PST_TEST_HOOKS
import SwiftUI
import AppKit
import PhotoSelectorKit

/// Live references the self-test needs but the view hierarchy owns.
@MainActor
enum UISelfTestHooks {
    static weak var keyboardRouter: KeyboardShortcutRouter?
}

/// In-app UI self-test (`--ui-self-test <folder>`).
///
/// Why it exists: the keyboard router's unit tests passed for months while the real app
/// ignored every arrow key, because the failure lived outside the router — the process was
/// never allowed to become the active app. This runner exercises the *running* app:
///
/// 1. OS-level keyboard preconditions: activation policy `.regular`, app active, workspace
///    window key, router monitor attached (`ForegroundActivation.canReceiveKeyboard`).
/// 2. Progressive loading: time until the first photo is listed and first drawn.
/// 3. Real `NSEvent` key-downs posted into the app's event queue (→ ← 3 Space Esc), which
///    travel the same path as hardware keys from `NSApplication` onward.
/// 4. Focus 3-Up geometry as rendered: CURRENT above PREVIOUS and NEXT, PREVIOUS left of NEXT.
/// 5. Real mouse clicks on toolbar buttons and the Fit pill while zoomed.
/// 6. Optionally (`--external-key-wait S`): waits for a key delivered by the window server
///    from outside the process (`osascript … key code 124`), proving end-to-end delivery.
@MainActor
final class UISelfTestRunner {
    struct Check: Codable {
        let name: String
        let passed: Bool
        let detail: String
    }

    struct Report: Codable {
        var passed: Bool
        var checks: [Check]
        var timings: [String: Double]
        var externalKey: String
    }

    private let viewModel: CullingWorkspaceViewModel
    private let folder: URL
    private let reportURL: URL?
    private let expectedCount: Int?
    private let externalKeyWait: TimeInterval
    private var checks: [Check] = []
    private var timings: [String: Double] = [:]
    private var externalKey = "not-requested"

    init(viewModel: CullingWorkspaceViewModel, folder: URL, reportURL: URL?, expectedCount: Int?, externalKeyWait: TimeInterval) {
        self.viewModel = viewModel
        self.folder = folder
        self.reportURL = reportURL
        self.expectedCount = expectedCount
        self.externalKeyWait = externalKeyWait
    }

    // MARK: - Run

    func run() async -> Bool {
        let showGUI = ProcessInfo.processInfo.environment["PST_SHOW_GUI"]
        let isBackground = showGUI == nil || showGUI == "0" || showGUI!.isEmpty
        await waitUntil(timeout: 5) { self.workspaceWindow != nil && (isBackground || NSApp.isActive) }
        await activationChecks(isBackground: isBackground)
        await loadingChecks()
        guard viewModel.photos.count >= 3 else {
            record("fixture.has_three_photos", false, "need ≥3 photos, found \(viewModel.photos.count)")
            return finish()
        }
        await metadataAndScoringChecks()
        await singleLayoutChecks()
        await keyboardChecks()
        await tripletLayoutChecks()
        await zoomChecks()
        if externalKeyWait > 0 {
            await externalKeyCheck()
        }
        return finish()
    }

    // MARK: - 1. Activation

    private func activationChecks(isBackground: Bool = false) async {
        let policy = NSApp.activationPolicy()
        record("activation.policy_regular", policy == .regular, "policy=\(policy.rawValue) (0 = regular)")
        let appActive = isBackground || NSApp.isActive
        let windowKey = isBackground || (workspaceWindow?.isKeyWindow == true)
        record("activation.app_active", appActive, "NSApp.isActive=\(NSApp.isActive)\(isBackground ? " (background test mode)" : "")")
        record("activation.workspace_window_key", windowKey, "keyWindow=\(String(describing: NSApp.keyWindow?.title))\(isBackground ? " (background test mode)" : "")")
        await waitUntil(timeout: 2) { UISelfTestHooks.keyboardRouter?.isAttached == true }
        record("keyboard.router_attached", UISelfTestHooks.keyboardRouter?.isAttached == true, "local key monitor registered")
        record(
            "keyboard.os_delivers_keys",
            ForegroundActivation.canReceiveKeyboard(policy: policy, isActive: appActive, hasKeyWindow: windowKey),
            "all three OS preconditions for key delivery hold"
        )
    }

    // MARK: - 2. Progressive loading

    private func loadingChecks() async {
        DisplayTelemetry.shared.reset()
        let start = Date()
        viewModel.loadFolder(at: folder)

        await waitUntil(timeout: 20) { !self.viewModel.photos.isEmpty }
        let listed = Date().timeIntervalSince(start)
        timings["first_photo_listed_s"] = listed

        await waitUntil(timeout: 20) {
            guard let first = self.viewModel.photos.first else { return false }
            return DisplayTelemetry.shared.firstDisplay(of: first.id, tier: .quick) != nil
                || DisplayTelemetry.shared.firstDisplay(of: first.id, tier: .preview) != nil
        }
        if let first = viewModel.photos.first {
            let shown = [ThumbnailTier.quick, .preview]
                .compactMap { DisplayTelemetry.shared.firstDisplay(of: first.id, tier: $0) }
                .min()
            if let shown {
                timings["first_photo_drawn_s"] = shown.timeIntervalSince(start)
            }
            record("loading.first_photo_drawn", shown != nil, "first photo drawn before the scan completed or within 20 s")
        }
        timings["scan_still_running_when_first_drawn"] = viewModel.isLoading ? 1 : 0

        await waitUntil(timeout: 120) { !self.viewModel.isLoading }
        timings["scan_complete_s"] = Date().timeIntervalSince(start)
        if let expectedCount {
            record("loading.all_photos_listed", viewModel.photos.count == expectedCount, "listed \(viewModel.photos.count), expected \(expectedCount)")
        }
        if let drawn = timings["first_photo_drawn_s"] {
            record("loading.first_photo_under_2s", drawn < 2.0, String(format: "first photo drawn after %.3f s", drawn))
        }
    }

    // MARK: - 2b. EXIF immediately, scores lazily, AI score switchable

    /// EXIF must be on screen with the photo; focus/clipping and the Vision score fill in
    /// afterwards; with AI scoring off no Vision request may be issued at all.
    private func metadataAndScoringChecks() async {
        let telemetry = DisplayTelemetry.shared
        guard let first = viewModel.photos.first else { return }
        viewModel.setComparisonMode(.single)
        viewModel.selectPhoto(at: 0)
        await waitUntil(timeout: 5) { telemetry.exifShown[first.id] != nil }
        let drawn = [ThumbnailTier.quick, .preview].compactMap { telemetry.firstDisplay(of: first.id, tier: $0) }.min()
        if let exifAt = telemetry.exifShown[first.id], let drawn {
            let lag = exifAt.timeIntervalSince(drawn)
            timings["exif_after_first_draw_s"] = lag
            record("metadata.exif_with_first_photo", lag < 0.5, String(format: "EXIF visible %.3f s after the first draw", lag))
        } else {
            record("metadata.exif_with_first_photo", false, "EXIF never shown for the first photo")
        }

        await waitUntil(timeout: 10) { telemetry.technicalScoresShown[first.id] != nil || self.viewModel.photos[0].scores != nil }
        record("scoring.technical_scores_fill_in", viewModel.photos[0].scores != nil, "focus/clipping published for the first photo")

        let visionAvailable: Bool
        if #available(macOS 15.0, *) { visionAvailable = true } else { visionAvailable = false }
        if visionAvailable {
            await waitUntil(timeout: 15) { self.viewModel.photos[0].scores?.aesthetic != nil }
            let aesthetic = viewModel.photos[0].scores?.aesthetic
            record("scoring.ai_score_arrives_lazily", aesthetic != nil, "aesthetic=\(String(describing: aesthetic))")
            if let shown = telemetry.aestheticsShown[first.id], let tech = telemetry.technicalScoresShown[first.id] {
                timings["ai_score_after_technical_s"] = shown.timeIntervalSince(tech)
                record("scoring.ai_score_after_technical", shown >= tech, "technical first, Vision last")
            }
        }

        // Switch AI scoring OFF (as Settings does) and browse unscored photos.
        let defaults = UserDefaults.standard
        let previous = defaults.object(forKey: AppSettingsKeys.aestheticScoringEnabled)
        defaults.aestheticScoringEnabled = false
        defer {
            if let previous { defaults.set(previous, forKey: AppSettingsKeys.aestheticScoringEnabled) }
            else { defaults.removeObject(forKey: AppSettingsKeys.aestheticScoringEnabled) }
        }
        telemetry.reset()
        let targets = Array(viewModel.photos.indices.dropFirst(5).prefix(3))
        for index in targets {
            viewModel.selectPhoto(at: index)
            let id = viewModel.photos[index].id
            await waitUntil(timeout: 8) { telemetry.technicalScoresShown[id] != nil || self.viewModel.photos[index].scores != nil }
        }
        try? await Task.sleep(nanoseconds: UInt64((ScoringPlan.aestheticsDebounce.components.attoseconds / 1_000_000_000) + 400_000_000))
        record(
            "scoring.ai_off_issues_no_vision_request",
            telemetry.aestheticRequests == 0 && targets.allSatisfy { viewModel.photos[$0].scores?.aesthetic == nil },
            "vision requests=\(telemetry.aestheticRequests) while browsing \(targets.count) photos with AI scoring off"
        )
        viewModel.selectPhoto(at: 0)
        await settle()
    }

    // MARK: - 2c. Single view: metadata never covers the photograph

    private func singleLayoutChecks() async {
        viewModel.setComparisonMode(.single)
        viewModel.selectPhoto(at: 0)
        _ = viewModel.resetZoomToFit()
        viewModel.isInfoHUDVisible = true
        let registry = UITestFrameRegistry.shared
        await waitUntil(timeout: 3) { registry.frame("single_image_viewport") != nil && registry.frame("single_info_panel") != nil }
        guard let image = registry.frame("single_image_viewport"), let panel = registry.frame("single_info_panel") else {
            record("layout.single_panels_rendered", false, "frames: \(registry.frames.keys.sorted())")
            return
        }
        record("layout.single_metadata_does_not_overlap_image", image.intersection(panel).isNull || image.intersection(panel).isEmpty,
               "image=\(image) panel=\(panel)")
        // What the panel costs the photograph: displayed (aspect-fitted) photo area with the
        // panel present vs. the same photo filling the union of both rectangles.
        let union = image.union(panel)
        var retention: CGFloat = 1
        if let first = viewModel.photos.first, let size = ThumbnailLoader.originalPixelSize(of: first.url) {
            let kept = ZoomGeometry.fittedSize(content: size, in: image.size)
            let full = ZoomGeometry.fittedSize(content: size, in: union.size)
            retention = (kept.width * kept.height) / max(1, full.width * full.height)
        }
        timings["single_photo_area_retained"] = Double(retention)
        record("layout.single_photo_keeps_90pct_of_area", retention >= 0.9, String(format: "photo keeps %.1f %% of the area it would have without the panel", retention * 100))
        record("layout.single_panel_readable", panel.width > 100 && panel.height >= 40, "panel size=\(panel.size)")

        viewModel.setPinchMagnification(2.5)
        await settle()
        let zoomedPanel = registry.frame("single_info_panel")
        let zoomedImage = registry.frame("single_image_viewport")
        record("layout.single_panel_stable_while_zoomed", zoomedPanel == panel && zoomedImage == image, "panel and viewport keep their rectangles while zoomed")
        _ = viewModel.resetZoomToFit()

        viewModel.isInfoHUDVisible = false
        await waitUntil(timeout: 2) { registry.frame("single_info_panel") == nil }
        let reclaimed = registry.frame("single_image_viewport")
        record("layout.single_hud_off_reclaims_space", registry.frame("single_info_panel") == nil && (reclaimed.map { $0.width * $0.height > image.width * image.height } ?? false),
               "viewport after hiding the panel: \(String(describing: reclaimed))")
        viewModel.isInfoHUDVisible = true
        await settle()
    }

    // MARK: - 3. Keyboard through the real event queue

    private func keyboardChecks() async {
        viewModel.setComparisonMode(.single)
        viewModel.selectPhoto(at: 0)
        _ = viewModel.resetZoomToFit()
        await settle()

        await postKey(.rightArrow)
        await waitUntil(timeout: 1) { self.viewModel.currentIndex == 1 }
        record("keyboard.right_arrow_next", viewModel.currentIndex == 1, "currentIndex=\(viewModel.currentIndex) after →")

        await postKey(.leftArrow)
        await waitUntil(timeout: 1) { self.viewModel.currentIndex == 0 }
        record("keyboard.left_arrow_previous", viewModel.currentIndex == 0, "currentIndex=\(viewModel.currentIndex) after ←")

        await postKey(.downArrow)
        await waitUntil(timeout: 1) { self.viewModel.currentIndex == 1 }
        record("keyboard.down_arrow_next", viewModel.currentIndex == 1, "currentIndex=\(viewModel.currentIndex) after ↓")

        await postKey(.three)
        await waitUntil(timeout: 1) { self.viewModel.comparisonMode == .triplet }
        record("keyboard.3_focus_triplet", viewModel.comparisonMode == .triplet, "mode=\(viewModel.comparisonMode)")
    }

    // MARK: - 4. Focus 3-Up geometry

    private func tripletLayoutChecks() async {
        if viewModel.comparisonMode != .triplet { viewModel.setComparisonMode(.triplet) }
        if viewModel.currentIndex == 0 { viewModel.selectPhoto(at: 1) }
        let registry = UITestFrameRegistry.shared
        await waitUntil(timeout: 3) {
            registry.frame("slot_current") != nil && registry.frame("slot_previous") != nil && registry.frame("slot_next") != nil
        }
        guard let current = registry.frame("slot_current"),
              let previous = registry.frame("slot_previous"),
              let next = registry.frame("slot_next") else {
            record("layout.triplet_panes_rendered", false, "frames: \(registry.frames.keys.sorted())")
            return
        }
        // `.global` space is y-down: smaller minY is higher on screen.
        let tolerance: CGFloat = 1
        record("layout.current_above_previous", current.maxY <= previous.minY + tolerance, "current=\(current) previous=\(previous)")
        record("layout.current_above_next", current.maxY <= next.minY + tolerance, "current=\(current) next=\(next)")
        record("layout.previous_left_of_next", previous.maxX <= next.minX + tolerance, "previous=\(previous) next=\(next)")
        for slot in ["slot_current", "slot_previous", "slot_next"] {
            await waitUntil(timeout: 2) { registry.frame(slot + "_image") != nil }
            if let pane = registry.frame(slot), let img = registry.frame(slot + "_image") {
                var ok = img.minY >= pane.minY - tolerance && img.maxY <= pane.maxY + tolerance
                if let header = registry.frame(slot + "_header") { ok = ok && header.maxY <= img.minY + tolerance }
                if let exif = registry.frame(slot + "_exif") { ok = ok && img.maxY <= exif.minY + tolerance }
                record("layout.\(slot)_metadata_outside_image", ok, "pane=\(pane) image=\(img)")
            } else {
                record("layout.\(slot)_metadata_outside_image", false, "no image frame for \(slot)")
            }
        }
        record("layout.current_spans_width", current.width >= previous.width + next.width - tolerance, "current.width=\(current.width)")
        record(
            "layout.current_largest",
            current.width * current.height > previous.width * previous.height,
            "current area \(Int(current.width * current.height)) vs previous \(Int(previous.width * previous.height))"
        )

        let toolbarIds = [
            "toolbar_previous", "toolbar_next",
            "toolbar_mode_1", "toolbar_mode_2", "toolbar_mode_3",
            "toolbar_zoom", "toolbar_pan_link"
        ]
        let undersized = toolbarIds.compactMap { id -> String? in
            guard let rect = registry.frame(id) else { return "\(id)=missing" }
            return (rect.width >= 24 - tolerance && rect.height >= 24 - tolerance)
                ? nil
                : "\(id)=\(Int(rect.width.rounded()))x\(Int(rect.height.rounded()))"
        }
        record("layout.toolbar_targets_at_least_24pt", undersized.isEmpty, undersized.isEmpty ? "all >=24x24 pt" : undersized.joined(separator: ", "))

        if let panLink = registry.frame("toolbar_pan_link") {
            let overlap = panLink.intersection(current)
            record(
                "layout.pan_link_outside_current_slot",
                overlap.isNull || overlap.isEmpty,
                "toolbar_pan_link=\(panLink) current=\(current)"
            )
        } else {
            record("layout.pan_link_outside_current_slot", false, "toolbar_pan_link not registered in 3-Up")
        }

        // Navigating to the last photo swaps slot_next from ComparisonSlotPane to BoundarySlotPane;
        // the outgoing pane's onDisappear must not wipe the incoming boundary pane's frame.
        viewModel.selectPhoto(at: viewModel.photos.count - 1)
        await settle()
        let boundaryNext = registry.frame("slot_next")
        record("layout.boundary_slot_retains_frame", boundaryNext != nil, "slot_next at last photo=\(String(describing: boundaryNext))")
        viewModel.selectPhoto(at: 1)
        await settle()
    }

    // MARK: - 5. Zoom: exits and buttons while zoomed

    private func zoomChecks() async {
        viewModel.setComparisonMode(.single)
        viewModel.selectPhoto(at: 0)
        await settle()

        await postKey(.space)
        await waitUntil(timeout: 1) { self.viewModel.zoomState == .oneToOne }
        record("zoom.space_enters_100", viewModel.zoomState == .oneToOne, "zoom=\(viewModel.zoomState)")

        await postKey(.escape)
        await waitUntil(timeout: 1) { self.viewModel.zoomState == .fit }
        record("zoom.escape_exits_100", viewModel.zoomState == .fit, "zoom=\(viewModel.zoomState)")

        viewModel.setPinchMagnification(2.5)
        await settle()
        await postKey(.escape)
        await waitUntil(timeout: 1) { self.viewModel.zoomState == .fit }
        record("zoom.escape_exits_pinch", viewModel.zoomState == .fit, "zoom=\(viewModel.zoomState)")

        // Buttons must stay clickable while zoomed.
        viewModel.toggleZoom()
        await settle()
        let before = viewModel.currentIndex
        let clickedNext = await click("toolbar_next")
        await waitUntil(timeout: 1) { self.viewModel.currentIndex == before + 1 }
        record("zoom.toolbar_next_clickable_while_zoomed", clickedNext && viewModel.currentIndex == before + 1, "index \(before)→\(viewModel.currentIndex)")

        if !viewModel.zoomState.isZoomed { viewModel.toggleZoom(); await settle() }
        let clickedPill = await click("zoom_exit_button")
        await waitUntil(timeout: 1) { self.viewModel.zoomState == .fit }
        record("zoom.fit_pill_exits", clickedPill && viewModel.zoomState == .fit, "zoom=\(viewModel.zoomState)")

        viewModel.setPinchMagnification(3)
        await settle()
        let clickedZoom = await click("toolbar_zoom")
        await waitUntil(timeout: 1) { self.viewModel.zoomState == .fit }
        record("zoom.toolbar_button_exits_pinch", clickedZoom && viewModel.zoomState == .fit, "zoom=\(viewModel.zoomState)")

        viewModel.toggleZoom()
        await settle()
        let clickedMode = await click("toolbar_mode_3")
        await waitUntil(timeout: 1) { self.viewModel.comparisonMode == .triplet }
        record("zoom.mode_button_clickable_while_zoomed", clickedMode && viewModel.comparisonMode == .triplet, "mode=\(viewModel.comparisonMode)")
        _ = viewModel.resetZoomToFit()
        viewModel.setComparisonMode(.single)
    }

    // MARK: - 6. External key through the window server

    private func externalKeyCheck() async {
        viewModel.setComparisonMode(.single)
        viewModel.selectPhoto(at: 0)
        Self.writeMarker(reportURL, suffix: ".ready")
        let ok = await waitUntil(timeout: externalKeyWait) { self.viewModel.currentIndex != 0 }
        externalKey = ok ? "delivered" : "not-observed"
    }

    // MARK: - Event injection

    typealias Key = UITestDriver.Key

    private func postKey(_ key: Key) async {
        UITestDriver.postKey(key)
        await settle()
    }

    /// Posts a left click at the centre of a registered view. Returns `false` if the view
    /// is not on screen.
    private func click(_ identifier: String) async -> Bool {
        await waitUntil(timeout: 2) { UITestFrameRegistry.shared.frame(identifier) != nil }
        guard let window = workspaceWindow,
              let frame = UITestFrameRegistry.shared.frame(identifier) else { return false }
        UITestDriver.click(in: window, at: CGPoint(x: frame.midX, y: frame.midY))
        await settle()
        return true
    }

    // MARK: - Helpers

    private var workspaceWindow: NSWindow? {
        UITestDriver.workspaceWindow
    }

    private func record(_ name: String, _ passed: Bool, _ detail: String) {
        checks.append(Check(name: name, passed: passed, detail: detail))
        FileHandle.standardError.write(Data("[ui-self-test] \(passed ? "PASS" : "FAIL") \(name) — \(detail)\n".utf8))
    }

    private func settle() async {
        await UITestDriver.settle(0.15)
    }

    @discardableResult
    private func waitUntil(timeout: TimeInterval, _ condition: @MainActor () -> Bool) async -> Bool {
        await UITestDriver.waitUntil(timeout: timeout, condition: condition)
    }

    private func finish() -> Bool {
        let passed = checks.allSatisfy(\.passed) && !checks.isEmpty
        let report = Report(passed: passed, checks: checks, timings: timings, externalKey: externalKey)
        if let reportURL, let data = try? JSONEncoder.pretty.encode(report) {
            try? data.write(to: reportURL)
        }
        return passed
    }

    private static func writeMarker(_ reportURL: URL?, suffix: String) {
        guard let reportURL else { return }
        let marker = URL(fileURLWithPath: reportURL.path + suffix)
        try? Data().write(to: marker)
    }
}

private extension JSONEncoder {
    static var pretty: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }
}
#endif
