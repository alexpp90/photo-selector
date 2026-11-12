import SwiftUI
import AppKit
import Foundation

/// MainActor state machine managing photos, navigation, comparison slots,
/// zoom/pan sync, optimistic status mutations (<1ms), auto-advance, and keyboard routing.
@MainActor
public final class CullingWorkspaceViewModel: ObservableObject {

    // MARK: - Published State
    @Published public private(set) var photos: [PhotoItem] = []
    @Published public var currentIndex: Int = 0 {
        didSet {
            if currentIndex != oldValue { browseDirection = currentIndex < oldValue ? .backward : .forward }
            syncSlotsToCurrentIndex()
            prefetchAroundCurrentIndex()
            rememberCurrentPhoto()
        }
    }
    @Published public var comparisonSlotIndices: [Int] = [0]
    @Published public var activeSlotIndex: Int = 0
    @Published public var comparisonMode: ComparisonMode = .single {
        didSet {
            setupSlotsForMode(comparisonMode)
        }
    }
    @Published public var zoomState: ZoomState = .fit
    @Published public var isZoomSynced: Bool = true
    @Published public var isFilmstripVisible: Bool = true
    @Published public var isInfoHUDVisible: Bool = true
    /// After-cull advance. The stored key is the single source: setting it here (menu) persists it,
    /// and a change made elsewhere (Settings) is pulled in by `observeStoredAutoAdvance`.
    @Published public var autoAdvance: Bool = true
    private var lastStoredAutoAdvance = true

    /// The Culling menu toggle: sets the live value and persists it (Settings follows via `@AppStorage`).
    public func setAutoAdvance(_ value: Bool) {
        autoAdvance = value
        lastStoredAutoAdvance = value
        userDefaults.autoAdvance = value
    }
    // Removed in deinit; written once in init on the main actor, never mutated after.
    private nonisolated(unsafe) var autoAdvanceObserver: NSObjectProtocol?

    deinit {
        if let observer = autoAdvanceObserver { NotificationCenter.default.removeObserver(observer) }
    }
    @Published public var isDropTargeted: Bool = false
    @Published public var currentToast: ToastMessage?
    @Published public private(set) var rootDirectoryURL: URL?
    @Published public private(set) var isLoading: Bool = false
    @Published public var isDuplicateFinderPresented: Bool = false
    @Published public var isLibraryStatisticsPresented: Bool = false
    @Published public var isShortcutsHelpPresented: Bool = false
    /// File > Open Recent (newest first, at most ten); see `CullingWorkspaceViewModel+Session.swift`.
    @Published public internal(set) var recentFolders: [URL] = []
    /// The end-of-cull card or notice was dismissed (Esc, navigation); the next decision clears it.
    @Published var endStateDismissed = false

    // MARK: - Cloud / File Provider State (U-29)
    @Published public private(set) var isRemoteFolder: Bool = false
    @Published public private(set) var cloudBadgeText: String? = nil
    @Published public private(set) var requiresFullPassConfirmation: Bool = false
    @Published public private(set) var pendingPermanentDelete: PendingPermanentDeleteConfirmation? = nil
    /// Set instead of starting a scoring pass on a cloud folder; the view asks "Download all photos?".
    @Published public private(set) var pendingDownloadConfirmation: PendingDownloadConfirmation? = nil
    /// The user agreed, for this folder, to read every photo (reset when a folder opens).
    private var fullPassConsented = false

    // MARK: - Sort & Sharpness Stop Threshold (U-34)
    @Published public var sortOrder: WorkspaceSortOrder = .defaultOrder
    /// Overrides the persisted "Stop at sharpness" setting when set (tests); nil reads the setting.
    @Published public var sharpnessStopThreshold: Double? = nil
    /// Progress of the background scoring pass over the whole library (nil when none runs).
    @Published public private(set) var scoringProgress: ScoringProgress? = nil
    @Published public var isGoToPhotoPresented: Bool = false

    // MARK: - Local Decision Log (U-35)
    @Published public private(set) var decisionLog: [CullingDecisionLogEntry] = []

    public func presentDuplicateFinder() {
        isDuplicateFinderPresented = true
    }

    public func presentLibraryStatistics() {
        isLibraryStatisticsPresented = true
    }

    public func presentShortcutsHelp() {
        isShortcutsHelpPresented = true
    }

    public var selectedCount: Int {
        photos.filter { $0.status == .selected }.count
    }

    public var trashedCount: Int {
        photos.filter { $0.status == .trashed }.count
    }

    public var copiedCount: Int {
        photos.filter { $0.status == .copied }.count
    }

    /// Photos still undecided: a copied photo is decided, so it is not "Remaining".
    public var remainingCount: Int {
        max(0, photos.count - selectedCount - copiedCount - trashedCount)
    }

    public var canUndo: Bool {
        !undoStack.isEmpty
    }

    /// The action the next Undo takes back (names the Edit menu item).
    public var lastUndoRecord: CullingExecutionRecord? { undoStack.last }

    /// Effective forward prefetch radius: 1 ahead on remote/cloud folders (UC-17),
    /// or user-configured `preloadWindowAfter` on local folders.
    public var prefetchRadius: Int {
        isRemoteFolder ? 1 : max(2, min(userDefaults.preloadWindowAfter, 10))
    }

    /// Estimated bytes that a full-folder metadata/statistics/duplicate pass would download
    /// on a remote/cloud folder (UC-17).
    public var estimatedFullPassDownloadBytes: Int64 {
        guard isRemoteFolder else { return 0 }
        let onlineOnly = photos.map(\.url).filter { cloudInspector.isOnlineOnlyItem($0) }
        if !onlineOnly.isEmpty {
            return cloudInspector.estimatedDownloadBytes(for: onlineOnly)
        }
        let uncached = photos.filter { $0.exif == nil || $0.scores == nil }.map(\.url)
        return cloudInspector.estimatedDownloadBytes(for: uncached)
    }

    /// Confirms and starts the full-folder metadata pass on a remote/cloud folder after user consent.
    public func confirmAndRunFullPass() {
        requiresFullPassConfirmation = false
        fullPassConsented = true
        startMetadataEnrichment()
    }

    /// "Download" in the "Download all photos?" question: runs the scoring pass that was held back.
    public func confirmDownloadAll() {
        guard let pending = pendingDownloadConfirmation else { return }
        pendingDownloadConfirmation = nil
        fullPassConsented = true
        switch pending.purpose {
        case .statistics: startLibraryScoring()
        case .lowestSharpnessSort: setSortOrder(.lowestSharpnessFirst)
        }
    }

    /// "Cancel": nothing downloads; Statistics opens on what is already scored, the sort stays as it was.
    public func declineDownloadAll() {
        pendingDownloadConfirmation = nil
    }

