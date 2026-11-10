import Testing
import Foundation
import CoreGraphics
import ImageIO
@testable import PhotoSelectorKit

/// Scanner that emits the given batches one after another (a progressive scan with settle).
private struct BatchScanner: DirectoryScanning {
    let batches: [[PhotoItem]]
    func scanStream(at directoryURL: URL, recursive: Bool, batchSize: Int, firstBatchSize: Int) -> AsyncStream<[PhotoItem]> {
        let batches = batches
        return AsyncStream { continuation in
            for batch in batches { continuation.yield(batch) }
            continuation.finish()
        }
    }
    func scanDirectory(at directoryURL: URL, recursive: Bool) async throws -> [PhotoItem] { batches.flatMap { $0 } }
}

@MainActor
private func makeDefaults(_ label: String) -> (UserDefaults, () -> Void) {
    let name = "com.photoselector.tests.sort.\(label).\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: name)!
    return (defaults, { defaults.removePersistentDomain(forName: name) })
}

private func photo(_ name: String, daysAgo: Double?) -> PhotoItem {
    PhotoItem(
        url: URL(fileURLWithPath: "/nonexistent/\(name)"),
        modificationDate: daysAgo.map { Date(timeIntervalSince1970: 1_800_000_000 - $0 * 86_400) }
    )
}

@MainActor private func waitUntil(_ timeout: Double = 5, _ condition: @MainActor () -> Bool) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if condition() { return true }
        try? await Task.sleep(nanoseconds: 20_000_000)
    }
    return condition()
}

@Suite("Sort order, Go to Photo and the scoring pass")
@MainActor
struct SortGoToAndScoringTests {

    // MARK: Go to Photo

    @Test("Digits are the position the counter shows; anything else matches a file name")
    func goToResolution() {
        let photos = ["IMG_0001.CR3", "IMG_0002.JPG", "holiday.jpg", "Sunset.png"].map { photo($0, daysAgo: nil) }
        #expect(GoToPhotoResolver.resolve(query: "", photos: photos) == .empty)
        #expect(GoToPhotoResolver.resolve(query: "  ", photos: photos) == .empty)
        #expect(GoToPhotoResolver.resolve(query: "3", photos: photos) == .index(2))
        #expect(GoToPhotoResolver.resolve(query: "1", photos: photos) == .index(0))
        #expect(GoToPhotoResolver.resolve(query: "0", photos: photos) == .noMatch)
        #expect(GoToPhotoResolver.resolve(query: "5", photos: photos) == .noMatch)
        #expect(GoToPhotoResolver.resolve(query: "sunset", photos: photos) == .index(3))      // stem, any case
        #expect(GoToPhotoResolver.resolve(query: "IMG_0002.JPG", photos: photos) == .index(1))
        #expect(GoToPhotoResolver.resolve(query: "img_0002", photos: photos) == .index(1))     // exact stem
        #expect(GoToPhotoResolver.resolve(query: "hol", photos: photos) == .index(2))          // prefix
        #expect(GoToPhotoResolver.resolve(query: "0002", photos: photos) == .index(1))         // digits are a position: 2
        #expect(GoToPhotoResolver.resolve(query: "nothing", photos: photos) == .noMatch)
        #expect(GoToPhotoResolver.resolve(query: "1", photos: []) == .noMatch)
    }

    @Test("The workspace jumps to the photo the query names and reports no match")
    func goToMovesTheWorkspace() {
        let (defaults, cleanup) = makeDefaults("goto"); defer { cleanup() }
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        vm.setPhotos(["a.jpg", "b.jpg", "c.jpg"].map { photo($0, daysAgo: nil) })
        #expect(vm.goToPhoto(matching: "3"))
        #expect(vm.currentIndex == 2)
        #expect(vm.goToPhoto(matching: "a.jpg"))
        #expect(vm.currentIndex == 0)
        #expect(!vm.goToPhoto(matching: "zzz"))
        #expect(vm.currentIndex == 0)
        vm.presentGoToPhoto()
        #expect(vm.isGoToPhotoPresented)
    }

    // MARK: Sort order

