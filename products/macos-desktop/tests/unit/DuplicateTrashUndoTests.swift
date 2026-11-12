import Testing
import Foundation
@testable import PhotoSelectorKit

/// A duplicate trashed in the Duplicate Finder lands on the workspace undo stack.
@Suite("Duplicate Finder trash is undoable")
struct DuplicateTrashUndoTests {
    @Test("Trash a duplicate and its sidecar through the workspace, undo, both are back")
    @MainActor
    func trashThenUndoRestores() async throws {
        let root = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
            .appendingPathComponent("DupUndo_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let keep = root.appendingPathComponent("A.JPG"), dup = root.appendingPathComponent("B.JPG")
        let sidecar = root.appendingPathComponent("B.xmp")
        for u in [keep, dup, sidecar] { try Data("same".utf8).write(to: u) }

        let suite = "com.photoselector.tests.dupundo.\(UUID().uuidString)"
        let vm = CullingWorkspaceViewModel(
            cullingActor: AsyncCullingActor(trashService: DirectoryTrashService(trashDirectory: root.appendingPathComponent(".SimTrash"))),
            userDefaults: UserDefaults(suiteName: suite)!)
        vm.setPhotos([PhotoItem(url: keep), PhotoItem(url: dup)], rootURL: root)

        try await vm.trashDuplicate(at: dup)
        #expect(!FileManager.default.fileExists(atPath: dup.path))
        #expect(!FileManager.default.fileExists(atPath: sidecar.path), "the companion goes with it")
        #expect(vm.canUndo)
        #expect(vm.photos[1].status == .trashed)

        vm.undoLastAction()
        for _ in 0..<100 where !FileManager.default.fileExists(atPath: dup.path) {
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        #expect(FileManager.default.fileExists(atPath: dup.path))
        #expect(FileManager.default.fileExists(atPath: sidecar.path))
        #expect(vm.photos[1].status == .candidate)
    }
}
