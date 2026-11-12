import Testing
import Foundation
@testable import PhotoSelectorKit

/// The beginning and the end of a cull: resume, Open Recent, the destination on the workspace,
/// the end card and its format verdict.

private struct ListScanner: DirectoryScanning {
    let itemsByFolder: [URL: [PhotoItem]]
    func scanStream(at directoryURL: URL, recursive: Bool, batchSize: Int, firstBatchSize: Int) -> AsyncStream<[PhotoItem]> {
        let items = itemsByFolder[directoryURL.standardizedFileURL] ?? []
        return AsyncStream { continuation in
            if !items.isEmpty { continuation.yield(items) }
            continuation.finish()
        }
    }
    func scanDirectory(at directoryURL: URL, recursive: Bool) async throws -> [PhotoItem] {
        itemsByFolder[directoryURL.standardizedFileURL] ?? []
    }
}

private func makeFolder(_ photos: Int) throws -> (URL, [PhotoItem]) {
    let dir = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
        .appendingPathComponent("CullSession_\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    var items: [PhotoItem] = []
    for i in 0..<photos {
        let url = dir.appendingPathComponent("P\(i).JPG")
        try Data("PAYLOAD\(i)".utf8).write(to: url)
        items.append(PhotoItem(id: UUID(), url: url, status: .candidate))
    }
    return (dir, items)
}

private func makeDefaults() -> (UserDefaults, String) {
    let name = "com.photoselector.tests.session.\(UUID().uuidString)"
    return (UserDefaults(suiteName: name)!, name)
}

// MARK: - The stores and the pure rules

@Suite("Recent folders and the remembered photo")
struct RecentFoldersTests {
    @Test("Newest first, a repeat moves up, at most ten, folders that are gone are left out")
    func listRules() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = RecentFolders(defaults: defaults)
        var dirs: [URL] = []
        for _ in 0..<12 {
            let (dir, _) = try makeFolder(0)
            dirs.append(dir)
            store.record(dir)
        }
        defer { dirs.forEach { try? FileManager.default.removeItem(at: $0) } }
        #expect(store.folders.count == RecentFolders.limit)
        #expect(store.folders.first == dirs.last)
        store.record(dirs[5])
        #expect(store.folders.first == dirs[5])
        #expect(store.folders.filter { $0 == dirs[5] }.count == 1)
        try FileManager.default.removeItem(at: dirs[5])
        #expect(store.lastFolder == dirs[11], "the folder that is gone is skipped, not offered")
        store.clear()
        #expect(store.folders.isEmpty)
    }

    @Test("The remembered photo is found by its file, and is nil once that file is gone")
    func positionByIdentity() throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let (dir, items) = try makeFolder(3)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = RecentFolders(defaults: defaults)
        store.record(dir)
        store.remember(photo: items[2].url, in: dir)
        #expect(store.rememberedPhoto(in: dir)?.lastPathComponent == "P2.JPG")
        try FileManager.default.removeItem(at: items[2].url)
        #expect(store.rememberedPhoto(in: dir) == nil)
    }

    @Test("It is session state, not a preference: not in the migrated keys")
    func notASetting() {
        #expect(!AppSettingsKeys.all.contains(RecentFolders.foldersKey))
        #expect(!AppSettingsKeys.all.contains(RecentFolders.positionsKey))
    }
}

@Suite("The end of a cull")
struct CullEndRuleTests {
    private func card() -> CullEndCard { CullEndCard(selected: 1, copied: 0, trashed: 0, destinationPath: "~/x/Selection/") }

    @Test("Not before the first decision, not while a file is still moving, once everything is decided")
    func doneRule() {
        func state(_ s: Int, _ c: Int, _ t: Int, _ u: Int, inFlight: Int = 0, last: Bool = false) -> CullEndState {
            CullEnd.state(selected: s, copied: c, trashed: t, undecided: u, inFlight: inFlight, onLastPhoto: last, card: card())
        }
        #expect(state(0, 0, 0, 5) == .none)
        #expect(state(0, 0, 0, 0) == .none, "an empty cull has no end")
        #expect(state(2, 0, 1, 1) == .none)
        #expect(state(2, 0, 1, 0, inFlight: 1) == .none)
        #expect(state(2, 0, 1, 0) == .done(card()))
        #expect(state(0, 3, 0, 0) == .done(card()), "a copied photo is decided")
        #expect(state(2, 0, 1, 4, last: true) == .remaining(count: 4))
    }
}

