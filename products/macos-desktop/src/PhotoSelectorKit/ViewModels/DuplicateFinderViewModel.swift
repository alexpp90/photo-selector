import Foundation
import SwiftUI
import AppKit
import ImageIO

/// Represents an individual duplicate file candidate inside a duplicate cluster.
public struct DuplicateFileEntry: Identifiable, Sendable, Equatable {
    public var id: URL { url }
    public let url: URL
    public let filename: String
    public let relativePath: String
    public let fullPath: String
    public let fileSize: Int64
    public let formattedFileSize: String
    public let captureDate: Date?
    public let formattedDate: String
    public var scores: QualityScores?
    public var status: PhotoStatus
    public var isMarkedForTrash: Bool

    public init(
        url: URL,
        rootURL: URL? = nil,
        fileSize: Int64,
        captureDate: Date?,
        scores: QualityScores? = nil,
        status: PhotoStatus = .candidate,
        isMarkedForTrash: Bool = false
    ) {
        self.url = url
        self.filename = url.lastPathComponent
        self.fullPath = url.path
        if let rootURL, url.path.hasPrefix(rootURL.path) {
            let rel = String(url.path.dropFirst(rootURL.path.count))
                .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            self.relativePath = rel.isEmpty ? url.lastPathComponent : rel
        } else {
            self.relativePath = url.lastPathComponent
        }
        self.fileSize = fileSize
        self.formattedFileSize = ByteCountFormatter.string(fromByteCount: fileSize, countStyle: .file)
        self.captureDate = captureDate
        if let captureDate {
            let formatter = DateFormatter()
            formatter.dateStyle = .medium
            formatter.timeStyle = .short
            self.formattedDate = formatter.string(from: captureDate)
        } else {
            self.formattedDate = "Unknown Date"
        }
        self.scores = scores
        self.status = status
        self.isMarkedForTrash = isMarkedForTrash
    }

    /// Composite quality score (sharpness + aesthetic scaled to 100) used for quality ranking.
    public var compositeQualityScore: Double {
        let sharpnessScore = scores?.sharpness ?? 0.0
        let aestheticScore = (scores?.aesthetic ?? 0.0) * 10.0
        return sharpnessScore + aestheticScore
    }
}

/// Represents an analyzed duplicate cluster with file entries and resolution state.
public struct DuplicateGroupModel: Identifiable, Sendable, Equatable {
    public var id: String { hash }
    public let hash: String
    public let fileSize: Int64
    public let formattedFileSize: String
    public var entries: [DuplicateFileEntry]

    public init(
        hash: String,
        fileSize: Int64,
        formattedFileSize: String,
        entries: [DuplicateFileEntry]
    ) {
        self.hash = hash
        self.fileSize = fileSize
        self.formattedFileSize = formattedFileSize
        self.entries = entries
    }

    /// Wasted storage in bytes if all but one duplicate are trashed.
    public var wastedBytes: Int64 {
        let activeCount = entries.filter { $0.status != .trashed }.count
        let duplicateCount = max(0, activeCount - 1)
        return fileSize * Int64(duplicateCount)
    }

    public var formattedWastedBytes: String {
        ByteCountFormatter.string(fromByteCount: wastedBytes, countStyle: .file)
    }

    /// Whether this group has at most one non-trashed entry remaining.
    public var isResolved: Bool {
        entries.filter { !$0.isMarkedForTrash && $0.status != .trashed }.count <= 1
    }

    public var activeEntries: [DuplicateFileEntry] {
        entries.filter { $0.status != .trashed }
    }
}

public enum DuplicateScanState: Equatable {
    case idle
    case scanning(phase: String)
    case completed
    case empty
    case error(String)
    /// The folder is a cloud folder: hashing reads every file, so nothing is read (REQ-MAC-CLOUD.01).
    case refusedCloud(String)
}

/// MainActor ViewModel orchestrating duplicate scanning, quick resolution, and companion-safe trashing.
@MainActor
public final class DuplicateFinderViewModel: ObservableObject {

    // MARK: - Published State
    @Published public private(set) var scanState: DuplicateScanState = .idle
    @Published public private(set) var targetDirectoryURL: URL?
    @Published public private(set) var duplicateGroups: [DuplicateGroupModel] = []
    @Published public var selectedGroupID: String?
    @Published public var filterQuery: String = ""
    @Published public var isScanning: Bool = false
    @Published public var toastMessage: String?
    @Published public var showingTrashConfirmation: Bool = false

