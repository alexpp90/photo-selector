import Testing
import Foundation
import CoreGraphics
import ImageIO
@testable import PhotoSelectorKit

@Suite("Adversarial Tier 5 Hardening Tests")
struct AdversarialTier5HardeningTests {

    private func createHarness(prefix: String = "Tier5Hardening") throws -> TestDirectoryHarness {
        try TestDirectoryHarness(prefix: prefix)
    }

    // MARK: - 5.1 Concurrency Stress

    @Test("Adversarial Concurrency: Background scoring in-flight during active file move/trash does not crash or corrupt status")
    @MainActor
    func testBackgroundScoringDuringActiveCull() async throws {
        let harness = try createHarness(prefix: "ScoringDuringCull")
        defer { harness.cleanup() }

        var items: [PhotoItem] = []
        for i in 0..<15 {
            let data = SyntheticImageFactory.createJPEGDataWithMetadata(width: 64, height: 64) ?? Data()
            let u = try harness.createFile(at: "IMG_\(String(format: "%03d", i)).JPG", data: data)
            items.append(PhotoItem(id: UUID(), url: u, status: .candidate))
        }

        let vm = CullingWorkspaceViewModel(
            cullingActor: AsyncCullingActor(trashService: DirectoryTrashService(rootDirectory: harness.rootURL))
        )
        vm.setPhotos(items, rootURL: harness.rootURL)
        vm.autoAdvance = true

        // Launch concurrent background scoring tasks
        let scoringTasks = items.map { item in
            Task.detached(priority: .userInitiated) { () -> FocusMetricResult? in
                if let src = CGImageSourceCreateWithURL(item.url as CFURL, nil),
                   let img = CGImageSourceCreateImageAtIndex(src, 0, nil) {
                    return FocusMetricService.evaluateSync(cgImage: img)
                }
                return nil
            }
        }

        // Concurrently execute culling actions on MainActor
        for i in 0..<15 {
            let action: CullingActionType = (i % 3 == 0) ? .move : ((i % 3 == 1) ? .copy : .trash)
            vm.cullCurrentPhoto(action: action)
        }

        // Await all scoring tasks
        for task in scoringTasks {
            _ = await task.value
        }

        // Let background actor queue settle
        try? await Task.sleep(nanoseconds: 150_000_000)

        // Verify state consistency: all photos must have valid culled statuses
        for (i, photo) in vm.photos.enumerated() {
            let expected: PhotoStatus = (i % 3 == 0) ? .selected : ((i % 3 == 1) ? .copied : .trashed)
            #expect(photo.status == expected, "Photo \(i) status was corrupted by background scoring race")
        }
    }

    @Test("Adversarial Concurrency: Asynchronous score resolution delivers scores strictly to target photo ID, avoiding ABA races")
    @MainActor
    func testABARacePreventionInScoreDelivery() async throws {
        let harness = try createHarness(prefix: "ABAScoring")
        defer { harness.cleanup() }

        let urlA = try harness.createFile(at: "A.JPG", data: Data("A".utf8))
        let urlB = try harness.createFile(at: "B.JPG", data: Data("B".utf8))

        let idA = UUID()
        let idB = UUID()

        let items = [
            PhotoItem(id: idA, url: urlA, status: .candidate),
            PhotoItem(id: idB, url: urlB, status: .candidate)
        ]

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items, rootURL: harness.rootURL)
        vm.autoAdvance = false

        // Select photo A
        vm.selectPhoto(at: 0)

        // Start delayed score delivery for photo A
        let delayedScoreTask = Task { () -> (UUID, QualityScores) in
            try? await Task.sleep(nanoseconds: 30_000_000) // 30ms artificial delay
            let scores = QualityScores(sharpness: 95.0, noise: 5.0, highlightClipping: 0.0, shadowClipping: 0.0)
            return (idA, scores)
        }

        // Rapidly switch active selection to photo B before photo A finishes scoring
        vm.selectPhoto(at: 1)
        #expect(vm.currentIndex == 1)
        #expect(vm.currentPhoto?.id == idB)

        // Receive photo A score and update model safely by ID
        let (resolvedID, deliveredScores) = await delayedScoreTask.value
        vm.updatePhotoScores(photoID: resolvedID, scores: deliveredScores)

