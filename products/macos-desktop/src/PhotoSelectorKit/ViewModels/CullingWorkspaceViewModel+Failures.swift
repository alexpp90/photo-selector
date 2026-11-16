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
        guard currentToast?.isFailure != true else { return }
        dismissStickyToast()
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
