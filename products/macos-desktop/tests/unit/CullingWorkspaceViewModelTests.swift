import Testing
import Foundation
import SwiftUI
import AppKit
@testable import PhotoSelectorKit

// MARK: - Unit Tests

@Suite("CullingWorkspaceViewModel Unit Tests")
struct CullingWorkspaceViewModelTests {

    private func createSamplePhotos(count: Int) -> [PhotoItem] {
        (0..<count).map { i in
            let url = URL(fileURLWithPath: "/tmp/sample_\(i).jpg")
            return PhotoItem(id: UUID(), url: url, status: .candidate)
        }
    }

    @Test("ViewModel Initialization: default state is valid")
    @MainActor
    func testInitialization() {
        let vm = CullingWorkspaceViewModel()
        #expect(vm.photos.isEmpty)
        #expect(vm.currentIndex == 0)
        #expect(vm.activeSlotIndex == 0)
        #expect(vm.comparisonMode == .single)
        #expect(vm.zoomState == .fit)
        #expect(vm.isZoomSynced == true)
        #expect(vm.isFilmstripVisible == true)
        #expect(vm.isInfoVisible == true)
        #expect(vm.autoAdvance == true)
        #expect(vm.hasActivePhoto == false)
        #expect(vm.currentPhoto == nil)
    }

    @Test("Navigation: next and previous navigate cleanly and clamp at boundaries")
    @MainActor
    func testNavigationAndClamping() {
        let vm = CullingWorkspaceViewModel()
        let sample = createSamplePhotos(count: 5)
        vm.setPhotos(sample)

        #expect(vm.currentIndex == 0)
        #expect(vm.currentPhoto?.id == sample[0].id)

        // Previous at 0 clamps at 0
        vm.navigateToPrevious()
        #expect(vm.currentIndex == 0)

        // Navigate forward
        vm.navigateToNext()
        #expect(vm.currentIndex == 1)

        vm.navigateToNext()
        #expect(vm.currentIndex == 2)

        // Jump to end
        vm.selectPhoto(at: 4)
        #expect(vm.currentIndex == 4)

        // Next at end clamps at 4
        vm.navigateToNext()
        #expect(vm.currentIndex == 4)

        // Navigate back
        vm.navigateToPrevious()
        #expect(vm.currentIndex == 3)
    }

    @Test("Comparison Modes: single, 2-Up, and 3-Up configure slot indices")
    @MainActor
    func testComparisonModesAndSlotSetup() {
        let vm = CullingWorkspaceViewModel()
        let sample = createSamplePhotos(count: 6)
        vm.setPhotos(sample)

        // Default: Single
        #expect(vm.comparisonMode == .single)
        #expect(vm.comparisonSlotIndices == [0])

        // Switch to 2-Up
        vm.setComparisonMode(.sideBySide)
        #expect(vm.comparisonMode == .sideBySide)
        #expect(vm.comparisonSlotIndices == [0, 1])
        #expect(vm.comparisonSlotPhotos.count == 2)

        // Switch to 3-Up
        vm.setComparisonMode(.triplet)
        #expect(vm.comparisonMode == .triplet)
        #expect(vm.comparisonSlotIndices == [0, 1, 2])
        #expect(vm.comparisonSlotPhotos.count == 3)

        // Tab does nothing in 3-Up: the three frames have no active slot to switch.
        #expect(vm.activeSlotIndex == 0)
        #expect(vm.advanceComparisonSlotFocus() == false)
        #expect(vm.activeSlotIndex == 0)
    }

