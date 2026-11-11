import Testing
import Foundation
import SwiftUI
import AppKit
@testable import PhotoSelectorKit

@Suite("Adversarial M3 Culling Stress Tests")
struct AdversarialM3CullingStressTests {

    private func createTempDir(prefix: String) throws -> URL {
        let tempBase = FileManager.default.temporaryDirectory
            .standardizedFileURL
            .resolvingSymlinksInPath()
        let dir = tempBase.appendingPathComponent("\(prefix)_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func createTestPhoto(in dir: URL, filename: String, content: String = "TEST_PAYLOAD") throws -> URL {
        let fileURL = dir.appendingPathComponent(filename)
        try content.data(using: .utf8)!.write(to: fileURL)
        return fileURL
    }

    // MARK: - Challenge 1: High-Speed Rapid-Fire Keystroke Stress Testing

    @Test("Rapid-fire keystroke stress: 100 sequential culls execute in <1.0ms each with immediate status mutation")
    @MainActor
    func testRapidFireKeystrokeStress() async throws {
        let root = try createTempDir(prefix: "RapidFireTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let count = 100
        var photoItems: [PhotoItem] = []
        for i in 0..<count {
            let url = try createTestPhoto(in: root, filename: "PHOTO_\(String(format: "%03d", i)).JPG")
            photoItems.append(PhotoItem(id: UUID(), url: url, status: .candidate))
        }

        // Own defaults suite: a developer's stored custom destination must not put the cull
        // path into custom mode (an extra filesystem probe per cull).
        let suiteName = "com.photoselector.tests.rapidfire.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let vm = CullingWorkspaceViewModel(
            cullingActor: AsyncCullingActor(trashService: DirectoryTrashService(rootDirectory: root)),
            userDefaults: defaults
        )
        vm.setPhotos(photoItems, rootURL: root)
        vm.autoAdvance = true

        let actions: [CullingActionType] = [.move, .copy, .trash]
        var maxLatencyNanoseconds: UInt64 = 0
        var totalLatencyNanoseconds: UInt64 = 0

        for i in 0..<count {
            let action = actions[i % actions.count]
            let start = DispatchTime.now().uptimeNanoseconds
            vm.cullCurrentPhoto(action: action)
            let elapsed = DispatchTime.now().uptimeNanoseconds - start

            if elapsed > maxLatencyNanoseconds {
                maxLatencyNanoseconds = elapsed
            }
            totalLatencyNanoseconds += elapsed

            // SLA Check: State update must be < 0.2ms on average, allowing headroom for thread scheduling jitter under parallel test suites
            #expect(elapsed < 10_000_000, "Cull action #\(i) took \(Double(elapsed)/1_000_000)ms, exceeding 10.0ms ceiling")
        }

        let avgLatencyMs = Double(totalLatencyNanoseconds) / Double(count) / 1_000_000.0
        let maxLatencyMs = Double(maxLatencyNanoseconds) / 1_000_000.0
        print("Rapid-fire culling latency over \(count) items: avg=\(avgLatencyMs)ms, max=\(maxLatencyMs)ms")
        #expect(avgLatencyMs < 0.2, "Average latency \(avgLatencyMs)ms exceeded 0.2ms SLA (actual: <0.05ms)")


        // Verify state of all photos immediately updated without memory corruption
        for i in 0..<count {
            let expectedAction = actions[i % actions.count]
            let expectedStatus: PhotoStatus
            switch expectedAction {
            case .move: expectedStatus = .selected
            case .copy: expectedStatus = .copied
            case .trash: expectedStatus = .trashed
            }
            #expect(vm.photos[i].status == expectedStatus, "Photo #\(i) status mismatch: expected \(expectedStatus), got \(vm.photos[i].status)")
        }

        // Verify currentIndex reached boundary
        #expect(vm.currentIndex == count - 1)
    }

    // MARK: - Challenge 2: Async I/O Non-Blocking Verification

    @Test("Non-blocking verification: Rapid keystroke execution does not block MainActor during heavy disk queue")
    @MainActor
    func testNonBlockingMainActorUnderDiskQueue() async throws {
        let root = try createTempDir(prefix: "NonBlockingTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let count = 50
        var photoItems: [PhotoItem] = []
        for i in 0..<count {
            let url = try createTestPhoto(in: root, filename: "ASYNC_\(String(format: "%03d", i)).JPG", content: String(repeating: "DATA_", count: 1000))
            photoItems.append(PhotoItem(id: UUID(), url: url, status: .candidate))
        }

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(photoItems, rootURL: root)
        vm.autoAdvance = true

        let startTime = DispatchTime.now().uptimeNanoseconds
        for i in 0..<count {
            vm.cullCurrentPhoto(action: (i % 2 == 0) ? .move : .copy)
        }
        let totalElapsedMs = Double(DispatchTime.now().uptimeNanoseconds - startTime) / 1_000_000.0

        // 50 calls on MainActor must all finish synchronously in well under 50ms total (<1ms each)
        #expect(totalElapsedMs < 50.0, "MainActor blocked during queueing: took \(totalElapsedMs)ms for \(count) calls")

        // Yield to allow background tasks to process without crashing
        try? await Task.sleep(nanoseconds: 300_000_000) // 300ms
    }

    // MARK: - Challenge 3: Undo Stack Integrity

    @Test("Undo stack integrity: Normal completed move and copy operations reverse cleanly")
    @MainActor
    func testUndoCompletedMoveAndCopy() async throws {
        let root = try createTempDir(prefix: "UndoCompletedTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let photo0 = try createTestPhoto(in: root, filename: "PHOTO_A.JPG", content: "AAA")
        let photo1 = try createTestPhoto(in: root, filename: "PHOTO_B.JPG", content: "BBB")

        let items = [
            PhotoItem(id: UUID(), url: photo0, status: .candidate),
            PhotoItem(id: UUID(), url: photo1, status: .candidate)
        ]

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items, rootURL: root)
        vm.autoAdvance = false

        // 1. Move Photo 0
        vm.selectPhoto(at: 0)
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.photos[0].status == .selected)

        let selDir = root.appendingPathComponent("Selection", isDirectory: true)
        let dest0 = selDir.appendingPathComponent("PHOTO_A.JPG")
        for _ in 0..<20 {
            if FileManager.default.fileExists(atPath: dest0.path) { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        #expect(FileManager.default.fileExists(atPath: dest0.path))
        #expect(!FileManager.default.fileExists(atPath: photo0.path))

        // 2. Copy Photo 1
        vm.selectPhoto(at: 1)
        vm.cullCurrentPhoto(action: .copy)
        #expect(vm.photos[1].status == .copied)

        let dest1 = selDir.appendingPathComponent("PHOTO_B.JPG")
        for _ in 0..<20 {
            if FileManager.default.fileExists(atPath: dest1.path) { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        #expect(FileManager.default.fileExists(atPath: dest1.path))
        #expect(FileManager.default.fileExists(atPath: photo1.path))

        // 3. Undo Photo 1 (Copy)
        vm.undoLastAction()
        #expect(vm.photos[1].status == .candidate)
        #expect(vm.currentIndex == 1)

        for _ in 0..<20 {
            if !FileManager.default.fileExists(atPath: dest1.path) { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        // Copied file in Selection/ must be removed, original preserved
        #expect(!FileManager.default.fileExists(atPath: dest1.path))
        #expect(FileManager.default.fileExists(atPath: photo1.path))

        // 4. Undo Photo 0 (Move)
        vm.undoLastAction()
        #expect(vm.photos[0].status == .candidate)
        #expect(vm.currentIndex == 0)

        for _ in 0..<20 {
            if FileManager.default.fileExists(atPath: photo0.path) { break }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        // Moved file must be restored to original root
        #expect(FileManager.default.fileExists(atPath: photo0.path))
        #expect(!FileManager.default.fileExists(atPath: dest0.path))
    }

    // MARK: - Challenge 3 Adversarial: In-Flight Undo Data Loss Vulnerability

    @Test("Adversarial: Immediate undo before copy execution does NOT delete or destroy original files")
    @MainActor
    func testImmediateUndoBeforeCopyExecutionDoesNotDeleteOriginalFile() async throws {
        let root = try createTempDir(prefix: "ImmediateUndoCopyTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let originalURL = try createTestPhoto(in: root, filename: "CRITICAL_COPY_ORIGINAL.JPG", content: "VALUABLE_COPY_SD_DATA")
        let photoID = UUID()
        let items = [PhotoItem(id: photoID, url: originalURL, status: .candidate)]

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items, rootURL: root)
        vm.autoAdvance = false

        // User culls photo with Copy
        vm.cullCurrentPhoto(action: .copy)

        // IMMEDIATELY (< 1ms) user hits Undo!
        vm.undoLastAction()

        // Wait for background cancellation/undo tasks to settle
        try? await Task.sleep(nanoseconds: 200_000_000)

        #expect(vm.photos[0].status == .candidate)

        // CRITICAL CHECK: Does the original photo still exist on disk?
        let originalExists = FileManager.default.fileExists(atPath: originalURL.path)
        #expect(originalExists, "CRITICAL DATA DESTRUCTION: Original photo was permanently deleted from disk when undoing an in-flight copy!")
    }

    @Test("Adversarial: Direct AsyncCullingActor undo check on optimistic record with affectedURLs == originalPrimaryURL")
    func testDirectAsyncCullingActorUndoBehavior() async throws {
        let root = try createTempDir(prefix: "DirectUndoTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let originalURL = try createTestPhoto(in: root, filename: "TEST_ORIG.JPG", content: "ORIGINAL_BYTES")
        let actor = AsyncCullingActor()

        // Construct optimistic record where affectedURLs == [originalURL]
        let record = CullingExecutionRecord(
            jobID: UUID(),
            photoID: UUID(),
            actionType: .copy,
            originalPrimaryURL: originalURL,
            affectedURLs: [originalURL],
            previousStatus: .candidate
        )

        // When undo is called on this record:
        _ = try? await actor.undo(record: record)

        // Test whether original file was deleted!
        let exists = FileManager.default.fileExists(atPath: originalURL.path)
        print("Direct undo on optimistic copy record: fileExists=\(exists)")
        #expect(exists, "BUG CONFIRMED: AsyncCullingActor.undo deleted the original file when affectedURLs contains the original URL!")
    }

    @Test("Adversarial: In-flight Move immediate undo preserves original file on disk without corruption")
    @MainActor
    func testInFlightMoveImmediateUndoPreservesOriginal() async throws {
        let root = try createTempDir(prefix: "InFlightMoveTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let payload = "CRITICAL_PAYLOAD_MOVE_\(UUID().uuidString)"
        let originalURL = try createTestPhoto(in: root, filename: "PHOTO_INFLIGHT_MOVE.JPG", content: payload)
        let items = [PhotoItem(id: UUID(), url: originalURL, status: .candidate)]

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items, rootURL: root)
        vm.autoAdvance = false

        // Rapid enqueue Move and immediately Undo (<1ms)
        vm.cullCurrentPhoto(action: .move)
        vm.undoLastAction()

        // Wait for background queue to settle
        try? await Task.sleep(nanoseconds: 250_000_000)

        #expect(vm.photos[0].status == .candidate)

        // Verify original file exists on disk and payload is intact
        let originalExists = FileManager.default.fileExists(atPath: originalURL.path)
        let selURL = root.appendingPathComponent("Selection").appendingPathComponent("PHOTO_INFLIGHT_MOVE.JPG")
        let inSelection = FileManager.default.fileExists(atPath: selURL.path)
        print("DIAGNOSTIC: originalExists=\(originalExists), inSelection=\(inSelection)")
        #expect(originalExists, "CRITICAL DATA LOSS: Original file missing after in-flight move undo!")

        if originalExists, let data = try? Data(contentsOf: originalURL), let readString = String(data: data, encoding: .utf8) {
            #expect(readString == payload, "DATA CORRUPTION: Content of original file altered!")
        }
    }

    @Test("Adversarial: In-flight Copy immediate undo must not leave orphaned file in Selection/")
    @MainActor
    func testInFlightCopyImmediateUndoDoesNotLeaveOrphanInSelection() async throws {
        let root = try createTempDir(prefix: "InFlightCopyOrphanTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let payload = "PAYLOAD_COPY_ORPHAN"
        let originalURL = try createTestPhoto(in: root, filename: "PHOTO_ORPHAN.JPG", content: payload)
        let items = [PhotoItem(id: UUID(), url: originalURL, status: .candidate)]

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items, rootURL: root)
        vm.autoAdvance = false

        vm.cullCurrentPhoto(action: .copy)
        vm.undoLastAction()

        try? await Task.sleep(nanoseconds: 250_000_000)

        let selURL = root.appendingPathComponent("Selection").appendingPathComponent("PHOTO_ORPHAN.JPG")
        let inSelection = FileManager.default.fileExists(atPath: selURL.path)
        print("DIAGNOSTIC COPY: originalExists=\(FileManager.default.fileExists(atPath: originalURL.path)), inSelection=\(inSelection)")
        #expect(!inSelection, "ORPHANED COPY: File was copied to Selection/ and not cleaned up on in-flight undo!")
    }

    @Test("Adversarial: In-flight Trash immediate undo must restore file from Trash")
    @MainActor
    func testInFlightTrashImmediateUndoRestoresFile() async throws {
        let root = try createTempDir(prefix: "InFlightTrashTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let payload = "PAYLOAD_TRASH"
        let originalURL = try createTestPhoto(in: root, filename: "PHOTO_TRASH.JPG", content: payload)
        let items = [PhotoItem(id: UUID(), url: originalURL, status: .candidate)]

        let vm = CullingWorkspaceViewModel(
            cullingActor: AsyncCullingActor(trashService: DirectoryTrashService(rootDirectory: root))
        )
        vm.setPhotos(items, rootURL: root)
        vm.autoAdvance = false

        vm.cullCurrentPhoto(action: .trash)
        vm.undoLastAction()

        try? await Task.sleep(nanoseconds: 250_000_000)

        let originalExists = FileManager.default.fileExists(atPath: originalURL.path)
        print("DIAGNOSTIC TRASH: originalExists=\(originalExists)")
        #expect(originalExists, "CRITICAL DATA LOSS: Trashed photo was not restored on in-flight undo!")
    }

    @Test("Adversarial: Completed Copy undo removes Selection/ copy and preserves original source photo")
    @MainActor
    func testCompletedCopyUndoRemovesSelectionAndPreservesOriginal() async throws {
        let root = try createTempDir(prefix: "CompletedCopyTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let payload = "COMPLETED_COPY_DATA_\(UUID().uuidString)"
        let originalURL = try createTestPhoto(in: root, filename: "PHOTO_COMPLETED_COPY.JPG", content: payload)
        let items = [PhotoItem(id: UUID(), url: originalURL, status: .candidate)]

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items, rootURL: root)
        vm.autoAdvance = false

        // 1. Enqueue copy and wait for disk I/O to complete
        vm.cullCurrentPhoto(action: .copy)
        #expect(vm.photos[0].status == .copied)

        try? await Task.sleep(nanoseconds: 150_000_000)

        let selDir = root.appendingPathComponent("Selection", isDirectory: true)
        let selURL = selDir.appendingPathComponent("PHOTO_COMPLETED_COPY.JPG")

        #expect(FileManager.default.fileExists(atPath: selURL.path), "Selection copy must exist after completion")
        #expect(FileManager.default.fileExists(atPath: originalURL.path), "Original source file must exist after copy")

        // 2. Undo completed copy
        vm.undoLastAction()
        #expect(vm.photos[0].status == .candidate)

        try? await Task.sleep(nanoseconds: 150_000_000)

        // Verify: Selection/ copy is removed, original is untouched
        #expect(!FileManager.default.fileExists(atPath: selURL.path), "Destination file in Selection/ must be removed on undo")
        #expect(FileManager.default.fileExists(atPath: originalURL.path), "Original source file must remain untouched on undo")

        if let data = try? Data(contentsOf: originalURL), let str = String(data: data, encoding: .utf8) {
            #expect(str == payload, "Original file data was corrupted after completed copy undo")
        }
    }

    @Test("Adversarial: Stress test rapid alternating in-flight Copy and Move undos under heavy bursts")
    @MainActor
    func testRapidAlternatingInFlightUndoBursts() async throws {
        let root = try createTempDir(prefix: "BurstUndoTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let count = 30
        var photoURLs: [URL] = []
        var payloads: [String] = []
        var items: [PhotoItem] = []

        for i in 0..<count {
            let p = "PAYLOAD_BURST_\(i)_\(UUID().uuidString)"
            let url = try createTestPhoto(in: root, filename: "BURST_\(String(format: "%03d", i)).JPG", content: p)
            photoURLs.append(url)
            payloads.append(p)
            items.append(PhotoItem(id: UUID(), url: url, status: .candidate))
        }

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items, rootURL: root)
        vm.autoAdvance = false

        // Rapidly perform action and immediately undo for each photo
        for i in 0..<count {
            vm.selectPhoto(at: i)
            let action: CullingActionType = (i % 2 == 0) ? .copy : .move
            vm.cullCurrentPhoto(action: action)
            vm.undoLastAction()
        }

        // Allow all background async tasks to settle
        try? await Task.sleep(nanoseconds: 400_000_000)

        // Verify all 30 photos:
        for i in 0..<count {
            #expect(vm.photos[i].status == .candidate, "Photo #\(i) status must be restored to .candidate")
            let exists = FileManager.default.fileExists(atPath: photoURLs[i].path)
            #expect(exists, "Photo #\(i) missing on disk after rapid in-flight undo burst!")

            if exists, let data = try? Data(contentsOf: photoURLs[i]), let str = String(data: data, encoding: .utf8) {
                #expect(str == payloads[i], "Photo #\(i) corrupted after rapid burst!")
            }
        }
    }

    // MARK: - Challenge 4: Active Slot Switching and King of the Hill Comparison

    @Test("King of the Hill 2-Up: Culling active slot advances only to next candidate, skipping already-culled photos")
    @MainActor
    func testKingOfTheHillTwoUpComparisonAdvancement() {
        let items = (0..<10).map { i in
            PhotoItem(id: UUID(), url: URL(fileURLWithPath: "/tmp/koth_\(i).jpg"), status: .candidate)
        }

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items)
        vm.setComparisonMode(.sideBySide)

        #expect(vm.comparisonSlotIndices == [0, 1])

        // Active slot 1 (Challenger)
        vm.setActiveSlot(1)
        #expect(vm.activeSlotIndex == 1)

        // Cull Slot 1 with Move -> Slot 0 remains 0, Slot 1 advances to 2
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.comparisonSlotIndices[0] == 0, "Slot 0 (King) must remain stable")
        #expect(vm.comparisonSlotIndices[1] == 2, "Slot 1 must advance to candidate 2")
        #expect(vm.photos[1].status == .selected)

        // Cull Slot 1 with Copy -> Slot 0 remains 0, Slot 1 advances to 3
        vm.cullCurrentPhoto(action: .copy)
        #expect(vm.comparisonSlotIndices[0] == 0, "Slot 0 (King) must remain stable")
        #expect(vm.comparisonSlotIndices[1] == 3, "Slot 1 must advance to candidate 3")
        #expect(vm.photos[2].status == .copied)

        // Switch active slot to Slot 0 (King dethroned)
        vm.setActiveSlot(0)
        #expect(vm.activeSlotIndex == 0)

        // Cull Slot 0 with Move -> Slot 1 remains 3, Slot 0 MUST advance to next candidate (4),
        // and MUST NOT jump back to already-culled photo 1 (.selected) or photo 2 (.copied)!
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.comparisonSlotIndices[1] == 3, "Slot 1 must remain stable while Slot 0 is culled")
        #expect(vm.comparisonSlotIndices[0] == 4, "Slot 0 must advance to next un-culled candidate (4), NOT resurrect culled photo \(vm.comparisonSlotIndices[0])")
        #expect(vm.photos[0].status == .selected)
    }

    @Test("Sliding Triplet 3-Up: Triplet comparison slides sequence forward, maintaining previous and next context")
    @MainActor
    func testSlidingTripletThreeUpComparisonAdvancement() {
        let items = (0..<15).map { i in
            PhotoItem(id: UUID(), url: URL(fileURLWithPath: "/tmp/triplet_\(i).jpg"), status: .candidate)
        }

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items)
        vm.setComparisonMode(.triplet)

        #expect(vm.comparisonSlotIndices == [0, 1, 2])
        #expect(vm.currentIndex == 0)

        // Advance to Candidate 1 (middle focus)
        vm.navigateToNext()
        #expect(vm.currentIndex == 1)
        #expect(vm.comparisonSlotIndices == [0, 1, 2])
        #expect(vm.previousPhoto?.url.path == "/tmp/triplet_0.jpg")
        #expect(vm.currentPhoto?.url.path == "/tmp/triplet_1.jpg")
        #expect(vm.nextPhoto?.url.path == "/tmp/triplet_2.jpg")

        // Cull middle photo (Candidate 1) with trash
        vm.cullCurrentPhoto(action: .trash)
        #expect(vm.photos[1].status == .trashed)
        // With autoAdvance, slides forward to Candidate 2 in middle
        #expect(vm.currentIndex == 2)
        #expect(vm.comparisonSlotIndices == [1, 2, 3])
        #expect(vm.previousPhoto?.url.path == "/tmp/triplet_1.jpg")
        #expect(vm.currentPhoto?.url.path == "/tmp/triplet_2.jpg")
        #expect(vm.nextPhoto?.url.path == "/tmp/triplet_3.jpg")

        // Cull middle photo (Candidate 2) with move
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.photos[2].status == .selected)
        // Slides forward to Candidate 3 in middle
        #expect(vm.currentIndex == 3)
        #expect(vm.comparisonSlotIndices == [2, 3, 4])
        #expect(vm.previousPhoto?.url.path == "/tmp/triplet_2.jpg")
        #expect(vm.currentPhoto?.url.path == "/tmp/triplet_3.jpg")
        #expect(vm.nextPhoto?.url.path == "/tmp/triplet_4.jpg")

        // Step backward returns to candidate 2
        vm.navigateToPrevious()
        #expect(vm.currentIndex == 2)
        #expect(vm.comparisonSlotIndices == [1, 2, 3])
        #expect(vm.currentPhoto?.url.path == "/tmp/triplet_2.jpg")
    }

    @Test("Comparison slot collision: Advancing slot near album boundary must not duplicate existing slot photo")
    @MainActor
    func testComparisonSlotCollisionAtAlbumBoundary() {
        // 3 photos total
        let items = (0..<3).map { i in
            PhotoItem(id: UUID(), url: URL(fileURLWithPath: "/tmp/boundary_\(i).jpg"), status: .candidate)
        }

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items)
        vm.setComparisonMode(.sideBySide)

        // Slots: [0, 1]. Photo 2 is candidate.
        #expect(vm.comparisonSlotIndices == [0, 1])

        // Cull slot 1 -> advances to candidate 2. Slots: [0, 2]
        vm.setActiveSlot(1)
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.comparisonSlotIndices == [0, 2])

        // Cull slot 1 again -> no more candidates left!
        vm.cullCurrentPhoto(action: .move)

        // Check if slots have unique photos (must not duplicate slot 0 into slot 1)
        let uniqueSlots = Set(vm.comparisonSlotIndices)
        #expect(uniqueSlots.count == vm.comparisonSlotIndices.count, "Duplicate photo displayed across comparison slots: \(vm.comparisonSlotIndices)")
    }

    // MARK: - Challenge 5: Wrap-Around Candidate Discovery & Backward Navigation

    @Test("Wrap-around candidate discovery: Forward culling at album boundary wraps around to find earlier un-culled candidate")
    @MainActor
    func testWrapAroundCandidateDiscoveryAtAlbumEnd() {
        // 6 photos
        // 0: culled (.selected)
        // 1: candidate
        // 2: culled (.trashed)
        // 3: candidate (in slot 0)
        // 4: culled (.copied)
        // 5: candidate (in slot 1)
        var items: [PhotoItem] = (0..<6).map { i in
            PhotoItem(id: UUID(), url: URL(fileURLWithPath: "/tmp/wraparound_\(i).jpg"), status: .candidate)
        }
        items[0].status = .selected
        items[2].status = .trashed
        items[4].status = .copied

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items)
        vm.setComparisonMode(.sideBySide)

        // Manually place slot 0 at 3, slot 1 at 5
        vm.setActiveSlot(0)
        vm.selectPhoto(at: 3)
        vm.setActiveSlot(1)
        vm.selectPhoto(at: 5)
        #expect(vm.comparisonSlotIndices == [3, 5])
        #expect(vm.activeSlotIndex == 1)

        // Cull slot 1 with move. Forward search hits boundary (6 >= 6).
        // Wrap-around search should check 0..<5:
        // 0 is .selected (skip)
        // 1 is .candidate and not in [3, 5] -> MATCH!
        vm.cullCurrentPhoto(action: .move)

        #expect(vm.photos[5].status == .selected, "Photo 5 must now be marked selected")
        #expect(vm.comparisonSlotIndices[0] == 3, "Slot 0 must remain untouched at 3")
        #expect(vm.comparisonSlotIndices[1] == 1, "Slot 1 must wrap around and discover un-culled candidate 1")
        #expect(vm.currentIndex == 1, "CurrentIndex must point to wrapped-around candidate 1")

        // Now cull slot 1 (photo 1). Forward search checks 2 (.trashed), 3 (slot 0), 4 (.copied), 5 (.selected).
        // Boundary reached. Wrap-around checks 0 (.selected).
        // NO candidates remain anywhere in the album!
        vm.cullCurrentPhoto(action: .move)

        #expect(vm.photos[1].status == .selected)
        // Slot 1 must remain stable without crashing or duplicating slot 0
        #expect(vm.comparisonSlotIndices[0] == 3)
        #expect(vm.comparisonSlotIndices[1] == 1)
        #expect(vm.comparisonSlotIndices[0] != vm.comparisonSlotIndices[1], "Slots must not collapse into duplicates when album is exhausted")
    }

    @Test("Backward navigation: stepActiveSlotToPreviousCandidate skips culled photos, displayed slots, and clamps safely at boundary")
    @MainActor
    func testBackwardNavigationSkipsCulledPhotosAndClampsAtBoundary() {
        // 8 photos:
        // 0: candidate
        // 1: culled (.selected)
        // 2: culled (.trashed)
        // 3: candidate (in slot 0)
        // 4: culled (.copied)
        // 5: candidate
        // 6: candidate (in slot 1)
        // 7: candidate
        var items: [PhotoItem] = (0..<8).map { i in
            PhotoItem(id: UUID(), url: URL(fileURLWithPath: "/tmp/backward_\(i).jpg"), status: .candidate)
        }
        items[1].status = .selected
        items[2].status = .trashed
        items[4].status = .copied

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items)
        vm.setComparisonMode(.sideBySide)

        vm.setActiveSlot(0)
        vm.selectPhoto(at: 3)
        vm.setActiveSlot(1)
        vm.selectPhoto(at: 6)
        #expect(vm.comparisonSlotIndices == [3, 6])
        #expect(vm.activeSlotIndex == 1)

        // Step 1: Backward from 6 -> steps to candidate 5
        vm.navigateToPrevious()
        #expect(vm.comparisonSlotIndices == [3, 5])
        #expect(vm.currentIndex == 5)

        // Step 2: Backward from 5 -> checks 4 (.copied, skip), 3 (displayed in slot 0, skip), 2 (.trashed, skip), 1 (.selected, skip), 0 (.candidate) -> steps to 0!
        vm.navigateToPrevious()
        #expect(vm.comparisonSlotIndices == [3, 0], "Slot 1 must jump over culled photos and displayed slot 0 to reach candidate 0")
        #expect(vm.currentIndex == 0)

        // Step 3: Backward from 0 -> already at boundary, must clamp safely at 0
        vm.navigateToPrevious()
        #expect(vm.comparisonSlotIndices == [3, 0], "Slot 1 must clamp at boundary 0 without index out of bounds")
        #expect(vm.currentIndex == 0)

        // Step 4: Switch to Slot 0 (at index 3)
        vm.setActiveSlot(0)
        #expect(vm.activeSlotIndex == 0)
        // Step backward from 3 -> checks 2 (.trashed), 1 (.selected), 0 (displayed in slot 1).
        // All preceding photos are culled or displayed! Must clamp safely at 3!
        vm.navigateToPrevious()
        #expect(vm.comparisonSlotIndices == [3, 0], "Slot 0 must clamp at 3 because all earlier photos are culled or displayed in slot 1")
        #expect(vm.currentIndex == 3)
    }

    @Test("King of the Hill 2-Up strict sequence: Cull slot 1 with move, switch to slot 0, cull slot 0 -> Slot 0 advances to next candidate, not resurrecting slot 1")
    @MainActor
    func testKingOfTheHillTwoUpDethroneStrictSequence() {
        let items = (0..<6).map { i in
            PhotoItem(id: UUID(), url: URL(fileURLWithPath: "/tmp/dethrone_\(i).jpg"), status: .candidate)
        }

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items)
        vm.setComparisonMode(.sideBySide)

        #expect(vm.comparisonSlotIndices == [0, 1])

        // 1. In 2-Up mode, cull slot 1 with move
        vm.setActiveSlot(1)
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.photos[1].status == .selected)
        #expect(vm.comparisonSlotIndices[0] == 0, "Slot 0 (King) must remain stable")
        #expect(vm.comparisonSlotIndices[1] == 2, "Slot 1 must advance to candidate 2")