    /// Executes a pending permanent deletion after the user confirms that Trash is unavailable.
    public func confirmPermanentDelete() {
        guard let pending = pendingPermanentDelete else { return }
        pendingPermanentDelete = nil
        var failure: (url: URL, error: Error)?
        for url in pending.urls {
            if FileManager.default.fileExists(atPath: url.path) {
                do { try FileManager.default.removeItem(at: url) } catch { failure = failure ?? (url, error) }
            }
        }
        // A caught error surfaces: the photo is not marked deleted, and the system's reason is shown.
        if let failure {
            showToast(
                icon: "exclamationmark.triangle.fill",
                title: "Couldn't delete \(failure.url.lastPathComponent)",
                subtitle: failure.error.localizedDescription
            )
            return
        }
        if let index = photos.firstIndex(where: { $0.id == pending.photoID }) {
            photos[index].status = .trashed
        }
        showToast(
            icon: "trash.slash.fill",
            title: "Deleted Permanently",
            subtitle: "No Undo",
            actionType: .trash
        )
    }

    /// Cancels a pending permanent-delete confirmation without deleting any files.
    public func cancelPermanentDelete() {
        pendingPermanentDelete = nil
    }

    /// Summary of SD card culling completion, in-flight transfers, and format safety (UC-10).
    public var cardCullSummary: CardCullSummary {
        let kept = photos.filter { $0.status == .selected || $0.status == .copied }.count
        let trashed = photos.filter { $0.status == .trashed }.count
        let undecided = photos.filter { $0.status == .candidate }.count
        let inFlight = inFlightJobIDs.count

        let hasExternalDest: Bool = {
            let destination = CullingDestinationResolver.resolve(defaults: userDefaults, openFolderURL: rootDirectoryURL)
            guard destination.mode == .customFolder, let extURL = destination.baseFolderURL else { return false }
            guard let rootURL = rootDirectoryURL else { return true }
            let extPath = extURL.standardizedFileURL.resolvingSymlinksInPath().path
            let rootPath = rootURL.standardizedFileURL.resolvingSymlinksInPath().path
            return !extPath.hasPrefix(rootPath)
        }()

        let keepersOnCard = kept > 0 && !hasExternalDest
        let isSafe = !photos.isEmpty && undecided == 0 && inFlight == 0 && !keepersOnCard

        let summary: String
        if photos.isEmpty {
            summary = "No photos loaded."
        } else if inFlight > 0 {
            summary = "Transfers in progress (\(inFlight) remaining) — do not format or eject card."
        } else if undecided > 0 {
            summary = "\(kept) kept, \(trashed) trashed, \(undecided) undecided."
        } else if keepersOnCard {
            summary = "All \(photos.count) photos decided, but \(kept) keeper(s) are still saved on this card — copy them off the card before formatting."
        } else {
            summary = "All \(photos.count) photos decided (\(kept) kept externally, \(trashed) trashed) — safe to format card."
        }

        return CardCullSummary(
            keptCount: kept,
            trashedCount: trashed,
            undecidedCount: undecided,
            inFlightCount: inFlight,
            keepersOnCard: keepersOnCard,
            isSafeToFormat: isSafe,
            summaryText: summary
        )
    }

    /// The user's entry point of View > Sort By: persists the order and applies it. Lowest sharpness
    /// first needs a score for every photo, so the unscored ones are scored in the background
    /// first (progress in `scoringProgress`) and the list is ordered when that finishes.
    public func setSortOrder(_ order: WorkspaceSortOrder) {
        if order == .lowestSharpnessFirst, photos.contains(where: { $0.scores == nil }),
           isRemoteFolder, !fullPassConsented {
            // Scoring a cloud folder downloads all of it: ask first, and keep the current order.
            pendingDownloadConfirmation = PendingDownloadConfirmation(
                purpose: .lowestSharpnessSort(previous: sortOrder), estimatedBytes: estimatedFullPassDownloadBytes)
            return
        }
        userDefaults.workspaceSortOrder = order
        if order == .lowestSharpnessFirst, photos.contains(where: { $0.scores == nil }) {
            sortOrder = order
            sortAfterScoring = true
            startLibraryScoring()
        } else {
            sortAfterScoring = false
            applySortOrder(order)
        }
    }

    /// Applies the candidate order to the list now and returns to its first photo (UC-13).
    public func applySortOrder(_ order: WorkspaceSortOrder) {
        sortOrder = order
        guard !photos.isEmpty else { return }
        switch order {
        case .fileName:
            photos.sort(by: PhotoOrdering.byName)
        case .newestFirst:
            photos.sort(by: PhotoOrdering.byDate(newestFirst: true))
        case .oldestFirst:
            photos.sort(by: PhotoOrdering.byDate(newestFirst: false))
        case .lowestSharpnessFirst:
            photos.sort { lhs, rhs in
                switch (lhs.scores?.focusScore, rhs.scores?.focusScore) {
                case let (lScore?, rScore?):
                    if lScore != rScore { return lScore < rScore }
                    return PhotoOrdering.byName(lhs, rhs)
                case (_?, nil):
                    return true
                case (nil, _?):
                    return false
                case (nil, nil):
                    return PhotoOrdering.byName(lhs, rhs)
                }
            }
        }
        currentIndex = 0
        activeSlotIndex = 0
        setupSlotsForMode(comparisonMode)
        prefetchAroundCurrentIndex()
        checkSharpnessStopThresholdOnAdvance()
    }

    /// Re-orders once the progressive scan has settled (newest/oldest first need the whole folder;
    /// the batches arrive in scan order). The photo on screen stays on screen by identity once
    /// the user has navigated; an untouched session lands on the first photo of the new order.
    func reorderAfterScan() {
        // File-name order is the scan order (folder by folder), exactly what installs that predate
        // the sort menu always had; only the date orders need the whole folder.
        guard sortOrder == .newestFirst || sortOrder == .oldestFirst, photos.count > 1 else { return }
        let keepID = userHasNavigated ? currentPhoto?.id : nil
        let comparator = PhotoOrdering.byDate(newestFirst: sortOrder == .newestFirst)
        guard !photos.isSorted(by: comparator) else { return }
        photos.sort(by: comparator)
        if let keepID, let index = photos.firstIndex(where: { $0.id == keepID }) {
            currentIndex = index
        } else {
            currentIndex = 0
        }
        activeSlotIndex = 0
        setupSlotsForMode(comparisonMode)
        prefetchAroundCurrentIndex()
    }

    // MARK: - Library scoring pass

    /// Scores every photo that has no sharpness score yet, off the main actor, publishing in
    /// batches; `scoringProgress` is "done / total". Used by Library Statistics (figures for the
    /// whole library) and by lowest-sharpness-first. Decodes the same 2048 px preview the viewport
    /// scores, and caches nothing, so memory stays bounded however large the library is.
    public func startLibraryScoring() {
        libraryScoringTask?.cancel()
        let total = photos.count
        let targets: [(UUID, URL)] = photos.filter { $0.scores == nil }.map { ($0.id, $0.url) }
        guard !targets.isEmpty else {
            scoringProgress = nil
            finishScoringIfNeeded()
            return
        }
        // On a cloud folder this decodes, and so downloads, every online-only photo: only after
        // the user said yes (REQ-MAC-CLOUD.01). The view asks; nothing is read until it answers.
        if isRemoteFolder, !fullPassConsented {
            scoringProgress = nil
            if pendingDownloadConfirmation == nil {
                pendingDownloadConfirmation = PendingDownloadConfirmation(
                    purpose: .statistics, estimatedBytes: estimatedFullPassDownloadBytes)
            }
            return
        }
        scoringProgress = ScoringProgress(done: total - targets.count, total: total)
        libraryScoringTask = Task.detached(priority: .utility) { [weak self] in
            var batch: [(UUID, QualityScores)] = []
            var handled = 0
            for (id, url) in targets {
                if Task.isCancelled { return }
                if let image = ThumbnailLoader.decodeThumbnail(from: url, maxPixelSize: ThumbnailTier.preview.rawValue) {
                    batch.append((id, QualityScoringService.scoreTechnical(cgImage: image)))
                }
                handled += 1
                if handled % 8 == 0 {
                    let flush = batch
                    batch.removeAll(keepingCapacity: true)
                    await self?.applyScoreBatch(flush, handled: handled)
                }
            }
            if Task.isCancelled { return }
            await self?.applyScoreBatch(batch, handled: handled, finished: true)
        }
    }

