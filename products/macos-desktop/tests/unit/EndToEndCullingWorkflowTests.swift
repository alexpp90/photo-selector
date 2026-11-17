import Testing
import Foundation
import CoreGraphics
import ImageIO
@testable import PhotoSelectorKit

/// Helper test harness providing sandboxed directory creation and automatic cleanup.
struct TestDirectoryHarness: Sendable {
    let rootURL: URL

    init(prefix: String = "E2EWorkflow") throws {
        let tempBase = FileManager.default.temporaryDirectory
            .standardizedFileURL
            .resolvingSymlinksInPath()
        let dir = tempBase.appendingPathComponent("\(prefix)_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let canonicalPath = (try? dir.resourceValues(forKeys: [.canonicalPathKey]))?.canonicalPath ?? dir.path
        self.rootURL = URL(fileURLWithPath: canonicalPath, isDirectory: true)
    }

    func createSubdirectory(_ relativePath: String) throws -> URL {
        let sub = rootURL.appendingPathComponent(relativePath, isDirectory: true)
        try FileManager.default.createDirectory(at: sub, withIntermediateDirectories: true)
        return sub
    }

    func createFile(at relativePath: String, data: Data) throws -> URL {
        let fileURL = rootURL.appendingPathComponent(relativePath)
        let parent = fileURL.deletingLastPathComponent()
        if !FileManager.default.fileExists(atPath: parent.path) {
            try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        }
        try data.write(to: fileURL)
        return fileURL
    }

    func cleanup() {
        try? FileManager.default.removeItem(at: rootURL)
    }
}

@Suite("End-to-End Culling Workflow Tests")
struct EndToEndCullingWorkflowTests {

    private func createHarness(prefix: String = "E2EWorkflow") throws -> TestDirectoryHarness {
        try TestDirectoryHarness(prefix: prefix)
    }

