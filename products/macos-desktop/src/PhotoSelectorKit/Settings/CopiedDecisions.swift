import Foundation

/// A Copy to the Selection is a decision that survives closing and reopening the folder
/// (docs/shared/FILE_OPERATIONS.md §5, maintainer decision 2026-11-16): a copied photo is not
/// undecided again after a reopen.
///
/// The record is local app state, never written into the photo or its folder. A photo is
/// identified by its path relative to the opened folder plus its modification time in whole
/// seconds, both known from the folder listing, so marking a streamed batch costs no file
/// access. A photo edited since (new time) or renamed is treated as a new photo: a stale
/// "copied" mark is worse than a missing one.
///
/// Like `RecentFolders` this is session state, not a preference: it is kept out of
/// `AppSettingsKeys.all`.
public struct CopiedDecisions {
    /// Folders that keep records (least recently written dropped first).
    public static let folderLimit = 50
    /// Records kept per folder (oldest dropped first).
    public static let entryLimit = 20_000
    static let recordsKey = "lensjet.copiedDecisions"
    static let orderKey = "lensjet.copiedDecisionFolders"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private static func folderPath(_ folder: URL) -> String {
        folder.standardizedFileURL.resolvingSymlinksInPath().path
    }

    /// The identity of `photo` inside `folder`; nil when its time is unknown or it is outside.
    public static func identity(of url: URL, modified: Date?, in folder: URL) -> String? {
        guard let modified else { return nil }
        let root = folderPath(folder)
        let path = url.standardizedFileURL.resolvingSymlinksInPath().path
        guard path.hasPrefix(root + "/") else { return nil }
        return "\(path.dropFirst(root.count + 1))|\(Int64(modified.timeIntervalSince1970.rounded(.down)))"
    }

    private var records: [String: [String]] {
        defaults.dictionary(forKey: Self.recordsKey) as? [String: [String]] ?? [:]
    }

    /// The identities of the copied photos of `folder`.
    public func copied(in folder: URL) -> Set<String> {
        Set(records[Self.folderPath(folder)] ?? [])
    }

    /// Marks one photo as copied.
    public func add(_ identity: String, in folder: URL) {
        let path = Self.folderPath(folder)
        var all = records
        var entries = all[path] ?? []
        guard !entries.contains(identity) else { return }
        entries.append(identity)
        if entries.count > Self.entryLimit { entries.removeFirst(entries.count - Self.entryLimit) }
        all[path] = entries
        var order = (defaults.stringArray(forKey: Self.orderKey) ?? []).filter { $0 != path }
        order.append(path)
        while order.count > Self.folderLimit {
            all.removeValue(forKey: order.removeFirst())
        }
        defaults.set(all, forKey: Self.recordsKey)
        defaults.set(order, forKey: Self.orderKey)
    }

    /// Takes the mark away: the Copy was undone.
    public func remove(_ identity: String, in folder: URL) {
        let path = Self.folderPath(folder)
        var all = records
        guard var entries = all[path], entries.contains(identity) else { return }
        entries.removeAll { $0 == identity }
        all[path] = entries.isEmpty ? nil : entries
        defaults.set(all, forKey: Self.recordsKey)
    }

    /// Forgets everything (tests; no UI today).
    public func clear() {
        defaults.removeObject(forKey: Self.recordsKey)
        defaults.removeObject(forKey: Self.orderKey)
    }

    /// `items` with every undecided photo whose identity is in `copied` marked `.copied`.
    public static func marking(_ items: [PhotoItem], copied: Set<String>, in folder: URL) -> [PhotoItem] {
        guard !copied.isEmpty else { return items }
        return items.map { item in
            guard item.status == .candidate,
                  let identity = identity(of: item.url, modified: item.modificationDate, in: folder),
                  copied.contains(identity) else { return item }
            var marked = item
            marked.status = .copied
            return marked
        }
    }
}