    // MARK: - Dependencies & Callbacks
    private let cullingManager: FileCullingManager
    private let cloudInspector: CloudResourceInspecting
    private var scanTask: Task<Void, Never>?
    private var knownPhotos: [URL: PhotoItem] = [:]
    public var onPhotoTrashed: ((URL) -> Void)?
    /// When set, a duplicate is trashed through it (the workspace's culling actor and undo stack)
    /// instead of directly, so the workspace's Undo brings the file back.
    public var trashHandler: (@MainActor (URL) async throws -> Void)?

    // MARK: - Computed Properties
    public var selectedGroup: DuplicateGroupModel? {
        guard let selectedGroupID else { return duplicateGroups.first }
        return duplicateGroups.first(where: { $0.id == selectedGroupID })
    }

    public var filteredGroups: [DuplicateGroupModel] {
        if filterQuery.trimmingCharacters(in: .whitespaces).isEmpty {
            return duplicateGroups
        }
        let query = filterQuery.lowercased()
        return duplicateGroups.filter { group in
            group.entries.contains {
                $0.filename.lowercased().contains(query) ||
                $0.relativePath.lowercased().contains(query)
            }
        }
    }

    public var totalWastedBytes: Int64 {
        duplicateGroups.reduce(0) { $0 + $1.wastedBytes }
    }

    public var formattedTotalWastedBytes: String {
        ByteCountFormatter.string(fromByteCount: totalWastedBytes, countStyle: .file)
    }

    public var markedForTrashCount: Int {
        duplicateGroups.reduce(0) { acc, group in
            acc + group.entries.filter { $0.isMarkedForTrash && $0.status != .trashed }.count
        }
    }

    public init(
        targetDirectoryURL: URL? = nil,
        photos: [PhotoItem] = [],
        cullingManager: FileCullingManager = FileCullingManager(),
        cloudInspector: CloudResourceInspecting = SystemCloudFolderInspector(),
        onPhotoTrashed: ((URL) -> Void)? = nil,
        trashHandler: (@MainActor (URL) async throws -> Void)? = nil
    ) {
        self.targetDirectoryURL = targetDirectoryURL
        self.cullingManager = cullingManager
        self.cloudInspector = cloudInspector
        self.onPhotoTrashed = onPhotoTrashed
        self.trashHandler = trashHandler
        var dict: [URL: PhotoItem] = [:]
        for photo in photos {
            dict[photo.url] = photo
            dict[photo.url.standardizedFileURL.resolvingSymlinksInPath()] = photo
        }
        self.knownPhotos = dict
    }

    // MARK: - Date Extraction
    public static func extractCaptureDate(for url: URL) -> Date? {
        let sourceOptions: [CFString: Any] = [kCGImageSourceShouldCache: false]
        if let source = CGImageSourceCreateWithURL(url as CFURL, sourceOptions as CFDictionary),
           let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] {
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy:MM:dd HH:mm:ss"

            if let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any],
               let dateStr = (exif[kCGImagePropertyExifDateTimeOriginal] ?? exif[kCGImagePropertyExifDateTimeDigitized]) as? String,
               let parsed = formatter.date(from: dateStr) {
                return parsed
            }