    // MARK: - Test 1: Full Photographer Workflow
    @Test("Full Photographer Workflow: SD card ingestion, 3-Up King-of-the-Hill burst triage, focus metric evaluation, companion preservation, instant undo, duplicate cleanup, library statistics audit")
    @MainActor
    func testFullPhotographerWorkflow() async throws {
        let harness = try createHarness(prefix: "FullPhotographerWorkflow")
        defer { harness.cleanup() }

        // Step 1: Populate SD Card DCIM Structure with 10 photos
        // - 3 burst shots (Sony ARW + JPG + ARW.xmp): 1 sharp, 1 acceptable, 1 blurry
        // - 2 portrait shots
        // - 2 exact duplicate files
        // - 1 corrupted file (0-byte)
        _ = try harness.createSubdirectory("DCIM/100SONY")

        // 1. Sharp Burst Shot (High contrast edges)
        var sharpPixels = [UInt8](repeating: 0, count: 64 * 64)
        for y in 0..<64 {
            for x in 0..<64 {
                sharpPixels[y * 64 + x] = ((x / 4) + (y / 4)) % 2 == 0 ? 250 : 10
            }
        }
        guard let sharpImage = SyntheticImageFactory.createGrayscaleCGImage(width: 64, height: 64, pixels: sharpPixels) else {
            Issue.record("Failed to create sharp synthetic image")
            return
        }
        let sharpData = NSMutableData()
        guard let sharpDest = CGImageDestinationCreateWithData(sharpData as CFMutableData, "public.jpeg" as CFString, 1, nil) else {
            Issue.record("Failed to create destination for sharp image")
            return
        }
        CGImageDestinationAddImage(sharpDest, sharpImage, nil)
        CGImageDestinationFinalize(sharpDest)

        let sharpRaw = try harness.createFile(at: "DCIM/100SONY/DSC0001.ARW", data: (sharpData as Data) + Data([0xAA, 0xBB]))
        _ = try harness.createFile(at: "DCIM/100SONY/DSC0001.JPG", data: sharpData as Data)
        _ = try harness.createFile(at: "DCIM/100SONY/DSC0001.ARW.xmp", data: "<?xmp sidecar?>".data(using: .utf8)!)

        // 2. Acceptable Burst Shot (Moderate contrast)
        let burst2Data = SyntheticImageFactory.createJPEGDataWithMetadata(width: 64, height: 64) ?? Data()
        _ = try harness.createFile(at: "DCIM/100SONY/DSC0002.ARW", data: burst2Data + Data([0xCC, 0xDD]))
        _ = try harness.createFile(at: "DCIM/100SONY/DSC0002.JPG", data: burst2Data)

        // 3. Blurry Burst Shot (Uniform smooth image)
        let blurryPixels = [UInt8](repeating: 128, count: 64 * 64)
        guard let blurryImage = SyntheticImageFactory.createGrayscaleCGImage(width: 64, height: 64, pixels: blurryPixels) else {
            Issue.record("Failed to create blurry synthetic image")
            return
        }
        let blurryData = NSMutableData()
        guard let blurryDest = CGImageDestinationCreateWithData(blurryData as CFMutableData, "public.jpeg" as CFString, 1, nil) else {
            Issue.record("Failed to create destination for blurry image")
            return
        }
        CGImageDestinationAddImage(blurryDest, blurryImage, nil)
        CGImageDestinationFinalize(blurryDest)

        _ = try harness.createFile(at: "DCIM/100SONY/DSC0003.ARW", data: blurryData as Data)

        // 4. Portrait 1 & 2
        let p1Data = SyntheticImageFactory.createJPEGDataWithMetadata(width: 64, height: 64) ?? Data()
        _ = try harness.createFile(at: "DCIM/100SONY/DSC0004.JPG", data: p1Data)

        let p2Data = SyntheticImageFactory.createJPEGDataWithMetadata(width: 64, height: 64) ?? Data()
        _ = try harness.createFile(at: "DCIM/100SONY/DSC0005.JPG", data: p2Data)

        // 5. Duplicate of Portrait 2
        _ = try harness.createFile(at: "DCIM/100SONY/DSC0005_DUP.JPG", data: p2Data)

        // 6. Corrupted file (0-byte)
        _ = try harness.createFile(at: "DCIM/100SONY/DSC0006_CORRUPT.JPG", data: Data())

        // Step 2: Progressive Streaming Ingestion via CullingWorkspaceViewModel
        let trashService = DirectoryTrashService(rootDirectory: harness.rootURL)
        let vm = CullingWorkspaceViewModel(cullingActor: AsyncCullingActor(trashService: trashService))
        vm.loadFolder(at: harness.rootURL)

        var attempts = 0
        while vm.isLoading && attempts < 150 {
            try await Task.sleep(nanoseconds: 20_000_000)
            attempts += 1
        }
        #expect(!vm.isLoading, "Folder loading must complete within polling budget")
        #expect(vm.photos.count >= 6, "Expected at least 6 candidate photo items after pairing")

        // Step 3: Focus Metric Evaluation on Burst Photos
        let sharpVariance = FocusMetricService.computeLaplacianVariance(cgImage: sharpImage)
        let blurryVariance = FocusMetricService.computeLaplacianVariance(cgImage: blurryImage)
        #expect(sharpVariance != nil && sharpVariance! > 10.0, "Sharp image must have high focus variance")
        #expect(blurryVariance != nil && blurryVariance! < 1.0, "Blurry image must have near-zero focus variance")

        // Step 4: 3-Up Triplet King-of-the-Hill Burst Triage
        vm.setComparisonMode(.triplet)
        #expect(vm.comparisonMode == .triplet)
        #expect(vm.comparisonSlotIndices.count == 3)

        // Find index of blurry photo (DSC0003.ARW)
        if let blurryIndex = vm.photos.firstIndex(where: { $0.url.lastPathComponent == "DSC0003.ARW" }) {
            vm.selectPhoto(at: blurryIndex)
            vm.trashCurrentPhoto()
            #expect(vm.photos[blurryIndex].status == .trashed)
        }

        // Find index of sharp photo (DSC0001.ARW)
        if let sharpIndex = vm.photos.firstIndex(where: { $0.url.lastPathComponent == "DSC0001.ARW" }) {
            vm.selectPhoto(at: sharpIndex)
            vm.selectCurrentPhoto() // Move to Selection
            #expect(vm.photos[sharpIndex].status == .selected)
        }

        // Allow background disk I/O to settle
        try await Task.sleep(nanoseconds: 100_000_000)

        // Step 5: Companion Preservation Verification
        let selectionDir = harness.rootURL.appendingPathComponent("Selection")
        #expect(FileManager.default.fileExists(atPath: selectionDir.appendingPathComponent("DSC0001.ARW").path))
        #expect(FileManager.default.fileExists(atPath: selectionDir.appendingPathComponent("DSC0001.JPG").path))
        #expect(FileManager.default.fileExists(atPath: selectionDir.appendingPathComponent("DSC0001.ARW.xmp").path))

        // Step 6: Instant Undo
        vm.undoLastAction()
        if let sharpIndex = vm.photos.firstIndex(where: { $0.url.lastPathComponent == "DSC0001.ARW" }) {
            #expect(vm.photos[sharpIndex].status == .candidate)
        }

        // Wait for reverse disk I/O
        try await Task.sleep(nanoseconds: 100_000_000)
        #expect(FileManager.default.fileExists(atPath: sharpRaw.path), "Sharp RAW must be restored to original path")

        // Re-move sharp photo to Selection for statistics audit
        if let sharpIndex = vm.photos.firstIndex(where: { $0.url.lastPathComponent == "DSC0001.ARW" }) {
            vm.selectPhoto(at: sharpIndex)
            vm.selectCurrentPhoto()
        }

        // Step 7: Duplicate Finder Secondary Tool Simulation
        let dupVM = DuplicateFinderViewModel(
            targetDirectoryURL: harness.rootURL,
            photos: vm.photos,
            cullingManager: FileCullingManager(trashService: trashService),
            onPhotoTrashed: { trashedURL in
                vm.markPhotoAsTrashed(url: trashedURL)
            }
        )
        dupVM.startScan()

        var dupAttempts = 0
        while dupVM.isScanning && dupAttempts < 250 {
            try await Task.sleep(nanoseconds: 20_000_000)
            dupAttempts += 1
        }
        #expect(!dupVM.isScanning, "Duplicate scan must complete within polling budget")
        let targetGroup = dupVM.duplicateGroups.first(where: { group in
            group.entries.contains(where: { $0.url.lastPathComponent == "DSC0005_DUP.JPG" })
        })
        #expect(targetGroup != nil, "Duplicate Finder must detect duplicate group containing DSC0005_DUP.JPG")

        if let group = targetGroup {
            for entry in group.entries {
                let isDup = entry.url.lastPathComponent == "DSC0005_DUP.JPG"
                if entry.isMarkedForTrash != isDup {
                    dupVM.toggleTrashMark(entryID: entry.url, in: group.id)
                }
            }
        }
        dupVM.trashMarkedDuplicates()

        // Verify synchronized trashing in workspace
        let dupPhoto = vm.photos.first(where: { $0.url.lastPathComponent == "DSC0005_DUP.JPG" })
        #expect(dupPhoto?.status == .trashed, "Duplicate photo must be synchronized as trashed in workspace")

        // Step 8: Library Statistics Audit
        let stats = LibraryStatisticsEngine.calculate(from: vm.photos)
        #expect(stats.totalPhotoCount == vm.photos.count)
        #expect(stats.selectedCount >= 1)
        #expect(stats.trashedCount >= 2)
    }

