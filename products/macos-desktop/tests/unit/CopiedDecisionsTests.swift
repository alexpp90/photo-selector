import Testing
import Foundation
@testable import PhotoSelectorKit

/// D3 (maintainer decision 2026-11-16, docs/shared/FILE_OPERATIONS.md §5): a Copy to the Selection
/// is stored and survives reopening the folder; the photo is not undecided again.
@Suite("A copied photo stays decided after the folder is reopened")
@MainActor
struct CopiedDecisionsTests {

    private func makeDefaults() -> (UserDefaults, String) {
        let suite = "com.photoselector.tests.copied.\(UUID().uuidString)"
        return (UserDefaults(suiteName: suite)!, suite)
    }

    @Test("REQ-MAC-CULL.04: the store keys photos by relative path and time, and is bounded")
    func storeBasics() {
        let (defaults, suite) = makeDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = CopiedDecisions(defaults: defaults)
        let folder = URL(fileURLWithPath: "/tmp/lensjet-copied-folder")
        let date = Date(timeIntervalSince1970: 1_700_000_000.7)

        let id = CopiedDecisions.identity(of: folder.appendingPathComponent("sub/A.JPG"), modified: date, in: folder)
        #expect(id == "sub/A.JPG|1700000000")
        #expect(CopiedDecisions.identity(of: folder.appendingPathComponent("A.JPG"), modified: nil, in: folder) == nil)
        #expect(CopiedDecisions.identity(of: URL(fileURLWithPath: "/elsewhere/A.JPG"), modified: date, in: folder) == nil)

        store.add("A.JPG|1", in: folder)
        store.add("A.JPG|1", in: folder)
        store.add("B.JPG|2", in: folder)
        #expect(store.copied(in: folder) == ["A.JPG|1", "B.JPG|2"])
        store.remove("A.JPG|1", in: folder)
        #expect(store.copied(in: folder) == ["B.JPG|2"])

        for index in 0..<(CopiedDecisions.folderLimit + 3) {
            store.add("x|1", in: URL(fileURLWithPath: "/tmp/lensjet-copied-\(index)"))
        }
        #expect(store.copied(in: folder).isEmpty, "the oldest folder was dropped")
        #expect(store.copied(in: URL(fileURLWithPath: "/tmp/lensjet-copied-\(CopiedDecisions.folderLimit + 2)")) == ["x|1"])
    }

    private func makeFolder(_ names: [String]) throws -> URL {
        let root = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
            .appendingPathComponent("CopiedReopen_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        for name in names {
            let data = try #require(SyntheticImageFactory.createJPEGDataWithMetadata(width: 32, height: 32))
            try data.write(to: root.appendingPathComponent(name))
        }
        return root
    }

    private func open(_ root: URL, defaults: UserDefaults) async -> CullingWorkspaceViewModel {
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        vm.loadFolder(at: root)
        await waitUntil { !vm.isLoading && vm.photos.count > 0 }
        return vm
    }

    private func waitUntil(_ condition: @MainActor () -> Bool) async {
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline, !condition() { try? await Task.sleep(nanoseconds: 10_000_000) }
    }

    @Test("REQ-MAC-CULL.04, FILE_OPERATIONS §5: Copy, close, reopen: the photo is copied, not undecided; Undo of the Copy takes it back")
    func copySurvivesReopen() async throws {
        let (defaults, suite) = makeDefaults()
        let root = try makeFolder(["C0.JPG", "C1.JPG", "C2.JPG"])
        defer {
            try? FileManager.default.removeItem(at: root)
            defaults.removePersistentDomain(forName: suite)
        }

        let first = await open(root, defaults: defaults)
        let copiedName = try #require(first.currentPhoto?.filename)
        first.cullCurrentPhoto(action: .copy)
        await waitUntil { first.cardCullSummary.inFlightCount == 0 }
        #expect(FileManager.default.fileExists(atPath: root.appendingPathComponent("Selection/\(copiedName)").path))

        // A new view model on the same settings is a new session.
        let second = await open(root, defaults: defaults)
        #expect(second.photos.first { $0.filename == copiedName }?.status == .copied)
        #expect(second.photos.filter { $0.status == .candidate }.count == 2, "the other photos are still to decide")
    }

    @Test("REQ-MAC-CULL.04, FILE_OPERATIONS §5: Undo of the Copy makes the photo open again after a reopen")
    func undoneCopyIsNotRemembered() async throws {
        let (defaults, suite) = makeDefaults()
        let root = try makeFolder(["U0.JPG", "U1.JPG"])
        defer {
            try? FileManager.default.removeItem(at: root)
            defaults.removePersistentDomain(forName: suite)
        }

        let first = await open(root, defaults: defaults)
        let name = try #require(first.currentPhoto?.filename)
        first.cullCurrentPhoto(action: .copy)
        await waitUntil { first.cardCullSummary.inFlightCount == 0 }
        await waitUntil { !CopiedDecisions(defaults: defaults).copied(in: root).isEmpty }
        first.undoLastAction()
        await waitUntil { CopiedDecisions(defaults: defaults).copied(in: root).isEmpty }
        #expect(CopiedDecisions(defaults: defaults).copied(in: root).isEmpty)
        await waitUntil { !FileManager.default.fileExists(atPath: root.appendingPathComponent("Selection/\(name)").path) }

        let second = await open(root, defaults: defaults)
        #expect(second.photos.allSatisfy { $0.status == .candidate })
    }
}
