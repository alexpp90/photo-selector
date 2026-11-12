import Foundation

/// The reasons a cloud folder refuses work (REQ-MAC-CLOUD.01, [USER 2026-11-12] decision #26).
/// Lensjet reads a cloud folder only where the user looks: no full pass over every photo (it would
/// download them all), and no Delete (a File Provider has no Trash to take it back from).
public enum CloudRefusal {
    /// Library scoring, Statistics, Lowest Sharpness First and the duplicate hash pass.
    public static let fullPassLine = "Cloud folder: would download every photo"
    /// Delete on a remote folder.
    public static let deleteBanner = "No Trash in cloud folders. Nothing deleted."
    /// How long the Delete banner stays.
    static let bannerNanoseconds: UInt64 = 4_000_000_000
}

extension CullingWorkspaceViewModel {
    /// Shown in place of a full pass's start control or progress; nil on a local folder.
    public var fullPassRefusal: String? { isRemoteFolder ? CloudRefusal.fullPassLine : nil }

    /// The non-modal banner of a refused Delete: 4 s, then gone.
    func showCloudBanner(_ text: String) {
        cloudBannerTask?.cancel()
        cloudBanner = text
        cloudBannerTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: CloudRefusal.bannerNanoseconds)
            if !Task.isCancelled { self?.cloudBanner = nil }
        }
    }
}
