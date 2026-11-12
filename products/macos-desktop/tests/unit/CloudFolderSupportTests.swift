import Testing
import Foundation
@testable import PhotoSelectorKit

/// Deterministic fake `CloudResourceInspecting` for testing File Provider / cloud folder behavior.
struct FakeCloudFolderInspector: CloudResourceInspecting {
    var remoteFolders: Set<URL> = []
    var treatAllAsRemote: Bool = false

    func isRemoteOrCloudFolder(_ url: URL) -> Bool {
        if treatAllAsRemote { return true }
        let std = url.standardizedFileURL.resolvingSymlinksInPath()
        return remoteFolders.contains(url) || remoteFolders.contains(std)
    }
}

/// Fake `TrashService` that simulates a cloud File Provider refusing system Trash.
struct RefusingCloudTrashService: TrashService {
    func trash(_ url: URL) throws -> URL {
        throw FileCullingError.trashFailed(
            url: url,
            reason: "File Provider does not support moving items to Trash."
        )
    }
}

@Suite("U-29: Cloud & File Provider Folder Support Tests")
struct CloudFolderSupportTests {

    private func createTempDir(prefix: String) throws -> URL {
        let tempBase = FileManager.default.temporaryDirectory
            .standardizedFileURL
            .resolvingSymlinksInPath()
        let dir = tempBase.appendingPathComponent("\(prefix)_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @Test("Remote folder detection sets isRemoteFolder, publishes Cloud Folder badge, and clamps prefetchRadius to 1")
    @MainActor
    func testRemoteFolderDetectionBadgeAndOneAheadPrefetch() async throws {
        let dir = try createTempDir(prefix: "CloudFolderBadge")
        defer { try? FileManager.default.removeItem(at: dir) }

        let p1 = dir.appendingPathComponent("IMG_0001.JPG")
        let p2 = dir.appendingPathComponent("IMG_0002.JPG")
        let p3 = dir.appendingPathComponent("IMG_0003.JPG")
        let p4 = dir.appendingPathComponent("IMG_0004.JPG")
        for u in [p1, p2, p3, p4] {
            try Data("JPEG".utf8).write(to: u)
        }

        let inspector = FakeCloudFolderInspector(remoteFolders: [dir], treatAllAsRemote: true)
        let cache = ThumbnailCache()
        let loader = ThumbnailLoader(cache: cache)
        let vm = CullingWorkspaceViewModel(
            thumbnailLoader: loader,
            thumbnailCache: cache,
            cloudInspector: inspector
        )

        let items = [p1, p2, p3, p4].map { PhotoItem(url: $0) }
        vm.setPhotos(items, rootURL: dir)

        #expect(vm.isRemoteFolder == true)
        #expect(vm.cloudBadgeText == "Cloud Folder")
        #expect(vm.prefetchRadius == 1)

        // Allow the async prefetch task to register its keys
        try await Task.sleep(nanoseconds: 50_000_000)
        await loader.cancelAll()
    }
}
