import Foundation

/// How much text the culling workspace's toolbar and action dock can afford at the current
/// window width (REQ-MAC-UI.01 / .02). Labels never wrap and never truncate: when the full
/// form does not fit, the chrome steps down to the next denser form (macOS HIG: toolbar
/// items collapse to icon-only with a tooltip and accessibility label).
///
/// The SwiftUI layer renders one variant per density inside a `ViewThatFits(in: .horizontal)`
/// and SwiftUI picks the first that fits; this type owns every label string so the forms are
/// unit-testable and identical in the toolbar, the dock and the accessibility labels.
public enum ChromeDensity: Int, CaseIterable, Sendable {
    /// Everything: "Focus 3-Up (3)", "Photo 3 of 120", "Move to Selection", "2 Selected · …".
    case full
    /// Short labels: "3-Up", "3 / 120", "Move", icon + number tally.
    case compact
    /// Icon-only mode switcher and dock buttons (badge kept), icon + number tally.
    case minimal

    // MARK: Toolbar

    /// Mode switcher label; `nil` means icon-only.
    public func modeLabel(shortcut: Int) -> String? {
        switch self {
        case .full: return shortcut == 3 ? "Focus 3-Up" : "\(shortcut)-Up"
        case .compact: return "\(shortcut)-Up"
        case .minimal: return nil
        }
    }

    /// Whether the mode button shows its "(n)" shortcut hint.
    public var showsModeShortcut: Bool { self == .full }

    /// Full accessibility / tooltip name of a mode button, independent of density.
    public static func modeAccessibilityName(shortcut: Int) -> String {
        let name = shortcut == 3 ? "Focus 3-Up" : "\(shortcut)-Up"
        return "\(name) view (shortcut \(shortcut))"
    }

    /// Position counter: "Photo 3 of 120" (full) or "3 / 120".
    public func positionText(index: Int, total: Int) -> String {
        switch self {
        case .full: return "Photo \(index) of \(total)"
        case .compact, .minimal: return "\(index) / \(total)"
        }
    }

    /// "Open Folder…" collapses to its icon below `.full`.
    public var showsOpenFolderTitle: Bool { self == .full }
    /// The folder-name chip is dropped at `.minimal` (it stays available as the window title).
    public var showsFolderName: Bool { self != .minimal }
    /// "Scanning… (N photos)" at `.full`, "Scanning… N" at `.compact`, spinner only at `.minimal`.
    public func scanningText(photoCount: Int) -> String? {
        switch self {
        case .full: return "Scanning… (\(photoCount) photos)"
        case .compact: return "Scanning… \(photoCount)"
        case .minimal: return nil
        }
    }

    // MARK: Action dock

    public enum DockAction: Sendable, CaseIterable {
        case move, copy, trash, undo

        public var fullTitle: String {
            switch self {
            case .move: return "Move to Selection"
            case .copy: return "Copy to Selection"
            case .trash: return "Move to Trash"
            case .undo: return "Undo"
            }
        }

        public var shortTitle: String {
            switch self {
            case .move: return "Move"
            case .copy: return "Copy"
            case .trash: return "Trash"
            case .undo: return "Undo"
            }
        }
    }

    /// Dock button title; `nil` means icon + shortcut badge only.
    public func dockTitle(_ action: DockAction) -> String? {
        switch self {
        case .full: return action.fullTitle
        case .compact: return action.shortTitle
        case .minimal: return nil
        }
    }

    /// Tally capsule: words at `.full`, icon + number below.
    public var tallyUsesWords: Bool { self == .full }

    /// Spoken / tooltip form of the tally, independent of density.
    public static func tallyAccessibilityText(selected: Int, trashed: Int, remaining: Int) -> String {
        "\(selected) selected, \(trashed) trashed, \(remaining) remaining"
    }
}