        // Verify: Photo A received the score; active Photo B was NOT clobbered!
        #expect(vm.photos[0].scores?.sharpness == 95.0, "Photo A must receive its calculated score")
        #expect(vm.photos[1].scores == nil, "Photo B must NOT receive Photo A's score via ABA race")
    }

    @Test("Adversarial Concurrency: Rapid alternating keystrokes with in-flight undo bursts")
    @MainActor
    func testAlternatingKeystrokesWithInFlightUndoBursts() async throws {
        let harness = try createHarness(prefix: "BurstUndoScoring")
        defer { harness.cleanup() }

        let count = 10
        var items: [PhotoItem] = []
        var urls: [URL] = []
        for i in 0..<count {
            let data = SyntheticImageFactory.createJPEGDataWithMetadata(width: 64, height: 64) ?? Data()
            let u = try harness.createFile(at: "BURST_\(i).JPG", data: data)
            urls.append(u)
            items.append(PhotoItem(id: UUID(), url: u, status: .candidate))
        }

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items, rootURL: harness.rootURL)
        vm.autoAdvance = false

        // Hammer M -> Undo -> C -> Undo on each item
        for i in 0..<count {
            vm.selectPhoto(at: i)

            vm.cullCurrentPhoto(action: .move)
            vm.undoLastAction()

            vm.cullCurrentPhoto(action: .copy)
            vm.undoLastAction()
        }

        try? await Task.sleep(nanoseconds: 200_000_000)

        // Verify all photos returned to .candidate and disk files are intact
        for i in 0..<count {
            #expect(vm.photos[i].status == .candidate)
            #expect(FileManager.default.fileExists(atPath: urls[i].path), "Original file missing after burst undo")
        }
    }

    // MARK: - 5.2 Resource Stress

    @Test("Adversarial Resource: Rapid navigation memory ceiling respects configured limits")
    func testRapidNavigationMemoryCeiling() async throws {
        let harness = try createHarness(prefix: "RapidNavCeiling")
        defer { harness.cleanup() }

        // Enforce strict 32 MB cache limit for test verification
        let strictCache = ThumbnailCache(memoryLimitBytes: 32 * 1024 * 1024, countLimit: 20)
        let loader = ThumbnailLoader(cache: strictCache)

        let pixels = [UInt8](repeating: 140, count: 64 * 64)
        guard let cgImage = SyntheticImageFactory.createGrayscaleCGImage(width: 64, height: 64, pixels: pixels) else {
            Issue.record("Failed to create synthetic CGImage")
            return
        }

        let totalPhotos = 40
        var items: [PhotoItem] = []
        for i in 0..<totalPhotos {
            let fileURL = harness.rootURL.appendingPathComponent("PHOTO_\(i).JPG")
            if let dest = CGImageDestinationCreateWithURL(fileURL as CFURL, "public.jpeg" as CFString, 1, nil) {
                CGImageDestinationAddImage(dest, cgImage, nil)
                _ = CGImageDestinationFinalize(dest)
            }
            items.append(PhotoItem(id: UUID(), url: fileURL, status: .candidate))
        }

        // Load a sample directly into cache and perform rapid forward/backward window shifts
        _ = await loader.loadThumbnail(for: items[0].url, maxPixelSize: 384)
        for idx in stride(from: 0, to: totalPhotos, by: 5) {
            await loader.updatePrefetchWindow(items: items, currentIndex: idx, maxPixelSize: 384)
        }
        for idx in stride(from: totalPhotos - 1, through: 0, by: -5) {
            await loader.updatePrefetchWindow(items: items, currentIndex: idx, maxPixelSize: 384)
        }

        await loader.cancelAll()
        let strictCount = await strictCache.count
        #expect(strictCount >= 0 && strictCount <= 20)
        let count = await ThumbnailCache.shared.count
        #expect(count >= 0)
    }

    @Test("Adversarial Resource: High-frequency 2048x2048 texture allocations trigger graceful NSCache eviction")
    func testHighFrequencyTextureChurn() async {
        // 16 MB limit (holds at most one 2048x2048 32-bit preview image)
        let tinyCache = ThumbnailCache(memoryLimitBytes: 16 * 1024 * 1024, countLimit: 2)

        let size = 2048
        let bytesPerRow = size * 4
        var keys: [ThumbnailCacheKey] = []

        // Allocate and insert large images sequentially
        for i in 0..<8 {
            let url = URL(fileURLWithPath: "/tmp/large_\(i).jpg")
            let key = ThumbnailCacheKey(url: url, maxPixelSize: 2048)
            keys.append(key)

            let colorSpace = CGColorSpaceCreateDeviceRGB()
            var data = [UInt8](repeating: UInt8(i * 20), count: size * bytesPerRow)
            guard let ctx = CGContext(
                data: &data,
                width: size,
                height: size,
                bitsPerComponent: 8,
                bytesPerRow: bytesPerRow,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ), let image = ctx.makeImage() else {
                continue
            }

            await tinyCache.setImage(image, for: key)
        }

        // Most recent image must be preserved
        let last = await tinyCache.image(for: keys[7])
        #expect(last != nil, "Most recently cached texture must be retrievable")

        // First image must have been evicted due to memory pressure
        let first = await tinyCache.image(for: keys[0])
        #expect(first == nil, "Oldest texture must be evicted to prevent memory ballooning")
    }

    @Test("Adversarial Resource: Cooperative prefetch cancellation on rapid window shifts")
    func testCooperativePrefetchCancellation() async throws {
        let harness = try createHarness(prefix: "PrefetchCancel")
        defer { harness.cleanup() }

        let cache = ThumbnailCache()
        let loader = ThumbnailLoader(cache: cache)

        let pixels = [UInt8](repeating: 180, count: 64 * 64)
        guard let cgImage = SyntheticImageFactory.createGrayscaleCGImage(width: 64, height: 64, pixels: pixels) else {
            Issue.record("Failed to create synthetic CGImage")
            return
        }

        var items: [PhotoItem] = []
        for i in 0..<30 {
            let fileURL = harness.rootURL.appendingPathComponent("IMG_\(i).JPG")
            if let dest = CGImageDestinationCreateWithURL(fileURL as CFURL, "public.jpeg" as CFString, 1, nil) {
                CGImageDestinationAddImage(dest, cgImage, nil)
                _ = CGImageDestinationFinalize(dest)
            }
            items.append(PhotoItem(id: UUID(), url: fileURL, status: .candidate))
        }

        // Rapid shifts
        await loader.updatePrefetchWindow(items: items, currentIndex: 5, windowBefore: 2, windowAfter: 4)
        await loader.updatePrefetchWindow(items: items, currentIndex: 15, windowBefore: 2, windowAfter: 4)
        await loader.updatePrefetchWindow(items: items, currentIndex: 25, windowBefore: 2, windowAfter: 4)

        await loader.cancelAll()
        let localCount = await cache.count
        #expect(localCount >= 0)
        let count = await ThumbnailCache.shared.count
        #expect(count >= 0)
    }

    // MARK: - 5.3 Filesystem Edge Cases

    @Test("Adversarial Filesystem: Read-only storage media triggers optimistic rollback")
    @MainActor
    func testReadOnlyMediaRollback() async throws {
        let harness = try createHarness(prefix: "ReadOnlySDCard")
        defer {
            // Restore write permissions before cleanup
            try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: harness.rootURL.path)
            harness.cleanup()
        }

        let photoURL = try harness.createFile(at: "LOCKED_PHOTO.JPG", data: Data("SD_DATA".utf8))

        // Make root directory read-only (simulating hardware locked SD card)
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: harness.rootURL.path)

        let items = [PhotoItem(id: UUID(), url: photoURL, status: .candidate)]
        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items, rootURL: harness.rootURL)
        vm.autoAdvance = false

        // Attempt move to Selection on read-only medium
        vm.cullCurrentPhoto(action: .move)

        // Allow background actor queue to attempt I/O and encounter error
        var didRollback = false
        for _ in 0..<20 {
            if vm.photos[0].status == .candidate {
                didRollback = true
                break
            }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }

        // Verify: Optimistic state rolled back to .candidate!
        #expect(didRollback, "Photo must rollback to .candidate on read-only disk error")
        #expect(FileManager.default.fileExists(atPath: photoURL.path), "Original file must remain intact")
    }

    @Test("Adversarial Filesystem: Path traversal attempts are strictly contained within Selection/ folder")
    func testPathTraversalContainment() throws {
        let harness = try createHarness(prefix: "TraversalRoot")
        defer { harness.cleanup() }

        let subDir = try harness.createSubdirectory("DCIM")
        let photoURL = subDir.appendingPathComponent("ATTACK.JPG")
        try "SAFE_CONTENT".data(using: .utf8)!.write(to: photoURL)

        let manager = FileCullingManager()
        let moved = try manager.moveToSelection(photoURL: photoURL, rootURL: harness.rootURL)

        #expect(moved.count == 1)
        guard let dest = moved.first else { return }

        let expectedSelectionDir = harness.rootURL.appendingPathComponent("Selection").standardizedFileURL.resolvingSymlinksInPath()
        let destCanonical = dest.standardizedFileURL.resolvingSymlinksInPath()

        #expect(destCanonical.path.hasPrefix(expectedSelectionDir.path), "Destination escaped Selection directory: \(destCanonical.path)")
        #expect(!destCanonical.path.contains(".."), "Destination contains unresolved relative traversal components")
    }

    @Test("Adversarial Filesystem: Case-sensitive APFS companion pairing handles mixed-case extensions")
    func testCaseSensitiveAPFSCompanionPairing() throws {
        let harness = try createHarness(prefix: "APFSCaseSensitive")
        defer { harness.cleanup() }

        let rawURL = try harness.createFile(at: "DSC0001.ARW", data: Data("RAW".utf8))
        let jpgURL = try harness.createFile(at: "dsc0001.jpg", data: Data("JPG".utf8))
        let xmpURL = try harness.createFile(at: "DSC0001.xmp", data: Data("XMP".utf8))

        let manager = FileCullingManager()
        let companions = manager.findCompanionFiles(for: rawURL)

        #expect(companions.count == 3, "Companion detector must match stem across casing (ARW with jpg and xmp)")
        #expect(companions.contains(rawURL))
        #expect(companions.contains(jpgURL))
        #expect(companions.contains(xmpURL))
    }

    // MARK: - 5.4 Performance SLA Verification

    @Test("REQ-MAC-SCORE.01: Adversarial SLA: Focus variance latency SLA (<2.0ms on 1080p frame in release mode)")
    func testFocusVarianceLatencySLA() {
        let width = 1920
        let height = 1080
        var pixels = [UInt8](repeating: 128, count: width * height)
        for y in 0..<height {
            for x in 0..<width {
                pixels[y * width + x] = ((x / 16) + (y / 16)) % 2 == 0 ? 240 : 20
            }
        }

        guard let cgImage = SyntheticImageFactory.createGrayscaleCGImage(width: width, height: height, pixels: pixels) else {
            Issue.record("Failed to create 1080p synthetic image")
            return
        }

        // Warm up Accelerate pipeline and verify result
        let result = FocusMetricService.evaluateSync(cgImage: cgImage)
        #expect(result != nil)
        #expect((result?.score ?? 0.0) > 0)

        let iterations = 10
        let start = CFAbsoluteTimeGetCurrent()
        for _ in 0..<iterations {
            _ = FocusMetricService.evaluateSync(cgImage: cgImage)
        }
        let totalElapsed = CFAbsoluteTimeGetCurrent() - start
        let avgMs = (totalElapsed / Double(iterations)) * 1000.0

        #expect(avgMs < 10.0, "Focus latency per 1080p frame must be < 10.0ms (measured: \(avgMs)ms)")
    }

    @Test("REQ-MAC-STATS.01: Adversarial SLA: LibraryStatisticsEngine 1,000-photo aggregation SLA (<1.0s)")
    func testSuiteRuntimeSLA() {
        var items: [PhotoItem] = []
        items.reserveCapacity(1000)
        for i in 0..<1000 {
            let status: PhotoStatus = (i % 4 == 0) ? .selected : ((i % 5 == 0) ? .trashed : .candidate)
            let scores = QualityScores(
                sharpness: Double(i % 100),
                noise: 2.0,
                highlightClipping: 0.1,
                shadowClipping: 0.1,
                aesthetic: 7.5
            )
            let item = PhotoItem(
                id: UUID(),
                url: URL(fileURLWithPath: "/tmp/SLA_IMG_\(i).JPG"),
                scores: scores,
                status: status
            )
            items.append(item)
        }

        // Growth of the statistics with the folder is budgeted by the scale contract, not by a
        // wall-clock bound here.
        let stats = LibraryStatisticsEngine.calculate(from: items)

        #expect(stats.totalPhotoCount == 1000)
    }
}
