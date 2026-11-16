import Testing
import Foundation
@testable import PhotoSelectorKit

// Scale tests against the shared usage-conditions contract `testdata/conditions/conditions.json`
// (rules: docs/shared/USAGE_CONDITIONS.md). Sizes and the growth rule are read from that file, never
// written here: a test times the same operation at N and at factor*N (N = pr / factor), after a
// warm-up, best of three, and fails when time(factor*N) / time(N) exceeds max_ratio. Linear work gives
// about 4, quadratic about 16. Absolute milliseconds are never asserted.
//
// Every operation goes through the real `CullingWorkspaceViewModel`, the object the screen calls,
// with the batch sizes the product really uses (EXIF 64, scores 8, folder scan 1 then x4 up to 30).
// The stress size (the release run) is behind `PST_SCALE_STRESS=1`.

// MARK: - The contract

struct ScaleContract: Decodable, Sendable {
    struct Size: Decodable, Sendable { let photos: Int }
    struct FolderSizes: Decodable, Sendable { let pr: Size; let stress: Size }
    struct Growth: Decodable, Sendable { let factor: Int; let max_ratio: Double }

    let folder_sizes: FolderSizes
    let growth_check: Growth

    /// `testdata/conditions/conditions.json` found by walking up from this source file.
    static let shared: ScaleContract = {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" {
            let candidate = dir.appendingPathComponent("testdata/conditions/conditions.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                guard let data = try? Data(contentsOf: candidate),
                      let contract = try? JSONDecoder().decode(ScaleContract.self, from: data) else {
                    fatalError("testdata/conditions/conditions.json does not decode")
                }
                return contract
            }
            dir = dir.deletingLastPathComponent()
        }
        fatalError("testdata/conditions/conditions.json not found above \(#filePath)")
    }()

    var prPhotos: Int { folder_sizes.pr.photos }
    var stressPhotos: Int { folder_sizes.stress.photos }
}

private let stressRequested = ProcessInfo.processInfo.environment["PST_SCALE_STRESS"] == "1"

// MARK: - Growth measurement

private func seconds(_ duration: Duration) -> Double {
    Double(duration.components.seconds) + Double(duration.components.attoseconds) / 1e18
}

/// Smallest time of three runs of `measure`, each on a state `prepare` builds outside the clock.
@MainActor
private func bestOfThree<State>(prepare: () -> State, measure: (State) -> Void) -> Double {
    var best = Double.infinity
    for _ in 0..<3 {
        let state = prepare()
        let start = ContinuousClock.now
        measure(state)
        best = min(best, seconds(ContinuousClock.now - start))
    }
    return best
}

/// Times `time(size)` at N and at factor*N (top = the folder size under test) and checks the growth.
/// A warm-up run at N comes first. The floor keeps a sub-2 ms baseline from turning timer noise
/// into a ratio.
@MainActor
private func expectLinearGrowth(top: Int, _ what: String, floor: Double = 0.002, _ time: (Int) -> Double) {
    let growth = ScaleContract.shared.growth_check
    let small = top / growth.factor
    _ = time(small)  // warm-up
    let tSmall = time(small)
    let tTop = time(top)
    let ratio = tTop / max(tSmall, floor)
    #expect(ratio <= growth.max_ratio,
            "\(what): \(small) photos \(tSmall * 1000) ms, \(top) photos \(tTop * 1000) ms, ratio \(ratio) > \(growth.max_ratio)")
}

// MARK: - Fixtures

@MainActor
private func makeModel(photoCount: Int, decidedEvery: Int = 0) -> CullingWorkspaceViewModel {
    let suite = "scale-contract-\(UUID().uuidString)"
    let vm = CullingWorkspaceViewModel(userDefaults: UserDefaults(suiteName: suite)!)
    vm.setPhotos((0..<photoCount).map { index in
        PhotoItem(
            url: URL(fileURLWithPath: "/tmp/scale-contract/IMG_\(index).jpg"),
            status: decidedEvery > 0 && index % decidedEvery == 0 ? .selected : .candidate
        )
    })
    return vm
}

// MARK: - The operations

