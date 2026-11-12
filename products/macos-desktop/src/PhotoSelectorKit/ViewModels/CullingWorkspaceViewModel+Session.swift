import Foundation

/// The beginning and the end of a cull: resume the last folder, File > Open Recent, the place of
/// the Selection folder on the workspace, and the end-of-cull card. Kept out of the view model's
/// main file (size ceiling); every piece of state it needs is declared there.
extension CullingWorkspaceViewModel {

    // MARK: Destination

    /// The configured Selection folder name ("Selection", "Keepers", …) for tooltips and menu items.
    public var destinationName: String {
        AppSettings.sanitizeDestinationFolderName(userDefaults.cullingDestinationFolderName)
    }

    /// Where Move and Copy put photos, resolved from the settings and the opened folder.
    public var resolvedDestination: ResolvedCullingDestination {
        CullingDestinationResolver.resolve(defaults: userDefaults, openFolderURL: rootDirectoryURL)
    }

    /// The same path text Settings shows (REQ-MAC-SETTINGS.02), `~`-abbreviated; nil with no folder open.
    public var destinationPathText: String? {
        CullingDestinationResolver.summary(of: resolvedDestination).selectionPathText
    }

    /// The Selection folder if it exists on disk (nothing has been filed yet otherwise).
    public var existingSelectionFolderURL: URL? {
        guard let url = resolvedDestination.selectionFolderURL else { return nil }
        var isDirectory: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) && isDirectory.boolValue ? url : nil
    }

    /// "Open Selection": the Selection folder becomes the working folder, for a second pass over the keepers.
    @discardableResult
    public func openSelectionFolder() -> Bool {
        guard let url = existingSelectionFolderURL else { return false }
        loadFolder(at: url)
        return true
    }

    // MARK: Undo title

    /// "Undo Move of IMG_0001.CR3"; plain "Undo" when empty (the item is disabled then).
    public var undoMenuTitle: String {
        LensjetCommand.undoMenuTitle(
            action: lastUndoRecord?.actionType, filename: lastUndoRecord?.originalPrimaryURL.lastPathComponent)
    }

    // MARK: Recent folders and resume

    func noteFolderOpened(_ url: URL) {
        let store = RecentFolders(defaults: userDefaults)
        store.record(url)
        recentFolders = store.folders
        endStateDismissed = false
    }

    public func clearRecentFolders() {
        RecentFolders(defaults: userDefaults).clear()
        recentFolders = []
    }

    /// Launch without a folder given: reopens the newest recent folder that still exists, without a
    /// dialog. Returns false (start view) when there is none or a folder is already open.
    @discardableResult
    public func resumeLastFolder() -> Bool {
        guard rootDirectoryURL == nil, let folder = RecentFolders(defaults: userDefaults).lastFolder else { return false }
        loadFolder(at: folder)
        return true
    }

    /// Remembers the photo on screen, by file path, for the next time this folder opens.
    func rememberCurrentPhoto() {
        guard !isLoading, let root = rootDirectoryURL, let photo = currentPhoto else { return }
        RecentFolders(defaults: userDefaults).remember(photo: photo.url, in: root)
    }

    /// After the scan has settled: back to the remembered photo, if it is still in the folder and
    /// the user has not already moved on.
    func restoreRememberedPhoto() {
        guard !userHasNavigated, let root = rootDirectoryURL,
              let remembered = RecentFolders(defaults: userDefaults).rememberedPhoto(in: root) else { return }
        let wanted = remembered.standardizedFileURL.path
        guard let index = photos.firstIndex(where: { $0.url.standardizedFileURL.path == wanted }),
              index != currentIndex else { return }
        selectPhoto(at: index)
        userHasNavigated = false
    }

    // MARK: End of the cull

    /// The end card ("Done"), the "N Remaining" notice on the last photo, or nothing.
    public var endState: CullEndState {
        guard !endStateDismissed, !photos.isEmpty else { return .none }
        var selected = 0, copied = 0, trashed = 0, undecided = 0
        for photo in photos {
            switch photo.status {
            case .selected: selected += 1
            case .copied: copied += 1
            case .trashed: trashed += 1
            default: undecided += 1
            }
        }
        let onLastPhoto = currentIndex == photos.count - 1
        // Cheap exits first: this runs on every navigation key and every render.
        if selected + copied + trashed == 0 || (undecided > 0 && !onLastPhoto) { return .none }
        return CullEnd.state(
            selected: selected, copied: copied, trashed: trashed, undecided: undecided,
            inFlight: cardCullSummary.inFlightCount,
            onLastPhoto: onLastPhoto,
            card: CullEndCard(
                selected: selected, copied: copied, trashed: trashed,
                destinationPath: destinationPathText,
                copiedURLs: photos.filter { $0.status == .copied }.map(\.url),
                selectionIsInsideSource: (selected + copied) > 0 && resolvedDestination.mode == .workingFolder))
    }

    /// Esc or a navigation key drops the card / notice. Returns whether one was showing.
    @discardableResult
    func dismissEndState() -> Bool {
        guard case .none = endState else {
            endStateDismissed = true
            return true
        }
        return false
    }

    /// "Show first": the first photo that has no decision yet.
    public func showFirstUndecided() {
        if let index = photos.firstIndex(where: { $0.status == .candidate }) { selectPhoto(at: index) }
    }
}