    /// Stops the scoring pass (Library Statistics closed) unless a sort is waiting for it.
    public func cancelLibraryScoring() {
        guard !sortAfterScoring else { return }
        libraryScoringTask?.cancel()
        libraryScoringTask = nil
        scoringProgress = nil
    }

    private func applyScoreBatch(_ batch: [(UUID, QualityScores)], handled: Int, finished: Bool = false) {
        if !batch.isEmpty {
            let updates = Dictionary(batch, uniquingKeysWith: { first, _ in first })
            var updated = photos
            for index in updated.indices where updated[index].scores == nil {
                if let scores = updates[updated[index].id] { updated[index].scores = scores }
            }
            photos = updated
        }
        let total = photos.count
        let remaining = photos.filter { $0.scores == nil }.count
        if finished {
            scoringProgress = nil
            libraryScoringTask = nil
            finishScoringIfNeeded()
        } else {
            scoringProgress = ScoringProgress(done: total - remaining, total: total)
        }
    }

    private func finishScoringIfNeeded() {
        guard sortAfterScoring else { return }
        sortAfterScoring = false
        applySortOrder(.lowestSharpnessFirst)
    }

    // MARK: - Go to Photo

    public func presentGoToPhoto() {
        guard !photos.isEmpty else { return }
        isGoToPhotoPresented = true
    }

    /// Jumps to the photo `query` names (see `GoToPhotoResolver`). Returns false when nothing matches.
    @discardableResult
    public func goToPhoto(matching query: String) -> Bool {
        guard case .index(let index) = GoToPhotoResolver.resolve(query: query, photos: photos) else { return false }
        selectPhoto(at: index)
        return true
    }

    /// Synchronizes file deletion from secondary tools into the active culling candidate list.
    /// Duplicate Finder's trash: the same actor, companions and undo stack as a cull, so Undo
    /// (`⌘Z`) in the workspace brings the file and its companions back.
    public func trashDuplicate(at url: URL) async throws {
        let canonical = url.standardizedFileURL.resolvingSymlinksInPath()
        let index = photos.firstIndex { $0.url == url || $0.url.standardizedFileURL.resolvingSymlinksInPath() == canonical }
        let previous = index.map { photos[$0].status } ?? .candidate
        let job = CullingJob(
            photoID: index.map { photos[$0].id } ?? UUID(), actionType: .trash, primaryURL: url,
            rootURL: rootDirectoryURL ?? url.deletingLastPathComponent(),
            destinationFolderName: userDefaults.cullingDestinationFolderName)
        guard let record = try await cullingActor.enqueue(job) else { return }
        undoStack.append(CullingExecutionRecord(
            jobID: record.jobID, photoID: record.photoID, actionType: record.actionType,
            originalPrimaryURL: record.originalPrimaryURL, affectedURLs: record.affectedURLs,
            trashedPairs: record.trashedPairs, previousStatus: previous, timestamp: record.timestamp))
        markPhotoAsTrashed(url: url)
    }

    public func markPhotoAsTrashed(url: URL) {
        let canonical = url.standardizedFileURL.resolvingSymlinksInPath()
        let normPath = Self.normalizedPathString(url)
        if let index = photos.firstIndex(where: {
            $0.url == url
                || $0.url.standardizedFileURL.resolvingSymlinksInPath() == canonical
                || Self.normalizedPathString($0.url) == normPath
        }) {
            photos[index].status = .trashed
        }
    }

    private static func normalizedPathString(_ u: URL) -> String {
        let p = u.path
        if p.hasPrefix("/private/") {
            return String(p.dropFirst(8))
        }
        return p
    }

    /// Safely updates quality scores for a specific photo by its unique identifier,
    /// avoiding index-based ABA race conditions during rapid navigation.
    public func updatePhotoScores(photoID: UUID, scores: QualityScores) {
        if let index = photos.firstIndex(where: { $0.id == photoID }) {
            photos[index].scores = scores
        }
    }

    /// Safely updates EXIF metadata for a specific photo by its unique identifier.
    public func updatePhotoExif(photoID: UUID, exif: ExifData) {
        if let index = photos.firstIndex(where: { $0.id == photoID }) {
            photos[index].exif = exif
        }
    }

    // MARK: - Internal Dependencies
    private let directoryScanner: DirectoryScanning
    private let thumbnailLoader: ThumbnailLoader
    private let thumbnailCache: ThumbnailCache
    private let cullingActor: AsyncCullingActor
    let userDefaults: UserDefaults
    private let cloudInspector: CloudResourceInspecting
    private var undoStack: [CullingExecutionRecord] = []
    /// Undo stacks of folders that are no longer open, keyed by standardized root URL. A finished
    /// operation's undo record is a durable outcome and must survive a folder switch.
    private var parkedUndoStacks: [URL: [CullingExecutionRecord]] = [:]
    /// Parked folders, oldest first, for eviction.
    private var parkedUndoOrder: [URL] = []
    public static let maxParkedUndoFolders = 8

    private static func folderKey(_ url: URL?) -> URL? {
        url?.standardizedFileURL
    }

    private func isCurrentFolder(_ key: URL?) -> Bool {
        Self.folderKey(rootDirectoryURL) == key
    }

    private func parkUndoStack(for key: URL?) {
        guard let key, !undoStack.isEmpty else { return }
        parkedUndoOrder.removeAll { $0 == key }
        parkedUndoStacks[key] = undoStack
        parkedUndoOrder.append(key)
        while parkedUndoOrder.count > Self.maxParkedUndoFolders {
            let oldest = parkedUndoOrder.removeFirst()
            parkedUndoStacks[oldest] = nil
        }
    }

    private func restoreUndoStack(for key: URL?) {
        guard let key, let stack = parkedUndoStacks.removeValue(forKey: key) else {
            undoStack = []
            return
        }
        parkedUndoOrder.removeAll { $0 == key }
        undoStack = stack
    }