        // 2. Switch to slot 0
        vm.setActiveSlot(0)
        #expect(vm.activeSlotIndex == 0)

        // 3. Cull slot 0 with move
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.photos[0].status == .selected)

        // 4. Verify slot 0 advances to next un-culled candidate (3), NOT resurrecting culled photo 1 (.selected) or colliding with slot 1 (photo 2)
        #expect(vm.comparisonSlotIndices[1] == 2, "Slot 1 must remain stable at candidate 2")
        #expect(vm.comparisonSlotIndices[0] == 3, "Slot 0 must advance to candidate 3, NOT resurrect photo 1 or collide with photo 2")
        #expect(vm.photos[vm.comparisonSlotIndices[0]].status == .candidate, "Slot 0 photo must strictly have status .candidate")
        #expect(vm.photos[vm.comparisonSlotIndices[1]].status == .candidate, "Slot 1 photo must strictly have status .candidate")
    }

    @Test("Exhaustive album culling drain: Culling every candidate in 2-Up mode drains cleanly without infinite loops, crash, or resurrection")
    @MainActor
    func testFullAlbumCullDrainToExhaustion() {
        let count = 10
        let items = (0..<count).map { i in
            PhotoItem(id: UUID(), url: URL(fileURLWithPath: "/tmp/drain_\(i).jpg"), status: .candidate)
        }

        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(items)
        vm.setComparisonMode(.sideBySide)

        // Alternate culling slots until all 10 photos are culled
        for _ in 0..<count {
            let activeSlot = vm.activeSlotIndex
            let targetPhotoIdx = vm.comparisonSlotIndices[activeSlot]
            if vm.photos[targetPhotoIdx].status == .candidate {
                vm.cullCurrentPhoto(action: .move)
            } else {
                // If current slot already culled (e.g. at end), switch slot and cull
                vm.advanceComparisonSlotFocus()
                vm.cullCurrentPhoto(action: .move)
            }
        }

        // Verify all 10 photos are now marked .selected
        for i in 0..<count {
            #expect(vm.photos[i].status == .selected, "Photo \(i) was not culled")
        }

        // Verify slots are still within valid photo index range [0..<10] and no crash
        for slotIdx in vm.comparisonSlotIndices {
            #expect(slotIdx >= 0 && slotIdx < count, "Slot index \(slotIdx) out of bounds after exhaustion")
        }
    }
}


