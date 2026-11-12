import Testing
import Foundation
@testable import PhotoSelectorKit

/// REQ-MAC-CLOUD.01 ([USER 2026-11-12] decision #26): a cloud folder refuses every full pass (it
/// would download each photo) and Delete (there is no Trash to take it back from), with a one-line
/// reason; nothing is read or deleted, and a refused Trash never shows "Moved to Trash".
@Suite("Cloud folders: full passes and Delete are refused")
struct CloudConsentTests {
    private func makeDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
            .appendingPathComponent("CloudConsent_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @MainActor
    private func cloudViewModel(dir: URL, count: Int) -> CullingWorkspaceViewModel {
        let urls = (0..<count).map { dir.appendingPathComponent("IMG_\($0).JPG") }
        let name = "com.photoselector.tests.cloudconsent.\(UUID().uuidString)"
        let vm = CullingWorkspaceViewModel(
            userDefaults: UserDefaults(suiteName: name)!,
            cloudInspector: FakeCloudFolderInspector(remoteFolders: [dir], treatAllAsRemote: true))
        vm.setPhotos(urls.map { PhotoItem(url: $0) }, rootURL: dir)
        return vm
    }

    @Test("Library Statistics on a cloud folder starts no pass and shows the one-line reason")
    @MainActor
    func statisticsRefused() throws {
        let dir = try makeDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let vm = cloudViewModel(dir: dir, count: 3)
        #expect(vm.fullPassRefusal == "Cloud folder: would download every photo")
        vm.startLibraryScoring() // what LibraryStatisticsView does on appear
        #expect(vm.scoringProgress == nil, "no pass started, so nothing decoded or downloaded")
    }

    @Test("Lowest Sharpness First on a cloud folder is refused and the order stays")
    @MainActor
    func sortRefused() throws {
        let dir = try makeDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let vm = cloudViewModel(dir: dir, count: 2)
        let before = vm.sortOrder
        vm.setSortOrder(.lowestSharpnessFirst)
        #expect(vm.scoringProgress == nil)
        #expect(vm.sortOrder == before)
        #expect(vm.currentToast?.title == CloudRefusal.fullPassLine)
        #expect(vm.userDefaults.workspaceSortOrder == before, "the refused order is not stored")
    }

    @Test("A local folder is unaffected: no refusal, the pass starts")
    @MainActor
    func localFolderScoresWithoutAsking() throws {
        let dir = try makeDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let name = "com.photoselector.tests.cloudconsent.local.\(UUID().uuidString)"
        let vm = CullingWorkspaceViewModel(userDefaults: UserDefaults(suiteName: name)!)
        vm.setPhotos([PhotoItem(url: dir.appendingPathComponent("a.jpg"))], rootURL: dir)
        #expect(vm.fullPassRefusal == nil)
        vm.startLibraryScoring()
        #expect(vm.scoringProgress != nil)
        vm.cancelLibraryScoring()
    }

    @Test("Delete on a cloud folder deletes nothing, logs nothing, offers no Undo and never says \"Moved to Trash\"")
    @MainActor
    func deleteRefused() async throws {
        let dir = try makeDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let raw = dir.appendingPathComponent("IMG_1.CR3")
        try Data("x".utf8).write(to: raw)
        let vm = CullingWorkspaceViewModel(
            cullingActor: AsyncCullingActor(),
            cloudInspector: FakeCloudFolderInspector(remoteFolders: [dir], treatAllAsRemote: true))
        vm.autoAdvance = false
        vm.setPhotos([PhotoItem(url: raw)], rootURL: dir)
        vm.cullCurrentPhoto(action: .trash)
        #expect(vm.cloudBanner == "No Trash in cloud folders. Nothing deleted.")
        #expect(vm.currentToast == nil, "no \"Moved to Trash\"")
        #expect(vm.photos[0].status == .candidate)
        #expect(vm.canUndo == false)
        #expect(vm.decisionLog.isEmpty)
        try await Task.sleep(nanoseconds: 150_000_000)
        #expect(FileManager.default.fileExists(atPath: raw.path), "the file is untouched")
        #expect(vm.currentToast == nil)
    }

    @Test("Move and Copy still work on a cloud folder (only Delete is refused)")
    @MainActor
    func moveStillWorks() throws {
        let dir = try makeDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let vm = cloudViewModel(dir: dir, count: 1)
        vm.autoAdvance = false
        vm.cullCurrentPhoto(action: .copy)
        #expect(vm.cloudBanner == nil)
        #expect(vm.photos[0].status == .copied)
    }

    @Test("The Duplicate Finder's hash pass on a cloud folder reads nothing and says why")
    @MainActor
    func duplicateFinderRefused() throws {
        let dir = try makeDir(); defer { try? FileManager.default.removeItem(at: dir) }
        try Data("same".utf8).write(to: dir.appendingPathComponent("a.jpg"))
        try Data("same".utf8).write(to: dir.appendingPathComponent("b.jpg"))
        let vm = DuplicateFinderViewModel(
            targetDirectoryURL: dir,
            cloudInspector: FakeCloudFolderInspector(remoteFolders: [dir], treatAllAsRemote: true))
        vm.startScan()
        #expect(vm.scanState == .refusedCloud("Cloud folder: would download every photo"))
        #expect(vm.isScanning == false)
        #expect(vm.duplicateGroups.isEmpty)
    }

    @Test("The Duplicate Finder still scans a local folder")
    @MainActor
    func duplicateFinderLocalScans() async throws {
        let dir = try makeDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let vm = DuplicateFinderViewModel(targetDirectoryURL: dir, cloudInspector: FakeCloudFolderInspector())
        vm.startScan()
        for _ in 0..<80 where vm.isScanning { try await Task.sleep(nanoseconds: 25_000_000) }
        #expect(vm.scanState == .empty)
    }
}
