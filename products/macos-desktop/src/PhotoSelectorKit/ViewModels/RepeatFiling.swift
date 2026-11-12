import Foundation

/// A photo that is already filed is decided: a second Copy, or a Move after a Copy, files nothing a
/// second time (no `-2` beside the first file). The press is answered with a notice instead
/// ([USER 2026-11-12] decision #29, `docs/shared/FILE_OPERATIONS.md`).
public enum RepeatFiling {
    public struct Notice: Equatable, Sendable {
        public let title: String
        public let subtitle: String?
    }

    /// The notice to show instead of filing again, or nil when the press files normally.
    public static func notice(action: CullingActionType, status: PhotoStatus, destinationFolder: String) -> Notice? {
        switch (action, status) {
        case (.copy, .copied):
            return Notice(title: "Already copied", subtitle: nil)
        case (.move, .copied):
            return Notice(title: "Already in \(destinationFolder)", subtitle: "Undo the Copy to move the original")
        case (.copy, .selected), (.move, .selected):
            return Notice(title: "Already in \(destinationFolder)", subtitle: nil)
        default:
            return nil
        }
    }
}
