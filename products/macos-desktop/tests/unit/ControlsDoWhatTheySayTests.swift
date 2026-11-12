import Testing
import Foundation
@testable import PhotoSelectorKit

/// Controls that said one thing and did another (backlog 2026-11-11, Lensjet P1): the Settings
/// sliders, the two Auto-Advance toggles, Tab, the tally and the Stop-at-sharpness notice.
@Suite("Controls do what they say")
struct ControlsDoWhatTheySayTests {
    private func makeDefaults() -> (UserDefaults, () -> Void) {
        let name = "com.photoselector.tests.controls.\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        return (d, { d.removePersistentDomain(forName: name) })
    }

    private func photos(_ n: Int, scores: [Double]? = nil) -> [PhotoItem] {
        (0..<n).map { i in
            PhotoItem(
                url: URL(fileURLWithPath: "/nonexistent/IMG_\(i).JPG"),
                scores: scores.map { QualityScores(sharpness: $0[i], noise: 0, highlightClipping: 0, shadowClipping: 0) })
        }
    }

    @MainActor
    private func waitUntil(_ condition: @MainActor () -> Bool) async -> Bool {
        for _ in 0..<100 {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        return condition()
    }

    // MARK: Auto-Advance

    @Test("The menu toggle persists, and a Settings change reaches the running view model")
    @MainActor
    func autoAdvanceIsOneSetting() async {
        let (defaults, cleanup) = makeDefaults(); defer { cleanup() }
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        #expect(vm.autoAdvance == true)

        vm.setAutoAdvance(false) // Culling menu
        #expect(defaults.autoAdvance == false)

        defaults.autoAdvance = true // Settings (@AppStorage writes the same key)
        #expect(await waitUntil { vm.autoAdvance == true })
    }

    // MARK: Tab

    @Test("Tab switches photo in 2-Up only; 1-Up and 3-Up leave the key unconsumed")
    @MainActor
    func tabOnlyActsInTwoUp() {
        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(photos(6))
        #expect(vm.canSwitchPhoto == false)
        #expect(vm.advanceComparisonSlotFocus() == false)
        #expect(vm.currentIndex == 0, "Tab no longer steps forward in 1-Up")

        vm.setComparisonMode(.triplet)
        #expect(vm.canSwitchPhoto == false)
        #expect(vm.advanceComparisonSlotFocus() == false)

        vm.setComparisonMode(.sideBySide)
        #expect(vm.canSwitchPhoto)
        let before = vm.activeSlotIndex
        #expect(vm.advanceComparisonSlotFocus() == true)
        #expect(vm.activeSlotIndex != before)
    }

    // MARK: Tally

    @Test("A copied photo is no longer Remaining, and the spoken tally agrees")
    @MainActor
    func remainingSubtractsCopied() {
        let vm = CullingWorkspaceViewModel()
        var items = photos(5)
        items[0].status = .selected
        items[1].status = .copied
        items[2].status = .trashed
        vm.setPhotos(items)
        #expect(vm.remainingCount == 2)
        let spoken = ChromeDensity.tallyAccessibilityText(
            selected: vm.selectedCount, trashed: vm.trashedCount, remaining: vm.remainingCount, copied: vm.copiedCount)
        #expect(spoken == "1 selected, 1 copied, 1 trashed, 2 remaining")
    }

    // MARK: Stop at sharpness

    @Test("The Stop-at-sharpness notice stays until the next navigation key or Esc")
    @MainActor
    func stopBannerPersists() async {
        let (defaults, cleanup) = makeDefaults(); defer { cleanup() }
        defaults.sharpnessStopThreshold = 60
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        vm.setPhotos(photos(3, scores: [10, 40, 75]))
        vm.applySortOrder(.lowestSharpnessFirst)
        vm.navigateToNext(); vm.navigateToNext()
        #expect(vm.currentToast?.title == "Sharpness 60 reached")
        #expect(vm.currentToast?.isPersistent == true)

        try? await Task.sleep(nanoseconds: 1_500_000_000) // longer than the old 1.2 s toast
        #expect(vm.currentToast?.title == "Sharpness 60 reached")

        #expect(vm.dismissStickyToast() == true) // Esc
        #expect(vm.currentToast == nil)

        vm.navigateToPrevious() // lands below the threshold; the next key also clears it
        #expect(vm.currentToast == nil)
    }

    @Test("Undo is unavailable until something was done")
    @MainActor
    func undoUnavailableAtStart() {
        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(photos(2))
        #expect(vm.canUndo == false)
    }

    // MARK: Sharpness bands

    @Test("The Blurry and Sharp sliders change the category of a score")
    func bandsFollowTheSettings() {
        let user = SharpnessBands(blurryBelow: 50, sharpFrom: 80)
        #expect(SharpnessCategory.from(score: 45, bands: user) == .blurry)
        #expect(SharpnessCategory.from(score: 75, bands: user) == .acceptable)
        #expect(SharpnessCategory.from(score: 85, bands: user) == .sharp)
        #expect(SharpnessCategory.from(score: 45) == .acceptable, "defaults stay 35 / 70")
        #expect(SharpnessCategory.from(score: 75) == .sharp)
    }

    @Test("Stored thresholds feed badges, tooltips, spoken text and Statistics")
    func storedBandsReachEveryReader() {
        let (defaults, cleanup) = makeDefaults(); defer { cleanup() }
        #expect(SharpnessBands.stored(in: defaults) == .standard)
        defaults.sharpnessBlurryThreshold = 50
        defaults.sharpnessAcceptableThreshold = 80
        let bands = SharpnessBands.stored(in: defaults)
        #expect(bands == SharpnessBands(blurryBelow: 50, sharpFrom: 80))

        let scores = QualityScores(sharpness: 45, noise: 0, highlightClipping: 0, shadowClipping: 0)
        #expect(ScoreLabels.sharpnessBadge(scores, bands: bands) == "45.0 Blurry")
        #expect(ScoreLabels.sharpnessTooltip(scores, bands: bands).contains("Blurry below 50, sharp from 80"))
        #expect(ScoreLabels.sharpnessSpoken(scores, bands: bands).hasSuffix("Blurry"))
        #expect(ScoreLabels.sharpnessBadge(scores) == "45.0 Acceptable")

        let items = photos(2, scores: [45, 75])
        let stats = LibraryStatisticsEngine.calculate(from: items, bands: bands)
        #expect(stats.focusDistribution[.blurry] == 1)
        #expect(stats.focusDistribution[.acceptable] == 1)
        #expect(stats.focusDistribution[.sharp] == 0)
    }

    // MARK: Every stored setting has a reader

    @Test("Every AppSettingsKeys key has a production reader outside Settings")
    func everyKeyIsRead() throws {
        let src = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("src")
        var text = ""
        let files = FileManager.default.enumerator(at: src, includingPropertiesForKeys: nil)!
        for case let url as URL in files where url.pathExtension == "swift" {
            let path = url.path
            if path.hasSuffix("Settings/AppSettings.swift") || path.contains("/Views/Settings/")
                || path.hasSuffix("HermeticLabSession.swift") || path.contains("SettingsMigration") { continue }
            text += try String(contentsOf: url, encoding: .utf8) + "\n"
        }
        // A default is not a reader.
        text = text.replacingOccurrences(of: "AppSettingsDefaults.", with: "AppSettingsDefaults_")
        // Retained only so an update never destroys a saved value (the slider is gone).
        let keptForMigrationOnly: Set<String> = ["aestheticThreshold"]
        for key in AppSettingsKeys.all where !keptForMigrationOnly.contains(key) {
            #expect(text.contains(".\(key)"), "\(key) is stored but nothing outside Settings reads it")
        }
    }
}
