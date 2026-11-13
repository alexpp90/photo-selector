import Testing
import Foundation
@testable import PhotoSelectorKit

/// [USER 2026-11-12] decision #20: Settings → Rejects can send Delete to `<folder>/Rejected`
/// instead of the Trash; the folder is excluded from every scan and the Duplicate Finder (REQ-MAC-EXIF.06).
@Suite("Rejected folder: Delete without the Trash")
@MainActor
struct RejectedFolderTests {
    private struct Env {
        let root: URL
        let defaults: UserDefaults
        let suite: String
        let vm: CullingWorkspaceViewModel
        var rejected: URL { root.appendingPathComponent("Rejected") }
        func cleanUp() { try? FileManager.default.removeItem(at: root); defaults.removePersistentDomain(forName: suite) }
    }

    private func makeEnv(rejects: RejectsDestination?, files: [String] = ["IMG_1.JPG"]) throws -> Env {
        let root = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
            .appendingPathComponent("Rejected_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var items: [PhotoItem] = []
        for name in files {
            let url = root.appendingPathComponent(name)
            try Data("x".utf8).write(to: url)
            if !name.hasSuffix(".xmp") { items.append(PhotoItem(url: url)) }
        }
        let suite = "com.photoselector.tests.rejected.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        if let rejects { defaults.rejectsDestination = rejects }
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        vm.autoAdvance = false
        vm.setPhotos(items, rootURL: root)
        return Env(root: root, defaults: defaults, suite: suite, vm: vm)
    }

    private func wait(_ condition: () -> Bool) async throws {
        for _ in 0..<80 where !condition() { try await Task.sleep(nanoseconds: 25_000_000) }
    }

    @Test("The default is the Trash and an unknown stored value reads as the Trash")
    func defaultIsTrash() {
        let suite = "com.photoselector.tests.rejected.default.\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        defer { d.removePersistentDomain(forName: suite) }
        #expect(d.rejectsDestination == .trash)
        d.set("garbage", forKey: AppSettingsKeys.rejectsDestination)
        #expect(d.rejectsDestination == .trash)
        d.rejectsDestination = .rejectedFolder
        #expect(d.string(forKey: AppSettingsKeys.rejectsDestination) == "rejectedFolder")
        #expect(AppSettingsKeys.all.contains(AppSettingsKeys.rejectsDestination), "migrated with the other keys")
        d.resetAppSettingsToDefaults()
        #expect(d.rejectsDestination == .trash)
    }

    @Test("Delete lands in <folder>/Rejected with its companions, never the Trash, and says so")
    func deleteGoesToRejected() async throws {
        let env = try makeEnv(rejects: .rejectedFolder, files: ["IMG_1.JPG", "IMG_1.xmp"]); defer { env.cleanUp() }
        env.vm.cullCurrentPhoto(action: .trash)
        #expect(env.vm.currentToast?.title == "Moved to Rejected")
        #expect(env.vm.currentToast?.icon == "folder.badge.minus")
        try await wait { FileManager.default.fileExists(atPath: env.rejected.appendingPathComponent("IMG_1.JPG").path) }
        #expect(FileManager.default.fileExists(atPath: env.rejected.appendingPathComponent("IMG_1.JPG").path))
        #expect(FileManager.default.fileExists(atPath: env.rejected.appendingPathComponent("IMG_1.xmp").path))
        #expect(!FileManager.default.fileExists(atPath: env.root.appendingPathComponent("IMG_1.JPG").path))
        #expect(env.vm.undoMenuTitle == "Undo Reject of IMG_1.JPG")
    }

    @Test("Undo moves the photo and its companions back")
    func undoRestores() async throws {
        let env = try makeEnv(rejects: .rejectedFolder, files: ["IMG_1.JPG", "IMG_1.xmp"]); defer { env.cleanUp() }
        env.vm.cullCurrentPhoto(action: .trash)
        try await wait { FileManager.default.fileExists(atPath: env.rejected.appendingPathComponent("IMG_1.JPG").path) }
        try await Task.sleep(nanoseconds: 150_000_000)
        env.vm.undoLastAction()
        try await wait { FileManager.default.fileExists(atPath: env.root.appendingPathComponent("IMG_1.JPG").path) }
        #expect(FileManager.default.fileExists(atPath: env.root.appendingPathComponent("IMG_1.JPG").path))
        #expect(FileManager.default.fileExists(atPath: env.root.appendingPathComponent("IMG_1.xmp").path))
        #expect(env.vm.photos[0].status == .candidate)
    }

    @Test("A name already in Rejected is never overwritten")
    func neverOverwrites() throws {
        let env = try makeEnv(rejects: nil); defer { env.cleanUp() }
        try FileManager.default.createDirectory(at: env.rejected, withIntermediateDirectories: true)
        try Data("old".utf8).write(to: env.rejected.appendingPathComponent("IMG_1.JPG"))
        let service = RejectedFolderService(folder: env.rejected)
        let landed = try service.trash(env.root.appendingPathComponent("IMG_1.JPG"))
        #expect(landed.lastPathComponent == "IMG_1-2.JPG")
        #expect(try Data(contentsOf: env.rejected.appendingPathComponent("IMG_1.JPG")) == Data("old".utf8))
    }

    @Test("The Rejected folder is skipped by the scanner and the Duplicate Finder, and a scan of it as the root still works")
    func scansSkipRejected() async throws {
        let env = try makeEnv(rejects: nil, files: ["A.JPG"]); defer { env.cleanUp() }
        try FileManager.default.createDirectory(at: env.rejected, withIntermediateDirectories: true)
        try Data("dupe".utf8).write(to: env.rejected.appendingPathComponent("R1.JPG"))
        try Data("dupe".utf8).write(to: env.root.appendingPathComponent("D.JPG"))
        try Data("dupe".utf8).write(to: env.rejected.appendingPathComponent("R2.JPG"))

        var names: [String] = []
        for await batch in DirectoryScanner(userDefaults: env.defaults).scanStream(at: env.root, recursive: true, batchSize: 30, firstBatchSize: 30) {
            names += batch.map(\.filename)
        }
        #expect(names.sorted() == ["A.JPG", "D.JPG"], "nothing from Rejected/")

        let clusters = try await DuplicateFinder.findDuplicates(in: env.root, recursive: true)
        #expect(clusters.isEmpty, "R1 and R2 match D but are in Rejected/")

        var rootScan: [String] = []
        for await batch in DirectoryScanner(userDefaults: env.defaults).scanStream(at: env.rejected, recursive: true, batchSize: 30, firstBatchSize: 30) {
            rootScan += batch.map(\.filename)
        }
        #expect(rootScan.sorted() == ["R1.JPG", "R2.JPG"], "opened as the root it is scanned")
    }

    @Test("With the Trash chosen nothing is created and the texts are the Trash's")
    func trashTexts() {
        #expect(LensjetCommand.trash.name() == "Move to Trash")
        #expect(LensjetCommand.trash.name(rejects: .rejectedFolder) == "Move to Rejected")
        #expect(LensjetCommand.trash.tooltip(rejects: .rejectedFolder) == "Move to Rejected (\u{232B})")
        #expect(LensjetCommand.trash.shortName(rejects: .rejectedFolder) == "Reject")
        let url = URL(fileURLWithPath: "/shoot/IMG_1.CR3")
        #expect(CullingFeedback.companionTitle(action: .trash, originalPrimaryURL: url, fileCount: 3, destination: nil, rejects: .rejectedFolder)?.title == "Rejected IMG_1 +2")
        #expect(CullingFeedback.companionTitle(action: .trash, originalPrimaryURL: url, fileCount: 3, destination: nil)?.title == "Trashed IMG_1 +2")
        #expect(ChromeDensity.full.dockTitle(.trash, rejects: .rejectedFolder) == "Move to Rejected")
    }
}
