#if PST_TEST_HOOKS
import Foundation
import PhotoSelectorKit

/// Moves "trashed" files into a folder owned by the run instead of the account's `~/.Trash`.
/// Compiled only into the test-hooks build: the shipped app has no environment or flag branch
/// that swaps its trash (`FileCullingManager.swift`), and `check_no_test_hooks.sh` proves the
/// absence in the release binary.
struct HermeticLabTrashService: TrashService {
    let directory: URL

    func trash(_ url: URL) throws -> URL {
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
            var destination = directory.appendingPathComponent(url.lastPathComponent)
            var counter = 2
            while fm.fileExists(atPath: destination.path) {
                let stem = url.deletingPathExtension().lastPathComponent
                let ext = url.pathExtension
                destination = directory.appendingPathComponent(ext.isEmpty ? "\(stem) \(counter)" : "\(stem) \(counter).\(ext)")
                counter += 1
            }
            try fm.moveItem(at: url, to: destination)
            return destination
        } catch {
            throw FileCullingError.trashFailed(url: url, reason: error.localizedDescription)
        }
    }
}

/// What a lab run (`--ui-scenario`, `--ui-self-test`, `--screenshot`) is allowed to touch: its own
/// `UserDefaults` suite (never the account's `autoAdvance`, destination folder or sort order) and
/// its own trash folder next to the run's artifacts (never `~/.Trash`). The suite pins the
/// settings a scenario depends on, so a run is the same on every account.
@MainActor
final class HermeticLabSession {
    static var current: HermeticLabSession?

    let suiteName: String
    let defaults: UserDefaults
    let trashDirectory: URL

    init(trashParent: URL) {
        suiteName = "app.alpatix.lensjet.lab.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        trashDirectory = trashParent.appendingPathComponent("trash", isDirectory: true)
        // Pinned: the working-folder destination named Selection, auto-advance on, file-name order.
        defaults.cullingDestinationFolderName = AppSettingsDefaults.cullingDestinationFolderName
        defaults.autoAdvance = true
        defaults.workspaceSortOrder = .fileName
    }

    /// The workspace for a lab run: its own defaults, its own trash, its own scanner settings.
    func makeViewModel() -> CullingWorkspaceViewModel {
        CullingWorkspaceViewModel(
            directoryScanner: DirectoryScanner(userDefaults: defaults),
            cullingActor: AsyncCullingActor(trashService: HermeticLabTrashService(directory: trashDirectory)),
            userDefaults: defaults
        )
    }

    /// The files in this run's trash, for `fs.json`.
    func trashedFiles() -> [[String: Any]] {
        let fm = FileManager.default
        guard let names = try? fm.contentsOfDirectory(atPath: trashDirectory.path) else { return [] }
        return names.sorted().map { name in
            let size = (try? fm.attributesOfItem(atPath: trashDirectory.appendingPathComponent(name).path))?[.size] as? Int ?? 0
            return ["path": name, "size": size]
        }
    }

    /// Removes the suite so no preference file outlives the run.
    func cleanup() {
        defaults.removePersistentDomain(forName: suiteName)
    }
}
#endif
