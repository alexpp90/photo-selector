import Foundation

/// Undo and failure bookkeeping of the culling workspace, kept out of the view model's main file
/// (size ceiling): the restored location after an undo, failure toasts and remembered copies.
extension CullingWorkspaceViewModel {

    /// After an undo that had to rename the restored primary (`IMG-2.CR3`, original name in use),
    /// the item points at the file as it now is, so later actions find it (H-LJ-8).
    func adoptRestoredLocation(photoID: UUID, record: CullingExecutionRecord, restored: [URL]) {
        guard let index = photos.firstIndex(where: { $0.id == photoID }),
              let primary = CullingFeedback.primaryURL(matching: record.originalPrimaryURL, in: restored),
              primary.lastPathComponent != photos[index].url.lastPathComponent else { return }
        photos[index].url = primary
    }

    /// A navigation key drops the sharpness banner but never a failure (H-LJ-2).
    func dismissBannerOnNavigation() {
        guard let toast = currentToast, toast.isPersistent, !toast.isFailure else { return }
        toastDismissTask?.cancel()
        currentToast = nil
    }

    func showToast(
        icon: String,
        title: String,
        subtitle: String?,
        actionType: CullingActionType? = nil,
        isPersistent: Bool = false,
        isFailure: Bool = false,
        spoken: String? = nil
    ) {
        toastDismissTask?.cancel()
        let toast = ToastMessage(
            icon: icon, title: title, subtitle: subtitle, actionType: actionType, isPersistent: isPersistent,
            isFailure: isFailure, spoken: spoken)
        currentToast = toast
        // A failure joins the stack and stays; a newer toast of any kind never replaces it there.
        if isFailure { failureToasts.insert(toast, at: 0) }
        if isPersistent || isFailure { return }
        toastDismissTask = Task {
            try? await Task.sleep(nanoseconds: 1_200_000_000) // 1.2 seconds
            if !Task.isCancelled {
                currentToast = nil
            }
        }
    }

    /// Esc: drops the end card, else the newest failure, else a persistent banner. Returns whether
    /// anything was showing.
    @discardableResult
    public func dismissStickyToast() -> Bool {
        let hadEndState = dismissEndState()
        if let newest = failureToasts.first {
            dismissFailure(newest.id)
            return true
        }
        guard currentToast?.isPersistent == true else { return hadEndState }
        toastDismissTask?.cancel()
        currentToast = nil
        return true
    }

    /// The close button of one failure.
    public func dismissFailure(_ id: UUID) {
        failureToasts.removeAll { $0.id == id }
        if currentToast?.id == id { currentToast = nil }
    }

    /// "Dismiss all" of the collapsed stack.
    public func dismissAllFailures() {
        failureToasts.removeAll()
        if currentToast?.isFailure == true { currentToast = nil }
    }

    /// "Sort By" on `S`: the choice of the four orders.
    public func presentSortChoice() {
        guard !photos.isEmpty else { return }
        isSortChoicePresented = true
    }

    /// The Copy reached the disk: remember it, so the photo stays decided after a reopen (D3).
    func rememberCopy(of photo: PhotoItem, in folder: URL) {
        guard let identity = CopiedDecisions.identity(of: photo.url, modified: photo.modificationDate, in: folder) else { return }
        CopiedDecisions(defaults: userDefaults).add(identity, in: folder)
    }

    /// An undone Copy is not a decision any more.
    func forgetCopy(at url: URL, modified: Date?, in folder: URL) {
        guard let identity = CopiedDecisions.identity(of: url, modified: modified, in: folder) else { return }
        CopiedDecisions(defaults: userDefaults).remove(identity, in: folder)
    }
}
