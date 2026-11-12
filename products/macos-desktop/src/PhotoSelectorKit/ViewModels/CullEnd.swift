import Foundation

/// What the end of a cull looks like: nothing, a "N Remaining" notice on the last photo while
/// photos are undecided, or the "Done" card once every photo has a decision.
public enum CullEndState: Equatable, Sendable {
    case none
    /// On the last photo with `count` photos still undecided: offers "Show first".
    case remaining(count: Int)
    case done(CullEndCard)
}

/// The numbers and the destination on the "Done" card. Counts use the same words as the tally.
public struct CullEndCard: Equatable, Sendable {
    public let selected: Int
    public let copied: Int
    public let trashed: Int
    /// The `~`-abbreviated Selection path, the same text as Settings (REQ-MAC-SETTINGS.02); nil with no destination.
    public let destinationPath: String?
    /// Photos that were copied: their originals are still where they were (input of `SafeToFormat`).
    public let copiedURLs: [URL]
    /// Move and Copy file into the opened folder (its Selection is on the card being culled).
    public let selectionIsInsideSource: Bool

    public init(selected: Int, copied: Int, trashed: Int, destinationPath: String?,
                copiedURLs: [URL] = [], selectionIsInsideSource: Bool = false) {
        self.selected = selected
        self.copied = copied
        self.trashed = trashed
        self.destinationPath = destinationPath
        self.copiedURLs = copiedURLs
        self.selectionIsInsideSource = selectionIsInsideSource
    }
}

public enum CullEnd {
    /// The end state from the counts. A copied photo is decided (as in "Remaining"); a decision whose
    /// file operation is still running (`inFlight`) keeps the card back, so it never says "Done"
    /// while a file is still moving.
    public static func state(
        selected: Int, copied: Int, trashed: Int, undecided: Int, inFlight: Int,
        onLastPhoto: Bool, card: @autoclosure () -> CullEndCard
    ) -> CullEndState {
        guard selected + copied + trashed > 0 else { return .none }
        if undecided == 0 { return inFlight == 0 ? .done(card()) : .none }
        return onLastPhoto ? .remaining(count: undecided) : .none
    }
}

/// What the "Done" card says about formatting the card. Only a removable source volume gets a line,
/// and only a verdict when every file under the folder (video and RAW sidecars included) is accounted for.
public enum FormatVerdict: Equatable, Sendable {
    case safe
    /// Files other than the decided photos remain under the folder: no verdict, the count instead.
    case otherFilesLeft(Int)
    /// Keepers were filed inside the folder being culled, i.e. on the card itself.
    case selectionOnCard

    public var text: String {
        switch self {
        case .safe: return "Safe to format"
        case .otherFilesLeft(let count): return "\(count) other files left"
        case .selectionOnCard: return "Selection is on this card"
        }
    }
}

public enum SafeToFormat {
    /// True for a removable or ejectable volume (a camera card, a USB stick).
    public static func isRemovableVolume(_ url: URL) -> Bool {
        let values = try? url.resourceValues(forKeys: [.volumeIsRemovableKey, .volumeIsEjectableKey])
        return values?.volumeIsRemovable == true || values?.volumeIsEjectable == true
    }

    /// nil when `root` is not on a removable volume (no line at all).
    /// Blocking file-system work: call it off the main actor.
    public static func verdict(
        root: URL,
        copiedURLs: [URL],
        selectionIsInsideSource: Bool,
        isRemovable: (URL) -> Bool = SafeToFormat.isRemovableVolume,
        fileManager: FileManager = .default
    ) -> FormatVerdict? {
        guard isRemovable(root) else { return nil }
        if selectionIsInsideSource { return .selectionOnCard }
        let manager = FileCullingManager()
        var accounted = Set<String>()
        for url in copiedURLs {
            accounted.insert(url.standardizedFileURL.path)
            for companion in manager.findCompanionFiles(for: url) { accounted.insert(companion.standardizedFileURL.path) }
        }
        guard let walker = fileManager.enumerator(
            at: root, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles]
        ) else { return .otherFilesLeft(0) }
        var left = 0
        for case let url as URL in walker {
            guard (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true else { continue }
            if !accounted.contains(url.standardizedFileURL.path) { left += 1 }
        }
        return left == 0 ? .safe : .otherFilesLeft(left)
    }
}