    @Test("King of the Hill pairwise replacement in comparison mode")
    @MainActor
    func testKingOfTheHillComparisonAdvance() {
        let vm = CullingWorkspaceViewModel()
        let sample = createSamplePhotos(count: 6)
        vm.setPhotos(sample)
        vm.setComparisonMode(.sideBySide)

        // Slot 0 has sample[0], Slot 1 has sample[1]
        #expect(vm.comparisonSlotIndices == [0, 1])

        // Focus Slot 1 (Challenger)
        vm.setActiveSlot(1)
        #expect(vm.activeSlotIndex == 1)

        // Cull Slot 1 with Move action
        vm.cullCurrentPhoto(action: .move)

        // Slot 0 (sample[0]) remains untouched!
        #expect(vm.comparisonSlotIndices[0] == 0)

        // Slot 1 auto-advances to next uninspected candidate (sample[2])!
        #expect(vm.comparisonSlotIndices[1] == 2)
        #expect(vm.photos[1].status == .selected)
    }

    @Test("Comparison Mode: Backward navigation steps active slot safely without duplicate slots")
    @MainActor
    func testComparisonBackwardNavigationSkipsDisplayedSlots() {
        let vm = CullingWorkspaceViewModel()
        let sample = createSamplePhotos(count: 5)
        vm.setPhotos(sample)
        vm.setComparisonMode(.sideBySide)

        // Initial: [0, 1], active slot 1
        #expect(vm.comparisonSlotIndices == [0, 1])
        vm.setActiveSlot(1)

        // Previous on Slot 1 clamps at 1 because index 0 is displayed in Slot 0
        vm.navigateToPrevious()
        #expect(vm.comparisonSlotIndices == [0, 1])

        // Navigate Slot 1 forward to 2 -> [0, 2]
        vm.navigateToNext()
        #expect(vm.comparisonSlotIndices == [0, 2])

        // Navigate Slot 1 back -> steps to 1 -> [0, 1]
        vm.navigateToPrevious()
        #expect(vm.comparisonSlotIndices == [0, 1])

        // Switch to Slot 0, step forward to index 3 -> [3, 1]
        vm.setActiveSlot(0)
        vm.selectPhoto(at: 3)
        #expect(vm.comparisonSlotIndices == [3, 1])

        // Step Slot 0 backward -> steps to 2 -> [2, 1]
        vm.navigateToPrevious()
        #expect(vm.comparisonSlotIndices == [2, 1])

        // Step Slot 0 backward again -> skips 1 (displayed in Slot 1) and lands on 0 -> [0, 1]
        vm.navigateToPrevious()
        #expect(vm.comparisonSlotIndices == [0, 1])
    }

    @Test("Optimistic Culling: Status updates instantly with toasts (< 1ms)")
    @MainActor
    func testOptimisticCullingStatusAndToasts() {
        let suiteName = "com.photoselector.tests.toasts.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        // Own temporary folder with real files, never a fixed /tmp path.
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("toasts_\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let sample: [PhotoItem] = (0..<5).map { i in
            let url = root.appendingPathComponent("sample_\(i).jpg")
            try? Data("x".utf8).write(to: url)
            return PhotoItem(id: UUID(), url: url, status: .candidate)
        }
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        vm.setPhotos(sample, rootURL: root)
        vm.autoAdvance = false // Keep selection in place to verify status

        // Move to Selection
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.photos[0].status == .selected)
        #expect(vm.currentToast != nil)
        #expect(vm.currentToast?.title == "Moved to Selection")
        #expect(vm.currentToast?.actionType == .move)

        // Copy to Selection
        vm.selectPhoto(at: 1)
        vm.cullCurrentPhoto(action: .copy)
        #expect(vm.photos[1].status == .copied)
        #expect(vm.currentToast?.title == "Copied to Selection")
        #expect(vm.currentToast?.actionType == .copy)

