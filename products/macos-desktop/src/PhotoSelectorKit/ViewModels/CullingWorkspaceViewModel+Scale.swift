import Foundation

/// Work whose cost must not grow with the folder: the action bar's counters and the batches of
/// background results (REQ-MAC-PERF.01 to .03). A photo scored meanwhile by the viewport is not
/// counted in `scoredCount` until the scoring pass ends. Stored properties live in the class.
extension CullingWorkspaceViewModel {

    /// The four live counters of the action bar in ONE pass over the list (the bar read four
    /// properties, each a pass, `remainingCount` three more, on every render).
    public var statusTally: (selected: Int, trashed: Int, copied: Int, remaining: Int) {
        var selected = 0, trashed = 0, copied = 0
        for photo in photos {
            switch photo.status {
            case .selected: selected += 1
            case .trashed: trashed += 1
            case .copied: copied += 1
            default: break
            }
        }
        return (selected, trashed, copied, max(0, photos.count - selected - copied - trashed))
    }

    /// Applies one batch of scores. Per batch the work is the batch size, not the list size: ids
    /// are located through `photoIndex(of:rebuilt:)` and the list is published once.
    func applyScoreBatch(_ batch: [(UUID, QualityScores)], handled: Int, finished: Bool = false) {
        if !batch.isEmpty {
            var rebuilt = false
            var seen = Set<Int>()
            var writes: [(index: Int, scores: QualityScores)] = []
            for (id, scores) in batch {
                guard let index = photoIndex(of: id, rebuilt: &rebuilt),
                      photos[index].scores == nil,
                      seen.insert(index).inserted else { continue }
                writes.append((index, scores))
            }
            if !writes.isEmpty {
                var local = photos
                photos = []   // `local` is now the only holder: the writes below do not copy the list
                for write in writes { local[write.index].scores = write.scores }
                photos = local
                scoredCount += writes.count
            }
        }
        let total = photos.count
        if finished {
            scoringProgress = nil
            libraryScoringTask = nil
            finishScoringIfNeeded()
        } else {
            scoringProgress = ScoringProgress(done: min(total, scoredCount), total: total)
        }
    }

    /// Position of `id` in `photos`. A miss rebuilds the map once per call sequence (`rebuilt`); an id
    /// still missing after that is not in the list.
    func photoIndex(of id: UUID, rebuilt: inout Bool) -> Int? {
        if let index = photoIndexCache[id], photos.indices.contains(index), photos[index].id == id {
            return index
        }
        guard !rebuilt else { return nil }
        rebuilt = true
        var map = [UUID: Int](minimumCapacity: photos.count)
        for (index, photo) in photos.enumerated() { map[photo.id] = index }
        photoIndexCache = map
        return map[id]
    }

    /// Applies one batch of EXIF reads (`startMetadataEnrichment` flushes every 64). Per batch the
    /// work is the batch size, not the list size, and the list is published once.
    func applyMetadata(_ updates: [UUID: ExifData]) {
        var rebuilt = false
        var writes: [(index: Int, exif: ExifData)] = []
        for (id, exif) in updates {
            guard let index = photoIndex(of: id, rebuilt: &rebuilt), photos[index].exif == nil else { continue }
            writes.append((index, exif))
        }
        guard !writes.isEmpty else { return }
        var local = photos
        photos = []   // `local` is now the only holder: the writes below do not copy the list
        for write in writes { local[write.index].exif = write.exif }
        photos = local
    }
}
