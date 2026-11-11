import Testing
import Foundation
import CoreGraphics
import ImageIO
@testable import PhotoSelectorKit

@Suite("ThumbnailLoader & ThumbnailCache Unit Tests")
struct ThumbnailLoaderTests {

    private func createTempImageFile(width: Int, height: Int, orientation: Int = 1) throws -> URL {
        let tempBase = FileManager.default.temporaryDirectory
            .standardizedFileURL
            .resolvingSymlinksInPath()
        let fileURL = tempBase.appendingPathComponent("thumb_test_\(UUID().uuidString).jpg")

        let tiffProps: [CFString: Any] = [
            kCGImagePropertyTIFFOrientation: orientation
        ]
        guard let data = SyntheticImageFactory.createJPEGDataWithMetadata(
            width: width,
            height: height,
            tiffProperties: tiffProps
        ) else {
            throw NSError(domain: "ThumbnailLoaderTests", code: 1, userInfo: nil)
        }

        try data.write(to: fileURL)
        return fileURL
    }

    // MARK: - ThumbnailCache Tests

    @Test("ThumbnailCache: Dual-tier key separation prevents collisions")
    func testDualTierKeySeparation() async throws {
        let cache = ThumbnailCache(memoryLimitBytes: 10 * 1024 * 1024, countLimit: 100)
        let sampleURL = URL(fileURLWithPath: "/tmp/sample.jpg")

        let pixelsSmall = [UInt8](repeating: 100, count: 64 * 64)
        let pixelsLarge = [UInt8](repeating: 200, count: 256 * 256)
        guard let smallCG = SyntheticImageFactory.createGrayscaleCGImage(width: 64, height: 64, pixels: pixelsSmall),
              let largeCG = SyntheticImageFactory.createGrayscaleCGImage(width: 256, height: 256, pixels: pixelsLarge) else {
            #expect(Bool(false), "Failed to create synthetic images")
            return
        }

        let keyFilmstrip = ThumbnailCacheKey(url: sampleURL, maxPixelSize: ThumbnailTier.filmstrip.rawValue)
        let keyPreview = ThumbnailCacheKey(url: sampleURL, maxPixelSize: ThumbnailTier.preview.rawValue)

        await cache.setImage(smallCG, for: keyFilmstrip)
        await cache.setImage(largeCG, for: keyPreview)

        let cachedSmall = await cache.image(for: keyFilmstrip)
        let cachedLarge = await cache.image(for: keyPreview)

        #expect(cachedSmall != nil)
        #expect(cachedLarge != nil)
        #expect(cachedSmall?.width == 64)
        #expect(cachedLarge?.width == 256)

        // Remove only preview
        await cache.removeImage(for: keyPreview)
        #expect(await cache.image(for: keyPreview) == nil)
        #expect(await cache.image(for: keyFilmstrip) != nil)

        // Invalidate URL removes all
        await cache.removeImages(for: sampleURL)
        #expect(await cache.image(for: keyFilmstrip) == nil)
    }

    // MARK: - ThumbnailLoader Decoding Tests

    @Test("ThumbnailLoader: Downsamples image to maxPixelSize")
    func testMaxPixelSizeClamping() throws {
        let imageURL = try createTempImageFile(width: 400, height: 200)
        defer { try? FileManager.default.removeItem(at: imageURL) }

        let decoded = ThumbnailLoader.decodeThumbnail(from: imageURL, maxPixelSize: 100)
        #expect(decoded != nil)
        guard let image = decoded else { return }

        let maxEdge = max(image.width, image.height)
        #expect(maxEdge <= 100, "Expected max edge <= 100, got \(maxEdge)")
    }

    @Test("ThumbnailLoader: Applies EXIF orientation transform correctly")
    func testOrientationTransform() throws {
        // Orientation 6: Top-Right (rotated 90 deg clockwise)
        // Original: width=200, height=100.
        // After 90 deg transform: width should be 100, height should be 200 (or downsampled maintaining aspect ratio).
        let imageURL = try createTempImageFile(width: 200, height: 100, orientation: 6)
        defer { try? FileManager.default.removeItem(at: imageURL) }

        let decoded = ThumbnailLoader.decodeThumbnail(from: imageURL, maxPixelSize: 200)
        #expect(decoded != nil)
        guard let image = decoded else { return }

        // When orientation 6 is respected, height is greater than width
        #expect(image.height > image.width, "Expected portrait orientation (height > width), got \(image.width)x\(image.height)")
    }

    @Test("ThumbnailLoader: Asynchronous loading and in-memory caching")
    func testAsyncLoadingAndCaching() async throws {
        let imageURL = try createTempImageFile(width: 120, height: 80)
        defer { try? FileManager.default.removeItem(at: imageURL) }

        let customCache = ThumbnailCache()
        let loader = ThumbnailLoader(cache: customCache)

        let loaded = await loader.loadThumbnail(for: imageURL, maxPixelSize: 100)
        #expect(loaded != nil)

        // Second load should hit cache
        let key = ThumbnailCacheKey(url: imageURL, maxPixelSize: 100)
        let cached = await customCache.image(for: key)
        #expect(cached != nil)
    }