        // Move to Trash
        vm.selectPhoto(at: 2)
        vm.cullCurrentPhoto(action: .trash)
        #expect(vm.photos[2].status == .trashed)
        #expect(vm.currentToast?.title == "Moved to Trash")
        #expect(vm.currentToast?.actionType == .trash)
    }

    @Test("Auto-Advance: Automatically steps to next photo after cull when enabled")
    @MainActor
    func testAutoAdvanceAfterCulling() {
        let vm = CullingWorkspaceViewModel()
        let sample = createSamplePhotos(count: 5)
        vm.setPhotos(sample)
        vm.autoAdvance = true

        #expect(vm.currentIndex == 0)
        vm.cullCurrentPhoto(action: .move)

        // Automatically advanced to photo 1
        #expect(vm.currentIndex == 1)
        #expect(vm.photos[0].status == .selected)
    }

    @Test("Undo: Reverts status and restores selection immediately (< 1ms)")
    @MainActor
    func testUndoRestoration() async {
        let vm = CullingWorkspaceViewModel()
        let sample = createSamplePhotos(count: 5)
        vm.setPhotos(sample)
        vm.autoAdvance = true

        // Cull photo 0
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.photos[0].status == .selected)
        #expect(vm.currentIndex == 1)

        // Undo
        vm.undoLastAction()
        #expect(vm.photos[0].status == .candidate)
        #expect(vm.currentIndex == 0)
        #expect(vm.currentToast?.title == "Action Undone")
    }

    @Test("Zoom and Sync toggles update workspace state")
    @MainActor
    func testZoomAndSyncToggles() {
        let vm = CullingWorkspaceViewModel()

        #expect(vm.zoomState == .fit)
        vm.toggleZoom()
        #expect(vm.zoomState == .oneToOne)
        vm.toggleZoom()
        #expect(vm.zoomState == .fit)

        #expect(vm.isZoomSynced == true)
        vm.toggleZoomSync()
        #expect(vm.isZoomSynced == false)
        vm.toggleZoomSync()
        #expect(vm.isZoomSynced == true)
    }

    @Test("U-33: External culling destination routes Move/Copy off-card and cardCullSummary reports safe-to-format")
    @MainActor
    func testExternalDestinationAndCardCullSummary() async throws {
        let base = FileManager.default.temporaryDirectory
            .standardizedFileURL
            .resolvingSymlinksInPath()
            .appendingPathComponent("CardSummary_\(UUID().uuidString)", isDirectory: true)
        let cardDir = base.appendingPathComponent("SDCard", isDirectory: true)
        let ssdDir = base.appendingPathComponent("ExternalSSD", isDirectory: true)
        try FileManager.default.createDirectory(at: cardDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: ssdDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: base) }

        let p1 = cardDir.appendingPathComponent("IMG_0001.JPG")
        let p2 = cardDir.appendingPathComponent("IMG_0002.JPG")
        try Data("IMG1".utf8).write(to: p1)
        try Data("IMG2".utf8).write(to: p2)

        let suiteName = "com.photoselector.tests.extdest.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        defaults.externalCullingDestinationURL = ssdDir
        defaults.cullingDestinationFolderName = "Selection"

        let actor = AsyncCullingActor(trashService: DirectoryTrashService(rootDirectory: base))
        let vm = CullingWorkspaceViewModel(cullingActor: actor, userDefaults: defaults)
        vm.setPhotos([PhotoItem(url: p1), PhotoItem(url: p2)], rootURL: cardDir)

        // Initially: 2 undecided, not safe to format
        #expect(vm.cardCullSummary.undecidedCount == 2)
        #expect(vm.cardCullSummary.isSafeToFormat == false)

        // Move photo 1 (routes to ssdDir/Selection/IMG_0001.JPG) and Trash photo 2
        vm.cullCurrentPhoto(action: .move)
        vm.cullCurrentPhoto(action: .trash)

        // Wait for AsyncCullingActor to finish both jobs
        for _ in 0..<40 {
            if vm.cardCullSummary.inFlightCount == 0 { break }
            try await Task.sleep(nanoseconds: 25_000_000)
        }

        let summary = vm.cardCullSummary
        #expect(summary.keptCount == 1)
        #expect(summary.trashedCount == 1)
        #expect(summary.undecidedCount == 0)
        #expect(summary.inFlightCount == 0)
        #expect(summary.keepersOnCard == false)
        #expect(summary.isSafeToFormat == true)

        let externalKeeper = ssdDir.appendingPathComponent("Selection/IMG_0001.JPG")
        #expect(FileManager.default.fileExists(atPath: externalKeeper.path))
        #expect(!FileManager.default.fileExists(atPath: cardDir.appendingPathComponent("Selection/IMG_0001.JPG").path))
    }

    @Test("U-34: lowestSharpnessFirst sorts scored photos ascending before unscored and fires stop-threshold toast")
    @MainActor
    func testLowestSharpnessFirstSortAndStopThresholdToast() {
        let vm = CullingWorkspaceViewModel()
        vm.sharpnessStopThreshold = 70.0

        let pSharp = PhotoItem(
            url: URL(fileURLWithPath: "/tmp/IMG_0001.JPG"),
            scores: QualityScores(sharpness: 82.0, noise: 1, highlightClipping: 0, shadowClipping: 0)
        )
        let pBlurry = PhotoItem(
            url: URL(fileURLWithPath: "/tmp/IMG_0002.JPG"),
            scores: QualityScores(sharpness: 18.0, noise: 1, highlightClipping: 0, shadowClipping: 0)
        )
        let pUnscored = PhotoItem(
            url: URL(fileURLWithPath: "/tmp/IMG_0003.JPG"),
            scores: nil
        )
        let pMedium = PhotoItem(
            url: URL(fileURLWithPath: "/tmp/IMG_0004.JPG"),
            scores: QualityScores(sharpness: 45.0, noise: 1, highlightClipping: 0, shadowClipping: 0)
        )

        vm.setPhotos([pSharp, pBlurry, pUnscored, pMedium])
        vm.applySortOrder(.lowestSharpnessFirst)

        // Expected order: 18.0 (IMG_0002), 45.0 (IMG_0004), 82.0 (IMG_0001), unscored (IMG_0003)
        #expect(vm.photos.map(\.filename) == ["IMG_0002.JPG", "IMG_0004.JPG", "IMG_0001.JPG", "IMG_0003.JPG"])
        #expect(vm.currentIndex == 0)
        #expect(vm.currentToast == nil)

        // Advance to 45.0 -> below 70 threshold, no toast
        vm.navigateToNext()
        #expect(vm.currentIndex == 1)
        #expect(vm.currentToast == nil)

        // Advance to 82.0 -> reaches >= 70 threshold, shows toast!
        vm.navigateToNext()
        #expect(vm.currentIndex == 2)
        #expect(vm.currentToast?.title == "Sharpness 70 reached")
        #expect(vm.currentToast?.isPersistent == true)

        // Restore natural order
        vm.applySortOrder(.natural)
        #expect(vm.photos.map(\.filename) == ["IMG_0001.JPG", "IMG_0002.JPG", "IMG_0003.JPG", "IMG_0004.JPG"])
    }

    @Test("U-35: Culling actions append CullingDecisionLogEntry with EXIF and focusScore")
    @MainActor
    func testCullingDecisionLogRecording() {
        let vm = CullingWorkspaceViewModel()
        let item = PhotoItem(
            url: URL(fileURLWithPath: "/tmp/DSC0999.ARW"),
            scores: QualityScores(sharpness: 76.5, noise: 2, highlightClipping: 0, shadowClipping: 0),
            exif: ExifData(shutterSpeed: 0.002, aperture: 2.8, iso: 800, focalLength: 85)
        )
        vm.setPhotos([item])
        vm.cullCurrentPhoto(action: .move)

        #expect(vm.decisionLog.count == 1)
        let entry = vm.decisionLog[0]
        #expect(entry.filename == "DSC0999.ARW")
        #expect(entry.action == .move)
        #expect(entry.focusScore == 76.5)
        #expect(entry.iso == 800)
        #expect(entry.shutterSpeed == 0.002)
        #expect(entry.aperture == 2.8)
        #expect(entry.focalLength == 85.0)
    }
}