    /// Mutates the undo stack of `key`'s folder: the live stack when it is still open, else the parked one.
    private func mutateUndoStack(for key: URL?, _ body: (inout [CullingExecutionRecord]) -> Void) {
        if isCurrentFolder(key) {
            body(&undoStack)
        } else if let key, var stack = parkedUndoStacks[key] {
            body(&stack)
            if stack.isEmpty {
                parkedUndoStacks[key] = nil
                parkedUndoOrder.removeAll { $0 == key }
            } else {
                parkedUndoStacks[key] = stack
            }
        } else if let key {
            var stack: [CullingExecutionRecord] = []
            body(&stack)
            if !stack.isEmpty {
                parkedUndoStacks[key] = stack
                parkedUndoOrder.append(key)
                while parkedUndoOrder.count > Self.maxParkedUndoFolders {
                    parkedUndoStacks[parkedUndoOrder.removeFirst()] = nil
                }
            }
        }
    }

    /// Test hook: number of folders with a parked undo stack.
    var parkedUndoFolderCount: Int { parkedUndoStacks.count }
    @Published private var inFlightJobIDs: Set<UUID> = []
    private var toastDismissTask: Task<Void, Never>?
    private var libraryScoringTask: Task<Void, Never>?
    private var sortAfterScoring = false
    /// True once the user moved to another photo since the folder opened (decides where the
    /// settle re-order leaves the view).
    var userHasNavigated = false
    private var scanTask: Task<Void, Never>?
    private var metadataTask: Task<Void, Never>?

    public var hasActivePhoto: Bool {
        photos.indices.contains(currentIndex)
    }

    public var currentPhoto: PhotoItem? {
        hasActivePhoto ? photos[currentIndex] : nil
    }

    /// Previous photo in sequence relative to currentIndex (index - 1), or nil at beginning of library.
    public var previousPhoto: PhotoItem? {
        guard currentIndex > 0, photos.indices.contains(currentIndex - 1) else { return nil }
        return photos[currentIndex - 1]
    }

    /// Next photo in sequence relative to currentIndex (index + 1), or nil at end of library.
    public var nextPhoto: PhotoItem? {
        guard currentIndex < photos.count - 1, photos.indices.contains(currentIndex + 1) else { return nil }
        return photos[currentIndex + 1]
    }

    public var comparisonSlotPhotos: [PhotoItem] {
        comparisonSlotIndices.compactMap { idx in
            photos.indices.contains(idx) ? photos[idx] : nil
        }
    }

    /// Cancels any active folder scanning operation and resets loading state.
    public func cancelLoading() {
        scanTask?.cancel()
        scanTask = nil
        metadataTask?.cancel()
        metadataTask = nil
        isLoading = false
    }

