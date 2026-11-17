import Foundation
import CoreGraphics
import ImageIO

/// Standard image thumbnail and preview dimension tiers.
public enum ThumbnailTier: Int, Sendable {
    case quick = 320        // Instant placeholder from the embedded EXIF/RAW thumbnail
    case filmstrip = 384    // Compact bottom filmstrip / scrubber
    case preview = 2048     // High-resolution comparison and single-photo canvas
}

/// A decoded image plus the tier it was produced for (CGImage is immutable).
public struct ProgressiveImage: @unchecked Sendable {
    public let image: CGImage
    public let tier: ThumbnailTier

    public init(image: CGImage, tier: ThumbnailTier) {
        self.image = image
        self.tier = tier
    }
}

/// How a decode request is satisfied.
public enum ThumbnailDecodeStrategy: Equatable, Sendable {
    /// Use the thumbnail/preview embedded in the file when its long edge is at least
    /// `minimumLongEdge`; otherwise fall back to a full decode. Orders of magnitude faster for
    /// RAW files, whose full decode runs the RAW development pipeline.
    case embeddedPreferred(minimumLongEdge: Int)
    /// Always decode from the full image (sub-sampled by ImageIO).
    case fullDecode

    /// Policy: small tiers always try the embedded thumbnail; previews try it only for RAW
    /// (camera JPEG previews are ≥ 1 000 px), because a JPEG's embedded EXIF thumbnail is tiny
    /// while a sub-sampled JPEG decode is already fast.
    public static func forRequest(url: URL, maxPixelSize: Int) -> ThumbnailDecodeStrategy {
        if maxPixelSize <= ThumbnailTier.filmstrip.rawValue {
            return .embeddedPreferred(minimumLongEdge: 120)
        }
        let ext = url.pathExtension.lowercased()
        if SupportedFormats.rawExtensions.contains(ext) {
            return .embeddedPreferred(minimumLongEdge: min(1000, maxPixelSize))
        }
        return .fullDecode
    }
}

