import Foundation

/// Comprehensive photographic library metrics aggregate.
public struct LibraryStatistics: Sendable, Equatable {
    // 1. Photo Status Breakdown
    public let totalPhotoCount: Int
    public let candidateCount: Int
    public let selectedCount: Int
    public let copiedCount: Int
    public let trashedCount: Int

    // 2. Storage Footprint & Savings
    public let totalStorageBytes: Int64
    public let selectedStorageBytes: Int64
    public let trashedStorageBytes: Int64 // Recoverable savings from trashed photos

    // 3. Format Breakdown
    public let formatStatistics: [FormatStatistic]

    // 4. Quality & Score Metrics
    public let averageSharpness: Double?
    public let averageAesthetic: Double?
    public let focusDistribution: [SharpnessCategory: Int]
    public let utilityPhotoCount: Int
    /// How many photos the sharpness figures and the distribution are based on (of `totalPhotoCount`).
    public let sharpnessScoredCount: Int
    /// How many photos the aesthetics average is based on.
    public let aestheticScoredCount: Int

    // 5. Leaderboard / Top 5
    public let topSharpestPhotos: [PhotoItem]
    public let topAestheticPhotos: [PhotoItem]

    // 6. EXIF × Sharpness Breakdown (Mean focusScore per EXIF bucket)
    public let focalLengthSharpness: [String: Double]
    public let apertureSharpness: [String: Double]
    public let shutterSpeedSharpness: [String: Double]

    public init(
        totalPhotoCount: Int,
        candidateCount: Int,
        selectedCount: Int,
        copiedCount: Int,
        trashedCount: Int,
        totalStorageBytes: Int64,
        selectedStorageBytes: Int64,
        trashedStorageBytes: Int64,
        formatStatistics: [FormatStatistic],
        averageSharpness: Double?,
        averageAesthetic: Double?,
        focusDistribution: [SharpnessCategory: Int],
        utilityPhotoCount: Int,
        topSharpestPhotos: [PhotoItem],
        topAestheticPhotos: [PhotoItem],
        focalLengthSharpness: [String: Double] = [:],
        apertureSharpness: [String: Double] = [:],
        shutterSpeedSharpness: [String: Double] = [:],
        sharpnessScoredCount: Int = 0,
        aestheticScoredCount: Int = 0
    ) {
        self.totalPhotoCount = totalPhotoCount
        self.candidateCount = candidateCount
        self.selectedCount = selectedCount
        self.copiedCount = copiedCount
        self.trashedCount = trashedCount
        self.totalStorageBytes = totalStorageBytes
        self.selectedStorageBytes = selectedStorageBytes
        self.trashedStorageBytes = trashedStorageBytes
        self.formatStatistics = formatStatistics
        self.averageSharpness = averageSharpness
        self.averageAesthetic = averageAesthetic
        self.focusDistribution = focusDistribution
        self.utilityPhotoCount = utilityPhotoCount
        self.topSharpestPhotos = topSharpestPhotos
        self.topAestheticPhotos = topAestheticPhotos
        self.focalLengthSharpness = focalLengthSharpness
        self.apertureSharpness = apertureSharpness
        self.shutterSpeedSharpness = shutterSpeedSharpness
        self.sharpnessScoredCount = sharpnessScoredCount
        self.aestheticScoredCount = aestheticScoredCount
    }

    /// Empty baseline representation for uninitialized or cleared libraries.
    public static var empty: LibraryStatistics {
        LibraryStatistics(
            totalPhotoCount: 0,
            candidateCount: 0,
            selectedCount: 0,
            copiedCount: 0,
            trashedCount: 0,
            totalStorageBytes: 0,
            selectedStorageBytes: 0,
            trashedStorageBytes: 0,
            formatStatistics: [],
            averageSharpness: nil,
            averageAesthetic: nil,
            focusDistribution: [.blurry: 0, .acceptable: 0, .sharp: 0],
            utilityPhotoCount: 0,
            topSharpestPhotos: [],
            topAestheticPhotos: [],
            focalLengthSharpness: [:],
            apertureSharpness: [:],
            shutterSpeedSharpness: [:]
        )
    }

    // Convenience formatting helpers
    public var formattedTotalStorage: String {
        ByteCountFormatter.string(fromByteCount: totalStorageBytes, countStyle: .file)
    }

    public var formattedSelectedStorage: String {
        ByteCountFormatter.string(fromByteCount: selectedStorageBytes, countStyle: .file)
    }

    public var formattedTrashedSavings: String {
        ByteCountFormatter.string(fromByteCount: trashedStorageBytes, countStyle: .file)
    }

    /// "17 / 17 scored": what the sharpness figures and the distribution are based on.
    public var sharpnessCoverageText: String { "\(sharpnessScoredCount) / \(totalPhotoCount) scored" }

    /// True when every photo has a sharpness score, so the figures describe the whole library.
    public var isSharpnessComplete: Bool { totalPhotoCount > 0 && sharpnessScoredCount == totalPhotoCount }

    /// Progress percentage of photos inspected/culled (selected + copied + trashed) vs total.
    public var cullingProgressPercentage: Double {
        guard totalPhotoCount > 0 else { return 0.0 }
        let culled = Double(selectedCount + copiedCount + trashedCount)
        return (culled / Double(totalPhotoCount)) * 100.0
    }
}

/// Lightweight local decision log entry capturing a user's culling action alongside optical EXIF
/// and sharpness metrics (UC-14).
public struct CullingDecisionLogEntry: Sendable, Codable, Equatable, Identifiable {
    public let id: UUID
    public let timestamp: Date
    public let filename: String
    public let action: CullingActionType
    public let focusScore: Double?
    public let iso: Int?
    public let shutterSpeed: Double?
    public let aperture: Double?
    public let focalLength: Double?

    public init(
        id: UUID = UUID(),
        timestamp: Date = Date(),
        filename: String,
        action: CullingActionType,
        focusScore: Double? = nil,
        iso: Int? = nil,
        shutterSpeed: Double? = nil,
        aperture: Double? = nil,
        focalLength: Double? = nil
    ) {
        self.id = id
        self.timestamp = timestamp
        self.filename = filename
        self.action = action
        self.focusScore = focusScore
        self.iso = iso
        self.shutterSpeed = shutterSpeed
        self.aperture = aperture
        self.focalLength = focalLength
    }
}