    @Test("ThumbnailLoader: Prefetch window updates without crashing")
    func testPrefetchWindow() async throws {
        var items: [PhotoItem] = []
        var urls: [URL] = []
        for _ in 1...10 {
            let u = try createTempImageFile(width: 60, height: 60)
            urls.append(u)
            items.append(PhotoItem(id: UUID(), url: u, status: .candidate))
        }
        defer {
            for u in urls { try? FileManager.default.removeItem(at: u) }
        }

        let loader = ThumbnailLoader()
        await loader.updatePrefetchWindow(items: items, currentIndex: 4, maxPixelSize: 100)
        await loader.cancelAll()
    }

    // MARK: - Direction-aware prefetch window

    @Test("Prefetch window leans forward [i-2 ... i+3] and backward [i-3 ... i+2], same size")
    func testPrefetchRangeFollowsDirection() {
        let fwd = ThumbnailLoader.prefetchRange(count: 40, currentIndex: 20, windowBefore: 2, windowAfter: 3, direction: .forward)
        let back = ThumbnailLoader.prefetchRange(count: 40, currentIndex: 20, windowBefore: 2, windowAfter: 3, direction: .backward)
        #expect(fwd == 18...23)
        #expect(back == 17...22)
        #expect(fwd?.count == back?.count)
        // Clamped at the ends, empty input yields nothing.
        #expect(ThumbnailLoader.prefetchRange(count: 40, currentIndex: 1, windowBefore: 2, windowAfter: 3, direction: .backward) == 0...3)
        #expect(ThumbnailLoader.prefetchRange(count: 0, currentIndex: 0, windowBefore: 2, windowAfter: 3, direction: .forward) == nil)
    }

    @Test("Prefetch tasks that leave the window are cancelled when the direction turns")
    func testPrefetchCancelsTasksLeavingTheWindow() async {
        let items = (0..<30).map { PhotoItem(id: UUID(), url: URL(fileURLWithPath: "/nonexistent/IMG_\($0).jpg"), status: .candidate) }
        let loader = ThumbnailLoader(cache: ThumbnailCache(memoryLimitBytes: 1_000_000, countLimit: 50))
        await loader.updatePrefetchWindow(items: items, currentIndex: 10, windowBefore: 2, windowAfter: 3, direction: .forward)
        #expect(await loader.activePrefetchURLs() == Set(items[8...13].map(\.url)))
        await loader.updatePrefetchWindow(items: items, currentIndex: 9, windowBefore: 2, windowAfter: 3, direction: .backward)
        // Backward from 9: [6 ... 11]; 12 and 13 left the window, 6 and 7 joined.
        #expect(await loader.activePrefetchURLs() == Set(items[6...11].map(\.url)))
        await loader.cancelAll()
    }

    @Test("The workspace remembers the last navigation direction")
    @MainActor
    func testWorkspaceTracksBrowseDirection() {
        let items = (0..<10).map { PhotoItem(id: UUID(), url: URL(fileURLWithPath: "/nonexistent/IMG_\($0).jpg"), status: .candidate) }
        let vm = CullingWorkspaceViewModel(userDefaults: UserDefaults(suiteName: "lj-dir-\(UUID().uuidString)")!)
        vm.setPhotos(items, rootURL: URL(fileURLWithPath: "/nonexistent"))
        vm.currentIndex = 5
        #expect(vm.browseDirection == .forward)
        vm.currentIndex = 4
        #expect(vm.browseDirection == .backward)
        vm.currentIndex = 7
        #expect(vm.browseDirection == .forward)
    }
}

@Suite("RecentPreviewMemory")
struct RecentPreviewMemoryTests {
    private func image(_ side: Int) -> CGImage {
        SyntheticImageFactory.createGrayscaleCGImage(width: side, height: side, pixels: [UInt8](repeating: 100, count: side * side))!
    }

    @Test("A photo that left and came back (Trash, Move, Undo) is still there, and the memory stays bounded")
    func survivesAndStaysBounded() {
        let memory = RecentPreviewMemory(countLimit: 3, byteLimit: 10 * 1024 * 1024)
        let ids = (0..<5).map { _ in UUID() }
        for id in ids { memory.store(image(64), for: id) }
        #expect(memory.count == 3)
        #expect(memory.image(for: ids[0]) == nil)
        #expect(memory.image(for: ids[1]) == nil)
        for id in ids[2...] { #expect(memory.image(for: id) != nil) }
        // Reading refreshes: ids[2] survives the next store, ids[3] goes.
        _ = memory.image(for: ids[2])
        memory.store(image(64), for: UUID())
        #expect(memory.image(for: ids[2]) != nil)
        #expect(memory.image(for: ids[3]) == nil)
    }

    @Test("The byte limit bounds it even when the count would allow more")
    func byteLimit() {
        let memory = RecentPreviewMemory(countLimit: 100, byteLimit: 2 * 100 * 100 * 4)
        for _ in 0..<6 { memory.store(image(100), for: UUID()) }
        #expect(memory.totalBytes <= 2 * 100 * 100 * 4)
        #expect(memory.count == 2)
        // A single image above the limit is still kept (the newest is never dropped).
        let big = RecentPreviewMemory(countLimit: 4, byteLimit: 10)
        let id = UUID()
        big.store(image(50), for: id)
        #expect(big.image(for: id) != nil)
    }
}
