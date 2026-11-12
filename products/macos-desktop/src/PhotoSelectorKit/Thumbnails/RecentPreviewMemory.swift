import Foundation
import CoreGraphics

/// The decoded previews of the photos the user looked at last, keyed by photo identity (not by
/// file path), so a photo that was Moved or Trashed and comes back with Undo is on screen at
/// once instead of as a black pane with a spinner while it is decoded again (REQ-MAC-NAV).
///
/// Bounded twice: by count and by decoded bytes (width x height x 4), so a long session never grows
/// it. Least recently used goes first. Nothing here depends on the file still existing.
public final class RecentPreviewMemory: @unchecked Sendable {

    /// Shared by every viewport of the window.
    public static let shared = RecentPreviewMemory()

    public static let defaultCountLimit = 8
    public static let defaultByteLimit = 160 * 1024 * 1024

    private let countLimit: Int
    private let byteLimit: Int
    private let lock = NSLock()               // guards `entries` and `order`
    private var entries: [UUID: (image: CGImage, bytes: Int, rank: Int)] = [:]
    private var order: [UUID] = []            // least recently used first

    public init(countLimit: Int = RecentPreviewMemory.defaultCountLimit,
                byteLimit: Int = RecentPreviewMemory.defaultByteLimit) {
        self.countLimit = max(1, countLimit)
        self.byteLimit = max(1, byteLimit)
    }

    /// Keeps `image` for `id`. A lower `rank` (a smaller tier, decoded first) never replaces a higher
    /// one, so a photo decided before its full preview arrived still comes back from Undo with the
    /// small picture on screen at once instead of a black pane; the default rank always wins.
    public func store(_ image: CGImage, for id: UUID, rank: Int = Int.max) {
        lock.lock(); defer { lock.unlock() }
        if let existing = entries[id], existing.rank > rank { return }
        let bytes = image.width * image.height * 4
        entries[id] = (image, bytes, rank)
        order.removeAll { $0 == id }
        order.append(id)
        evictLocked()
    }

    public func image(for id: UUID) -> CGImage? {
        lock.lock(); defer { lock.unlock() }
        guard let entry = entries[id] else { return nil }
        order.removeAll { $0 == id }
        order.append(id)
        return entry.image
    }

    public func removeAll() {
        lock.lock(); defer { lock.unlock() }
        entries.removeAll()
        order.removeAll()
    }

    public var count: Int {
        lock.lock(); defer { lock.unlock() }
        return entries.count
    }

    public var totalBytes: Int {
        lock.lock(); defer { lock.unlock() }
        return entries.values.reduce(0) { $0 + $1.bytes }
    }

    private func evictLocked() {
        // Always keep the newest entry, even when it alone exceeds the byte limit.
        while order.count > 1,
              order.count > countLimit || entries.values.reduce(0, { $0 + $1.bytes }) > byteLimit {
            let oldest = order.removeFirst()
            entries[oldest] = nil
        }
    }
}
