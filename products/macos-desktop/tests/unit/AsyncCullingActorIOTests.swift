import Testing
import Foundation
import Dispatch
@testable import PhotoSelectorKit

private final class LabelRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var labels: [String] = []
    var recorded: [String] { lock.withLock { labels } }
    func record(_ label: String) { lock.withLock { labels.append(label) } }
}

/// Records the label of the dispatch queue each file move runs on.
private final class QueueRecordingFileManager: FileManager, @unchecked Sendable {
    let recorder: LabelRecorder

    init(recorder: LabelRecorder) {
        self.recorder = recorder
        super.init()
    }

    override func moveItem(at srcURL: URL, to dstURL: URL) throws {
        recorder.record(String(cString: __dispatch_queue_get_label(nil)))
        try super.moveItem(at: srcURL, to: dstURL)
    }
}

private func makeRecordingActor(_ recorder: LabelRecorder) -> AsyncCullingActor {
    let fm = QueueRecordingFileManager(recorder: recorder)
    return AsyncCullingActor(cullingManager: FileCullingManager(fileManager: fm), fileManager: fm)
}

@Suite("AsyncCullingActor I/O isolation and bookkeeping (C-3, Q-4)")
struct AsyncCullingActorIOTests {

    private func createTempDir() throws -> URL {
        let base = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
        let dir = base.appendingPathComponent("ActorIO_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @Test("Blocking file I/O runs on the actor's own serial queue, not the cooperative pool")
    func fileIORunsOnDedicatedQueue() async throws {
        let root = try createTempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let photo = root.appendingPathComponent("IMG_1.JPG")
        try "PIXELS".write(to: photo, atomically: true, encoding: .utf8)
        let recorder = LabelRecorder()
        let actor = makeRecordingActor(recorder)

        _ = try await actor.enqueue(CullingJob(photoID: UUID(), actionType: .move, primaryURL: photo, rootURL: root))

        #expect(recorder.recorded == [AsyncCullingActor.ioQueueLabel])
    }

    @Test("Bookkeeping of finished jobs is bounded; the newest jobs stay undoable")
    func jobBookkeepingIsBounded() async throws {
        let root = try createTempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let actor = AsyncCullingActor(retainedJobLimit: 3)
        var jobs: [CullingJob] = []
        for i in 0..<6 {
            let photo = root.appendingPathComponent("IMG_\(i).JPG")
            try "PIXELS".write(to: photo, atomically: true, encoding: .utf8)
            let job = CullingJob(photoID: UUID(), actionType: .move, primaryURL: photo, rootURL: root)
            jobs.append(job)
            _ = try await actor.enqueue(job)
        }

        #expect(await actor.trackedJobCount == 3)
        #expect(await actor.hasCompletedRecord(for: jobs[0].id) == false)
        #expect(await actor.hasCompletedRecord(for: jobs[5].id))
    }

    @Test("Cancelling a job that never arrives does not grow the bookkeeping without bound")
    func cancelledIDsAreBounded() async {
        let actor = AsyncCullingActor(retainedJobLimit: 4)
        for _ in 0..<20 { await actor.cancelJob(id: UUID()) }
        #expect(await actor.trackedJobCount == 4)
    }
}
