import Testing
import Foundation
@testable import PhotoSelectorKit

/// Scanner whose stream the test drives by hand, so "loading" and "first photo shown" can be observed
/// while the scan is still running. Records the batch sizes the view model asked for.
private final class ManualScanner: DirectoryScanning, @unchecked Sendable {
    // Guarded by `lock`: written by the stream setup (any thread), read by the test (main actor).
    private let lock = NSLock()
    private var continuation: AsyncStream<[PhotoItem]>.Continuation?
    private var requestedFirstBatch: Int?
    private var requestedBatch: Int?

    var firstBatchSize: Int? { lock.withLock { requestedFirstBatch } }
    var batchSize: Int? { lock.withLock { requestedBatch } }

    func emit(_ items: [PhotoItem]) { _ = lock.withLock { continuation }?.yield(items) }
    func finish() { lock.withLock { continuation }?.finish() }
    var isStreaming: Bool { lock.withLock { continuation != nil } }

    func scanStream(
        at directoryURL: URL,
        recursive: Bool,
        batchSize: Int,
        firstBatchSize: Int
    ) -> AsyncStream<[PhotoItem]> {
        AsyncStream { continuation in
            lock.withLock {
                self.continuation = continuation
                self.requestedFirstBatch = firstBatchSize
                self.requestedBatch = batchSize
            }
        }
    }

    func scanDirectory(at directoryURL: URL, recursive: Bool) async throws -> [PhotoItem] { [] }
}

/// F-LJ-006 (opening a folder showed no sign of loading), F-LJ-009 (the first photos must appear as soon
/// as they are read), F-LJ-010 (3-Up: current on top, previous/next below). REQ-MAC-CULL.01/.02,
/// REQ-MAC-LAYOUT.02. Both loading rows were still `open` in the ledger; on `main` they are met, so these
/// tests are their guard.
@Suite("Opening a folder: a loading sign first, then the first photo before the scan ends")
@MainActor
struct FolderLoadFeedbackTests {

    private func makeFolder(_ count: Int) throws -> (URL, [PhotoItem]) {
        let base = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
        let dir = base.appendingPathComponent("FolderLoad_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var items: [PhotoItem] = []
        for i in 0..<count {
            let url = dir.appendingPathComponent("L\(i).JPG")
            try Data("PAYLOAD".utf8).write(to: url)
            items.append(PhotoItem(id: UUID(), url: url, status: .candidate))
        }
        return (dir, items)
    }

    private func waitUntil(_ budget: TimeInterval = 10, _ condition: @MainActor () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(budget)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return condition()
    }

    @Test("REQ-MAC-CULL.02 F-LJ-006: the moment a folder is chosen the view model reports loading with no photos yet")
    func loadingSignIsImmediate() async throws {
        let (dir, _) = try makeFolder(1)
        defer { try? FileManager.default.removeItem(at: dir) }
        let scanner = ManualScanner()
        let vm = CullingWorkspaceViewModel(directoryScanner: scanner)

        #expect(!vm.isLoading)
        vm.loadFolder(at: dir)
        // Synchronous: no scheduling delay between the click and the sign (the overlay shows for
        // `isLoading && photos.isEmpty`, ContentView).
        #expect(vm.isLoading)
        #expect(vm.photos.isEmpty)

        #expect(await waitUntil { scanner.isStreaming })
        scanner.finish()
        #expect(await waitUntil { !vm.isLoading }, "an empty folder must end the loading sign too")
    }

    @Test("REQ-MAC-CULL.01 F-LJ-009: the first photo is shown while the scan is still running, and the first batch asked for is one photo")
    func firstPhotoBeforeScanEnds() async throws {
        let (dir, items) = try makeFolder(3)
        defer { try? FileManager.default.removeItem(at: dir) }
        let scanner = ManualScanner()
        let vm = CullingWorkspaceViewModel(directoryScanner: scanner)

        vm.loadFolder(at: dir)
        #expect(await waitUntil { scanner.isStreaming })
        #expect(scanner.firstBatchSize == 1, "the first emission must be a single photo")
        #expect(CullingWorkspaceViewModel.firstScanBatchSize == 1)
        #expect((scanner.batchSize ?? 0) > 1)

        scanner.emit([items[0]])
        #expect(await waitUntil { vm.photos.count == 1 })
        #expect(vm.isLoading, "the scan has not finished, so the progress pill must still show")
        #expect(vm.currentPhoto?.id == items[0].id, "the first photo is current at once and can be culled")

        scanner.emit([items[1], items[2]])
        #expect(await waitUntil { vm.photos.count == 3 })
        scanner.finish()
        #expect(await waitUntil { !vm.isLoading })
        #expect(vm.photos.map(\.id) == items.map(\.id), "append-only: nothing repeated or reordered")
    }

    @Test("REQ-MAC-LAYOUT.02 F-LJ-010: 3-Up fills current / previous / next from the neighbours as photos stream in")
    func tripletFillsFromStreamedPhotos() async throws {
        let (dir, items) = try makeFolder(4)
        defer { try? FileManager.default.removeItem(at: dir) }
        let scanner = ManualScanner()
        let vm = CullingWorkspaceViewModel(directoryScanner: scanner)
        vm.setComparisonMode(.triplet)

        vm.loadFolder(at: dir)
        #expect(await waitUntil { scanner.isStreaming })
        scanner.emit([items[0]])
        #expect(await waitUntil { vm.photos.count == 1 })
        scanner.emit([items[1], items[2], items[3]])
        #expect(await waitUntil { vm.photos.count == 4 })
        #expect(await waitUntil { vm.comparisonSlotIndices == [0, 1, 2] }, "slots must catch up with the late batches")
        #expect(vm.comparisonSlotPhotos.map(\.id) == items[0...2].map(\.id))
        scanner.finish()
        _ = await waitUntil { !vm.isLoading }
    }
}
