import Foundation

/// Protocol seam for inspecting whether a directory or file resides on a File Provider,
/// cloud-synced mount (`~/Library/CloudStorage/`, iCloud Drive), or non-local volume.
public protocol CloudResourceInspecting: Sendable {
    /// Returns `true` when `url` lives on a remote volume, iCloud Drive, or File Provider mount.
    func isRemoteOrCloudFolder(_ url: URL) -> Bool
}

/// Default production implementation backed by `URLResourceValues` and macOS File Provider path heuristics.
public struct SystemCloudFolderInspector: CloudResourceInspecting, Sendable {

    private static let cloudPathMarkers: [String] = [
        "/Library/CloudStorage/",
        "/Library/Mobile Documents/"
    ]

    public init() {}

    public func isRemoteOrCloudFolder(_ url: URL) -> Bool {
        let standardizedPath = url.standardizedFileURL.path
        for marker in Self.cloudPathMarkers {
            if standardizedPath.contains(marker) {
                return true
            }
        }

        let keys: Set<URLResourceKey> = [
            .isUbiquitousItemKey,
            .ubiquitousItemDownloadingStatusKey,
            .volumeIsLocalKey
        ]
        if let values = try? url.resourceValues(forKeys: keys) {
            if values.isUbiquitousItem == true {
                return true
            }
            if values.ubiquitousItemDownloadingStatus != nil {
                return true
            }
            if let isLocal = values.volumeIsLocal, !isLocal {
                return true
            }
        }

        return false
    }
}
