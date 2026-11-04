import Foundation

/// Where Move (`M`) and Copy (`C`) put photos: next to the photos, or in a folder the user chose.
public enum CullingDestinationMode: String, Sendable, CaseIterable, Equatable {
    /// `<opened folder>/<Selection name>/` (the default).
    case workingFolder
    /// `<custom folder>/<Selection name>/`, e.g. an SSD folder while culling off an SD card.
    case customFolder

    /// The mode in force for the stored values. A stored custom mode without a custom folder reads
    /// as `workingFolder` (never "Custom" with nothing behind it), and a store that has no mode yet
    /// but does hold a custom folder (written by an earlier version) reads as `customFolder`, so an
    /// update never changes where a user's photos go.
    public static func effective(storedRaw: String?, hasCustomFolder: Bool) -> CullingDestinationMode {
        let stored = storedRaw.flatMap { CullingDestinationMode(rawValue: $0) }
        if stored == .workingFolder { return .workingFolder }
        return hasCustomFolder ? .customFolder : .workingFolder
    }
}

/// Why a custom destination folder cannot receive photos.
public enum CustomFolderProblem: Equatable, Sendable {
    /// The path no longer exists (unplugged volume, deleted or renamed folder).
    case missing
    /// The path exists but is a file.
    case notADirectory
    /// The folder exists but the app may not write into it.
    case notWritable

    /// Warning shown in Settings and in the toast of a refused Move/Copy.
    public var message: String {
        switch self {
        case .missing: return "Folder not found. Move and Copy are off until you choose another."
        case .notADirectory: return "Not a folder. Move and Copy are off until you choose another."
        case .notWritable: return "Not writable. Move and Copy are off until you choose another."
        }
    }
}

/// The destination of Move and Copy, resolved from the settings and the opened folder.
///
/// `CullingDestinationResolver.resolve` is the only place that decides it: the file operations
/// (`CullingWorkspaceViewModel.cullCurrentPhoto`) and the Settings text both read this value, so
/// the label cannot disagree with where files go.
public struct ResolvedCullingDestination: Equatable, Sendable {
    public let mode: CullingDestinationMode
    /// Sanitized name of the subfolder (never blank).
    public let folderName: String
    /// The folder that contains the Selection subfolder: the opened folder (working mode, nil when
    /// none is open) or the custom folder.
    public let baseFolderURL: URL?
    /// Set when the custom folder cannot receive photos.
    public let problem: CustomFolderProblem?

    public init(mode: CullingDestinationMode, folderName: String, baseFolderURL: URL?, problem: CustomFolderProblem?) {
        self.mode = mode
        self.folderName = folderName
        self.baseFolderURL = baseFolderURL
        self.problem = problem
    }

    /// `<base>/<name>/`; the intended path even when `problem` is set; nil with no folder open.
    public var selectionFolderURL: URL? {
        baseFolderURL.map { CullingDestinationResolver.selectionFolder(in: $0, named: folderName) }
    }

    /// True when a Move or Copy can run.
    public var isUsable: Bool { problem == nil && baseFolderURL != nil }
}

/// Resolves and describes the Move/Copy destination. Pure apart from the existence probe of a custom folder.
public enum CullingDestinationResolver {
    /// `<base>/<sanitized name>/`. `FileCullingManager` builds its destination with this function too.
    public static func selectionFolder(in base: URL, named name: String) -> URL {
        base.appendingPathComponent(AppSettings.sanitizeDestinationFolderName(name), isDirectory: true)
    }

    /// Resolves the destination.
    /// - Parameters:
    ///   - mode: stored mode; `.customFolder` without `customFolderURL` is treated as working folder.
    ///   - folderName: the Selection folder name as stored; sanitized here (blank falls back to "Selection").
    ///   - customFolderURL: the folder the user chose, if any.
    ///   - openFolderURL: the folder being culled, nil when none is open.
    public static func resolve(
        mode: CullingDestinationMode,
        folderName: String,
        customFolderURL: URL?,
        openFolderURL: URL?,
        fileManager: FileManager = .default
    ) -> ResolvedCullingDestination {
        let name = AppSettings.sanitizeDestinationFolderName(folderName)
        if mode == .customFolder, let custom = customFolderURL {
            return ResolvedCullingDestination(
                mode: .customFolder,
                folderName: name,
                baseFolderURL: custom,
                problem: problem(of: custom, fileManager: fileManager)
            )
        }
        return ResolvedCullingDestination(
            mode: .workingFolder,
            folderName: name,
            baseFolderURL: openFolderURL,
            problem: nil
        )
    }

    /// Resolves from the persisted settings.
    public static func resolve(
        defaults: UserDefaults,
        openFolderURL: URL?,
        fileManager: FileManager = .default
    ) -> ResolvedCullingDestination {
        resolve(
            mode: defaults.cullingDestinationMode,
            folderName: defaults.cullingDestinationFolderName,
            customFolderURL: defaults.externalCullingDestinationURL,
            openFolderURL: openFolderURL,
            fileManager: fileManager
        )
    }

    /// Why `url` cannot receive photos, nil when it can.
    public static func problem(of url: URL, fileManager: FileManager = .default) -> CustomFolderProblem? {
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: url.path, isDirectory: &isDirectory) else { return .missing }
        guard isDirectory.boolValue else { return .notADirectory }
        guard fileManager.isWritableFile(atPath: url.path) else { return .notWritable }
        return nil
    }

    /// `path` with the home folder shortened to `~` (display only).
    public static func abbreviated(_ path: String, home: String = NSHomeDirectory()) -> String {
        let trimmedHome = home.hasSuffix("/") && home.count > 1 ? String(home.dropLast()) : home
        guard !trimmedHome.isEmpty, trimmedHome != "/" else { return path }
        if path == trimmedHome { return "~" }
        if path.hasPrefix(trimmedHome + "/") { return "~" + String(path.dropFirst(trimmedHome.count)) }
        return path
    }

    /// What Settings shows under the Working folder / Custom folder choice.
    public static func summary(of destination: ResolvedCullingDestination, home: String = NSHomeDirectory()) -> CullingDestinationSummary {
        let selectionText = destination.selectionFolderURL.map { abbreviated($0.standardizedFileURL.path, home: home) + "/" }
        switch destination.mode {
        case .workingFolder:
            return CullingDestinationSummary(
                selectionPathText: selectionText,
                customFolderPathText: nil,
                placeholderText: selectionText == nil
                    ? "\u{AB}\(destination.folderName)\u{BB} inside the folder you open (no folder open yet)"
                    : nil,
                warningText: nil
            )
        case .customFolder:
            return CullingDestinationSummary(
                selectionPathText: selectionText,
                customFolderPathText: destination.baseFolderURL.map { abbreviated($0.standardizedFileURL.path, home: home) },
                placeholderText: nil,
                warningText: destination.problem?.message
            )
        }
    }
}

/// Display text for the destination (pure, tested): paths are `~`-abbreviated, the Selection path ends in `/`.
public struct CullingDestinationSummary: Equatable, Sendable {
    /// Where photos go, e.g. `~/Pictures/Trip/Selection/`; nil while working-folder mode has no open folder.
    public let selectionPathText: String?
    /// The custom folder's own path (custom mode only).
    public let customFolderPathText: String?
    /// Honest stand-in for the path when no folder is open yet.
    public let placeholderText: String?
    /// Set when the custom folder is not usable.
    public let warningText: String?
}