@Suite("Safe to format")
struct SafeToFormatTests {
    @Test("No line off a removable volume; keepers inside the folder are on the card")
    func volumeRules() throws {
        let (dir, _) = try makeFolder(1)
        defer { try? FileManager.default.removeItem(at: dir) }
        #expect(SafeToFormat.verdict(root: dir, copiedURLs: [], selectionIsInsideSource: false, isRemovable: { _ in false }) == nil)
        #expect(SafeToFormat.verdict(root: dir, copiedURLs: [], selectionIsInsideSource: true, isRemovable: { _ in true }) == .selectionOnCard)
    }

    @Test("A verdict only when every file is accounted for: a video left behind withholds it")
    func everyFileCounts() throws {
        let (dir, items) = try makeFolder(2)
        defer { try? FileManager.default.removeItem(at: dir) }
        // Both photos were copied off the card: their originals (and sidecars) are accounted for.
        let sidecar = dir.appendingPathComponent("P0.xmp")
        try Data("x".utf8).write(to: sidecar)
        let urls = items.map(\.url)
        #expect(SafeToFormat.verdict(root: dir, copiedURLs: urls, selectionIsInsideSource: false, isRemovable: { _ in true }) == .safe)
        try Data("video".utf8).write(to: dir.appendingPathComponent("CLIP0001.MP4"))
        let verdict = SafeToFormat.verdict(root: dir, copiedURLs: urls, selectionIsInsideSource: false, isRemovable: { _ in true })
        #expect(verdict == .otherFilesLeft(1))
        #expect(verdict?.text == "1 other files left")
        #expect(FormatVerdict.safe.text == "Safe to format")
    }
}

// MARK: - The view model

@Suite("The workspace remembers and ends")
@MainActor
struct WorkspaceSessionTests {

    private func makeVM(folder: URL, items: [PhotoItem], defaults: UserDefaults) -> CullingWorkspaceViewModel {
        CullingWorkspaceViewModel(
            directoryScanner: ListScanner(itemsByFolder: [folder.standardizedFileURL: items]),
            userDefaults: defaults)
    }

    private func settle(_ vm: CullingWorkspaceViewModel) async {
        for _ in 0..<200 where vm.isLoading { try? await Task.sleep(nanoseconds: 10_000_000) }
    }

    private func awaitEnd(_ vm: CullingWorkspaceViewModel) async -> CullEndState {
        for _ in 0..<200 {
            if case .done = vm.endState { break }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return vm.endState
    }

    @Test("Relaunch reopens the last folder at the same photo, even in another order")
    func resumeByIdentity() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let (dir, items) = try makeFolder(5)
        defer { try? FileManager.default.removeItem(at: dir) }

        let first = makeVM(folder: dir, items: items, defaults: defaults)
        first.loadFolder(at: dir)
        await settle(first)
        first.selectPhoto(at: 3)
        let wanted = first.photos[3].url

        // Next launch: the scan returns the photos in another order, and no folder is given.
        let second = makeVM(folder: dir, items: Array(items.reversed()), defaults: defaults)
        #expect(second.recentFolders == [dir])
        #expect(second.resumeLastFolder())
        await settle(second)
        #expect(second.photos[second.currentIndex].url == wanted)
    }

    @Test("A photo that was moved away reopens on the first photo; a folder that is gone starts empty")
    func resumeFallbacks() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let (dir, items) = try makeFolder(4)
        defer { try? FileManager.default.removeItem(at: dir) }
        let first = makeVM(folder: dir, items: items, defaults: defaults)
        first.loadFolder(at: dir)
        await settle(first)
        first.selectPhoto(at: 2)
        try FileManager.default.removeItem(at: items[2].url)

