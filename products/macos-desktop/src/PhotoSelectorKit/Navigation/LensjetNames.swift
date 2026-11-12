import Foundation

/// The one table of names (REQ-MAC-UI, "one name per thing"). The menu bar, the toolbar, the
/// action dock, the shortcut sheet, the tooltips and the spoken labels all read their words from
/// here, so a thing is called the same everywhere; `LensjetNamesTests` fails when a surface
/// drifts from this table and when a retired word comes back.
///
/// The configured Selection folder name is a setting ("Keepers", "Selection", …): the names of
/// Move and Copy take it as `destination`, so a tooltip never says "Selection" over a folder that
/// has another name.
public enum LensjetCommand: CaseIterable, Sendable {
    case openFolder, openRecent, showInFinder, quickLook
    case move, copy, trash, undo, autoAdvance
    case previousPhoto, nextPhoto, switchPhoto, goToPhoto
    case singleUp, twoUp, threeUp
    case zoom100, linkPan, filmstrip, info, sortBy
    case findDuplicates, statistics, keyboardShortcuts
    /// Activity: the list of decisions ([USER 2026-11-12] decision #23). Clean View: full screen that
    /// hides the toolbar, the dock and the filmstrip (decision #24).
    case activity, cleanView

    /// The name in menus, the shortcut sheet and the long form of a tooltip.
    /// `rejects` says where Delete goes (Settings → Rejects): "Move to Trash" or "Move to Rejected".
    public func name(
        destination: String = AppSettingsDefaults.cullingDestinationFolderName,
        rejects: RejectsDestination = .trash
    ) -> String {
        switch self {
        case .openFolder: return "Open Folder\u{2026}"
        case .openRecent: return "Open Recent"
        case .showInFinder: return "Show in Finder"
        case .quickLook: return "Quick Look"
        case .move: return "Move to \(destination)"
        case .copy: return "Copy to \(destination)"
        case .trash: return rejects.commandName
        case .undo: return "Undo"
        case .autoAdvance: return "Auto-Advance"
        case .previousPhoto: return "Previous Photo"
        case .nextPhoto: return "Next Photo"
        case .switchPhoto: return "Switch Photo"
        case .goToPhoto: return "Go to Photo\u{2026}"
        case .singleUp: return "1-Up"
        case .twoUp: return "2-Up"
        case .threeUp: return "3-Up"
        case .zoom100: return "Zoom 100%"
        case .linkPan: return "Link Pan"
        case .filmstrip: return "Filmstrip"
        case .info: return "Info"
        case .sortBy: return "Sort By"
        case .findDuplicates: return "Find Duplicates"
        case .statistics: return "Statistics"
        case .keyboardShortcuts: return "Keyboard Shortcuts"
        case .activity: return "Activity"
        case .cleanView: return "Clean View"
        }
    }

    /// The short form of the dock and the compact toolbar; equals `name` where one form is enough.
    public var shortName: String { shortName(rejects: .trash) }

    /// The short form with the Delete destination of the setting.
    public func shortName(rejects: RejectsDestination) -> String {
        switch self {
        case .move: return "Move"
        case .copy: return "Copy"
        case .trash: return rejects.shortName
        default: return name()
        }
    }

    /// The key as drawn in a badge, the shortcut sheet and a tooltip; nil where there is none.
    public var key: String? {
        switch self {
        case .openFolder: return "\u{2318}O"
        case .showInFinder: return "\u{21E7}\u{2318}R"
        case .quickLook: return "\u{2318}Y"
        case .move: return "M"
        case .copy: return "C"
        case .trash: return "\u{232B}"
        case .undo: return "\u{2318}Z"
        case .autoAdvance: return "\u{21E7}\u{2318}A"
        case .previousPhoto: return "\u{2318}["
        case .nextPhoto: return "\u{2318}]"
        case .switchPhoto: return "Tab"
        case .goToPhoto: return "G"
        case .singleUp: return "1"
        case .twoUp: return "2"
        case .threeUp: return "3"
        case .zoom100: return "Space"
        case .linkPan: return "\u{2325}\u{2318}P"
        case .filmstrip: return "T"
        case .info: return "I"
        case .findDuplicates: return "\u{2318}D"
        case .statistics: return "\u{2318}L"
        case .keyboardShortcuts: return "\u{2318}/"
        case .activity: return "\u{21E7}\u{2318}L"
        case .cleanView: return "F"
        case .openRecent, .sortBy: return nil
        }
    }