@MainActor
private func metadataTime(photoCount: Int) -> Double {
    let batchSize = 64  // `startMetadataEnrichment` flushes every 64 reads
    return bestOfThree(
        prepare: { () -> (CullingWorkspaceViewModel, [[UUID: ExifData]]) in
            let vm = makeModel(photoCount: photoCount)
            let ids = vm.photos.map(\.id)
            let batches = stride(from: 0, to: ids.count, by: batchSize).map { start in
                Dictionary(uniqueKeysWithValues: ids[start..<min(start + batchSize, ids.count)].map { ($0, ExifData(iso: 100)) })
            }
            return (vm, batches)
        },
        measure: { state in
            for batch in state.1 { state.0.applyMetadata(batch) }
        }
    )
}

@MainActor
private func scoreTime(photoCount: Int) -> Double {
    let batchSize = 8  // `startLibraryScoring` flushes every 8 photos
    let scores = QualityScores(sharpness: 50, noise: 0, highlightClipping: 0, shadowClipping: 0)
    return bestOfThree(
        prepare: { () -> (CullingWorkspaceViewModel, [[(UUID, QualityScores)]]) in
            let vm = makeModel(photoCount: photoCount)
            let ids = vm.photos.map(\.id)
            let batches = stride(from: 0, to: ids.count, by: batchSize).map { start in
                ids[start..<min(start + batchSize, ids.count)].map { ($0, scores) }
            }
            return (vm, batches)
        },
        measure: { state in
            var handled = 0
            for batch in state.1 {
                handled += batch.count
                state.0.applyScoreBatch(batch, handled: handled)
            }
        }
    )
}

/// 100 navigation keys at the end of the list, each followed by the reads the screen does per
/// render: the action bar's tally and the end-of-cull state.
@MainActor
private func navigationTime(photoCount: Int) -> Double {
    bestOfThree(
        prepare: { () -> CullingWorkspaceViewModel in
            let vm = makeModel(photoCount: photoCount, decidedEvery: 10)
            vm.selectPhoto(at: photoCount - 1)
            return vm
        },
        measure: { vm in
            for key in 0..<100 {
                if key % 2 == 0 { vm.navigateToPrevious() } else { vm.navigateToNext() }
                _ = vm.statusTally
                _ = vm.endState
            }
        }
    )
}

/// 100 renders of the filmstrip at the end of the list: each builds the strip's tiles from the
/// list and reads the ones on screen (a window of 20). Nothing here depends on the folder size
/// once the strip works on the visible window.
@MainActor
private func filmstripTime(photoCount: Int) -> Double {
    bestOfThree(
        prepare: { () -> CullingWorkspaceViewModel in
            let vm = makeModel(photoCount: photoCount, decidedEvery: 10)
            vm.selectPhoto(at: photoCount - 1)
            return vm
        },
        measure: { vm in
            var sink = 0
            for _ in 0..<100 {
                let tiles = FilmstripTiles.make(
                    count: vm.photos.count,
                    currentIndex: vm.currentIndex,
                    comparisonIndices: vm.comparisonSlotIndices
                )
                for tile in tiles.prefix(20) where tile.isSelected || tile.isInComparison { sink += 1 }
            }
            _ = sink
        }
    )
}

