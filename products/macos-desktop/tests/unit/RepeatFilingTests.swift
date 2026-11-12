import Testing
import Foundation
@testable import PhotoSelectorKit

/// [USER 2026-11-12] decision #29 (docs/shared/FILE_OPERATIONS.md): a copied photo is decided, marked,
/// counted once and out of Remaining, and a later Move or second Copy files nothing twice.
@Suite("A copied photo is decided and is never filed twice")
@MainActor
struct RepeatFilingTests {
    private struct Env {
        let root: URL
        let vm: CullingWorkspaceViewModel
        var selection: URL { root.appendingPathComponent("Selection") }
        func filesInSelection() -> [String] {
            ((try? FileManager.default.contentsOfDirectory(atPath: selection.path)) ?? [])
                .filter { !$0.hasPrefix(".") }.sorted()
        }
        func cleanUp() { try? FileManager.default.removeItem(at: root) }
    }

    private func makeEnv(count: Int) throws -> Env {
        let root = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
            .appendingPathComponent("RepeatFiling_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var items: [PhotoItem] = []
        for i in 0..<count {
            let url = root.appendingPathComponent(String(format: "IMG_%02d.JPG", i))
            try Data("PAYLOAD\(i)".utf8).write(to: url)
            items.append(PhotoItem(url: url))
        }
        let suite = "com.photoselector.tests.repeatfiling.\(UUID().uuidString)"
        let vm = CullingWorkspaceViewModel(userDefaults: UserDefaults(suiteName: suite)!)
        vm.autoAdvance = false
        vm.setPhotos(items, rootURL: root)
        return Env(root: root, vm: vm)
    }

    /// Lets the actor finish the queued jobs.
    private func settle(_ env: Env, expecting files: Int) async throws {
        for _ in 0..<80 where env.filesInSelection().count < files { try await Task.sleep(nanoseconds: 25_000_000) }
        try await Task.sleep(nanoseconds: 100_000_000)
    }

    @Test("Copy then Move leaves exactly one file in the Selection and keeps the original")
    func copyThenMove() async throws {
        let env = try makeEnv(count: 2); defer { env.cleanUp() }
        env.vm.cullCurrentPhoto(action: .copy)
        try await settle(env, expecting: 1)
        env.vm.cullCurrentPhoto(action: .move)
        #expect(env.vm.currentToast?.title == "Already in Selection")
        try await Task.sleep(nanoseconds: 200_000_000)
        #expect(env.filesInSelection() == ["IMG_00.JPG"], "no -2 copy beside the first")
        #expect(FileManager.default.fileExists(atPath: env.root.appendingPathComponent("IMG_00.JPG").path))
        #expect(env.vm.photos[0].status == .copied)
        #expect(env.vm.copiedCount == 1 && env.vm.selectedCount == 0)
    }

    @Test("A second Copy says \"Already copied\" and files nothing")
    func copyTwice() async throws {
        let env = try makeEnv(count: 1); defer { env.cleanUp() }
        env.vm.cullCurrentPhoto(action: .copy)
        try await settle(env, expecting: 1)
        env.vm.cullCurrentPhoto(action: .copy)
        #expect(env.vm.currentToast?.title == "Already copied")
        try await Task.sleep(nanoseconds: 200_000_000)
        #expect(env.filesInSelection() == ["IMG_00.JPG"])
        env.vm.undoLastAction()
        try await Task.sleep(nanoseconds: 300_000_000)
        #expect(env.vm.canUndo == false, "the refused press left no undo entry, so one Undo took the Copy back")
        #expect(env.filesInSelection().isEmpty)
    }

    @Test("Copy after Move is refused as well; the moved photo is decided")
    func copyAfterMove() async throws {
        let env = try makeEnv(count: 1); defer { env.cleanUp() }
        env.vm.cullCurrentPhoto(action: .move)
        try await settle(env, expecting: 1)
        env.vm.cullCurrentPhoto(action: .copy)
        #expect(env.vm.currentToast?.title == "Already in Selection")
        try await Task.sleep(nanoseconds: 200_000_000)
        #expect(env.filesInSelection() == ["IMG_00.JPG"])
    }

    @Test("12 photos: copy 5, move 5 (3 of them copied), trash 1 - the tally always adds up and files nothing twice")
    func twelvePhotoCase() async throws {
        let env = try makeEnv(count: 12); defer { env.cleanUp() }
        let vm = env.vm
        func sum() -> Int { vm.selectedCount + vm.copiedCount + vm.trashedCount + vm.remainingCount }
        for i in 0..<5 {
            vm.selectPhoto(at: i); vm.cullCurrentPhoto(action: .copy)
            #expect(sum() == 12)
        }
        for i in [0, 1, 2, 5, 6] {   // 0, 1, 2 were copied
            vm.selectPhoto(at: i); vm.cullCurrentPhoto(action: .move)
            #expect(sum() == 12)
        }
        vm.selectPhoto(at: 7); vm.cullCurrentPhoto(action: .trash)
        try await settle(env, expecting: 7)
        #expect(sum() == 12)
        // 5 copies + the two moves of uncopied photos; the three moves after a copy filed nothing.
        #expect(env.filesInSelection().count == 7)
        #expect(Set(env.filesInSelection()).count == 7)
        #expect(vm.copiedCount == 5 && vm.selectedCount == 2 && vm.trashedCount == 1 && vm.remainingCount == 4)
    }

    @Test("The notices follow the status and the action")
    func noticeTable() {
        #expect(RepeatFiling.notice(action: .copy, status: .candidate, destinationFolder: "Selection") == nil)
        #expect(RepeatFiling.notice(action: .move, status: .candidate, destinationFolder: "Selection") == nil)
        #expect(RepeatFiling.notice(action: .trash, status: .copied, destinationFolder: "Selection") == nil)
        #expect(RepeatFiling.notice(action: .copy, status: .copied, destinationFolder: "Picks")?.title == "Already copied")
        #expect(RepeatFiling.notice(action: .move, status: .copied, destinationFolder: "Picks")?.title == "Already in Picks")
    }
}
