import Foundation

extension CullingWorkspaceViewModel {
    /// The Activity list (Go ▸ Activity, `⇧⌘L`, the toolbar button): the decisions of this folder.
    public var activityRows: [ActivityRow] { ActivityList.rows(from: decisionLog) }

    public func presentActivity() { isActivityPresented = true }

    /// A click on a row: select the photo when it is still in the folder.
    @discardableResult
    public func selectActivity(_ row: ActivityRow) -> Bool {
        guard let id = row.photoID, let index = photos.firstIndex(where: { $0.id == id }) else { return false }
        selectPhoto(at: index)
        return true
    }
}