/// Actor-isolated asynchronous image thumbnail decoder and prefetcher.
/// Guarantees automatic EXIF orientation normalization, fast ImageIO sub-sampling,
/// and an asymmetric, direction-aware prefetch window ([i-2 ... i+3] while moving forward,
/// [i-3 ... i+2] while moving backward) with cooperative task cancellation.
public actor ThumbnailLoader {

    /// The way the user is browsing; the prefetch window leans the same way (REQ-MAC-NAV direction rule).
    public enum BrowseDirection: Sendable, Equatable {
        case forward
        case backward
    }

    /// The indices to keep decoded around `currentIndex`. Forward browsing keeps `windowBefore`
    /// frames behind and `windowAfter` ahead; backward browsing mirrors the two so the window
    /// keeps its size but leans toward the frames about to be shown.
    public static func prefetchRange(
        count: Int,
        currentIndex: Int,
        windowBefore: Int,
        windowAfter: Int,
        direction: BrowseDirection
    ) -> ClosedRange<Int>? {
        guard count > 0, (0..<count).contains(currentIndex) else { return nil }
        let before = max(0, min(windowBefore, 5))
        let after = max(1, min(windowAfter, 10))
        let (back, ahead) = direction == .forward ? (before, after) : (after, before)
        return max(0, currentIndex - back)...min(count - 1, currentIndex + ahead)
    }

    public static let shared = ThumbnailLoader()

    private let cache: ThumbnailCache
    private var inFlightTasks: [ThumbnailCacheKey: Task<CGImage?, Never>] = [:]
    /// Keys of in-flight tasks started by the prefetcher (the only ones it may cancel).
    private var prefetchKeys: Set<ThumbnailCacheKey> = []
    /// Which prefetch task owns each in-flight entry, so a finished or cancelled task never
    /// removes the entry of a successor for the same key.
    private var prefetchTokens: [ThumbnailCacheKey: UUID] = [:]

    public init(cache: ThumbnailCache = ThumbnailCache.shared) {
        self.cache = cache
    }

    // MARK: - Direct Image Loading

    /// Loads a thumbnail or preview for the given URL, checking cache first,
    /// then in-flight tasks, and finally decoding from disk.
    /// - Parameter cacheResult: `false` for one-off huge decodes (full-resolution zoom) that
    ///   would otherwise evict the whole preview working set.
    public func loadThumbnail(
        for url: URL,
        maxPixelSize: Int = ThumbnailTier.preview.rawValue,
        priority: TaskPriority = .userInitiated,
        cacheResult: Bool = true
    ) async -> CGImage? {
        let key = ThumbnailCacheKey(url: url, maxPixelSize: maxPixelSize)

        // 1. Check in-memory cache
        if let cached = await cache.image(for: key) {
            return cached
        }

        // 2. Reuse in-flight task if already decoding (promoting a prefetch so it is no
        //    longer cancellable by window updates — someone is now waiting on screen).
        if let existingTask = inFlightTasks[key] {
            prefetchKeys.remove(key)
            let result = await existingTask.value
            // The promoted task is done: drop it, or a finished decode stays here for good and is
            // handed back instead of a fresh load once the cache evicts its image (H-LJ-3).
            if inFlightTasks[key] == existingTask {
                inFlightTasks.removeValue(forKey: key)
                prefetchTokens.removeValue(forKey: key)
            }
            return result
        }

        // 3. Spawn background decode task
        let cacheRef = self.cache
        let task = Task.detached(priority: priority) { () -> CGImage? in
            guard let image = Self.decodeThumbnail(from: url, maxPixelSize: maxPixelSize) else {
                return nil
            }
            if cacheResult {
                await cacheRef.setImage(image, for: key)
            }
            return image
        }

        inFlightTasks[key] = task
        let result = await task.value
        if inFlightTasks[key] == task { inFlightTasks.removeValue(forKey: key) }

        return result
    }

    /// Decodes the photograph at its original resolution (orientation applied) without caching.
    /// Used only for true 1:1 inspection.
    public func loadFullResolution(for url: URL) async -> CGImage? {
        guard let size = Self.originalPixelSize(of: url) else { return nil }
        let longEdge = Int(max(size.width, size.height))
        return await loadThumbnail(for: url, maxPixelSize: longEdge, priority: .userInitiated, cacheResult: false)
    }

    /// Progressive display stream for one photograph:
    /// 1. the cached preview, if present (then the stream ends), otherwise
    /// 2. an instant `quick` placeholder from the embedded thumbnail, then
    /// 3. the full `preview` tier.
    /// Cancelling the consumer cancels the stream.
    public nonisolated func progressivePreview(for url: URL) -> AsyncStream<ProgressiveImage> {
        AsyncStream { continuation in
            let task = Task { [cache] in
                let previewKey = ThumbnailCacheKey(url: url, maxPixelSize: ThumbnailTier.preview.rawValue)
                if let cached = await cache.image(for: previewKey) {
                    continuation.yield(ProgressiveImage(image: cached, tier: .preview))
                    continuation.finish()
                    return
                }
                if let quick = await self.loadThumbnail(
                    for: url,
                    maxPixelSize: ThumbnailTier.quick.rawValue,
                    priority: .userInitiated
                ), !Task.isCancelled {
                    continuation.yield(ProgressiveImage(image: quick, tier: .quick))
                }
                guard !Task.isCancelled else {
                    continuation.finish()
                    return
                }
                if let preview = await self.loadThumbnail(
                    for: url,
                    maxPixelSize: ThumbnailTier.preview.rawValue,
                    priority: .userInitiated
                ), !Task.isCancelled {
                    continuation.yield(ProgressiveImage(image: preview, tier: .preview))
                }
                continuation.finish()
            }
            continuation.onTermination = { @Sendable _ in task.cancel() }
        }
    }

    /// Convenience method for preview loading.
    public func loadPreview(
        for url: URL,
        maxPixelSize: Int = ThumbnailTier.preview.rawValue
    ) async -> CGImage? {
        await loadThumbnail(for: url, maxPixelSize: maxPixelSize, priority: .userInitiated)
    }

    // MARK: - Prefetch Sliding Window [i-2 ... i+3]

    /// Updates the prefetch sliding window around the current index.
    /// Cancels obsolete decode tasks outside the window and launches
    /// background prefetch tasks at .utility priority for upcoming items.
    public func updatePrefetchWindow(
        items: [PhotoItem],
        currentIndex: Int,
        windowBefore: Int = 2,
        windowAfter: Int = 4,
        direction: BrowseDirection = .forward,
        maxPixelSize: Int = ThumbnailTier.preview.rawValue
    ) {
        guard let range = Self.prefetchRange(
            count: items.count, currentIndex: currentIndex,
            windowBefore: windowBefore, windowAfter: windowAfter, direction: direction
        ) else { return }
        let windowIndices = Set(range)

        var neededKeys = Set<ThumbnailCacheKey>()
        for idx in windowIndices {
            let item = items[idx]
            let key = ThumbnailCacheKey(url: item.url, maxPixelSize: maxPixelSize)
            neededKeys.insert(key)
        }

        // 1. Cancel obsolete *prefetch* tasks outside the active window. Direct, user-visible
        //    requests (quick placeholders, full-resolution zoom) are never cancelled here.
        for key in prefetchKeys where !neededKeys.contains(key) {
            inFlightTasks[key]?.cancel()
            inFlightTasks.removeValue(forKey: key)
            prefetchTokens.removeValue(forKey: key)
            prefetchKeys.remove(key)
        }

        // 2. Enqueue prefetch tasks for uncached window items
        let cacheRef = self.cache
        for key in neededKeys {
            if inFlightTasks[key] != nil || prefetchKeys.contains(key) { continue }

            let token = UUID()
            let task = Task.detached(priority: .utility) { [weak self] () -> CGImage? in
                let image = await Self.prefetchDecode(key: key, cache: cacheRef)
                await self?.prefetchFinished(key, token: token)
                return image
            }

            inFlightTasks[key] = task
            prefetchTokens[key] = token
            prefetchKeys.insert(key)
        }
    }

    private static func prefetchDecode(key: ThumbnailCacheKey, cache: ThumbnailCache) async -> CGImage? {
        if Task.isCancelled { return nil }
        // Avoid re-decoding if already in cache
        if let cached = await cache.image(for: key) { return cached }
        guard let image = decodeThumbnail(from: key.url, maxPixelSize: key.maxPixelSize) else { return nil }
        if Task.isCancelled { return nil }
        await cache.setImage(image, for: key)
        return image
    }

    /// A prefetch decode ended: forget its task (the window membership in `prefetchKeys` stays
    /// until the window moves). Ignored when the entry now belongs to someone else.
    private func prefetchFinished(_ key: ThumbnailCacheKey, token: UUID) {
        guard prefetchTokens[key] == token else { return }
        prefetchTokens.removeValue(forKey: key)
        inFlightTasks.removeValue(forKey: key)
    }

    /// Number of decodes the loader still tracks (tests: a finished decode must not stay).
    func inFlightCount() -> Int { inFlightTasks.count }

    /// URLs currently tracked in the active prefetch window.
    public func activePrefetchURLs() -> Set<URL> {
        Set(prefetchKeys.map(\.url))
    }

    /// Cancels all in-flight decode tasks.
    public func cancelAll() {
        for task in inFlightTasks.values {
            task.cancel()
        }
        inFlightTasks.removeAll()
        prefetchTokens.removeAll()
        prefetchKeys.removeAll()
    }

    // MARK: - Native ImageIO Decoder

    /// Pure ImageIO decoding method with automatic EXIF orientation transformation,
    /// max pixel size clamping, and immediate off-thread rasterization.
    /// The strategy defaults to `ThumbnailDecodeStrategy.forRequest(url:maxPixelSize:)`.
    public static func decodeThumbnail(
        from url: URL,
        maxPixelSize: Int,
        strategy: ThumbnailDecodeStrategy? = nil
    ) -> CGImage? {
        if Task.isCancelled { return nil }

        let sourceOptions: [CFString: Any] = [
            kCGImageSourceShouldCache: false
        ]

        guard let source = CGImageSourceCreateWithURL(url as CFURL, sourceOptions as CFDictionary) else {
            return nil
        }

        if Task.isCancelled { return nil }

        let resolved = strategy ?? ThumbnailDecodeStrategy.forRequest(url: url, maxPixelSize: maxPixelSize)
        var embedded: CGImage?
        if case .embeddedPreferred(let minimumLongEdge) = resolved {
            embedded = embeddedThumbnail(from: source, maxPixelSize: maxPixelSize)
            if let embedded, max(embedded.width, embedded.height) >= minimumLongEdge {
                return embedded
            }
            if Task.isCancelled { return nil }
        }

        // Embedded preview absent or too small (phone DNGs embed ~672 px): real decode.
        let thumbnailOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true
        ]
        if let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions as CFDictionary) {
            return decoded
        }
        if Task.isCancelled { return nil }

        // The real decode failed (ImageIO cannot develop e.g. Samsung SRW): the embedded
        // preview, whatever its size, beats a blank canvas.
        return embedded ?? embeddedThumbnail(from: source, maxPixelSize: maxPixelSize)
    }

    /// The embedded thumbnail/preview (or a decode when ImageIO finds none), oriented.
    private static func embeddedThumbnail(from source: CGImageSource, maxPixelSize: Int) -> CGImage? {
        let embeddedOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
            kCGImageSourceCreateThumbnailFromImageIfAbsent: true,
            kCGImageSourceShouldCacheImmediately: true
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, embeddedOptions as CFDictionary)
    }

    /// Original pixel dimensions of the primary image, **after** EXIF orientation
    /// (portrait captures report width < height). Reads only the header.
    public static func originalPixelSize(of url: URL) -> CGSize? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue,
              let height = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue,
              width > 0, height > 0 else {
            return nil
        }
        let orientation = (props[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        let swapsAxes = (5...8).contains(orientation)
        return swapsAxes ? CGSize(width: height, height: width) : CGSize(width: width, height: height)
    }
}