            if let tiff = properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any],
               let dateStr = tiff[kCGImagePropertyTIFFDateTime] as? String,
               let parsed = formatter.date(from: dateStr) {
                return parsed
            }
        }

        // Fallback to filesystem creation or modification date
        if let values = try? url.resourceValues(forKeys: [.creationDateKey, .contentModificationDateKey]) {
            return values.creationDate ?? values.contentModificationDate
        }

        return nil
    }

    // MARK: - Scanning
    public func startScan(directoryURL: URL? = nil) {
        scanTask?.cancel()
        let scanURL = directoryURL ?? targetDirectoryURL
        guard let scanURL else {
            self.scanState = .idle
            return
        }

        self.targetDirectoryURL = scanURL
        // The hash pass reads every file, which downloads a whole cloud folder: refused, nothing read.
        if cloudInspector.isRemoteOrCloudFolder(scanURL) {
            self.isScanning = false
            self.duplicateGroups = []
            self.selectedGroupID = nil
            self.scanState = .refusedCloud(CloudRefusal.fullPassLine)
            return
        }
        self.isScanning = true
        self.scanState = .scanning(phase: "Analyzing file sizes and discovering duplicates...")
        self.duplicateGroups = []
        self.selectedGroupID = nil

        scanTask = Task { [weak self] in
            do {
                let clusters = try await DuplicateFinder.findDuplicates(in: scanURL, recursive: true)
                if Task.isCancelled { return }

                if clusters.isEmpty {
                    await MainActor.run {
                        self?.duplicateGroups = []
                        self?.isScanning = false
                        self?.scanState = .empty
                    }
                    return
                }

                await MainActor.run {
                    self?.scanState = .scanning(phase: "Extracting EXIF dates and quality metrics...")
                }

                var models: [DuplicateGroupModel] = []
                for cluster in clusters {
                    if Task.isCancelled { return }

                    var entries: [DuplicateFileEntry] = []
                    for url in cluster.fileURLs {
                        let known = await MainActor.run {
                            self?.knownPhotos[url] ?? self?.knownPhotos[url.standardizedFileURL.resolvingSymlinksInPath()]
                        }
                        let date = Self.extractCaptureDate(for: url)
                        let scores = known?.scores
                        let status = known?.status ?? .candidate

                        entries.append(DuplicateFileEntry(
                            url: url,
                            rootURL: scanURL,
                            fileSize: cluster.fileSize,
                            captureDate: date,
                            scores: scores,
                            status: status,
                            isMarkedForTrash: false
                        ))
                    }

                    models.append(DuplicateGroupModel(
                        hash: cluster.hash,
                        fileSize: cluster.fileSize,
                        formattedFileSize: ByteCountFormatter.string(fromByteCount: cluster.fileSize, countStyle: .file),
                        entries: entries
                    ))
                }

                await MainActor.run {
                    self?.duplicateGroups = models
                    self?.selectedGroupID = models.first?.id
                    self?.isScanning = false
                    self?.scanState = .completed
                }
            } catch is CancellationError {
                await MainActor.run {
                    self?.isScanning = false
                    self?.scanState = .idle
                }
            } catch {
                await MainActor.run {
                    self?.isScanning = false
                    self?.scanState = .error(error.localizedDescription)
                }
            }
        }
    }

    public func cancelScan() {
        scanTask?.cancel()
        scanTask = nil
        isScanning = false
        scanState = .idle
    }

    public func setDuplicateGroupsForTesting(_ groups: [DuplicateGroupModel]) {
        self.duplicateGroups = groups
        self.selectedGroupID = groups.first?.id
        self.scanState = groups.isEmpty ? .empty : .completed
    }

    // MARK: - Automated Action: Keep Newest
    public func keepNewest(in groupID: String? = nil) {
        for gIndex in duplicateGroups.indices {
            if let groupID, duplicateGroups[gIndex].id != groupID { continue }
            var entries = duplicateGroups[gIndex].entries
            guard entries.count > 1 else { continue }

            var newestIndex = 0
            var newestDate = entries[0].captureDate ?? Date.distantPast
            for i in 1..<entries.count {
                let entryDate = entries[i].captureDate ?? Date.distantPast
                if entryDate > newestDate {
                    newestDate = entryDate
                    newestIndex = i
                }
            }

            for i in entries.indices {
                entries[i].isMarkedForTrash = (i != newestIndex)
            }
            duplicateGroups[gIndex].entries = entries
        }
        showToast("Marked older duplicates for trash (keeping newest)")
    }

    // MARK: - Automated Action: Keep Highest Score
    public func keepHighestScore(in groupID: String? = nil) async {
        for gIndex in duplicateGroups.indices {
            if let groupID, duplicateGroups[gIndex].id != groupID { continue }
            var entries = duplicateGroups[gIndex].entries
            guard entries.count > 1 else { continue }

            // Compute on-demand sharpness if unpopulated
            for i in entries.indices {
                if entries[i].scores == nil {
                    if let source = CGImageSourceCreateWithURL(entries[i].url as CFURL, nil),
                       let cgImage = CGImageSourceCreateImageAtIndex(source, 0, nil) {
                        let variance = FocusMetricService.computeLaplacianVariance(cgImage: cgImage) ?? 0.0
                        entries[i].scores = QualityScores(
                            sharpness: min(100.0, variance),
                            noise: 0.0,
                            highlightClipping: 0.0,
                            shadowClipping: 0.0,
                            aesthetic: nil
                        )
                    }
                }
            }

            var bestIndex = 0
            var bestScore = entries[0].compositeQualityScore
            for i in 1..<entries.count {
                let score = entries[i].compositeQualityScore
                if score > bestScore {
                    bestScore = score
                    bestIndex = i
                }
            }

            for i in entries.indices {
                entries[i].isMarkedForTrash = (i != bestIndex)
            }
            duplicateGroups[gIndex].entries = entries
        }
        showToast("Marked lower-scoring duplicates for trash")
    }

    // MARK: - Trashing Actions
    public func trashSingleEntry(entry: DuplicateFileEntry, in groupID: String) {
        if let handler = trashHandler {
            Task { @MainActor in
                do {
                    try await handler(entry.url)
                    markTrashed(entry.url, in: groupID)
                    showToast("Moved \(entry.filename) to Trash. Undo with \u{2318}Z")
                } catch {
                    showToast("Failed to trash file: \(error.localizedDescription)")
                }
            }
            return
        }
        do {
            _ = try cullingManager.moveToTrash(photoURL: entry.url)
            onPhotoTrashed?(entry.url)

            if let gIndex = duplicateGroups.firstIndex(where: { $0.id == groupID }) {
                if let eIndex = duplicateGroups[gIndex].entries.firstIndex(where: { $0.url == entry.url }) {
                    duplicateGroups[gIndex].entries[eIndex].status = .trashed
                    duplicateGroups[gIndex].entries[eIndex].isMarkedForTrash = false
                }
            }
            showToast("Moved \(entry.filename) to Trash")
        } catch {
            showToast("Failed to trash file: \(error.localizedDescription)")
        }
    }

    private func markTrashed(_ url: URL, in groupID: String) {
        guard let g = duplicateGroups.firstIndex(where: { $0.id == groupID }),
              let e = duplicateGroups[g].entries.firstIndex(where: { $0.url == url }) else { return }
        duplicateGroups[g].entries[e].status = .trashed
        duplicateGroups[g].entries[e].isMarkedForTrash = false
    }

    /// The batch through `trashHandler`: each file is its own undo step.
    private func trashMarkedThroughHandler(_ handler: @escaping @MainActor (URL) async throws -> Void) async {
        var count = 0
        var errors = 0
        let marked = duplicateGroups.flatMap { group in
            group.entries.filter { $0.isMarkedForTrash && $0.status != .trashed }.map { (group.id, $0.url) }
        }
        for (groupID, url) in marked {
            do { try await handler(url); markTrashed(url, in: groupID); count += 1 } catch { errors += 1 }
        }
        duplicateGroups.removeAll { $0.entries.filter { $0.status != .trashed }.count <= 1 }
        if let sel = selectedGroupID, !duplicateGroups.contains(where: { $0.id == sel }) {
            selectedGroupID = duplicateGroups.first?.id
        }
        showToast(errors > 0 ? "Trashed \(count) files with \(errors) errors"
                             : "Moved \(count) duplicate files to Trash. Undo with \u{2318}Z")
    }

    public func trashMarkedDuplicates() {
        if let handler = trashHandler {
            Task { @MainActor in await trashMarkedThroughHandler(handler) }
            return
        }
        var count = 0
        var errors: [String] = []

        for gIndex in duplicateGroups.indices {
            for eIndex in duplicateGroups[gIndex].entries.indices {
                let entry = duplicateGroups[gIndex].entries[eIndex]
                if entry.isMarkedForTrash && entry.status != .trashed {
                    do {
                        _ = try cullingManager.moveToTrash(photoURL: entry.url)
                        duplicateGroups[gIndex].entries[eIndex].status = .trashed
                        duplicateGroups[gIndex].entries[eIndex].isMarkedForTrash = false
                        onPhotoTrashed?(entry.url)
                        count += 1
                    } catch {
                        errors.append("\(entry.filename): \(error.localizedDescription)")
                    }
                }
            }
        }

        // Remove fully resolved groups from the list
        duplicateGroups.removeAll { group in
            group.entries.filter { $0.status != .trashed }.count <= 1
        }
        if let currentSelected = selectedGroupID, !duplicateGroups.contains(where: { $0.id == currentSelected }) {
            selectedGroupID = duplicateGroups.first?.id
        }

        if !errors.isEmpty {
            showToast("Trashed \(count) files with \(errors.count) errors")
        } else {
            showToast("Moved \(count) duplicate files to macOS Trash")
        }
    }

    public func toggleTrashMark(entryID: URL, in groupID: String) {
        guard let gIndex = duplicateGroups.firstIndex(where: { $0.id == groupID }),
              let eIndex = duplicateGroups[gIndex].entries.firstIndex(where: { $0.url == entryID }) else {
            return
        }
        duplicateGroups[gIndex].entries[eIndex].isMarkedForTrash.toggle()
    }

    public func markToKeep(entryID: URL, in groupID: String) {
        guard let gIndex = duplicateGroups.firstIndex(where: { $0.id == groupID }) else { return }
        for i in duplicateGroups[gIndex].entries.indices {
            if duplicateGroups[gIndex].entries[i].url == entryID {
                duplicateGroups[gIndex].entries[i].isMarkedForTrash = false
            } else {
                duplicateGroups[gIndex].entries[i].isMarkedForTrash = true
            }
        }
    }

    private func showToast(_ msg: String) {
        self.toastMessage = msg
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            await MainActor.run {
                if self.toastMessage == msg {
                    self.toastMessage = nil
                }
            }
        }
    }
}
