import Testing
import Foundation
@testable import PhotoSelectorKit

/// An undo whose files cannot be moved back out of `Selection/`.
private final class UnrestorableFileManager: FileManager, @unchecked Sendable {
    override func moveItem(at srcURL: URL, to dstURL: URL) throws {
        if srcURL.deletingLastPathComponent().lastPathComponent == "Selection" {
            throw NSError(domain: NSCocoaErrorDomain, code: NSFileWriteUnknownError, userInfo: [
                NSLocalizedDescriptionKey: "The volume went away"
            ])
        }
        try super.moveItem(at: srcURL, to: dstURL)
    }
}

/// H-LJ-8 (URL and status halves) and H-LJ-2 (a failure stays until dismissed).
@Suite("Undo tells the truth about the photo; failures stay until dismissed")
@MainActor
struct UndoHonestyAndPersistentFailureTests {

    private struct Env {
        let root: URL
        let defaults: UserDefaults
        let suite: String
        let vm: CullingWorkspaceViewModel
        func cleanUp() {
            try? FileManager.default.removeItem(at: root)
            defaults.removePersistentDomain(forName: suite)
        }
    }

    private func makeEnv(names: [String], actor: AsyncCullingActor? = nil) throws -> Env {
        let base = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
        let root = base.appendingPathComponent("UndoHonest_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var items: [PhotoItem] = []
        for name in names {
            let url = root.appendingPathComponent(name)
            try Data("PAYLOAD-\(name)".utf8).write(to: url)
            items.append(PhotoItem(id: UUID(), url: url, status: .candidate))
        }
        let suite = "com.photoselector.tests.undohonest.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let vm = CullingWorkspaceViewModel(cullingActor: actor ?? AsyncCullingActor(), userDefaults: defaults)
        vm.setPhotos(items, rootURL: root)
        return Env(root: root, defaults: defaults, suite: suite, vm: vm)
    }

    private func waitUntil(_ budget: TimeInterval = 10, _ condition: @MainActor () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(budget)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return condition()
    }

    @Test("H-LJ-8: an undo that cannot put the files back leaves the photo marked as the action left it")
    func failedUndoKeepsTheStatus() async throws {
        let fm = UnrestorableFileManager()
        let env = try makeEnv(names: ["U0.JPG", "U1.JPG"], actor: AsyncCullingActor(
            cullingManager: FileCullingManager(fileManager: fm), fileManager: fm))
        defer { env.cleanUp() }
        let id = env.vm.photos[0].id

        env.vm.cullCurrentPhoto(action: .move)
        #expect(await waitUntil { env.vm.cardCullSummary.inFlightCount == 0 })
        #expect(env.vm.photos.first { $0.id == id }?.status == .selected)

        env.vm.undoLastAction()
        #expect(await waitUntil { env.vm.currentToast?.title == "Undo Failed" })
        #expect(env.vm.photos.first { $0.id == id }?.status == .selected, "the file is still in Selection")
        #expect(env.vm.currentToast?.isFailure == true, "a failure stays until dismissed")
        #expect(env.vm.canUndo, "the undo can be tried again")
        #expect(env.vm.dismissStickyToast())
        #expect(env.vm.currentToast == nil)
    }

    @Test("H-LJ-8: an undo that restores under another name points the photo at the restored file")
    func renamedRestoreUpdatesTheUrl() async throws {
        let env = try makeEnv(names: ["R0.JPG", "R1.JPG"])
        defer { env.cleanUp() }
        let id = env.vm.photos[0].id
        let original = env.vm.photos[0].url

        env.vm.cullCurrentPhoto(action: .move)
        #expect(await waitUntil { env.vm.cardCullSummary.inFlightCount == 0 })
        // Something new took the name while the photo was away.
        try Data("NEWCOMER".utf8).write(to: original)

        env.vm.undoLastAction()
        #expect(await waitUntil { env.vm.photos.first { $0.id == id }?.url != original })
        let item = try #require(env.vm.photos.first { $0.id == id })
        #expect(item.url.lastPathComponent == "R0-2.JPG")
        #expect(item.filename == "R0-2.JPG")
        #expect(FileManager.default.fileExists(atPath: item.url.path))
        #expect(try Data(contentsOf: item.url) == Data("PAYLOAD-R0.JPG".utf8))
        #expect(try Data(contentsOf: original) == Data("NEWCOMER".utf8), "the newcomer is untouched")
    }

    @Test("H-LJ-2: a failed action and a refused one stay on screen until dismissed")
    func failureToastIsPersistent() async throws {
        let env = try makeEnv(names: ["F0.JPG", "F1.JPG"])
        defer { env.cleanUp() }
        try FileManager.default.removeItem(at: env.vm.photos[0].url)

        env.vm.cullCurrentPhoto(action: .move)
        #expect(await waitUntil { env.vm.currentToast?.title == "Action Failed" })
        #expect(env.vm.currentToast?.isFailure == true)
        env.vm.navigateToNext()
        #expect(env.vm.currentToast?.title == "Action Failed", "a navigation key does not drop a failure")
        #expect(env.vm.dismissStickyToast())
        #expect(env.vm.currentToast == nil)
    }
}
