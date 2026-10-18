import Testing
import Foundation
@testable import PhotoSelectorKit

/// Scanner that emits a fixed list per folder, in a single batch.
private struct FixedScanner: DirectoryScanning {
    let itemsByFolder: [URL: [PhotoItem]]

    func scanStream(
        at directoryURL: URL,
        recursive: Bool,
        batchSize: Int,
        firstBatchSize: Int
    ) -> AsyncStream<[PhotoItem]> {
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

/// FileManager whose moves wait for a gate, then optionally behave like a copy whose original
/// cannot be removed (partial move).
private final class GatedMoveFileManager: FileManager, @unchecked Sendable {
    let gate = DispatchSemaphore(value: 0)
    let gated: Bool
    let partial: Bool

    init(gated: Bool, partial: Bool) {
        self.gated = gated
        self.partial = partial
        super.init()
    }

    override func moveItem(at srcURL: URL, to dstURL: URL) throws {
        if gated { gate.wait() }
        if partial {
            try copyItem(at: srcURL, to: dstURL)
            throw NSError(domain: NSCocoaErrorDomain, code: NSFileWriteNoPermissionError, userInfo: [
                NSLocalizedDescriptionKey: "The original could not be removed (read-only volume)"
            ])
        }
        try super.moveItem(at: srcURL, to: dstURL)
    }
}

private func makeGatedActor(gated: Bool, partial: Bool) -> (AsyncCullingActor, DispatchSemaphore) {
    let fm = GatedMoveFileManager(gated: gated, partial: partial)
    return (AsyncCullingActor(cullingManager: FileCullingManager(fileManager: fm), fileManager: fm), fm.gate)
}

@Suite("Undo records survive a folder switch (Q-7)")
@MainActor
struct UndoAcrossFolderSwitchTests {

    private func makeFolder(_ photoCount: Int) throws -> (URL, [PhotoItem]) {
        let base = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
        let dir = base.appendingPathComponent("UndoSwitch_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var items: [PhotoItem] = []
        for i in 0..<photoCount {
            let url = dir.appendingPathComponent("P\(i).JPG")
            try Data("PAYLOAD".utf8).write(to: url)
            items.append(PhotoItem(id: UUID(), url: url, status: .candidate))
        }
        return (dir, items)
    }

    private func makeVM(
        actor: AsyncCullingActor,
        folders: [(URL, [PhotoItem])]
    ) -> CullingWorkspaceViewModel {
        var map: [URL: [PhotoItem]] = [:]
        for (u, items) in folders { map[u.standardizedFileURL] = items }
        return CullingWorkspaceViewModel(directoryScanner: FixedScanner(itemsByFolder: map), cullingActor: actor)
    }

    private func waitUntil(_ budget: TimeInterval = 10, _ condition: @MainActor () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(budget)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return condition()
    }

    @Test("A move finishing after the switch is undoable after reopening its folder and not in the new one")
    func moveFinishingAfterSwitchKeepsUndo() async throws {
        let (actor, gate) = makeGatedActor(gated: true, partial: false)
        let (a, aItems) = try makeFolder(1)
        let (b, bItems) = try makeFolder(2)
        defer { try? FileManager.default.removeItem(at: a); try? FileManager.default.removeItem(at: b) }
        let vm = makeVM(actor: actor, folders: [(a, aItems), (b, bItems)])

        vm.loadFolder(at: a)
        #expect(await waitUntil { vm.photos.count == 1 })
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.canUndo)

        vm.loadFolder(at: b)
        #expect(!vm.canUndo)
        #expect(await waitUntil { vm.photos.count == 2 })
        gate.signal() // the move now finishes while B is open
        let moved = a.appendingPathComponent("Selection").appendingPathComponent("P0.JPG")
        #expect(await waitUntil { FileManager.default.fileExists(atPath: moved.path) })
        try? await Task.sleep(nanoseconds: 200_000_000) // let the completion hop run
        #expect(!vm.canUndo)
        #expect(vm.photos.allSatisfy { $0.status == .candidate })

        vm.loadFolder(at: a)
        #expect(vm.canUndo)
        #expect(await waitUntil { vm.photos.count == 1 })
        gate.signal() // the undo's move passes the gate too
        vm.undoLastAction()
        #expect(await waitUntil { FileManager.default.fileExists(atPath: aItems[0].url.path) })
        #expect(!vm.canUndo)
    }

    @Test("A partial move finishing after the switch keeps its record and leaves the new folder's photos alone")
    func partialMoveAfterSwitchDoesNotTouchNewFolder() async throws {
        let (actor, gate) = makeGatedActor(gated: true, partial: true)
        let (a, aItems) = try makeFolder(1)
        var (b, bItems) = try makeFolder(3)
        // B's first photo is already decided, so a stale write-back of the old status would show.
        bItems[0].status = .selected
        defer { try? FileManager.default.removeItem(at: a); try? FileManager.default.removeItem(at: b) }
        let vm = makeVM(actor: actor, folders: [(a, aItems), (b, bItems)])

        vm.loadFolder(at: a)
        #expect(await waitUntil { vm.photos.count == 1 })
        vm.cullCurrentPhoto(action: .move)
        vm.loadFolder(at: b)
        #expect(await waitUntil { vm.photos.count == 3 })
        vm.currentToast = nil
        gate.signal()
        let copy = a.appendingPathComponent("Selection").appendingPathComponent("P0.JPG")
        #expect(await waitUntil { FileManager.default.fileExists(atPath: copy.path) })
        try? await Task.sleep(nanoseconds: 300_000_000)

        #expect(vm.photos[0].status == .selected)
        #expect(vm.photos[1].status == .candidate)
        #expect(vm.photos[2].status == .candidate)
        #expect(vm.currentToast == nil)
        #expect(!vm.canUndo)

        vm.loadFolder(at: a)
        #expect(vm.canUndo)
    }

    @Test("Only the newest remembered folders keep their undo stacks")
    func parkedStacksAreBounded() async throws {
        let (actor, _) = makeGatedActor(gated: false, partial: false)
        let total = CullingWorkspaceViewModel.maxParkedUndoFolders + 3
        var folders: [(URL, [PhotoItem])] = []
        for _ in 0..<total { folders.append(try makeFolder(1)) }
        let (empty, _) = try makeFolder(0)
        defer { for f in folders { try? FileManager.default.removeItem(at: f.0) }; try? FileManager.default.removeItem(at: empty) }
        let vm = makeVM(actor: actor, folders: folders)

        for f in folders {
            vm.loadFolder(at: f.0)
            #expect(await waitUntil { vm.photos.count == 1 })
            vm.cullCurrentPhoto(action: .move)
        }
        vm.loadFolder(at: empty)
        #expect(vm.parkedUndoFolderCount == CullingWorkspaceViewModel.maxParkedUndoFolders)

        vm.loadFolder(at: folders[0].0) // oldest was evicted
        #expect(!vm.canUndo)
        vm.loadFolder(at: folders[total - 1].0) // newest is remembered
        #expect(vm.canUndo)
    }
}