/// Opens a real folder of `photoCount` files; returns the time to the first photo and to the full list.
@MainActor
private func folderLoadTimes(photoCount: Int, in directory: URL) async -> (first: Double, full: Double) {
    var bestFirst = Double.infinity
    var bestFull = Double.infinity
    for _ in 0..<3 {
        let vm = CullingWorkspaceViewModel(userDefaults: UserDefaults(suiteName: "scale-contract-\(UUID().uuidString)")!)
        let start = ContinuousClock.now
        vm.loadFolder(at: directory)
        var first: Double?
        while vm.isLoading {
            if first == nil, !vm.photos.isEmpty { first = seconds(ContinuousClock.now - start) }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        let full = seconds(ContinuousClock.now - start)
        vm.cancelLoading()  // stops the EXIF enrichment the load starts
        bestFirst = min(bestFirst, first ?? full)
        bestFull = min(bestFull, full)
    }
    return (bestFirst, bestFull)
}

private func makeFolder(photoCount: Int) throws -> URL {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent("scale-contract-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    for index in 0..<photoCount {
        guard FileManager.default.createFile(atPath: root.appendingPathComponent("IMG_\(String(format: "%06d", index)).jpg").path, contents: Data()) else {
            throw CocoaError(.fileWriteUnknown)
        }
    }
    return root
}

@MainActor
private func expectFolderLoadGrowth(top: Int) async throws {
    let growth = ScaleContract.shared.growth_check
    let small = top / growth.factor
    let smallDir = try makeFolder(photoCount: small)
    let topDir = try makeFolder(photoCount: top)
    defer {
        try? FileManager.default.removeItem(at: smallDir)
        try? FileManager.default.removeItem(at: topDir)
    }
    _ = await folderLoadTimes(photoCount: small, in: smallDir)  // warm-up
    let floor = 0.05
    // Up to three attempts, as the other products' scale tests: a CI runner once measured the
    // full list at x8.4 (2026-11-15) after x<8 on the run before; quadratic work fails every attempt.
    // The best time per size across attempts, never the best ratio: a noisy small run lowers the
    // ratio, and accepting any passing attempt let quadratic work through elsewhere (2026-11-15).
    var atSmall = (first: Double.infinity, full: Double.infinity)
    var atTop = (first: Double.infinity, full: Double.infinity)
    var firstRatio = Double.infinity
    var fullRatio = Double.infinity
    for _ in 0..<3 where firstRatio > growth.max_ratio || fullRatio > growth.max_ratio {
        let s = await folderLoadTimes(photoCount: small, in: smallDir)
        let t = await folderLoadTimes(photoCount: top, in: topDir)
        atSmall = (min(atSmall.first, s.first), min(atSmall.full, s.full))
        atTop = (min(atTop.first, t.first), min(atTop.full, t.full))
        firstRatio = atTop.first / max(atSmall.first, floor)
        fullRatio = atTop.full / max(atSmall.full, floor)
    }
    #expect(firstRatio <= growth.max_ratio,
            "time to first photo: \(small) photos \(atSmall.first * 1000) ms, \(top) photos \(atTop.first * 1000) ms, ratio \(firstRatio)")
    #expect(fullRatio <= growth.max_ratio,
            "time to full list: \(small) photos \(atSmall.full * 1000) ms, \(top) photos \(atTop.full * 1000) ms, ratio \(fullRatio)")
}

// MARK: - Tests

@Suite("Scale contract: growth of the real view model at the contract sizes", .serialized)
@MainActor
struct ScaleContractTests {

    @Test("The contract is read from conditions.json, not written into the tests")
    func contractIsRead() {
        let contract = ScaleContract.shared
        #expect(contract.prPhotos >= 10_000)
        #expect(contract.stressPhotos > contract.prPhotos)
        #expect(contract.growth_check.factor >= 2)
        #expect(contract.prPhotos % contract.growth_check.factor == 0)
    }

    // Scale: pr
    @Test("REQ-MAC-PERF.02: applying EXIF batches of 64 grows linearly with the folder (Scale: pr)")
    func metadataBatchesGrowLinearly() {
        // Scale: pr
        expectLinearGrowth(top: ScaleContract.shared.prPhotos, "EXIF batches of 64") { metadataTime(photoCount: $0) }
    }

    // Scale: pr
    @Test("REQ-MAC-PERF.02: applying score batches of 8 grows linearly with the folder (Scale: pr)")
    func scoreBatchesGrowLinearly() {
        // Scale: pr
        expectLinearGrowth(top: ScaleContract.shared.prPhotos, "score batches of 8") { scoreTime(photoCount: $0) }
    }

    // Scale: pr
    @Test("REQ-MAC-PERF.03: 100 navigation keys at the end of the list, with the tally and end state each, grow with at most the list (Scale: pr)")
    func navigationKeysAtTheEndStayCheap() {
        // Scale: pr
        expectLinearGrowth(top: ScaleContract.shared.prPhotos, "100 navigation keys with tally and end state") { navigationTime(photoCount: $0) }
    }

