import Foundation

/// Action type representing a culling operation.
public enum CullingActionType: String, Sendable, Codable, CaseIterable {
    case move
    case copy
    case trash
}

/// Represents a culling job to be executed asynchronously on disk.
public struct CullingJob: Sendable, Identifiable {
    public let id: UUID
    public let photoID: UUID
    public let actionType: CullingActionType
    public let primaryURL: URL
    public let rootURL: URL?
    public let destinationFolderName: String
    public let timestamp: Date

    public init(
        id: UUID = UUID(),
        photoID: UUID,
        actionType: CullingActionType,
        primaryURL: URL,
        rootURL: URL?,
        destinationFolderName: String = AppSettingsDefaults.cullingDestinationFolderName,
        timestamp: Date = Date()
    ) {
        self.id = id
        self.photoID = photoID
        self.actionType = actionType
        self.primaryURL = primaryURL
        self.rootURL = rootURL
        self.destinationFolderName = destinationFolderName
        self.timestamp = timestamp
    }
}

/// Execution record capturing affected files for auditing, undo, and rollback.
public struct CullingExecutionRecord: Sendable, Identifiable {
    public let jobID: UUID
    public var id: UUID { jobID }
    public let photoID: UUID
    public let actionType: CullingActionType
    public let originalPrimaryURL: URL
    public let affectedURLs: [URL]
    public let trashedPairs: [TrashedFilePair]
    /// Originals of a partial move that could not be removed: their verified copies are in
    /// `affectedURLs`, and undo deletes those copies instead of restoring them next to the originals.
    public let unremovedSourceURLs: [URL]
    public let previousStatus: PhotoStatus
    public let timestamp: Date

    public init(
        jobID: UUID,
        photoID: UUID,
        actionType: CullingActionType,
        originalPrimaryURL: URL,
        affectedURLs: [URL],
        trashedPairs: [TrashedFilePair] = [],
        unremovedSourceURLs: [URL] = [],
        previousStatus: PhotoStatus = .candidate,
        timestamp: Date = Date()
    ) {
        self.jobID = jobID
        self.photoID = photoID
        self.actionType = actionType
        self.originalPrimaryURL = originalPrimaryURL
        self.affectedURLs = affectedURLs
        self.trashedPairs = trashedPairs
        self.unremovedSourceURLs = unremovedSourceURLs
        self.previousStatus = previousStatus
        self.timestamp = timestamp
    }
}