    @Test("Newest first is the default and the order is persisted")
    func sortOrderDefaultAndPersistence() {
        let (defaults, cleanup) = makeDefaults("persist"); defer { cleanup() }
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        #expect(vm.sortOrder == .newestFirst)
        vm.setPhotos([photo("a.jpg", daysAgo: 1), photo("b.jpg", daysAgo: 2)])
        vm.setSortOrder(.oldestFirst)
        #expect(defaults.workspaceSortOrder == .oldestFirst)
        #expect(vm.photos.map(\.filename) == ["b.jpg", "a.jpg"])
        // A second launch reads it back.
        #expect(CullingWorkspaceViewModel(userDefaults: defaults).sortOrder == .oldestFirst)
        vm.setSortOrder(.newestFirst)
        #expect(vm.photos.map(\.filename) == ["a.jpg", "b.jpg"])
        vm.setSortOrder(.fileName)
        #expect(vm.photos.map(\.filename) == ["a.jpg", "b.jpg"])
        #expect(WorkspaceSortOrder.allCases.map(\.title) == ["Newest First", "Oldest First", "File Name", "Lowest Sharpness First"])
    }

    @Test("Undated photos go last and ties fall back to the file name")
    func dateTieBreak() {
        let (defaults, cleanup) = makeDefaults("ties"); defer { cleanup() }
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        vm.setPhotos([photo("c.jpg", daysAgo: nil), photo("b.jpg", daysAgo: 1), photo("a.jpg", daysAgo: 1), photo("d.jpg", daysAgo: 5)])
        vm.applySortOrder(.newestFirst)
        #expect(vm.photos.map(\.filename) == ["a.jpg", "b.jpg", "d.jpg", "c.jpg"])
    }

    @Test("Settle re-order: an untouched session lands on the newest photo")
    func settleLandsOnNewest() async {
        let (defaults, cleanup) = makeDefaults("settle1"); defer { cleanup() }
        // Scan order is oldest first; the newest arrives in the last batch.
        let scanner = BatchScanner(batches: [
            [photo("old.jpg", daysAgo: 30)],
            [photo("mid.jpg", daysAgo: 10), photo("mid2.jpg", daysAgo: 9)],
            [photo("new.jpg", daysAgo: 1)],
        ])
        let vm = CullingWorkspaceViewModel(directoryScanner: scanner, userDefaults: defaults)
        vm.loadFolder(at: URL(fileURLWithPath: "/nonexistent/folder-settle1"))
        #expect(await waitUntil { !vm.isLoading })
        #expect(vm.photos.map(\.filename) == ["new.jpg", "mid2.jpg", "mid.jpg", "old.jpg"])
        #expect(vm.currentIndex == 0)
        #expect(vm.currentPhoto?.filename == "new.jpg")
    }

    @Test("Settle re-order: the photo on screen survives it once the user has navigated, and undo still finds its photo")
    func settleKeepsTheFrameOnScreen() async {
        let (defaults, cleanup) = makeDefaults("settle2"); defer { cleanup() }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("settle2_\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        func real(_ name: String, _ daysAgo: Double) -> PhotoItem {
            let url = root.appendingPathComponent(name)
            try? Data("x".utf8).write(to: url)
            return PhotoItem(url: url, modificationDate: Date(timeIntervalSince1970: 1_800_000_000 - daysAgo * 86_400))
        }
        // A scanner that holds the stream open until the test has navigated.
        final class Gate: @unchecked Sendable { var open = false }
        let gate = Gate()
        struct HeldScanner: DirectoryScanning {
            let first: [PhotoItem]; let rest: [PhotoItem]; let gate: Gate
            func scanStream(at directoryURL: URL, recursive: Bool, batchSize: Int, firstBatchSize: Int) -> AsyncStream<[PhotoItem]> {
                let (first, rest, gate) = (first, rest, gate)
                return AsyncStream { continuation in
                    continuation.yield(first)
                    Task {
                        while !gate.open { try? await Task.sleep(nanoseconds: 10_000_000) }
                        continuation.yield(rest)
                        continuation.finish()
                    }
                }
            }
            func scanDirectory(at directoryURL: URL, recursive: Bool) async throws -> [PhotoItem] { first + rest }
        }
        let old = real("old.jpg", 30), mid = real("mid.jpg", 10), new = real("new.jpg", 1)
        let vm = CullingWorkspaceViewModel(
            directoryScanner: HeldScanner(first: [old, mid], rest: [new], gate: gate),
            userDefaults: defaults
        )
        vm.autoAdvance = false
        vm.loadFolder(at: root)
        #expect(await waitUntil { vm.photos.count == 2 })
        vm.navigateToNext()                                  // the user is looking at mid.jpg
        #expect(vm.currentPhoto?.filename == "mid.jpg")
        gate.open = true
        #expect(await waitUntil { !vm.isLoading })
        #expect(vm.photos.map(\.filename) == ["new.jpg", "mid.jpg", "old.jpg"])
        #expect(vm.currentPhoto?.filename == "mid.jpg", "the frame on screen must survive the settle re-order")

        // An action recorded before the re-order is undone on the right photo after it.
        vm.cullCurrentPhoto(action: .move)
        #expect(await waitUntil { !FileManager.default.fileExists(atPath: mid.url.path) })
        vm.selectPhoto(at: 0)
        vm.undoLastAction()
        #expect(vm.currentPhoto?.filename == "mid.jpg")
        #expect(vm.photos.first(where: { $0.filename == "mid.jpg" })?.status == .candidate)
    }

