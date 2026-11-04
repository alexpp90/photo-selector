import Testing
import Foundation
import Darwin
@testable import PhotoSelectorKit

/// REQ-MAC-SETTINGS.02 [USER 2026-11-04]: Settings shows where Move/Copy put photos (working folder or a
/// custom folder) as a real path, and that text comes from the function the file operation uses.
@Suite("Culling destination: resolution, display and file operations")
struct CullingDestinationTests {

    // MARK: - Helpers

    private func makeTempDirectory(_ label: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .standardizedFileURL
            .resolvingSymlinksInPath()
            .appendingPathComponent("Destination_\(label)_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func makeDefaults() -> (UserDefaults, String) {
        let suiteName = "com.photoselector.tests.destination.\(UUID().uuidString)"
        return (UserDefaults(suiteName: suiteName)!, suiteName)
    }

    private let openFolder = URL(fileURLWithPath: "/Volumes/Card/DCIM/100CANON", isDirectory: true)

    // MARK: - Working folder

    @Test("Working folder with an open folder resolves to <open folder>/Selection")
    func workingFolderWithOpenFolder() {
        let resolved = CullingDestinationResolver.resolve(
            mode: .workingFolder,
            folderName: "Selection",
            customFolderURL: nil,
            openFolderURL: openFolder
        )
        #expect(resolved.mode == .workingFolder)
        #expect(resolved.baseFolderURL == openFolder)
        #expect(resolved.selectionFolderURL?.path == "/Volumes/Card/DCIM/100CANON/Selection")
        #expect(resolved.problem == nil)
        #expect(resolved.isUsable)
    }

    @Test("Working folder with no folder open has no path and says so honestly")
    func workingFolderWithoutOpenFolder() {
        let resolved = CullingDestinationResolver.resolve(
            mode: .workingFolder,
            folderName: "Selection",
            customFolderURL: nil,
            openFolderURL: nil
        )
        #expect(resolved.selectionFolderURL == nil)
        #expect(!resolved.isUsable)

        let summary = CullingDestinationResolver.summary(of: resolved, home: "/Users/alex")
        #expect(summary.selectionPathText == nil)
        #expect(summary.placeholderText == "\u{AB}Selection\u{BB} inside the folder you open (no folder open yet)")
        #expect(summary.warningText == nil)
    }

    @Test("A blank Selection name falls back to the default; separators and traversal are stripped")
    func blankAndUnsafeNames() {
        let blank = CullingDestinationResolver.resolve(
            mode: .workingFolder, folderName: "   ", customFolderURL: nil, openFolderURL: openFolder
        )
        #expect(blank.folderName == "Selection")
        #expect(blank.selectionFolderURL?.lastPathComponent == "Selection")

        let unsafe = CullingDestinationResolver.resolve(
            mode: .workingFolder, folderName: "../Picks/x", customFolderURL: nil, openFolderURL: openFolder
        )
        #expect(unsafe.folderName == "Picksx")
        #expect(unsafe.selectionFolderURL?.path == "/Volumes/Card/DCIM/100CANON/Picksx")
    }

    // MARK: - Custom folder

    @Test("Custom folder resolves to <custom>/Selection and ignores the open folder")
    func customFolder() throws {
        let custom = try makeTempDirectory("custom")
        defer { try? FileManager.default.removeItem(at: custom) }

        let resolved = CullingDestinationResolver.resolve(
            mode: .customFolder, folderName: "Keepers", customFolderURL: custom, openFolderURL: openFolder
        )
        #expect(resolved.mode == .customFolder)
        #expect(resolved.baseFolderURL == custom)
        #expect(resolved.selectionFolderURL == custom.appendingPathComponent("Keepers", isDirectory: true))
        #expect(resolved.problem == nil)
        #expect(resolved.isUsable)

        // Usable with no folder open as well.
        let noOpen = CullingDestinationResolver.resolve(
            mode: .customFolder, folderName: "Keepers", customFolderURL: custom, openFolderURL: nil
        )
        #expect(noOpen.isUsable)
    }

    @Test("Custom mode without a custom folder is never 'Custom': it resolves as the working folder")
    func customModeWithoutFolder() {
        let resolved = CullingDestinationResolver.resolve(
            mode: .customFolder, folderName: "Selection", customFolderURL: nil, openFolderURL: openFolder
        )
        #expect(resolved.mode == .workingFolder)
        #expect(resolved.baseFolderURL == openFolder)
    }

    @Test("A missing custom folder is a problem, not a destination")
    func missingCustomFolder() throws {
        let parent = try makeTempDirectory("missing")
        defer { try? FileManager.default.removeItem(at: parent) }
        let gone = parent.appendingPathComponent("UnpluggedSSD", isDirectory: true)

        let resolved = CullingDestinationResolver.resolve(
            mode: .customFolder, folderName: "Selection", customFolderURL: gone, openFolderURL: openFolder
        )
        #expect(resolved.mode == .customFolder)
        #expect(resolved.problem == .missing)
        #expect(!resolved.isUsable)

        let summary = CullingDestinationResolver.summary(of: resolved, home: "/Users/alex")
        #expect(summary.warningText == CustomFolderProblem.missing.message)
        #expect(summary.customFolderPathText == gone.path)
    }

    @Test("A custom path that is a file is a problem")
    func customPathIsAFile() throws {
        let parent = try makeTempDirectory("file")
        defer { try? FileManager.default.removeItem(at: parent) }
        let file = parent.appendingPathComponent("notes.txt")
        try Data("x".utf8).write(to: file)

        let resolved = CullingDestinationResolver.resolve(
            mode: .customFolder, folderName: "Selection", customFolderURL: file, openFolderURL: nil
        )
        #expect(resolved.problem == .notADirectory)
        #expect(!resolved.isUsable)
    }

    @Test("A read-only custom folder is a problem")
    func readOnlyCustomFolder() throws {
        // root ignores permission bits, so the probe cannot be exercised there.
        guard getuid() != 0 else { return }
        let parent = try makeTempDirectory("readonly")
        let locked = parent.appendingPathComponent("Locked", isDirectory: true)
        try FileManager.default.createDirectory(at: locked, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: locked.path)
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: locked.path)
            try? FileManager.default.removeItem(at: parent)
        }

