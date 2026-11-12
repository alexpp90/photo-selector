import SwiftUI
import AppKit
import Foundation

/// Layout mode for the culling workspace preview canvas.
public enum ComparisonMode: Int, CaseIterable, Identifiable, Sendable {
    case single = 1
    case sideBySide = 2
    case triplet = 3

    public var id: Int { rawValue }

    public var title: String {
        switch self {
        case .single: return "1-Up"
        case .sideBySide: return "2-Up"
        case .triplet: return "3-Up"
        }
    }

    public var systemImage: String {
        switch self {
        case .single: return "rectangle.fill"
        case .sideBySide: return "rectangle.split.2x1.fill"
        case .triplet: return "rectangle.split.1x2.fill"
        }
    }
}

/// Zoom presentation state for canvas previews.
public enum ZoomState: Sendable, Equatable {
    /// Whole photograph fitted to its viewport.
    case fit
    /// True 100 %: one image pixel per screen pixel (computed from the original dimensions).
    case oneToOne
    /// Free trackpad pinch magnification, relative to Fit (always > 1).
    case magnified(Double)

    public var isZoomed: Bool { self != .fit }

    /// Pinch magnifications at or below this factor snap back to Fit.
    public static let snapToFitThreshold: Double = 1.02
}

/// Transient feedback toast notification message.
public struct ToastMessage: Identifiable, Equatable, Sendable {
    public let id: UUID
    public let icon: String
    public let title: String
    public let subtitle: String?
    public let actionType: CullingActionType?
    /// A banner that stays until the user's next navigation key or Esc (the "Stop at sharpness"
    /// notice), instead of fading after 1.2 s.
    public let isPersistent: Bool

    public init(
        id: UUID = UUID(),
        icon: String,
        title: String,
        subtitle: String? = nil,
        actionType: CullingActionType? = nil,
        isPersistent: Bool = false
    ) {
        self.isPersistent = isPersistent
        self.id = id
        self.icon = icon
        self.title = title
        self.subtitle = subtitle
        self.actionType = actionType
    }
}

/// Why a cloud folder is about to be read in full (every online-only photo downloads).
public enum FullPassPurpose: Equatable, Sendable {
    /// Library Statistics scores the whole library.
    case statistics
    /// View > Sort By > Lowest Sharpness First needs a score for every photo; `previous` is the
    /// order to keep if the user declines.
    case lowestSharpnessSort(previous: WorkspaceSortOrder)
}

/// A pending "Download all photos?" question (REQ-MAC-CLOUD.01).
public struct PendingDownloadConfirmation: Equatable, Sendable {
    public let purpose: FullPassPurpose
    /// `estimatedFullPassDownloadBytes` when the question was raised.
    public let estimatedBytes: Int64
}

/// Order of the culling workspace candidate list (View > Sort By, REQ-MAC-CULL.06 / .07).
public enum WorkspaceSortOrder: String, CaseIterable, Identifiable, Sendable {
    case newestFirst
    case oldestFirst
    case fileName
    case lowestSharpnessFirst

    public var id: String { rawValue }

    /// The order a new install opens with.
    public static let defaultOrder: WorkspaceSortOrder = .newestFirst

    /// Natural file-name order (the order of every release before the sort menu existed).
    public static var natural: WorkspaceSortOrder { .fileName }

    /// Menu title (macOS title case).
    public var title: String {
        switch self {
        case .newestFirst: return "Newest First"
        case .oldestFirst: return "Oldest First"
        case .fileName: return "File Name"
        case .lowestSharpnessFirst: return "Lowest Sharpness First"
        }
    }

    /// Symbol of the non-default indicator.
    public var symbol: String {
        switch self {
        case .newestFirst: return "arrow.down.to.line"
        case .oldestFirst: return "arrow.up.to.line"
        case .fileName: return "textformat.abc"
        case .lowestSharpnessFirst: return "scope"
        }
    }

    /// Reads a stored raw value; `natural` (the first name of file-name order) still reads.
    public init?(storedRaw: String?) {
        guard let storedRaw else { return nil }
        if storedRaw == "natural" { self = .fileName; return }
        self.init(rawValue: storedRaw)
    }
}

/// Orders `photos` in place for a sort order that does not depend on scores. Stable apart from the
/// tie-break: equal or missing dates fall back to the natural file-name order.
enum PhotoOrdering {
    static func byName(_ lhs: PhotoItem, _ rhs: PhotoItem) -> Bool {
        let cmp = lhs.filename.localizedStandardCompare(rhs.filename)
        if cmp != .orderedSame { return cmp == .orderedAscending }
        return lhs.url.path.localizedStandardCompare(rhs.url.path) == .orderedAscending
    }

    static func byDate(newestFirst: Bool) -> (PhotoItem, PhotoItem) -> Bool {
        { lhs, rhs in
            switch (lhs.modificationDate, rhs.modificationDate) {
            case let (l?, r?) where l != r: return newestFirst ? l > r : l < r
            case (_?, nil): return true      // dated photos before undated ones
            case (nil, _?): return false
            default: return byName(lhs, rhs)
            }
        }
    }
}

/// How far the background scoring pass has come ("12 / 17").
public struct ScoringProgress: Equatable, Sendable {
    public let done: Int
    public let total: Int
    public init(done: Int, total: Int) { self.done = done; self.total = total }
    public var text: String { "\(done) / \(total)" }
}

/// Pending permanent-delete confirmation published when a cloud provider refuses Trash (UC-17).
public struct PendingPermanentDeleteConfirmation: Sendable, Equatable, Identifiable {
    public var id: UUID { photoID }
    public let photoID: UUID
    public let urls: [URL]
    public let isShared: Bool
    public let message: String

    public init(photoID: UUID, urls: [URL], isShared: Bool, message: String) {
        self.photoID = photoID
        self.urls = urls
        self.isShared = isShared
        self.message = message
    }
}

/// Summary of SD card culling and transfer completion state (UC-10).
public struct CardCullSummary: Sendable, Equatable {
    public let keptCount: Int
    public let trashedCount: Int
    public let undecidedCount: Int
    public let inFlightCount: Int
    public let keepersOnCard: Bool
    public let isSafeToFormat: Bool
    public let summaryText: String

    public init(
        keptCount: Int,
        trashedCount: Int,
        undecidedCount: Int,
        inFlightCount: Int,
        keepersOnCard: Bool,
        isSafeToFormat: Bool,
        summaryText: String
    ) {
        self.keptCount = keptCount
        self.trashedCount = trashedCount
        self.undecidedCount = undecidedCount
        self.inFlightCount = inFlightCount
        self.keepersOnCard = keepersOnCard
        self.isSafeToFormat = isSafeToFormat
        self.summaryText = summaryText
    }
}
