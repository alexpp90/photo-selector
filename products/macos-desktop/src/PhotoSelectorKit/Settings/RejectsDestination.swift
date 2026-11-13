import Foundation

/// Where Delete puts a reject: the macOS Trash (default) or a `Rejected` folder inside the folder
/// being culled ([USER 2026-11-12] decision #20). The folder is excluded from every scan and from
/// the Duplicate Finder (REQ-MAC-EXIF.06), and Undo moves the photo back from it.
public enum RejectsDestination: String, CaseIterable, Sendable {
    case trash
    case rejectedFolder

    /// The folder name inside the culled folder.
    public static let folderName = "Rejected"

    /// An absent or unknown stored value reads as Trash.
    public init(storedRaw: String?) {
        self = storedRaw.flatMap(RejectsDestination.init(rawValue:)) ?? .trash
    }

    /// Settings picker text.
    public var title: String {
        switch self {
        case .trash: return "Trash"
        case .rejectedFolder: return "Rejected/"
        }
    }

    /// The word of the Delete action in menus, tooltips and spoken labels.
    public var commandName: String {
        switch self {
        case .trash: return "Move to Trash"
        case .rejectedFolder: return "Move to \(Self.folderName)"
        }
    }

    /// The short form of the dock button.
    public var shortName: String {
        switch self {
        case .trash: return "Trash"
        case .rejectedFolder: return "Reject"
        }
    }

    /// Toast title right after Delete.
    public var toastTitle: String {
        switch self {
        case .trash: return "Moved to Trash"
        case .rejectedFolder: return "Moved to \(Self.folderName)"
        }
    }

    /// SF Symbol of the toast and the dock button.
    public var symbol: String {
        switch self {
        case .trash: return "trash.fill"
        case .rejectedFolder: return "folder.badge.minus"
        }
    }

    /// The verb of the count line ("Trashed 3 files" / "Rejected 3 files").
    public var pastVerb: String {
        switch self {
        case .trash: return "Trashed"
        case .rejectedFolder: return "Rejected"
        }
    }
}

public extension UserDefaults {
    /// Where Delete puts a reject; Trash when nothing is stored.
    var rejectsDestination: RejectsDestination {
        get { RejectsDestination(storedRaw: string(forKey: AppSettingsKeys.rejectsDestination)) }
        set { set(newValue.rawValue, forKey: AppSettingsKeys.rejectsDestination) }
    }
}

/// Moves a reject into `<culled folder>/Rejected` instead of the Trash. It never overwrites: a
/// taken name gets a `-2`, `-3`, … suffix, and the folder carries the Selection marker file so a
/// later scan skips it whatever it is called (`DirectoryScanner.selectionMarkerFilename`).
public struct RejectedFolderService: TrashService, @unchecked Sendable {
    // FileManager is only used for the calls of one job, on the actor's serial queue.
    private let folder: URL
    private let fileManager: FileManager

    public init(folder: URL, fileManager: FileManager = .default) {
        self.folder = folder
        self.fileManager = fileManager
    }

    public func trash(_ url: URL) throws -> URL {
        do {
            try fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
        } catch {
            throw FileCullingError.directoryCreationFailed(folder, error.localizedDescription)
        }
        let marker = folder.appendingPathComponent(DirectoryScanner.selectionMarkerFilename)
        if !fileManager.fileExists(atPath: marker.path) {
            fileManager.createFile(atPath: marker.path, contents: nil)
        }
        let stem = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension
        var candidate = folder.appendingPathComponent(url.lastPathComponent)
        var suffix = 2
        while fileManager.fileExists(atPath: candidate.path) {
            let name = ext.isEmpty ? "\(stem)-\(suffix)" : "\(stem)-\(suffix).\(ext)"
            candidate = folder.appendingPathComponent(name)
            suffix += 1
        }
        do {
            try fileManager.moveItem(at: url, to: candidate)
        } catch {
            throw FileCullingError.trashFailed(url: url, reason: error.localizedDescription)
        }
        return candidate
    }
}
