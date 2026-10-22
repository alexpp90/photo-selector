import Foundation

/// User-facing feedback texts for file operations, from the templates in
/// `docs/shared/FILE_OPERATIONS.md`. Pure, so the wording is unit-tested without a window.
public enum CullingFeedback {

    /// "Moved to «folder» as «name» (+«K» related)" when the primary file had to be renamed to avoid
    /// a collision; `nil` when it kept its name (the plain "Moved to «folder»" toast already said it)
    /// or the action is not a transfer into a folder.
    public static func transferTitle(
        action: CullingActionType,
        destinationFolder: String,
        originalPrimaryURL: URL,
        destinationURLs: [URL]
    ) -> String? {
        guard action != .trash,
              let primary = primaryURL(matching: originalPrimaryURL, in: destinationURLs),
              primary.lastPathComponent != originalPrimaryURL.lastPathComponent else { return nil }
        let verb = action == .move ? "Moved" : "Copied"
        let related = destinationURLs.count - 1
        let suffix = related > 0 ? " (+\(related) related)" : ""
        return "\(verb) to \(destinationFolder) as \(primary.lastPathComponent)\(suffix)"
    }

    /// Toast title of a partial move: the original stayed behind (a verified copy was kept), or a failed
    /// rollback left members in the destination folder (D-12).
    public static func partialMoveTitle(name: String, destinationFolder: String, rollbackFailed: Bool) -> String {
        rollbackFailed
            ? "Move interrupted — some files of \(name) stayed in \(destinationFolder)/ and could not be put back"
            : "Copied to \(destinationFolder) — original could not be removed"
    }

    /// "Restored as «name» (original name was in use)" when an undo had to add a suffix; else `nil`.
    public static func restoredTitle(originalPrimaryURL: URL, restoredURLs: [URL]) -> String? {
        guard let primary = primaryURL(matching: originalPrimaryURL, in: restoredURLs),
              primary.lastPathComponent != originalPrimaryURL.lastPathComponent else { return nil }
        return "Restored as \(primary.lastPathComponent) (original name was in use)"
    }

    /// The URL in `urls` that is the (possibly suffixed) primary file: same extension, and a stem that
    /// starts with the original stem.
    private static func primaryURL(matching original: URL, in urls: [URL]) -> URL? {
        let stem = original.deletingPathExtension().lastPathComponent.lowercased()
        let ext = original.pathExtension.lowercased()
        return urls.first {
            $0.pathExtension.lowercased() == ext &&
                $0.deletingPathExtension().lastPathComponent.lowercased().hasPrefix(stem)
        }
    }
}
