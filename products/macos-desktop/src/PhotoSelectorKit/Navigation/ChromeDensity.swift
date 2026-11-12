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
    /// Everything: "3-Up (3)", "Photo 3 of 120", "Move to Selection", "2 Selected · …".
    case full
    /// Short labels: "3-Up", "3 / 120", "Move", icon + number tally.
    case compact
    /// Icon-only mode switcher and dock buttons (badge kept), icon + number tally.
    case minimal

    // MARK: Toolbar

    /// Mode switcher label; `nil` means icon-only.
    public func modeLabel(shortcut: Int) -> String? {
        switch self {
        case .full, .compact: return Self.modeName(shortcut: shortcut)
        case .minimal: return nil
        }
    }

    /// Whether the mode button shows its "(n)" shortcut hint.
    public var showsModeShortcut: Bool { self == .full }

    /// Full accessibility / tooltip name of a mode button, independent of density.
    public static func modeAccessibilityName(shortcut: Int) -> String {
        "\(modeName(shortcut: shortcut)) view (shortcut \(shortcut))"
    }

    /// "1-Up", "2-Up", "3-Up": the same name in the toolbar, the menu, the sheet and the tooltips.
    public static func modeName(shortcut: Int) -> String {
        switch shortcut {
        case 1: return LensjetCommand.singleUp.name()
        case 2: return LensjetCommand.twoUp.name()
        default: return LensjetCommand.threeUp.name()
        }
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

        public var command: LensjetCommand {
            switch self {
            case .move: return .move
            case .copy: return .copy
            case .trash: return .trash
            case .undo: return .undo
            }
        }

        public var fullTitle: String { command.name() }

        /// The full title with the configured destination folder name ("Move to Keepers").
        public func fullTitle(destination: String) -> String { command.name(destination: destination) }

        public var shortTitle: String { command.shortName }
    }

    /// Dock button title; `nil` means icon + shortcut badge only.
    public func dockTitle(_ action: DockAction, destination: String = AppSettingsDefaults.cullingDestinationFolderName) -> String? {
        switch self {
        case .full: return action.fullTitle(destination: destination)
        case .compact: return action.shortTitle
        case .minimal: return nil
        }
    }

    /// Tally capsule: words at `.full`, icon + number below.
    public var tallyUsesWords: Bool { self == .full }

    /// Spoken / tooltip form of the tally, independent of density.
    /// "Copied" appears only once something was copied, so the tally keeps its width until then.
    public static func tallyAccessibilityText(selected: Int, trashed: Int, remaining: Int, copied: Int = 0) -> String {
        let copiedPart = copied > 0 ? "\(copied) copied, " : ""
        return "\(selected) selected, \(copiedPart)\(trashed) trashed, \(remaining) remaining"
    }
}