    // MARK: Lowest sharpness first

    private func writeJPEG(_ url: URL, blur: Bool) throws {
        // A sharp chessboard, or a flat grey (no edges: lowest possible sharpness).
        let width = 256, height = 256
        var pixels = [UInt8](repeating: 128, count: width * height)
        if !blur {
            for y in 0..<height { for x in 0..<width { pixels[y * width + x] = ((x / 4) + (y / 4)) % 2 == 0 ? 20 : 235 } }
        }
        let image = SyntheticImageFactory.createGrayscaleCGImage(width: width, height: height, pixels: pixels)!
        let dest = CGImageDestinationCreateWithURL(url as CFURL, "public.jpeg" as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, image, nil)
        #expect(CGImageDestinationFinalize(dest))
    }

    @Test("Lowest sharpness first scores the whole library in the background, then orders it")
    func lowestSharpnessFirstScoresThenSorts() async throws {
        let (defaults, cleanup) = makeDefaults("lowest"); defer { cleanup() }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("lowest_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var items: [PhotoItem] = []
        for (i, blur) in [false, true, false, true, false].enumerated() {
            let url = root.appendingPathComponent("P\(i).jpg")
            try writeJPEG(url, blur: blur)
            items.append(PhotoItem(url: url))
        }
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        vm.setPhotos(items, rootURL: root)
        vm.setSortOrder(.lowestSharpnessFirst)
        #expect(vm.scoringProgress != nil, "a pass over the unscored photos must start")
        #expect(await waitUntil(15) { vm.scoringProgress == nil && vm.photos.allSatisfy { $0.scores != nil } })
        #expect(await waitUntil { vm.sortOrder == .lowestSharpnessFirst && vm.photos.first?.filename.hasPrefix("P") == true })
        let sharpness = vm.photos.compactMap { $0.scores?.sharpness }
        #expect(sharpness.count == 5)
        #expect(sharpness == sharpness.sorted(), "lowest sharpness must come first")
        #expect(Set(vm.photos.prefix(2).map(\.filename)) == ["P1.jpg", "P3.jpg"], "the flat frames are the two lowest")
        #expect(vm.currentIndex == 0)
    }

    @Test("The stop threshold of the settings toasts when a photo at or above it is reached")
    func stopThresholdFromSettings() {
        let (defaults, cleanup) = makeDefaults("stop"); defer { cleanup() }
        defaults.sharpnessStopThreshold = 60
        #expect(defaults.sharpnessStopThreshold == 60)
        defaults.sharpnessStopThreshold = 500
        #expect(defaults.sharpnessStopThreshold == 100)
        defaults.sharpnessStopThreshold = 60
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        func scored(_ name: String, _ s: Double) -> PhotoItem {
            PhotoItem(url: URL(fileURLWithPath: "/nonexistent/\(name)"),
                      scores: QualityScores(sharpness: s, noise: 0, highlightClipping: 0, shadowClipping: 0))
        }
        vm.setPhotos([scored("a.jpg", 10), scored("b.jpg", 40), scored("c.jpg", 75)])
        vm.applySortOrder(.lowestSharpnessFirst)
        #expect(vm.currentToast == nil)
        vm.navigateToNext(); vm.navigateToNext()
        #expect(vm.currentToast?.title == "Sharpness threshold reached (60+)")
        defaults.sharpnessStopThreshold = 0
        #expect(defaults.sharpnessStopThreshold == 0)
    }

    // MARK: Statistics coverage

    @Test("Statistics say how many photos the sharpness figures are based on")
    func statisticsCoverage() {
        func item(_ n: Int, scored: Bool) -> PhotoItem {
            PhotoItem(url: URL(fileURLWithPath: "/nonexistent/\(n).jpg"),
                      scores: scored ? QualityScores(sharpness: 50, noise: 0, highlightClipping: 0, shadowClipping: 0) : nil)
        }
        let partial = LibraryStatisticsEngine.calculate(from: (0..<17).map { item($0, scored: $0 < 12) })
        #expect(partial.sharpnessScoredCount == 12)
        #expect(partial.sharpnessCoverageText == "12 / 17 scored")
        #expect(!partial.isSharpnessComplete)
        #expect(partial.focusDistribution.values.reduce(0, +) == partial.sharpnessScoredCount, "distribution totals add up to the stated count")
        let full = LibraryStatisticsEngine.calculate(from: (0..<17).map { item($0, scored: true) })
        #expect(full.sharpnessCoverageText == "17 / 17 scored")
        #expect(full.isSharpnessComplete)
        #expect(full.focusDistribution.values.reduce(0, +) == 17)
    }

    @Test("Opening statistics scores the whole library so the figures cover all of it")
    func statisticsPassCoversTheLibrary() async throws {
        let (defaults, cleanup) = makeDefaults("statspass"); defer { cleanup() }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("statspass_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var items: [PhotoItem] = []
        for i in 0..<12 {
            let url = root.appendingPathComponent("S\(i).jpg")
            try writeJPEG(url, blur: i % 3 == 0)
            items.append(PhotoItem(url: url))
        }
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        vm.setPhotos(items, rootURL: root)
        vm.startLibraryScoring()
        #expect(await waitUntil(15) { vm.scoringProgress == nil && vm.photos.allSatisfy { $0.scores != nil } })
        let stats = LibraryStatisticsEngine.calculate(from: vm.photos)
        #expect(stats.sharpnessCoverageText == "12 / 12 scored")
        // Closing the sheet cancels a pass that is still running.
        vm.cancelLibraryScoring()
        #expect(vm.scoringProgress == nil)
    }

    // MARK: Copy tally

    @Test("Copied photos are counted, an undo takes them off again, and the tally text names them")
    func copyCountInTheTally() async throws {
        let (defaults, cleanup) = makeDefaults("copytally"); defer { cleanup() }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("copytally_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let items: [PhotoItem] = try (0..<3).map { i in
            let url = root.appendingPathComponent("C\(i).jpg")
            try Data("x".utf8).write(to: url)
            return PhotoItem(url: url)
        }
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        vm.setPhotos(items, rootURL: root)
        vm.autoAdvance = false
        #expect(vm.copiedCount == 0)
        vm.cullCurrentPhoto(action: .copy)
        vm.selectPhoto(at: 1)
        vm.cullCurrentPhoto(action: .copy)
        #expect(vm.copiedCount == 2)
        vm.undoLastAction()
        #expect(vm.copiedCount == 1)
        #expect(ChromeDensity.tallyAccessibilityText(selected: 0, trashed: 0, remaining: 2, copied: 1) == "0 selected, 1 copied, 0 trashed, 2 remaining")
        #expect(ChromeDensity.tallyAccessibilityText(selected: 1, trashed: 0, remaining: 2) == "1 selected, 0 trashed, 2 remaining")
        // Let the background copies finish before the temp folder goes.
        _ = await waitUntil { FileManager.default.fileExists(atPath: root.appendingPathComponent("Selection/C0.jpg").path) }
    }

    // MARK: Companion set and file-count toasts

    @Test("A RAW + JPEG + XMP set shows its companions and a multi-file action counts them")
    func companionChipsAndCounts() {
        let raw = URL(fileURLWithPath: "/shoot/IMG_1.DNG")
        let kinds = CompanionSet.kinds(primary: raw, companions: [
            raw, URL(fileURLWithPath: "/shoot/IMG_1.jpg"), URL(fileURLWithPath: "/shoot/IMG_1.xmp"),
        ])
        #expect(kinds == [.jpeg, .sidecar])
        #expect(CompanionSet.summary(kinds) == "JPG, XMP")
        #expect(CompanionSet.spoken(kinds) == "Travels with JPEG file, XMP sidecar")
        let edit = CompanionSet.kinds(primary: raw, companions: [raw, URL(fileURLWithPath: "/shoot/IMG_1-Edit.tif"), URL(fileURLWithPath: "/shoot/IMG_1.CR3")])
        #expect(edit == [.raw, .edit])
        #expect(CompanionSet.kinds(primary: raw, companions: [raw]).isEmpty)
        #expect(CompanionSet.summary([]) == nil)

        #expect(CullingFeedback.fileCountTitle(action: .move, fileCount: 3) == "Moved 3 files")
        #expect(CullingFeedback.fileCountTitle(action: .copy, fileCount: 3) == "Copied 3 files")
        #expect(CullingFeedback.fileCountTitle(action: .trash, fileCount: 3) == "Trashed 3 files")
        #expect(CullingFeedback.restoredCountTitle(fileCount: 3) == "Restored 3 files")
        #expect(CullingFeedback.fileCountTitle(action: .move, fileCount: 1) == nil, "a single file keeps today's text")
        #expect(CullingFeedback.restoredCountTitle(fileCount: 1) == nil)
    }

    @Test("Moving, copying and trashing a RAW+JPEG+XMP set says how many files and leaves all three together")
    func setActionsToastTheCount() async throws {
        let (defaults, cleanup) = makeDefaults("setcount"); defer { cleanup() }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("setcount_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        for stem in ["A", "B", "C"] {
            for ext in ["DNG", "jpg", "xmp"] { try Data(ext.utf8).write(to: root.appendingPathComponent("\(stem).\(ext)")) }
        }
        let trashDir = root.appendingPathComponent(".SimulatedTrash")
        let vm = CullingWorkspaceViewModel(
            cullingActor: AsyncCullingActor(trashService: DirectoryTrashService(trashDirectory: trashDir)),
            userDefaults: defaults
        )
        vm.setPhotos(["A", "B", "C"].map { PhotoItem(url: root.appendingPathComponent("\($0).DNG")) }, rootURL: root)
        vm.autoAdvance = false

        func toast(after action: () -> Void, equals title: String) async -> Bool {
            action()
            return await waitUntil { vm.currentToast?.title == title }
        }
        #expect(await toast(after: { vm.cullCurrentPhoto(action: .move) }, equals: "Moved 3 files"))
        for ext in ["DNG", "jpg", "xmp"] { #expect(FileManager.default.fileExists(atPath: root.appendingPathComponent("Selection/A.\(ext)").path)) }
        vm.selectPhoto(at: 1)
        #expect(await toast(after: { vm.cullCurrentPhoto(action: .copy) }, equals: "Copied 3 files"))
        for ext in ["DNG", "jpg", "xmp"] {
            #expect(FileManager.default.fileExists(atPath: root.appendingPathComponent("Selection/B.\(ext)").path))
            #expect(FileManager.default.fileExists(atPath: root.appendingPathComponent("B.\(ext)").path))
        }
        vm.selectPhoto(at: 2)
        #expect(await toast(after: { vm.cullCurrentPhoto(action: .trash) }, equals: "Trashed 3 files"))
        #expect(await waitUntil { !FileManager.default.fileExists(atPath: root.appendingPathComponent("C.jpg").path) })
        #expect(await toast(after: { vm.undoLastAction() }, equals: "Restored 3 files"))
        for ext in ["DNG", "jpg", "xmp"] { #expect(FileManager.default.fileExists(atPath: root.appendingPathComponent("C.\(ext)").path)) }
    }

    // MARK: Score labels

    @Test("Each score carries its scale, and the aesthetic value is never spoken as a star rating")
    func scoreScales() {
        let scores = QualityScores(sharpness: 59.6, noise: 0, highlightClipping: 0, shadowClipping: 0, aesthetic: 8.4)
        #expect(ScoreLabels.sharpnessBadge(scores) == "59.6 Acceptable")
        #expect(ScoreLabels.sharpness(59.6) == "Sharpness 59.6 / 100")
        #expect(ScoreLabels.aesthetics(8.4) == "Aesthetics 8.4 / 10")
        #expect(ScoreLabels.sharpnessTooltip(scores).contains("Blurry below 35, sharp from 70"))
        #expect(ScoreLabels.sharpnessTooltip(scores, blurryBelow: 20, sharpFrom: 60).contains("Blurry below 20, sharp from 60"))
        #expect(ScoreLabels.aestheticsTooltip(8.4).contains("Not a star rating"))
        #expect(ScoreLabels.sharpnessSpoken(scores) == "Sharpness 59.6 out of 100, Acceptable")
        #expect(ScoreLabels.aestheticsSpoken(8.4) == "Aesthetics score 8.4 out of 10, not a star rating")
    }

    @Test("The documented ranges are the real ones: sharpness 0...100, aesthetics 1...10")
    func scalesAreReal() {
        let sharp = FocusMetricService.evaluateSync(cgImage: SyntheticImageFactory.createGrayscaleCGImage(
            width: 128, height: 128, pixels: (0..<128 * 128).map { (($0 % 128 / 2) + ($0 / 128 / 2)) % 2 == 0 ? 0 : 255 })!)
        #expect((0.0...100.0).contains(sharp?.score ?? -1))
        for overall: Float in [-5, -1, 0, 1, 5] {
            let evaluation = AestheticEvaluation(overallScore: overall, isUtility: false)
            #expect((1.0...10.0).contains(evaluation.mappedScore))
        }
    }

    @Test("MotionPolicy applies the change exactly once, animated or not (Reduce Motion)")
    func motionPolicyAppliesTheChange() {
        var runs = 0
        MotionPolicy.animate(.easeInOut(duration: 0.1)) { runs += 1 }
        #expect(runs == 1)
    }
}