    /// A tooltip: the name, then the key in brackets.
    public func tooltip(
        destination: String = AppSettingsDefaults.cullingDestinationFolderName,
        rejects: RejectsDestination = .trash
    ) -> String {
        guard let key else { return name(destination: destination, rejects: rejects) }
        return "\(name(destination: destination, rejects: rejects)) (\(key))"
    }

    /// Menu title of Info, which is a toggle: "Show Info" / "Hide Info".
    public static func infoMenuTitle(isShown: Bool) -> String { isShown ? "Hide Info" : "Show Info" }
    /// Menu title of the Filmstrip toggle: "Show Filmstrip" / "Hide Filmstrip".
    public static func filmstripMenuTitle(isShown: Bool) -> String { isShown ? "Hide Filmstrip" : "Show Filmstrip" }

    /// Edit menu: "Undo Move of IMG_0001.CR3"; plain "Undo" when there is nothing to undo.
    public static func undoMenuTitle(action: CullingActionType?, filename: String?, rejected: Bool = false) -> String {
        guard let action, let filename else { return "Undo" }
        switch action {
        case .move: return "Undo Move of \(filename)"
        case .copy: return "Undo Copy of \(filename)"
        case .trash: return rejected ? "Undo Reject of \(filename)" : "Undo Trash of \(filename)"
        }
    }
}

/// Words that name a concept rather than a command: the empty state, the tally, the tools.
public enum LensjetWords {
    public static let dropFolder = "Drop a folder"
    public static let dropToOpen = "Drop to open"
    /// The formats line of the empty state.
    public static let supportedFormats = "RAW (CR3, ARW, NEF, DNG, RAF, RW2), JPEG, HEIC"
    public static let tally = ["Selected", "Copied", "Trashed", "Remaining"]
    public static let sharpness = "Sharpness"
    public static let aesthetics = "Aesthetics"
    /// Title of the Settings tab that holds Sharpness and Aesthetics.
    public static let scoresSettingsTab = "Scores"
    public static let findDuplicatesCaption = "Finds identical files."
    public static let statisticsCaption = "Counts, culling progress, file formats, and Sharpness and Aesthetics scores."

    /// Words that must not appear in any user-visible string (jargon and retired names).
    public static let banned = [
        "Ingest", "zero-latency", "bitwise", "cryptographic", "variance", "pin-sharp", "HUD", "Overlay",
        "Accelerate", "VNCalculate", "SHA-256", "Focus Mode", "Focus 3-Up", "Focus View",
        "Library Statistics", "Scoring & Aesthetics",
    ]
}

/// The menu bar: which menu holds which command. `AppCommands` builds exactly this; a test reads
/// its source and fails when an item sits under another menu or a menu title repeats.
/// View, Edit and File are the system's menus that the app adds items to (one View menu, which
/// keeps Enter Full Screen); Culling, Go and Tools are the app's own.
public enum LensjetMenu: String, CaseIterable, Sendable {
    case file = "File"
    case edit = "Edit"
    case culling = "Culling"
    case view = "View"
    case go = "Go"
    case tools = "Tools"
    case help = "Help"

    public var commands: [LensjetCommand] {
        switch self {
        case .file: return [.openFolder, .openRecent, .showInFinder, .quickLook]
        case .edit: return [.undo]
        case .culling: return [.move, .copy, .trash, .autoAdvance]
        case .view: return [.singleUp, .twoUp, .threeUp, .sortBy, .zoom100, .linkPan, .filmstrip, .info, .cleanView, .switchPhoto]
        case .go: return [.previousPhoto, .nextPhoto, .goToPhoto, .activity]
        case .tools: return [.findDuplicates, .statistics]
        case .help: return [.keyboardShortcuts]
        }
    }
}
