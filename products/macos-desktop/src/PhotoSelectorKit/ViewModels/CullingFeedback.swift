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

    /// The confirmation of a photo that took companions along ([USER 2026-11-12] decision #33, item 1):
    /// "Moved IMG_0001 +2 · Selection" (the visible form: file name without extension, "+N" companions,
    /// the destination) and the spoken form "Moved IMG_0001.CR3 and 2 companion files to Selection".
    /// `nil` for a single file, which keeps the plain toast. A long name is cut in the middle so "+N" and
    /// the destination are never cut. `destination` is nil for a Trash, which has no folder name.
    public static func companionTitle(
        action: CullingActionType,
        originalPrimaryURL: URL,
        fileCount: Int,
        destination: String?,
        rejects: RejectsDestination = .trash
    ) -> (title: String, spoken: String)? {
        guard fileCount > 1 else { return nil }
        let verb: String
        switch action {
        case .move: verb = "Moved"
        case .copy: verb = "Copied"
        case .trash: verb = rejects.pastVerb
        }
        let companions = fileCount - 1
        let stem = middleEllipsis(originalPrimaryURL.deletingPathExtension().lastPathComponent, limit: 24)
        let to = destination.map { " \u{00B7} \($0)" } ?? ""
        let spokenTo = destination.map { " to \($0)" } ?? ""
        return (
            "\(verb) \(stem) +\(companions)\(to)",
            "\(verb) \(originalPrimaryURL.lastPathComponent) and \(companions) companion file\(companions == 1 ? "" : "s")\(spokenTo)"
        )
    }

    static func middleEllipsis(_ text: String, limit: Int) -> String {
        guard text.count > limit else { return text }
        let head = (limit - 1) / 2 + (limit - 1) % 2
        let tail = (limit - 1) / 2
        return String(text.prefix(head)) + "\u{2026}" + String(text.suffix(tail))
    }

    /// "Restored 3 files" after an undo; `nil` for a single file.
    public static func restoredCountTitle(fileCount: Int) -> String? {
        fileCount > 1 ? "Restored \(fileCount) files" : nil
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