        let second = makeVM(folder: dir, items: items.filter { $0.url != items[2].url }, defaults: defaults)
        #expect(second.resumeLastFolder())
        await settle(second)
        #expect(second.currentIndex == 0)

        try FileManager.default.removeItem(at: dir)
        let third = makeVM(folder: dir, items: [], defaults: defaults)
        #expect(!third.resumeLastFolder())
        #expect(third.photos.isEmpty)
    }

    @Test("The end card appears after the last decision and not before; Esc and Undo take it away")
    func endCardLifecycle() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let (dir, items) = try makeFolder(2)
        defer { try? FileManager.default.removeItem(at: dir) }
        let vm = makeVM(folder: dir, items: items, defaults: defaults)
        vm.loadFolder(at: dir)
        await settle(vm)
        vm.autoAdvance = false

        #expect(vm.endState == .none)
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.endState == .none, "one photo is still undecided and it is not the last on screen")
        vm.selectPhoto(at: 1)
        vm.cullCurrentPhoto(action: .move)
        guard case .done(let card) = await awaitEnd(vm) else {
            Issue.record("no end card after the last decision")
            return
        }
        #expect(card.selected == 2 && card.copied == 0 && card.trashed == 0)

        // Esc drops it; the next decision (after an undo) brings it back.
        #expect(vm.dismissStickyToast())
        #expect(vm.endState == .none)
        vm.undoLastAction()
        #expect(vm.endState == .none)
    }

    @Test("On the last photo with photos undecided, a notice offers the first one")
    func remainingNotice() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let (dir, items) = try makeFolder(3)
        defer { try? FileManager.default.removeItem(at: dir) }
        let vm = makeVM(folder: dir, items: items, defaults: defaults)
        vm.loadFolder(at: dir)
        await settle(vm)
        vm.autoAdvance = false
        vm.selectPhoto(at: 2)
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.endState == .remaining(count: 2))
        vm.showFirstUndecided()
        #expect(vm.currentIndex == 0)
        #expect(vm.endState == .none)
    }

    @Test("The destination on the card and the dock is the Settings text, in working-folder and custom mode")
    func destinationMatchesSettings() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let (dir, items) = try makeFolder(1)
        let (custom, _) = try makeFolder(0)
        defer { try? FileManager.default.removeItem(at: dir); try? FileManager.default.removeItem(at: custom) }
        let vm = makeVM(folder: dir, items: items, defaults: defaults)
        vm.loadFolder(at: dir)
        await settle(vm)

        func settingsText() -> String? {
            CullingDestinationResolver.summary(of: CullingDestinationResolver.resolve(defaults: defaults, openFolderURL: dir)).selectionPathText
        }
        #expect(vm.destinationPathText != nil)
        #expect(vm.destinationPathText == settingsText())
        defaults.cullingDestinationFolderName = "Keepers"
        #expect(vm.destinationName == "Keepers")
        #expect(vm.destinationPathText?.hasSuffix("/Keepers/") == true)
        defaults.externalCullingDestinationURL = custom
        defaults.cullingDestinationMode = .customFolder
        #expect(vm.destinationPathText == settingsText())
        #expect(vm.destinationPathText?.contains(custom.lastPathComponent) == true)
    }

    @Test("The Edit menu's Undo names the action and file, and is plain when there is nothing to undo")
    func undoTitleFollowsTheStack() async throws {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let (dir, items) = try makeFolder(2)
        defer { try? FileManager.default.removeItem(at: dir) }
        let vm = makeVM(folder: dir, items: items, defaults: defaults)
        vm.loadFolder(at: dir)
        await settle(vm)
        vm.autoAdvance = false
        #expect(vm.undoMenuTitle == "Undo")
        #expect(!vm.canUndo)
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.canUndo)
        #expect(vm.undoMenuTitle == "Undo Move of \(vm.photos[0].filename)")
    }
}