    // MARK: - Test 2: Rapid Keyboard Culling Burst with In-Flight Undo
    @Test("Rapid Keyboard Culling Burst with In-Flight Undo: Stressing FIFO ordering and disk reversibility")
    @MainActor
    func testRapidKeyboardCullingBurstWithInFlightUndo() async throws {
        let harness = try createHarness(prefix: "BurstWithUndo")
        defer { harness.cleanup() }

        let count = 15
        var items: [PhotoItem] = []
        var urls: [URL] = []

        for i in 1...count {
            let data = SyntheticImageFactory.createJPEGDataWithMetadata(width: 64, height: 64) ?? Data()
            let url = try harness.createFile(at: "BURST_\(i).JPG", data: data)
            urls.append(url)
            items.append(PhotoItem(id: UUID(), url: url, status: .candidate))
        }

        let vm = CullingWorkspaceViewModel(
            cullingActor: AsyncCullingActor(trashService: DirectoryTrashService(rootDirectory: harness.rootURL))
        )
        vm.setPhotos(items, rootURL: harness.rootURL)
        vm.autoAdvance = false

        // Rapidly cull first 5 photos (Move, Copy, Trash)
        for i in 0..<5 {
            vm.selectPhoto(at: i)
            let action: CullingActionType = (i % 3 == 0) ? .move : ((i % 3 == 1) ? .copy : .trash)
            vm.cullCurrentPhoto(action: action)
        }

        // Fire 5 instant undos immediately without waiting for disk I/O to settle
        for _ in 0..<5 {
            vm.undoLastAction()
        }

        // Allow actor queue to settle and drain
        try await Task.sleep(nanoseconds: 250_000_000)

        // Verify all 5 photos returned to .candidate status
        for i in 0..<5 {
            #expect(vm.photos[i].status == .candidate, "Photo \(i) should have returned to .candidate after undo")
            #expect(FileManager.default.fileExists(atPath: urls[i].path), "Original file \(urls[i].lastPathComponent) must exist on disk")
        }
    }