        let resolved = CullingDestinationResolver.resolve(
            mode: .customFolder, folderName: "Selection", customFolderURL: locked, openFolderURL: nil
        )
        #expect(resolved.problem == .notWritable)
        #expect(!resolved.isUsable)
    }

    // MARK: - Display

    @Test("The home folder is shortened to ~ only on a path boundary")
    func abbreviation() {
        #expect(CullingDestinationResolver.abbreviated("/Users/alex", home: "/Users/alex") == "~")
        #expect(CullingDestinationResolver.abbreviated("/Users/alex/Pictures/Trip", home: "/Users/alex") == "~/Pictures/Trip")
        #expect(CullingDestinationResolver.abbreviated("/Users/alexander/Pictures", home: "/Users/alex") == "/Users/alexander/Pictures")
        #expect(CullingDestinationResolver.abbreviated("/Volumes/SSD/Picks", home: "/Users/alex") == "/Volumes/SSD/Picks")
        #expect(CullingDestinationResolver.abbreviated("/Users/alex/Pictures", home: "/Users/alex/") == "~/Pictures")
        #expect(CullingDestinationResolver.abbreviated("/tmp/x", home: "/") == "/tmp/x")
    }

    @Test("Summary shows the full Selection path with a trailing slash, home shortened")
    func summaryText() {
        let trip = URL(fileURLWithPath: "/Users/alex/Pictures/Trip", isDirectory: true)
        let working = CullingDestinationResolver.resolve(
            mode: .workingFolder, folderName: "Selection", customFolderURL: nil, openFolderURL: trip
        )
        let summary = CullingDestinationResolver.summary(of: working, home: "/Users/alex")
        #expect(summary.selectionPathText == "~/Pictures/Trip/Selection/")
        #expect(summary.customFolderPathText == nil)
        #expect(summary.placeholderText == nil)
        #expect(summary.warningText == nil)
    }

    @Test("Custom summary names the custom folder and the resolved destination")
    func customSummaryText() throws {
        let custom = try makeTempDirectory("summary")
        defer { try? FileManager.default.removeItem(at: custom) }

        let resolved = CullingDestinationResolver.resolve(
            mode: .customFolder, folderName: "Selection", customFolderURL: custom, openFolderURL: openFolder
        )
        let summary = CullingDestinationResolver.summary(of: resolved, home: "/nonexistent-home")
        #expect(summary.customFolderPathText == custom.path)
        #expect(summary.selectionPathText == custom.path + "/Selection/")
        #expect(summary.warningText == nil)
    }

    // MARK: - Persistence and migration

    @Test("No stored setting reads as Working folder")
    func defaultIsWorkingFolder() {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        #expect(defaults.cullingDestinationMode == .workingFolder)
    }

    @Test("An existing custom destination without a stored mode reads as Custom (update keeps the user's setup)")
    func existingCustomFolderReadsAsCustom() {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.externalCullingDestinationURL = URL(fileURLWithPath: "/Volumes/SSD/Picks", isDirectory: true)

        #expect(defaults.object(forKey: AppSettingsKeys.cullingDestinationMode) == nil)
        #expect(defaults.cullingDestinationMode == .customFolder)
    }

    @Test("Switching to Working folder takes effect at once and keeps the custom folder for later")
    func switchingBackKeepsCustomFolder() throws {
        let custom = try makeTempDirectory("keep")
        defer { try? FileManager.default.removeItem(at: custom) }
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }

        defaults.externalCullingDestinationURL = custom
        defaults.cullingDestinationMode = .customFolder
        #expect(CullingDestinationResolver.resolve(defaults: defaults, openFolderURL: openFolder).baseFolderURL == custom)

        defaults.cullingDestinationMode = .workingFolder
        let working = CullingDestinationResolver.resolve(defaults: defaults, openFolderURL: openFolder)
        #expect(working.mode == .workingFolder)
        #expect(working.baseFolderURL == openFolder)
        #expect(defaults.externalCullingDestinationURL == custom)

        defaults.cullingDestinationMode = .customFolder
        #expect(CullingDestinationResolver.resolve(defaults: defaults, openFolderURL: openFolder).baseFolderURL == custom)
    }

    @Test("A stored Custom mode with no custom folder reads as Working folder")
    func storedCustomWithoutFolder() {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(CullingDestinationMode.customFolder.rawValue, forKey: AppSettingsKeys.cullingDestinationMode)
        #expect(defaults.cullingDestinationMode == .workingFolder)
    }

    @Test("An unknown stored mode value falls back by the same rule")
    func unknownStoredMode() {
        #expect(CullingDestinationMode.effective(storedRaw: "bogus", hasCustomFolder: false) == .workingFolder)
        #expect(CullingDestinationMode.effective(storedRaw: "bogus", hasCustomFolder: true) == .customFolder)
        #expect(CullingDestinationMode.effective(storedRaw: nil, hasCustomFolder: false) == .workingFolder)
    }

    @Test("Reset removes the mode and the custom folder")
    func resetClearsDestination() {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.externalCullingDestinationURL = URL(fileURLWithPath: "/Volumes/SSD/Picks", isDirectory: true)
        defaults.cullingDestinationMode = .customFolder

        defaults.resetAppSettingsToDefaults()
        #expect(defaults.cullingDestinationMode == .workingFolder)
        #expect(defaults.externalCullingDestinationURL == nil)
    }

    @Test("The mode key is migrated with the other preferences")
    func modeKeyIsMigrated() {
        #expect(AppSettingsKeys.all.contains(AppSettingsKeys.cullingDestinationMode))
    }

    // MARK: - One source of truth: the file operation lands where Settings says

    @Test("Move and Copy through FileCullingManager land exactly in the resolved Selection folder")
    func fileOperationsFollowResolution() throws {
        let base = try makeTempDirectory("ops")
        defer { try? FileManager.default.removeItem(at: base) }
        let card = base.appendingPathComponent("Card", isDirectory: true)
        let ssd = base.appendingPathComponent("SSD", isDirectory: true)
        try FileManager.default.createDirectory(at: card, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: ssd, withIntermediateDirectories: true)

        let moved = card.appendingPathComponent("IMG_0001.JPG")
        let copied = card.appendingPathComponent("IMG_0002.JPG")
        try Data("one".utf8).write(to: moved)
        try Data("two".utf8).write(to: copied)

        let manager = FileCullingManager(trashService: DirectoryTrashService(rootDirectory: base))

        // Working folder.
        let working = CullingDestinationResolver.resolve(
            mode: .workingFolder, folderName: "Picks", customFolderURL: ssd, openFolderURL: card
        )
        let movedURLs = try manager.moveToSelection(
            photoURL: moved, rootURL: try #require(working.baseFolderURL), destinationFolderName: working.folderName
        )
        let selection = try #require(working.selectionFolderURL)
        #expect(movedURLs.map { $0.deletingLastPathComponent().path } == [selection.path])
        #expect(FileManager.default.fileExists(atPath: selection.appendingPathComponent("IMG_0001.JPG").path))

        // Custom folder.
        let custom = CullingDestinationResolver.resolve(
            mode: .customFolder, folderName: "Picks", customFolderURL: ssd, openFolderURL: card
        )
        let copiedURLs = try manager.copyToSelection(
            photoURL: copied, rootURL: try #require(custom.baseFolderURL), destinationFolderName: custom.folderName
        )
        let customSelection = try #require(custom.selectionFolderURL)
        #expect(copiedURLs.map { $0.deletingLastPathComponent().path } == [customSelection.path])
        #expect(customSelection.path == ssd.path + "/Picks")
        #expect(FileManager.default.fileExists(atPath: customSelection.appendingPathComponent("IMG_0002.JPG").path))
    }

    @Test("cullCurrentPhoto honours the working folder even while a custom folder is stored")
    @MainActor
    func viewModelWorkingFolderWithStoredCustom() async throws {
        let base = try makeTempDirectory("vm-working")
        defer { try? FileManager.default.removeItem(at: base) }
        let card = base.appendingPathComponent("Card", isDirectory: true)
        let ssd = base.appendingPathComponent("SSD", isDirectory: true)
        try FileManager.default.createDirectory(at: card, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: ssd, withIntermediateDirectories: true)
        let photo = card.appendingPathComponent("IMG_0001.JPG")
        try Data("one".utf8).write(to: photo)

        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.externalCullingDestinationURL = ssd
        defaults.cullingDestinationMode = .workingFolder

        let actor = AsyncCullingActor(trashService: DirectoryTrashService(rootDirectory: base))
        let vm = CullingWorkspaceViewModel(cullingActor: actor, userDefaults: defaults)
        vm.setPhotos([PhotoItem(url: photo)], rootURL: card)
        vm.autoAdvance = false
        vm.cullCurrentPhoto(action: .move)

        for _ in 0..<40 {
            if vm.cardCullSummary.inFlightCount == 0 { break }
            try await Task.sleep(nanoseconds: 25_000_000)
        }
        #expect(FileManager.default.fileExists(atPath: card.appendingPathComponent("Selection/IMG_0001.JPG").path))
        #expect(!FileManager.default.fileExists(atPath: ssd.appendingPathComponent("Selection/IMG_0001.JPG").path))
    }

    @Test("cullCurrentPhoto refuses Move and Copy into an unreachable custom folder and creates nothing")
    @MainActor
    func viewModelRefusesUnreachableCustomFolder() async throws {
        let base = try makeTempDirectory("vm-gone")
        defer { try? FileManager.default.removeItem(at: base) }
        let card = base.appendingPathComponent("Card", isDirectory: true)
        let gone = base.appendingPathComponent("UnpluggedSSD", isDirectory: true)
        try FileManager.default.createDirectory(at: card, withIntermediateDirectories: true)
        let photo = card.appendingPathComponent("IMG_0001.JPG")
        try Data("one".utf8).write(to: photo)

        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.externalCullingDestinationURL = gone
        defaults.cullingDestinationMode = .customFolder

        let actor = AsyncCullingActor(trashService: DirectoryTrashService(rootDirectory: base))
        let vm = CullingWorkspaceViewModel(cullingActor: actor, userDefaults: defaults)
        vm.setPhotos([PhotoItem(url: photo)], rootURL: card)
        vm.autoAdvance = false

        vm.cullCurrentPhoto(action: .move)
        #expect(vm.photos[0].status == .candidate)
        #expect(vm.currentToast?.title == "Custom folder not available")
        vm.cullCurrentPhoto(action: .copy)
        #expect(vm.photos[0].status == .candidate)

        try await Task.sleep(nanoseconds: 100_000_000)
        #expect(vm.canUndo == false)
        #expect(FileManager.default.fileExists(atPath: photo.path))
        #expect(!FileManager.default.fileExists(atPath: gone.path))
        #expect(!FileManager.default.fileExists(atPath: card.appendingPathComponent("Selection").path))
    }
}
