import Testing
import Foundation
@testable import PhotoSelectorKit

/// Opening the Selection folder itself to trim the keepers must not nest `Selection/Selection/`
/// (the scan hides that folder, so the photo would vanish from the list).
@Suite("Move and Copy inside an opened Selection folder")
struct OpenedSelectionFolderTests {
    private func makeDir(_ label: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .standardizedFileURL.resolvingSymlinksInPath()
            .appendingPathComponent("OpenedSel_\(label)_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    @MainActor
    private func makeViewModel(openedFolder: URL, defaults: UserDefaults) throws -> CullingWorkspaceViewModel {
        let photo = openedFolder.appendingPathComponent("keeper.jpg")
        try Data("x".utf8).write(to: photo)
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        vm.setPhotos([PhotoItem(id: UUID(), url: photo, status: .candidate)], rootURL: openedFolder)
        vm.autoAdvance = false
        return vm
    }

    private func makeDefaults() -> (UserDefaults, String) {
        let name = "com.photoselector.tests.openedsel.\(UUID().uuidString)"
        return (UserDefaults(suiteName: name)!, name)
    }

    @Test("A folder with the Selection marker refuses Move and Copy and creates nothing")
    @MainActor
    func markerFolderRefuses() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let selection = try makeDir("marker").appendingPathComponent("Keepers", isDirectory: true)
        try FileManager.default.createDirectory(at: selection, withIntermediateDirectories: true)
        FileManager.default.createFile(
            atPath: selection.appendingPathComponent(DirectoryScanner.selectionMarkerFilename).path, contents: Data())
        defer { try? FileManager.default.removeItem(at: selection.deletingLastPathComponent()) }

        let vm = try makeViewModel(openedFolder: selection, defaults: defaults)
        for action in [CullingActionType.move, .copy] {
            vm.cullCurrentPhoto(action: action)
            #expect(vm.photos[0].status == .candidate)
            #expect(vm.currentToast?.title == "This is the Selection folder")
            #expect(!FileManager.default.fileExists(atPath: selection.appendingPathComponent("Selection").path))
            #expect(FileManager.default.fileExists(atPath: selection.appendingPathComponent("keeper.jpg").path))
        }
    }

    @Test("A folder named like the Selection folder refuses Move")
    @MainActor
    func namedFolderRefuses() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let parent = try makeDir("named")
        let selection = parent.appendingPathComponent("Selection", isDirectory: true)
        try FileManager.default.createDirectory(at: selection, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: parent) }

        let vm = try makeViewModel(openedFolder: selection, defaults: defaults)
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.photos[0].status == .candidate)
        #expect(!FileManager.default.fileExists(atPath: selection.appendingPathComponent("Selection").path))
    }

    @Test("Trash still works in an opened Selection folder")
    @MainActor
    func trashStillAllowed() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let parent = try makeDir("trash")
        let selection = parent.appendingPathComponent("Selection", isDirectory: true)
        try FileManager.default.createDirectory(at: selection, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: parent) }

        let vm = try makeViewModel(openedFolder: selection, defaults: defaults)
        vm.cullCurrentPhoto(action: .trash)
        #expect(vm.photos[0].status == .trashed)
    }

    @Test("A custom destination that resolves to the opened folder is refused the same way")
    @MainActor
    func customDestinationEqualToOpenedFolder() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let parent = try makeDir("custom")
        let selection = parent.appendingPathComponent("Selection", isDirectory: true)
        try FileManager.default.createDirectory(at: selection, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: parent) }
        // custom base = parent, name = Selection  ->  <parent>/Selection == the opened folder.
        defaults.cullingDestinationMode = .customFolder
        defaults.externalCullingDestinationURL = parent

        let vm = try makeViewModel(openedFolder: selection, defaults: defaults)
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.photos[0].status == .candidate)
        #expect(vm.currentToast?.title == "This is the Selection folder")
        #expect(FileManager.default.fileExists(atPath: selection.appendingPathComponent("keeper.jpg").path))
    }

    @Test("Settings shows the sentence instead of a nested path")
    func settingsSentence() throws {
        let parent = try makeDir("settings")
        let selection = parent.appendingPathComponent("Selection", isDirectory: true)
        try FileManager.default.createDirectory(at: selection, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: parent) }
        let resolved = CullingDestinationResolver.resolve(
            mode: .workingFolder, folderName: "Selection", customFolderURL: nil, openFolderURL: selection)
        #expect(!resolved.isUsable)
        let summary = CullingDestinationResolver.summary(of: resolved)
        #expect(summary.selectionPathText == nil)
        #expect(summary.warningText?.hasPrefix("This is the Selection folder") == true)
    }

    @Test("An ordinary folder is unaffected")
    func ordinaryFolderUnaffected() throws {
        let parent = try makeDir("ordinary")
        defer { try? FileManager.default.removeItem(at: parent) }
        let resolved = CullingDestinationResolver.resolve(
            mode: .workingFolder, folderName: "Selection", customFolderURL: nil, openFolderURL: parent)
        #expect(resolved.isUsable)
    }
}