    // MARK: - Test 3: Large-Scale Library Performance & Progress Tracking
    @Test("Large-Scale Library Performance & Progress Tracking: 100+ candidates stream, navigate, and audit within SLA")
    @MainActor
    func testLargeScaleLibraryPerformanceAndProgressTracking() async throws {
        let harness = try createHarness(prefix: "LargeLibraryPerf")
        defer { harness.cleanup() }

        // Construct 100 photos in simulated folder structure
        let totalItems = 100
        var items: [PhotoItem] = []
        let dummyData = SyntheticImageFactory.createJPEGDataWithMetadata(width: 32, height: 32) ?? Data("MOCK".utf8)

        for i in 1...totalItems {
            let path = "DCIM/BATCH1/PHOTO_\(String(format: "%03d", i)).JPG"
            let url = try harness.createFile(at: path, data: dummyData)
            items.append(PhotoItem(id: UUID(), url: url, status: .candidate))
        }

        let vm = CullingWorkspaceViewModel()
        vm.loadFolder(at: harness.rootURL)

        var attempts = 0
        while vm.isLoading && attempts < 200 {
            try await Task.sleep(nanoseconds: 10_000_000)
            attempts += 1
        }
        #expect(!vm.isLoading, "100 photo streaming must complete within polling budget")
        // How fast the stream is belongs to the scale contract (ScaleContractTests, the UI smoke
        // test), never to a wall-clock bound here: this test proves every photo arrives.
        #expect(vm.photos.count == totalItems)

        // Rapid navigation across all 100 photos
        let navStart = CFAbsoluteTimeGetCurrent()
        for _ in 0..<totalItems - 1 {
            vm.navigateToNext()
        }
        let navElapsed = CFAbsoluteTimeGetCurrent() - navStart
        #expect(vm.currentIndex == totalItems - 1)
        // Hang guard only (10 ms per key): the PR gate never asserts absolute milliseconds, and growth
        // of navigation is `ScaleContractTests.navigationKeysAtTheEndStayCheap`.
        #expect(navElapsed < Double(totalItems) * 0.010, "Navigating 100 photos took \(navElapsed)s, over the 10 ms per key hang guard")

        // Instant statistics computation
        let statsStart = CFAbsoluteTimeGetCurrent()
        let stats = LibraryStatisticsEngine.calculate(from: vm.photos)
        let statsElapsed = CFAbsoluteTimeGetCurrent() - statsStart

        #expect(stats.totalPhotoCount == totalItems)
        #expect(stats.candidateCount == totalItems)
        #expect(statsElapsed < 0.05, "LibraryStatistics computation must take < 50ms (elapsed: \(statsElapsed)s)")
    }
}
