import Testing
import Foundation
@testable import PhotoSelectorKit

/// REQ-MAC-CLOUD.01: nothing reads (so downloads) every photo of a cloud folder, and no permanent
/// delete runs, before the user said yes; a refused Trash never shows "Moved to Trash".
@Suite("Cloud folders: consent before a full pass")
struct CloudConsentTests {
    private func makeDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
            .appendingPathComponent("CloudConsent_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @MainActor
    private func cloudViewModel(dir: URL, count: Int, size: Int64 = 1_000_000) -> CullingWorkspaceViewModel {
        let urls = (0..<count).map { dir.appendingPathComponent("IMG_\($0).JPG") }
        let inspector = FakeCloudFolderInspector(
            remoteFolders: [dir], onlineOnlyURLs: Set(urls),
            fileSizes: Dictionary(uniqueKeysWithValues: urls.map { ($0, size) }), treatAllAsRemote: true)
        let name = "com.photoselector.tests.cloudconsent.\(UUID().uuidString)"
        let vm = CullingWorkspaceViewModel(userDefaults: UserDefaults(suiteName: name)!, cloudInspector: inspector)
        vm.setPhotos(urls.map { PhotoItem(url: $0) }, rootURL: dir)
        return vm
    }

    @Test("Library Statistics on a cloud folder asks first and decodes nothing before the answer")
    @MainActor
    func statisticsAsksBeforeScoring() async throws {
        let dir = try makeDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let vm = cloudViewModel(dir: dir, count: 3)
        vm.startLibraryScoring() // what LibraryStatisticsView does on appear
        #expect(vm.scoringProgress == nil, "no pass started")
        let pending = try #require(vm.pendingDownloadConfirmation)
        #expect(pending.purpose == .statistics)
        #expect(pending.estimatedBytes == 3_000_000)

        vm.declineDownloadAll()
        #expect(vm.pendingDownloadConfirmation == nil)
        #expect(vm.scoringProgress == nil)

        vm.startLibraryScoring()
        vm.confirmDownloadAll()
        #expect(vm.pendingDownloadConfirmation == nil)
        #expect(vm.scoringProgress != nil, "the pass runs once the user said yes")
        vm.cancelLibraryScoring()
    }

    @Test("Lowest Sharpness First on a cloud folder asks first and Cancel keeps the order")
    @MainActor
    func sortAsksBeforeScoring() throws {
        let dir = try makeDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let vm = cloudViewModel(dir: dir, count: 2)
        let before = vm.sortOrder
        vm.setSortOrder(.lowestSharpnessFirst)
        #expect(vm.scoringProgress == nil)
        #expect(vm.sortOrder == before)
        guard case .lowestSharpnessSort(let previous)? = vm.pendingDownloadConfirmation?.purpose else {
            Issue.record("expected the download question"); return
        }
        #expect(previous == before)
        vm.declineDownloadAll()
        #expect(vm.sortOrder == before)
        #expect(vm.pendingDownloadConfirmation == nil)
    }

    @Test("A local folder is unaffected: no question, the pass starts")
    @MainActor
    func localFolderScoresWithoutAsking() throws {
        let dir = try makeDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let name = "com.photoselector.tests.cloudconsent.local.\(UUID().uuidString)"
        let vm = CullingWorkspaceViewModel(userDefaults: UserDefaults(suiteName: name)!)
        vm.setPhotos([PhotoItem(url: dir.appendingPathComponent("a.jpg"))], rootURL: dir)
        vm.startLibraryScoring()
        #expect(vm.pendingDownloadConfirmation == nil)
        vm.cancelLibraryScoring()
    }

    @Test("A refused Trash never shows \"Moved to Trash\"")
    @MainActor
    func refusedTrashShowsNoFalseToast() async throws {
        let dir = try makeDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let raw = dir.appendingPathComponent("IMG_1.CR3")
        try Data("x".utf8).write(to: raw)
        let vm = CullingWorkspaceViewModel(
            cullingActor: AsyncCullingActor(trashService: RefusingCloudTrashService()),
            cloudInspector: FakeCloudFolderInspector(remoteFolders: [dir], treatAllAsRemote: true))
        vm.autoAdvance = false
        vm.setPhotos([PhotoItem(url: raw)], rootURL: dir)
        var titles: [String] = []
        vm.cullCurrentPhoto(action: .trash)
        titles.append(vm.currentToast?.title ?? "")
        for _ in 0..<40 {
            if let t = vm.currentToast?.title { titles.append(t) }
            if vm.pendingPermanentDelete != nil { break }
            try await Task.sleep(nanoseconds: 25_000_000)
        }
        #expect(vm.pendingPermanentDelete != nil)
        #expect(!titles.contains("Moved to Trash"))
        #expect(vm.pendingPermanentDelete?.message == "No Trash here. Syncs to all devices.")
    }

    @Test("A permanent delete that fails says so and keeps the photo")
    @MainActor
    func failedPermanentDeleteSurfaces() async throws {
        let dir = try makeDir()
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        let raw = dir.appendingPathComponent("IMG_2.CR3")
        try Data("x".utf8).write(to: raw)
        let vm = CullingWorkspaceViewModel(
            cullingActor: AsyncCullingActor(trashService: RefusingCloudTrashService()),
            cloudInspector: FakeCloudFolderInspector(remoteFolders: [dir], treatAllAsRemote: true))
        vm.autoAdvance = false
        vm.setPhotos([PhotoItem(url: raw)], rootURL: dir)
        vm.cullCurrentPhoto(action: .trash)
        for _ in 0..<40 where vm.pendingPermanentDelete == nil { try await Task.sleep(nanoseconds: 25_000_000) }
        #expect(vm.pendingPermanentDelete != nil)

        // The folder becomes read-only, so removing the file fails.
        try FileManager.default.setAttributes([.posixPermissions: 0o555], ofItemAtPath: dir.path)
        vm.confirmPermanentDelete()
        #expect(vm.photos[0].status == .candidate, "the photo is not marked deleted")
        #expect(vm.currentToast?.title == "Couldn't delete IMG_2.CR3")
        #expect(vm.currentToast?.subtitle?.isEmpty == false, "the system's reason is shown")
        #expect(FileManager.default.fileExists(atPath: raw.path))
    }
}