/// Actor-isolated asynchronous executor providing a serial FIFO queue for non-blocking disk I/O.
/// Bridges between rapid UI interactions (< 1ms) and disk operations via FileCullingManager,
/// supporting in-flight job cancellation, undo reversals, and rollback support.
public actor AsyncCullingActor {

    /// Label of the serial queue all blocking file I/O runs on.
    public static let ioQueueLabel = "com.photoselector.culling-io"

    /// File operations are blocking system calls that can take seconds on an SD card or network
    /// volume. The actor therefore runs on its own serial dispatch queue instead of a thread of the
    /// cooperative pool, so a slow volume cannot starve other Swift tasks (finding Q-4).
    private nonisolated let ioQueue = DispatchSerialQueue(label: AsyncCullingActor.ioQueueLabel, qos: .utility)

    public nonisolated var unownedExecutor: UnownedSerialExecutor {
        ioQueue.asUnownedSerialExecutor()
    }

    private let cullingManager: FileCullingManager
    private let fileManager: FileManager
    private var cancelledJobIDs: Set<UUID> = []
    private var executedJobIDs: Set<UUID> = []
    private var completedRecords: [UUID: CullingExecutionRecord] = [:]
    /// Job IDs in the order they were first seen; the oldest are forgotten beyond `retainedJobLimit`.
    private var trackedOrder: [UUID] = []
    private let retainedJobLimit: Int

    /// Number of job IDs the actor still remembers (cancelled, executed or with a record).
    var trackedJobCount: Int { trackedOrder.count }

    public nonisolated var trashService: any TrashService {
        cullingManager.trashService
    }

    public init(
        cullingManager: FileCullingManager = FileCullingManager(),
        fileManager: FileManager = .default,
        retainedJobLimit: Int = 256
    ) {
        self.cullingManager = cullingManager
        self.fileManager = fileManager
        self.retainedJobLimit = max(1, retainedJobLimit)
    }

    public init(
        trashService: any TrashService,
        fileManager: FileManager = .default
    ) {
        self.cullingManager = FileCullingManager(fileManager: fileManager, trashService: trashService)
        self.fileManager = fileManager
        self.retainedJobLimit = 256
    }

    /// Remembers `id` and forgets the oldest jobs beyond the retention limit. The undo stack is
    /// 20 deep, so a job that old can no longer be reversed through this actor anyway (Q-4).
    private func track(_ id: UUID) {
        if !trackedOrder.contains(id) { trackedOrder.append(id) }
        while trackedOrder.count > retainedJobLimit {
            let oldest = trackedOrder.removeFirst()
            cancelledJobIDs.remove(oldest)
            executedJobIDs.remove(oldest)
            completedRecords.removeValue(forKey: oldest)
        }
    }

    private func forget(_ id: UUID) {
        executedJobIDs.remove(id)
        cancelledJobIDs.remove(id)
        completedRecords.removeValue(forKey: id)
        trackedOrder.removeAll { $0 == id }
    }

    /// Enqueues and serially executes a culling job.
    /// Returns the execution record upon completion, or nil if cancelled prior to execution.
    public func enqueue(_ job: CullingJob) async throws -> CullingExecutionRecord? {
        if cancelledJobIDs.contains(job.id) {
            return nil
        }

        if Task.isCancelled {
            cancelledJobIDs.insert(job.id)
            track(job.id)
            throw CancellationError()
        }

        let record = try executeJob(job)
        completedRecords[job.id] = record
        track(job.id)
        return record
    }

    /// Marks a job ID as cancelled. If the job hasn't started execution, it will be skipped.
    public func cancelJob(id: UUID) {
        cancelledJobIDs.insert(id)
        track(id)
    }

    /// Cancels a pending job or reverses a completed/in-flight culling operation.
    /// If the job completed on disk, uses the actor's cached completed record to restore files.
    /// If the job was cancelled prior to disk I/O, records cancellation and returns an empty array.
    /// If a fallback record contains affected files, reverses them.
    /// - Parameters:
    ///   - jobID: The unique identifier of the job to cancel or undo.
    ///   - fallbackRecord: The record available at the caller site (e.g. optimistic or cached record).
    /// - Returns: The list of restored URLs.
    public func cancelOrUndo(jobID: UUID, fallbackRecord: CullingExecutionRecord) async throws -> [URL] {
        cancelledJobIDs.insert(jobID)
        if let completedRecord = completedRecords[jobID] {
            return try await undo(record: completedRecord)
        } else if !fallbackRecord.affectedURLs.isEmpty || !fallbackRecord.trashedPairs.isEmpty {
            return try await undo(record: fallbackRecord)
        }
        return []
    }

    /// Reverses a previously executed culling operation.
    /// Never overwrites or deletes pre-existing files at the restore destination:
    /// if any original path in `originalDir` is now occupied, the restored bundle receives
    /// a shared collision-free suffix (`-2`, `-3`, …).
    /// - Parameter record: The execution record to undo.
    /// - Returns: The list of restored URLs.
    public func undo(record: CullingExecutionRecord) async throws -> [URL] {
        guard !record.affectedURLs.isEmpty || !record.trashedPairs.isEmpty else {
            return []
        }

        // If job was cancelled before execution completed, undo is a clean no-op
        if cancelledJobIDs.contains(record.jobID) && !executedJobIDs.contains(record.jobID) {
            return []
        }

        var restoredURLs: [URL] = []
        let originalDir = record.originalPrimaryURL.deletingLastPathComponent()
        let primaryStem = record.originalPrimaryURL.deletingPathExtension().lastPathComponent

        switch record.actionType {
        case .move:
            // Move files back from Selection/ to original directory without deleting occupied destinations
            let items: [(sourceURL: URL, unsuffixedName: String)] = record.affectedURLs.map { movedURL in
                let unsuffixedName = unsuffixedMoveRestoreFilename(
                    for: movedURL.lastPathComponent,
                    originalPrimaryURL: record.originalPrimaryURL,
                    firstAffectedURL: record.affectedURLs.first
                )
                return (sourceURL: movedURL, unsuffixedName: unsuffixedName)
            }
            // A partial move left some originals in place (D-3): deleting the copy is the undo for
            // those members — restoring it would put a "-2" duplicate next to the original. Only a
            // recorded unremoved source whose file still exists counts; a copy whose original has
            // since disappeared is the only one left and is restored like any other member.
            let unremoved = Set(record.unremovedSourceURLs.map { $0.standardizedFileURL.resolvingSymlinksInPath().path })
            var stillHome: [(sourceURL: URL, unsuffixedName: String)] = []
            var toRestore: [(sourceURL: URL, unsuffixedName: String)] = []
            for item in items {
                let original = originalDir.appendingPathComponent(item.unsuffixedName)
                let originalKey = original.standardizedFileURL.resolvingSymlinksInPath().path
                if unremoved.contains(originalKey), fileManager.fileExists(atPath: original.path) {
                    stillHome.append(item)
                } else {
                    toRestore.append(item)
                }
            }
            restoredURLs = try restoreBundleToOriginalDirectory(
                items: toRestore,
                originalDir: originalDir,
                primaryStem: primaryStem
            )
            for item in stillHome where fileManager.fileExists(atPath: item.sourceURL.path) {
                try fileManager.removeItem(at: item.sourceURL)
            }

        case .copy:
            // Remove only the destination replicas in Selection/ created by the copy operation itself
            let canonicalOriginal = record.originalPrimaryURL.standardizedFileURL.resolvingSymlinksInPath()
            let canonicalOriginalDir = originalDir.standardizedFileURL.resolvingSymlinksInPath()
            for copiedURL in record.affectedURLs {
                let canonicalCopied = copiedURL.standardizedFileURL.resolvingSymlinksInPath()
                let canonicalCopiedDir = copiedURL.deletingLastPathComponent().standardizedFileURL.resolvingSymlinksInPath()

                // Strict guard: NEVER delete the original source file or files in the original source directory!
                guard copiedURL.standardizedFileURL.path != record.originalPrimaryURL.standardizedFileURL.path,
                      canonicalCopied.path != canonicalOriginal.path,
                      canonicalCopiedDir.path != canonicalOriginalDir.path else {
                    continue
                }

                if fileManager.fileExists(atPath: copiedURL.path) {
                    // Remove only the replica created in Selection/ by the copy operation itself (never the original source file)
                    try fileManager.removeItem(at: copiedURL)
                    restoredURLs.append(copiedURL)
                }
            }

        case .trash:
            // Move files back from Trash to original directory using the recorded (originalURL, trashedURL) pairs
            let items: [(sourceURL: URL, unsuffixedName: String)]
            if !record.trashedPairs.isEmpty {
                items = record.trashedPairs.map { pair in
                    (sourceURL: pair.trashedURL, unsuffixedName: pair.originalURL.lastPathComponent)
                }
            } else {
                items = record.affectedURLs.enumerated().map { index, trashedURL in
                    let unsuffixedName = index == 0
                        ? record.originalPrimaryURL.lastPathComponent
                        : trashedURL.lastPathComponent
                    return (sourceURL: trashedURL, unsuffixedName: unsuffixedName)
                }
            }
            do {
                restoredURLs = try restoreBundleToOriginalDirectory(
                    items: items,
                    originalDir: originalDir,
                    primaryStem: primaryStem
                )
            } catch {
                // Never drop the undo entry while a member is still in the Trash
                let remainingPairs: [TrashedFilePair]
                if !record.trashedPairs.isEmpty {
                    remainingPairs = record.trashedPairs.filter { fileManager.fileExists(atPath: $0.trashedURL.path) }
                } else {
                    remainingPairs = items
                        .filter { fileManager.fileExists(atPath: $0.sourceURL.path) }
                        .map { TrashedFilePair(originalURL: originalDir.appendingPathComponent($0.unsuffixedName), trashedURL: $0.sourceURL) }
                }
                if !remainingPairs.isEmpty {
                    executedJobIDs.insert(record.jobID)
                    completedRecords[record.jobID] = CullingExecutionRecord(
                        jobID: record.jobID,
                        photoID: record.photoID,
                        actionType: .trash,
                        originalPrimaryURL: record.originalPrimaryURL,
                        affectedURLs: remainingPairs.map(\.trashedURL),
                        trashedPairs: remainingPairs,
                        previousStatus: record.previousStatus,
                        timestamp: record.timestamp
                    )
                }
                throw error
            }
        }

        forget(record.jobID)
        return restoredURLs
    }

    /// Returns true if the actor currently holds a completed execution record for the given job ID.
    public func hasCompletedRecord(for jobID: UUID) -> Bool {
        completedRecords[jobID] != nil
    }

    /// Returns the actor's cached completed execution record for the given job ID, if present.
    public func completedRecord(for jobID: UUID) -> CullingExecutionRecord? {
        completedRecords[jobID]
    }

    // MARK: - Private Execution

    private func executeJob(_ job: CullingJob) throws -> CullingExecutionRecord {
        guard !cancelledJobIDs.contains(job.id) else {
            throw CancellationError()
        }

        let affectedURLs: [URL]
        var trashedPairs: [TrashedFilePair] = []

        switch job.actionType {
        case .move:
            guard let root = job.rootURL else {
                throw FileCullingError.directoryCreationFailed(job.primaryURL, "Missing root directory")
            }
            do {
                affectedURLs = try cullingManager.moveToSelection(
                    photoURL: job.primaryURL,
                    rootURL: root,
                    destinationFolderName: job.destinationFolderName
                )
            } catch let FileCullingError.partialMove(destinations, unremovedSources, reason, rollbackFailed) {
                // Keep the record so the verified copies can be undone later (and never lost).
                executedJobIDs.insert(job.id)
                completedRecords[job.id] = CullingExecutionRecord(
                    jobID: job.id,
                    photoID: job.photoID,
                    actionType: .move,
                    originalPrimaryURL: job.primaryURL,
                    affectedURLs: destinations,
                    unremovedSourceURLs: unremovedSources,
                    previousStatus: .candidate,
                    timestamp: Date()
                )
                throw FileCullingError.partialMove(
                    destinations: destinations,
                    unremovedSources: unremovedSources,
                    reason: reason,
                    rollbackFailed: rollbackFailed
                )
            }

        case .copy:
            guard let root = job.rootURL else {
                throw FileCullingError.directoryCreationFailed(job.primaryURL, "Missing root directory")
            }
            affectedURLs = try cullingManager.copyToSelection(
                photoURL: job.primaryURL,
                rootURL: root,
                destinationFolderName: job.destinationFolderName
            )

        case .trash:
            do {
                let pairs = try cullingManager.moveToTrashWithPairs(photoURL: job.primaryURL)
                trashedPairs = pairs
                affectedURLs = pairs.map(\.trashedURL)
            } catch let FileCullingError.partialTrashRollbackFailed(url, reason, remainingPairs) {
                if !remainingPairs.isEmpty {
                    let partialRecord = CullingExecutionRecord(
                        jobID: job.id,
                        photoID: job.photoID,
                        actionType: .trash,
                        originalPrimaryURL: job.primaryURL,
                        affectedURLs: remainingPairs.map(\.trashedURL),
                        trashedPairs: remainingPairs,
                        previousStatus: .candidate,
                        timestamp: Date()
                    )
                    executedJobIDs.insert(job.id)
                    completedRecords[job.id] = partialRecord
                }
                throw FileCullingError.partialTrashRollbackFailed(
                    url: url,
                    reason: reason,
                    remainingTrashedPairs: remainingPairs
                )
            }
        }

        executedJobIDs.insert(job.id)

        return CullingExecutionRecord(
            jobID: job.id,
            photoID: job.photoID,
            actionType: job.actionType,
            originalPrimaryURL: job.primaryURL,
            affectedURLs: affectedURLs,
            trashedPairs: trashedPairs,
            previousStatus: .candidate,
            timestamp: Date()
        )
    }

    private func restoreBundleToOriginalDirectory(
        items: [(sourceURL: URL, unsuffixedName: String)],
        originalDir: URL,
        primaryStem: String
    ) throws -> [URL] {
        var validItems: [(sourceURL: URL, unsuffixedName: String, unsuffixedDest: URL)] = []
        var hasCollision = false

        for item in items {
            let unsuffixedDest = originalDir.appendingPathComponent(item.unsuffixedName)
            let canonicalSource = item.sourceURL.standardizedFileURL.resolvingSymlinksInPath()
            let canonicalDest = unsuffixedDest.standardizedFileURL.resolvingSymlinksInPath()

            // Guard against self-move: if sourceURL already resides at destination,
            // it was never moved away (e.g. in-flight cancellation or optimistic record)
            guard item.sourceURL.standardizedFileURL.path != unsuffixedDest.standardizedFileURL.path,
                  canonicalSource.path != canonicalDest.path else {
                continue
            }

            guard fileManager.fileExists(atPath: item.sourceURL.path) else {
                continue
            }

            if fileManager.fileExists(atPath: unsuffixedDest.path) {
                hasCollision = true
            }
            validItems.append((sourceURL: item.sourceURL, unsuffixedName: item.unsuffixedName, unsuffixedDest: unsuffixedDest))
        }

        guard !validItems.isEmpty else {
            return []
        }

        let resolvedDestinations: [(sourceURL: URL, destinationURL: URL)]
        if !hasCollision {
            resolvedDestinations = validItems.map { (sourceURL: $0.sourceURL, destinationURL: $0.unsuffixedDest) }
        } else {
            var suffix = 2
            while true {
                let allFree = validItems.allSatisfy { item in
                    let suffixedName = FileCullingManager.suffixedCompanionFilename(
                        item.unsuffixedName,
                        primaryStem: primaryStem,
                        suffix: suffix
                    )
                    let candidateDest = originalDir.appendingPathComponent(suffixedName)
                    return !fileManager.fileExists(atPath: candidateDest.path)
                }
                if allFree {
                    break
                }
                suffix += 1
            }

            resolvedDestinations = validItems.map { item in
                let suffixedName = FileCullingManager.suffixedCompanionFilename(
                    item.unsuffixedName,
                    primaryStem: primaryStem,
                    suffix: suffix
                )
                return (
                    sourceURL: item.sourceURL,
                    destinationURL: originalDir.appendingPathComponent(suffixedName)
                )
            }
        }

        var restoredURLs: [URL] = []
        for entry in resolvedDestinations {
            try fileManager.moveItem(at: entry.sourceURL, to: entry.destinationURL)
            restoredURLs.append(entry.destinationURL)
        }
        return restoredURLs
    }

    private func unsuffixedMoveRestoreFilename(
        for movedFilename: String,
        originalPrimaryURL: URL,
        firstAffectedURL: URL?
    ) -> String {
        let primaryStem = originalPrimaryURL.deletingPathExtension().lastPathComponent
        guard !primaryStem.isEmpty,
              let firstAffected = firstAffectedURL else {
            return movedFilename
        }
        let firstMovedStem = firstAffected.deletingPathExtension().lastPathComponent
        guard let stemRange = firstMovedStem.range(of: primaryStem, options: [.caseInsensitive, .anchored]) else {
            return movedFilename
        }
        let suffixPart = String(firstMovedStem[stemRange.upperBound...])
        guard suffixPart.range(of: #"^-\d+$"#, options: .regularExpression) != nil else {
            return movedFilename
        }
        let suffixedPrefix = primaryStem + suffixPart
        if let matchRange = movedFilename.range(of: suffixedPrefix, options: [.caseInsensitive, .anchored]) {
            let originalStemPrefix = movedFilename.prefix(primaryStem.count)
            let remainder = movedFilename[matchRange.upperBound...]
            return "\(originalStemPrefix)\(remainder)"
        }
        return movedFilename
    }
}