    public init(
        directoryScanner: DirectoryScanning = DirectoryScanner.shared,
        thumbnailLoader: ThumbnailLoader = ThumbnailLoader.shared,
        thumbnailCache: ThumbnailCache = ThumbnailCache.shared,
        cullingActor: AsyncCullingActor = AsyncCullingActor(),
        userDefaults: UserDefaults = .standard,
        cloudInspector: CloudResourceInspecting = SystemCloudFolderInspector()
    ) {
        self.directoryScanner = directoryScanner
        self.thumbnailLoader = thumbnailLoader
        self.thumbnailCache = thumbnailCache
        self.cullingActor = cullingActor
        self.userDefaults = userDefaults
        self.cloudInspector = cloudInspector
        self.autoAdvance = userDefaults.autoAdvance
        self.lastStoredAutoAdvance = userDefaults.autoAdvance
        self.autoAdvanceObserver = NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification, object: userDefaults, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                // Only a change of the stored value (Settings) moves the live one.
                let stored = self.userDefaults.autoAdvance
                if stored != self.lastStoredAutoAdvance {
                    self.lastStoredAutoAdvance = stored
                    self.autoAdvance = stored
                }
            }
        }
        self.sortOrder = userDefaults.workspaceSortOrder
        self.recentFolders = RecentFolders(defaults: userDefaults).folders
    }

    // MARK: - Folder Loading & Ingestion

    /// Opens an NSOpenPanel to let the user select a photo directory or mounted SD card.
    public func presentFolderPicker() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.canCreateDirectories = false
        panel.prompt = "Open"
        panel.title = "Select Photo Folder or SD Card"

        if panel.runModal() == .OK, let selectedURL = panel.url {
            loadFolder(at: selectedURL)
        }
    }

    /// Handles drag-and-drop file URLs.
    public func handleDroppedURLs(_ urls: [URL]) -> Bool {
        guard let folderURL = urls.first(where: { (try? $0.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true }) else {
            return false
        }
        loadFolder(at: folderURL)
        return true
    }

    /// Size of the first streamed batch: one photograph, so the canvas renders as soon as the
    /// first directory listing returns. Subsequent batches ramp ×4 up to `scanBatchSize`.
    public static let firstScanBatchSize = 1
    public static let scanBatchSize = 30

    /// Loads photos from the specified directory via progressive streaming.
    public func loadFolder(at url: URL) {
        scanTask?.cancel()
        scanTask = nil
        metadataTask?.cancel()
        metadataTask = nil

        let previousKey = Self.folderKey(self.rootDirectoryURL)
        let newKey = Self.folderKey(url)
        if previousKey != newKey {
            parkUndoStack(for: previousKey)
            restoreUndoStack(for: newKey)
        }
        self.rootDirectoryURL = url
        noteFolderOpened(url)
        let remote = cloudInspector.isRemoteOrCloudFolder(url)
        self.isRemoteFolder = remote
        self.cloudBadgeText = remote ? "Cloud Folder" : nil
        self.requiresFullPassConfirmation = remote
        self.fullPassConsented = false
        self.pendingDownloadConfirmation = nil
        self.pendingPermanentDelete = nil
        self.decisionLog.removeAll()
        self.inFlightJobIDs.removeAll()
        libraryScoringTask?.cancel()
        libraryScoringTask = nil
        scoringProgress = nil
        sortAfterScoring = false
        userHasNavigated = false
        self.photos = []
        self.currentIndex = 0
        self.activeSlotIndex = 0
        self.zoomState = .fit
        self.isLoading = true

        scanTask = Task { [weak self, directoryScanner] in
            var isFirstBatch = true
            for await batch in directoryScanner.scanStream(
                at: url,
                recursive: true,
                batchSize: Self.scanBatchSize,
                firstBatchSize: Self.firstScanBatchSize
            ) {
                if Task.isCancelled { break }
                guard let self else { break }
                let previousCount = self.photos.count
                self.photos.append(contentsOf: batch)
                if isFirstBatch, !self.photos.isEmpty {
                    isFirstBatch = false
                    self.currentIndex = 0
                    self.setupSlotsForMode(self.comparisonMode)
                } else if previousCount < self.comparisonMode.rawValue {
                    // Early batches could not fill every comparison slot yet.
                    self.setupSlotsForMode(self.comparisonMode)
                    self.prefetchAroundCurrentIndex()
                } else if previousCount <= self.currentIndex + 3 {
                    // The comparison neighbours / prefetch window just became available.
                    self.syncSlotsToCurrentIndex()
                    self.prefetchAroundCurrentIndex()
                }
            }
            guard let self, !Task.isCancelled else { return }
            self.reorderAfterScan()
            self.restoreRememberedPhoto()
            self.isLoading = false
            if !self.isRemoteFolder {
                self.startMetadataEnrichment()
            }
        }
    }

    /// Resolves EXIF for every photo still missing it, off the main actor, publishing in
    /// batches (one array assignment per batch) so Library Statistics and the HUD fill in
    /// without per-photo re-render storms. Cancelled on folder change.
    private func startMetadataEnrichment() {
        metadataTask?.cancel()
        let targets: [(UUID, URL)] = photos.filter { $0.exif == nil }.map { ($0.id, $0.url) }
        guard !targets.isEmpty else { return }

        metadataTask = Task.detached(priority: .utility) { [weak self] in
            var pending: [UUID: ExifData] = [:]
            for (id, url) in targets {
                if Task.isCancelled { return }
                if let exif = Self.readMetadataWithCompanionFallback(for: url) {
                    pending[id] = exif
                }
                if pending.count >= 64 {
                    let flush = pending
                    pending.removeAll(keepingCapacity: true)
                    await self?.applyMetadata(flush)
                }
            }
            if !pending.isEmpty, !Task.isCancelled {
                await self?.applyMetadata(pending)
            }
        }
    }

    /// Reads EXIF from the primary file, falling back to companions (e.g. the JPEG of a
    /// RAW+JPEG pair) when the primary yields none. Runs off the main actor.
    public nonisolated static func readMetadataWithCompanionFallback(for url: URL) -> ExifData? {
        if let exif = ImageMetadataReader.readMetadata(from: url), !exif.isFallback {
            return exif
        }
        let companions = FileCullingManager().findCompanionFiles(for: url)
        for companion in companions where companion != url {
            if let exif = ImageMetadataReader.readMetadata(from: companion), !exif.isFallback {
                return exif
            }
        }
        return ImageMetadataReader.readMetadata(from: url)
    }

    private func applyMetadata(_ updates: [UUID: ExifData]) {
        var updated = photos
        var changed = false
        for index in updated.indices where updated[index].exif == nil {
            if let exif = updates[updated[index].id] {
                updated[index].exif = exif
                changed = true
            }
        }
        if changed {
            photos = updated
        }
    }

    /// Convenience alias for scanning a directory.
    public func scanDirectory(at url: URL) {
        loadFolder(at: url)
    }

    /// Sets photos directly for testing or programmatic injection.
    public func setPhotos(_ items: [PhotoItem], rootURL: URL? = nil) {
        self.rootDirectoryURL = rootURL
        let remote = rootURL.map { cloudInspector.isRemoteOrCloudFolder($0) } ?? false
        self.isRemoteFolder = remote
        self.cloudBadgeText = remote ? "Cloud Folder" : nil
        self.requiresFullPassConfirmation = remote && items.contains(where: { $0.exif == nil || $0.scores == nil || cloudInspector.isOnlineOnlyItem($0.url) })
        self.pendingPermanentDelete = nil
        self.fullPassConsented = false
        self.pendingDownloadConfirmation = nil
        self.decisionLog.removeAll()
        self.inFlightJobIDs.removeAll()
        self.photos = items
        self.currentIndex = 0
        self.activeSlotIndex = 0
        setupSlotsForMode(comparisonMode)
        prefetchAroundCurrentIndex()
    }

    // MARK: - Navigation

    public func navigateToNext() {
        dismissStickyToast()
        guard !photos.isEmpty else { return }
        switch comparisonMode {
        case .single, .triplet:
            if currentIndex < photos.count - 1 {
                userHasNavigated = true
                currentIndex += 1
                checkSharpnessStopThresholdOnAdvance()
            }
        case .sideBySide:
            advanceActiveSlotToNextCandidate()
        }
    }

    public func navigateToPrevious() {
        dismissStickyToast()
        guard !photos.isEmpty else { return }
        switch comparisonMode {
        case .single, .triplet:
            if currentIndex > 0 {
                userHasNavigated = true
                currentIndex -= 1
            }
        case .sideBySide:
            stepActiveSlotToPreviousCandidate()
        }
    }

    public func selectPhoto(at index: Int) {
        dismissStickyToast()
        guard photos.indices.contains(index) else { return }
        if index != currentIndex { userHasNavigated = true }
        switch comparisonMode {
        case .single, .triplet:
            currentIndex = index
        case .sideBySide:
            if comparisonSlotIndices.indices.contains(activeSlotIndex) {
                comparisonSlotIndices[activeSlotIndex] = index
            }
            currentIndex = index
        }
    }

    public func setComparisonMode(_ mode: ComparisonMode) {
        guard comparisonMode != mode else { return }
        comparisonMode = mode
        setupSlotsForMode(mode)
        showToast(
            icon: mode.systemImage,
            title: "\(mode.title) View",
            subtitle: nil
        )
    }

    /// Tab / View > Switch Photo: switches the active photo in 2-Up only. In 1-Up and 3-Up there is
    /// no second slot to switch to (3-Up's slots never read `activeSlotIndex`), so nothing happens and
    /// the caller leaves the key unconsumed. Returns whether it acted.
    @discardableResult
    public func advanceComparisonSlotFocus() -> Bool {
        guard comparisonMode == .sideBySide else { return false }
        activeSlotIndex = (activeSlotIndex + 1) % max(1, comparisonSlotIndices.count)
        return true
    }

    /// Whether View > Switch Photo (Tab) does anything now.
    public var canSwitchPhoto: Bool { comparisonMode == .sideBySide }

    public func setActiveSlot(_ index: Int) {
        guard index >= 0, index < comparisonMode.rawValue else { return }
        activeSlotIndex = index
        switch comparisonMode {
        case .single:
            break
        case .sideBySide:
            if comparisonSlotIndices.indices.contains(index) {
                currentIndex = comparisonSlotIndices[index]
            }
        case .triplet:
            if comparisonSlotIndices.indices.contains(index) {
                currentIndex = comparisonSlotIndices[index]
            }
        }
    }

    /// Space / "100%" button / double-click: Fit → 100 %; any zoomed state → Fit.
    public func toggleZoom() {
        if zoomState.isZoomed {
            resetZoomToFit()
        } else {
            zoomState = .oneToOne
            showToast(
                icon: "viewfinder.circle.fill",
                title: "100% Zoom (1:1)",
                subtitle: "Esc or Space returns to Fit"
            )
        }
    }

    /// Escape / "Fit" pill: leaves any zoom (100 % or pinch) and returns to Fit.
    /// Returns `true` when a zoom was actually exited.
    @discardableResult
    public func resetZoomToFit() -> Bool {
        guard zoomState.isZoomed else { return false }
        zoomState = .fit
        showToast(
            icon: "arrow.up.left.and.down.right.and.arrow.up.right.and.down.left",
            title: "Fit to Window",
            subtitle: nil
        )
        return true
    }

    /// Trackpad pinch ended at `scale` (relative to Fit). Small residual scales snap to Fit.
    public func setPinchMagnification(_ scale: Double) {
        if scale <= ZoomState.snapToFitThreshold {
            zoomState = .fit
        } else {
            zoomState = .magnified(min(scale, 8.0))
        }
    }

    public func toggleZoomSync() {
        isZoomSynced.toggle()
        showToast(
            icon: isZoomSynced ? "link" : "link.badge.slash",
            title: isZoomSynced ? "Pan Linked" : "Pan Independent",
            subtitle: nil
        )
    }

    // MARK: - Zero-Latency Culling (< 1.0ms Optimistic State Mutation)

    public func cullCurrentPhoto(action: CullingActionType) {
        guard !photos.isEmpty else { return }
        let targetIndex: Int
        if comparisonMode == .sideBySide, comparisonSlotIndices.indices.contains(activeSlotIndex) {
            targetIndex = comparisonSlotIndices[activeSlotIndex]
        } else {
            guard photos.indices.contains(currentIndex) else { return }
            targetIndex = currentIndex
        }
        guard photos.indices.contains(targetIndex) else { return }
        let photo = photos[targetIndex]
        guard let sourceRootURL = rootDirectoryURL ?? photo.url.deletingLastPathComponent() as URL? else {
            return
        }
        // The same resolution the Settings text shows (CullingDestinationResolver). A custom folder
        // that is gone or unwritable refuses the Move/Copy: it must never silently create that path
        // (an unplugged SSD's mount point would otherwise be recreated on the system volume).
        let destination = CullingDestinationResolver.resolve(defaults: userDefaults, openFolderURL: sourceRootURL)
        let effectiveRootURL: URL
        if action == .move || action == .copy {
            if destination.problem == .openedFolderIsSelection {
                showToast(
                    icon: "exclamationmark.triangle.fill",
                    title: "This is the Selection folder",
                    subtitle: "Move and Copy are off here"
                )
                return
            }
            guard destination.isUsable, let base = destination.baseFolderURL else {
                showToast(
                    icon: "exclamationmark.triangle.fill",
                    title: "Custom folder not available",
                    subtitle: "Choose another in Settings (\u{2318},)"
                )
                return
            }
            effectiveRootURL = base
        } else {
            effectiveRootURL = sourceRootURL
        }

        let previousStatus = photo.status

        // 1. Instant Optimistic State Mutation (< 1.0ms)
        let newStatus: PhotoStatus
        let toastTitle: String
        let toastIcon: String

        let destinationFolder = destination.folderName

        switch action {
        case .move:
            newStatus = .selected
            toastTitle = "Moved to \(destinationFolder)"
            toastIcon = "checkmark.circle.fill"
        case .copy:
            newStatus = .copied
            toastTitle = "Copied to \(destinationFolder)"
            toastIcon = "doc.on.doc.fill"
        case .trash:
            newStatus = .trashed
            toastTitle = "Moved to Trash"
            toastIcon = "trash.fill"
        }

        photos[targetIndex].status = newStatus

        // Record decision log entry (U-35)
        let logEntry = CullingDecisionLogEntry(
            timestamp: Date(),
            filename: photo.filename,
            action: action,
            focusScore: photo.scores?.focusScore,
            iso: photo.exif?.iso.map { Int($0) },
            shutterSpeed: photo.exif?.shutterSpeed,
            aperture: photo.exif?.aperture,
            focalLength: photo.exif?.focalLength
        )
        decisionLog.append(logEntry)

        // 2. Immediate Toast HUD. Not for a Trash on a cloud folder: the provider may refuse it, and a
        // "Moved to Trash" that then reverts is false; the confirmation or the result says it.
        if !(action == .trash && isRemoteFolder) {
            showToast(
                icon: toastIcon,
                title: toastTitle,
                subtitle: photo.filename,
                actionType: action
            )
        }

        // 3. Auto-Advance (Slides candidate sequence forward)
        if autoAdvance {
            if comparisonMode == .sideBySide {
                advanceActiveSlotToNextCandidate()
            } else {
                navigateToNext()
            }
        }
        endStateDismissed = false  // a decision brings the end card / notice back (navigation above dismissed it)

        // 4. Background Serial Execution on AsyncCullingActor
        let job = CullingJob(
            photoID: photo.id,
            actionType: action,
            primaryURL: photo.url,
            rootURL: effectiveRootURL,
            destinationFolderName: destinationFolder
        )

        // Optimistic undo record: affectedURLs is empty because no destination files exist yet
        let optimisticRecord = CullingExecutionRecord(
            jobID: job.id,
            photoID: photo.id,
            actionType: action,
            originalPrimaryURL: photo.url,
            affectedURLs: [],
            previousStatus: previousStatus
        )
        let jobFolderKey = Self.folderKey(sourceRootURL)
        undoStack.append(optimisticRecord)
        inFlightJobIDs.insert(job.id)

        Task { [weak self, cullingActor] in
            do {
                guard let record = try await cullingActor.enqueue(job) else {
                    await MainActor.run {
                        self?.inFlightJobIDs.remove(job.id)
                    }
                    return
                }
                await MainActor.run {
                    self?.inFlightJobIDs.remove(job.id)
                    let stillHere = self?.isCurrentFolder(jobFolderKey) ?? false
                    if stillHere {
                        // A collision-renamed file says so; otherwise a photo that took companions
                        // with it (RAW + JPEG + XMP) says how many files moved, copied or were trashed.
                        let title = CullingFeedback.transferTitle(
                            action: action,
                            destinationFolder: destinationFolder,
                            originalPrimaryURL: record.originalPrimaryURL,
                            destinationURLs: record.affectedURLs
                        ) ?? CullingFeedback.fileCountTitle(action: action, fileCount: record.affectedURLs.count)
                        let remoteTrash = action == .trash && (self?.isRemoteFolder ?? false)
                        if let title = title ?? (remoteTrash ? "Moved to Trash" : nil) {
                            self?.showToast(
                                icon: action == .trash ? "trash.fill" : (action == .move ? "checkmark.circle.fill" : "doc.on.doc.fill"),
                                title: title,
                                subtitle: photo.filename,
                                actionType: action
                            )
                        }
                    }
                    self?.mutateUndoStack(for: jobFolderKey) { stack in
                        if let idx = stack.firstIndex(where: { $0.jobID == record.jobID }) {
                            stack[idx] = CullingExecutionRecord(
                                jobID: record.jobID,
                                photoID: record.photoID,
                                actionType: record.actionType,
                                originalPrimaryURL: record.originalPrimaryURL,
                                affectedURLs: record.affectedURLs,
                                trashedPairs: record.trashedPairs,
                                previousStatus: stack[idx].previousStatus,
                                timestamp: record.timestamp
                            )
                        }
                    }
                }
            } catch is CancellationError {
                await MainActor.run {
                    self?.inFlightJobIDs.remove(job.id)
                }
            } catch let error as FileCullingError {
                let partialMoveRecord: CullingExecutionRecord?
                if case .partialMove = error {
                    partialMoveRecord = await cullingActor.completedRecord(for: job.id)
                } else {
                    partialMoveRecord = nil
                }
                await MainActor.run {
                    self?.inFlightJobIDs.remove(job.id)
                    if case .partialMove = error, let partialMoveRecord {
                        // Verified copy in the destination, original still where it was: keep the
                        // record so Undo can delete the copy, put the photo back in the queue and say so.
                        self?.mutateUndoStack(for: jobFolderKey) { stack in
                            if let idx = stack.firstIndex(where: { $0.jobID == job.id }) {
                                stack[idx] = CullingExecutionRecord(
                                    jobID: partialMoveRecord.jobID,
                                    photoID: partialMoveRecord.photoID,
                                    actionType: partialMoveRecord.actionType,
                                    originalPrimaryURL: partialMoveRecord.originalPrimaryURL,
                                    affectedURLs: partialMoveRecord.affectedURLs,
                                    unremovedSourceURLs: partialMoveRecord.unremovedSourceURLs,
                                    previousStatus: stack[idx].previousStatus,
                                    timestamp: partialMoveRecord.timestamp
                                )
                            }
                        }
                        // Presentation only when the job's folder is still open; photo resolved by id.
                        if let self, self.isCurrentFolder(jobFolderKey),
                           let pIdx = self.photos.firstIndex(where: { $0.id == photo.id }) {
                            self.photos[pIdx].status = previousStatus
                            var rollbackFailed = false
                            if case .partialMove(_, _, _, let stranded) = error { rollbackFailed = !stranded.isEmpty }
                            self.showToast(
                                icon: "exclamationmark.triangle.fill",
                                title: CullingFeedback.partialMoveTitle(
                                    name: photo.filename,
                                    destinationFolder: destinationFolder,
                                    rollbackFailed: rollbackFailed
                                ),
                                subtitle: photo.filename
                            )
                            NSSound.beep()
                        }
                    } else if case .partialTrashRollbackFailed(_, _, let remainingPairs) = error, !remainingPairs.isEmpty {
                        self?.mutateUndoStack(for: jobFolderKey) { stack in
                            if let idx = stack.firstIndex(where: { $0.jobID == job.id }) {
                                stack[idx] = CullingExecutionRecord(
                                    jobID: job.id,
                                    photoID: photo.id,
                                    actionType: action,
                                    originalPrimaryURL: photo.url,
                                    affectedURLs: remainingPairs.map(\.trashedURL),
                                    trashedPairs: remainingPairs,
                                    previousStatus: stack[idx].previousStatus
                                )
                            }
                        }
                        if let self, self.isCurrentFolder(jobFolderKey),
                           let pIdx = self.photos.firstIndex(where: { $0.id == photo.id }) {
                            self.rollback(photoIndex: pIdx, previousStatus: previousStatus, error: error)
                        }
                    } else {
                        self?.mutateUndoStack(for: jobFolderKey) { $0.removeAll(where: { $0.jobID == job.id }) }
                        if let self, self.isCurrentFolder(jobFolderKey) {
                            self.handleCullingFailure(
                                action: action,
                                photo: photo,
                                photoIndex: self.photos.firstIndex(where: { $0.id == photo.id }) ?? targetIndex,
                                previousStatus: previousStatus,
                                error: error
                            )
                        }
                    }
                }
            } catch {
                await MainActor.run {
                    self?.inFlightJobIDs.remove(job.id)
                    self?.mutateUndoStack(for: jobFolderKey) { $0.removeAll(where: { $0.jobID == job.id }) }
                    if let self, self.isCurrentFolder(jobFolderKey) {
                        self.handleCullingFailure(
                            action: action,
                            photo: photo,
                            photoIndex: self.photos.firstIndex(where: { $0.id == photo.id }) ?? targetIndex,
                            previousStatus: previousStatus,
                            error: error
                        )
                    }
                }
            }
        }
    }

    /// Convenience alias: Move current photo to Selection.
    public func selectCurrentPhoto() {
        cullCurrentPhoto(action: .move)
    }

    /// Convenience alias: Copy current photo to Selection.
    public func copyCurrentPhoto() {
        cullCurrentPhoto(action: .copy)
    }

    /// Convenience alias: Move current photo to Trash.
    public func trashCurrentPhoto() {
        cullCurrentPhoto(action: .trash)
    }

    // MARK: - Undo Support

    public func undoLastAction() {
        guard let record = undoStack.popLast() else { return }
        let undoFolderKey = Self.folderKey(rootDirectoryURL)

        // Find photo in list
        if let index = photos.firstIndex(where: { $0.id == record.photoID }) {
            // 1. Immediate UI Restoration (< 1ms)
            photos[index].status = record.previousStatus
            selectPhoto(at: index)
        }

        showToast(
            icon: "arrow.uturn.backward.circle.fill",
            title: "Action Undone",
            subtitle: record.originalPrimaryURL.lastPathComponent
        )

        // 2. Background Reversal and in-flight cancellation on Actor
        Task { [weak self, cullingActor] in
            do {
                let restored = try await cullingActor.cancelOrUndo(jobID: record.jobID, fallbackRecord: record)
                if let title = CullingFeedback.restoredTitle(
                    originalPrimaryURL: record.originalPrimaryURL,
                    restoredURLs: restored
                ) ?? CullingFeedback.restoredCountTitle(fileCount: restored.count) {
                    await MainActor.run {
                        self?.showToast(
                            icon: "arrow.uturn.backward.circle.fill",
                            title: title,
                            subtitle: record.originalPrimaryURL.lastPathComponent
                        )
                    }
                }
            } catch {
                let remainingRecord = await cullingActor.completedRecord(for: record.jobID)
                await MainActor.run {
                    if let remainingRecord {
                        self?.mutateUndoStack(for: undoFolderKey) { $0.append(remainingRecord) }
                    }
                    self?.showToast(
                        icon: "exclamationmark.triangle.fill",
                        title: "Undo Failed",
                        subtitle: error.localizedDescription
                    )
                    NSSound.beep()
                }
            }
        }
    }

    /// Convenience alias: Undo last action.
    public func undo() {
        undoLastAction()
    }

    // MARK: - Private Helpers

    private func checkSharpnessStopThresholdOnAdvance() {
        let stored = userDefaults.sharpnessStopThreshold
        guard sortOrder == .lowestSharpnessFirst,
              let threshold = sharpnessStopThreshold ?? (stored > 0 ? stored : nil),
              let score = currentPhoto?.scores?.focusScore,
              score >= threshold else {
            return
        }
        showToast(
            icon: "scope",
            title: "Sharpness \(Int(threshold)) reached",
            subtitle: currentPhoto?.filename,
            isPersistent: true
        )
    }

    /// Drops a persistent banner (Esc or the next navigation key). Returns whether one was showing.
    @discardableResult
    public func dismissStickyToast() -> Bool {
        let hadEndState = dismissEndState()
        guard currentToast?.isPersistent == true else { return hadEndState }
        toastDismissTask?.cancel()
        currentToast = nil
        return true
    }

    private func handleCullingFailure(
        action: CullingActionType,
        photo: PhotoItem,
        photoIndex: Int,
        previousStatus: PhotoStatus,
        error: Error
    ) {
        if action == .trash && isRemoteFolder {
            if let idx = photos.firstIndex(where: { $0.id == photo.id }) {
                photos[idx].status = previousStatus
            } else if photos.indices.contains(photoIndex) {
                photos[photoIndex].status = previousStatus
            }
            let companions = FileCullingManager().findCompanionFiles(for: photo.url)
            let isShared = cloudInspector.isSharedItem(photo.url)
                || (rootDirectoryURL.map { cloudInspector.isSharedItem($0) } ?? false)
            let message = isShared
                ? "No Trash here. Deleting permanently removes it for everyone sharing this folder."
                : "No Trash here. Syncs to all devices."
            pendingPermanentDelete = PendingPermanentDeleteConfirmation(
                photoID: photo.id,
                urls: companions,
                isShared: isShared,
                message: message
            )
            return
        }
        rollback(photoIndex: photoIndex, previousStatus: previousStatus, error: error)
    }

    private func advanceActiveSlotToNextCandidate() {
        guard comparisonMode == .sideBySide else {
            navigateToNext()
            return
        }
        guard comparisonSlotIndices.indices.contains(activeSlotIndex) else { return }
        let currentSlotValue = comparisonSlotIndices[activeSlotIndex]
        let otherSlots = Set(comparisonSlotIndices.enumerated().filter { $0.offset != activeSlotIndex }.map { $0.element })

        // Find next candidate with index > currentSlotValue
        if let nextCandidate = (currentSlotValue + 1..<photos.count).first(where: { idx in
            photos[idx].status == .candidate && !otherSlots.contains(idx)
        }) {
            comparisonSlotIndices[activeSlotIndex] = nextCandidate
            currentIndex = nextCandidate
            checkSharpnessStopThresholdOnAdvance()
        } else if let anyCandidate = photos.indices.first(where: { idx in
            photos[idx].status == .candidate && !otherSlots.contains(idx) && idx != currentSlotValue
        }) {
            comparisonSlotIndices[activeSlotIndex] = anyCandidate
            currentIndex = anyCandidate
            checkSharpnessStopThresholdOnAdvance()
        }
    }

    private func stepActiveSlotToPreviousCandidate() {
        guard comparisonMode == .sideBySide else {
            navigateToPrevious()
            return
        }
        guard comparisonSlotIndices.indices.contains(activeSlotIndex) else { return }
        let currentSlotValue = comparisonSlotIndices[activeSlotIndex]
        let otherSlots = Set(comparisonSlotIndices.enumerated().filter { $0.offset != activeSlotIndex }.map { $0.element })

        // Find previous candidate with index < currentSlotValue
        if let prevCandidate = (0..<currentSlotValue).reversed().first(where: { idx in
            photos[idx].status == .candidate && !otherSlots.contains(idx)
        }) {
            comparisonSlotIndices[activeSlotIndex] = prevCandidate
            currentIndex = prevCandidate
        }
    }

    private func setupSlotsForMode(_ mode: ComparisonMode) {
        guard !photos.isEmpty else {
            comparisonSlotIndices = []
            activeSlotIndex = 0
            return
        }
        switch mode {
        case .single:
            comparisonSlotIndices = [currentIndex]
            activeSlotIndex = 0
        case .sideBySide:
            let next = min(photos.count - 1, currentIndex + 1)
            comparisonSlotIndices = [currentIndex, next]
            activeSlotIndex = 0
        case .triplet:
            if photos.count >= 3 {
                if currentIndex == 0 {
                    comparisonSlotIndices = [0, 1, 2]
                    activeSlotIndex = 0
                } else if currentIndex == photos.count - 1 {
                    comparisonSlotIndices = [photos.count - 3, photos.count - 2, photos.count - 1]
                    activeSlotIndex = 2
                } else {
                    comparisonSlotIndices = [currentIndex - 1, currentIndex, currentIndex + 1]
                    activeSlotIndex = 1
                }
            } else {
                let prev = max(0, currentIndex - 1)
                let next = min(photos.count - 1, currentIndex + 1)
                comparisonSlotIndices = [prev, currentIndex, next]
                activeSlotIndex = (currentIndex == 0 ? 0 : 1)
            }
        }
    }

    private func syncSlotsToCurrentIndex() {
        guard !photos.isEmpty else {
            comparisonSlotIndices = []
            return
        }
        switch comparisonMode {
        case .single:
            comparisonSlotIndices = [currentIndex]
            activeSlotIndex = 0
        case .sideBySide:
            if comparisonSlotIndices.count != 2 {
                let next = min(photos.count - 1, currentIndex + 1)
                comparisonSlotIndices = [currentIndex, next]
                activeSlotIndex = 0
            } else if comparisonSlotIndices.indices.contains(activeSlotIndex) {
                comparisonSlotIndices[activeSlotIndex] = currentIndex
            }
        case .triplet:
            if photos.count >= 3 {
                if currentIndex == 0 {
                    comparisonSlotIndices = [0, 1, 2]
                } else if currentIndex == photos.count - 1 {
                    comparisonSlotIndices = [photos.count - 3, photos.count - 2, photos.count - 1]
                } else {
                    comparisonSlotIndices = [currentIndex - 1, currentIndex, currentIndex + 1]
                }
            } else {
                let prev = max(0, currentIndex - 1)
                let next = min(photos.count - 1, currentIndex + 1)
                comparisonSlotIndices = [prev, currentIndex, next]
            }
            if let idx = comparisonSlotIndices.firstIndex(of: currentIndex) {
                activeSlotIndex = idx
            }
        }
    }

    /// The last navigation direction; the prefetch window leans the same way.
    public private(set) var browseDirection: ThumbnailLoader.BrowseDirection = .forward

    private func prefetchAroundCurrentIndex() {
        guard !photos.isEmpty else { return }
        let windowBefore = isRemoteFolder ? 0 : max(1, min(userDefaults.preloadWindowBefore, 5))
        let windowAfter = prefetchRadius
        let direction = browseDirection
        Task { [weak self, thumbnailLoader] in
            guard let self else { return }
            await thumbnailLoader.updatePrefetchWindow(
                items: self.photos,
                currentIndex: self.currentIndex,
                windowBefore: windowBefore,
                windowAfter: windowAfter,
                direction: direction,
                maxPixelSize: ThumbnailTier.preview.rawValue
            )
        }
    }

    private func rollback(photoIndex: Int, previousStatus: PhotoStatus, error: Error) {
        guard photos.indices.contains(photoIndex) else { return }
        photos[photoIndex].status = previousStatus
        showToast(
            icon: "exclamationmark.triangle.fill",
            title: "Action Failed",
            subtitle: error.localizedDescription
        )
        NSSound.beep()
    }

    private func showToast(
        icon: String,
        title: String,
        subtitle: String?,
        actionType: CullingActionType? = nil,
        isPersistent: Bool = false
    ) {
        toastDismissTask?.cancel()
        currentToast = ToastMessage(
            icon: icon, title: title, subtitle: subtitle, actionType: actionType, isPersistent: isPersistent)
        if isPersistent { return }
        toastDismissTask = Task {
            try? await Task.sleep(nanoseconds: 1_200_000_000) // 1.2 seconds
            if !Task.isCancelled {
                currentToast = nil
            }
        }
    }
}

extension Array {
    /// True when no element should come before the one preceding it under `areInIncreasingOrder`.
    fileprivate func isSorted(by areInIncreasingOrder: (Element, Element) -> Bool) -> Bool {
        guard count > 1 else { return true }
        for i in 1..<count where areInIncreasingOrder(self[i], self[i - 1]) { return false }
        return true
    }
}