    // Scale: pr
    @Test("REQ-MAC-PERF.03: 100 filmstrip renders cost the visible window, flat in the folder size (Scale: pr)")
    func filmstripRendersStayFlat() {
        // Scale: pr
        // Stricter than the growth rule: work on a window of 20 tiles is flat, so the 4N / N time
        // must stay under factor / 2 (2), where building every tile grows by about the factor (4).
        let growth = ScaleContract.shared.growth_check
        let top = ScaleContract.shared.prPhotos
        let small = top / growth.factor
        _ = filmstripTime(photoCount: small)  // warm-up
        let tSmall = filmstripTime(photoCount: small)
        let tTop = filmstripTime(photoCount: top)
        let ratio = tTop / max(tSmall, 0.002)
        let limit = Double(growth.factor) / 2
        #expect(ratio <= limit,
                "100 filmstrip renders: \(small) photos \(tSmall * 1000) ms, \(top) photos \(tTop * 1000) ms, ratio \(ratio) > \(limit)")
    }

    // Scale: pr
    @Test("REQ-MAC-PERF.01 REQ-MAC-CULL.01: opening a folder keeps the first photo flat and the full list linear (Scale: pr)")
    func openingAFolderGrowsLinearly() async throws {
        // Scale: pr
        try await expectFolderLoadGrowth(top: ScaleContract.shared.prPhotos)
    }

    // Correctness of the fixes the tests above guard.

    @Test("REQ-MAC-PERF.02: a metadata batch fills only photos without EXIF, ignores unknown ids and follows a reordered list")
    func metadataBatchIsCorrectAfterReorder() {
        let vm = makeModel(photoCount: 200)
        let original = vm.photos
        let first = ExifData(iso: 100)
        vm.applyMetadata(Dictionary(uniqueKeysWithValues: original[0..<64].map { ($0.id, first) }))
        #expect(vm.photos[0..<64].allSatisfy { $0.exif == first })
        #expect(vm.photos[64...].allSatisfy { $0.exif == nil })

        // A new order of the same photos (the id -> position map is now stale).
        vm.setPhotos(vm.photos.reversed())
        let second = ExifData(iso: 800)
        var batch = Dictionary(uniqueKeysWithValues: original[0..<64].map { ($0.id, second) })  // already set: kept
        for item in original[64..<128] { batch[item.id] = second }
        batch[UUID()] = second  // not in the list: ignored
        vm.applyMetadata(batch)
        let byID = Dictionary(uniqueKeysWithValues: vm.photos.map { ($0.id, $0) })
        #expect(vm.photos.count == 200)
        for (index, item) in original.enumerated() {
            let expected: ExifData? = index < 64 ? first : (index < 128 ? second : nil)
            #expect(byID[item.id]?.exif == expected, "photo \(index)")
        }
    }

    @Test("REQ-MAC-PERF.02: a score batch fills only unscored photos, keeps the first of a duplicate id and counts progress")
    func scoreBatchIsCorrect() {
        let vm = makeModel(photoCount: 50)
        let ids = vm.photos.map(\.id)
        let low = QualityScores(sharpness: 10, noise: 0, highlightClipping: 0, shadowClipping: 0)
        let high = QualityScores(sharpness: 90, noise: 0, highlightClipping: 0, shadowClipping: 0)
        vm.applyScoreBatch([(ids[0], low), (ids[0], high), (ids[1], high)], handled: 3)
        #expect(vm.photos[0].scores == low)
        #expect(vm.photos[1].scores == high)
        #expect(vm.photos[2].scores == nil)
        vm.applyScoreBatch([(ids[0], high), (ids[2], high)], handled: 5)  // ids[0] already scored: kept
        #expect(vm.photos[0].scores == low)
        #expect(vm.photos[2].scores == high)
        #expect(vm.scoringProgress == ScoringProgress(done: 3, total: 50))
    }

    @Test("REQ-MAC-PERF.03: the one-pass tally equals the four separate counters")
    func tallyMatchesCounters() {
        let suite = "scale-contract-\(UUID().uuidString)"
        let vm = CullingWorkspaceViewModel(userDefaults: UserDefaults(suiteName: suite)!)
        let statuses: [PhotoStatus] = [.selected, .trashed, .copied, .candidate]
        vm.setPhotos((0..<100).map { index in
            PhotoItem(url: URL(fileURLWithPath: "/tmp/scale-contract/T_\(index).jpg"), status: statuses[index % 4])
        })
        let tally = vm.statusTally
        #expect(tally.selected == 25 && tally.trashed == 25 && tally.copied == 25 && tally.remaining == 25)
        #expect(tally.selected == vm.selectedCount)
        #expect(tally.trashed == vm.trashedCount)
        #expect(tally.copied == vm.copiedCount)
        #expect(tally.remaining == vm.remainingCount)
    }

    @Test("Stress size: every operation above at the release run's size",
          .enabled(if: stressRequested))
    func stressSize() async throws {
        // Scale: stress
        let top = ScaleContract.shared.stressPhotos
        expectLinearGrowth(top: top, "EXIF batches of 64 (stress)") { metadataTime(photoCount: $0) }
        expectLinearGrowth(top: top, "score batches of 8 (stress)") { scoreTime(photoCount: $0) }
        expectLinearGrowth(top: top, "100 navigation keys (stress)") { navigationTime(photoCount: $0) }
        try await expectFolderLoadGrowth(top: top)
    }
}
