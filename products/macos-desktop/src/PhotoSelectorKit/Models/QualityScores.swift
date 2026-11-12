import Foundation

/// Where the Blurry and Sharp bands start: the two thresholds the user sets in Settings (defaults
/// 35 and 70). Every reader of a band (badges, tooltips, filmstrip dots, Statistics) builds its
/// category from these, so moving a slider changes what the whole window shows.
public struct SharpnessBands: Equatable, Sendable {
    /// Scores below this are Blurry.
    public let blurryBelow: Double
    /// Scores from this are Sharp; between the two is Acceptable. Never below `blurryBelow`.
    public let sharpFrom: Double

    public static let standard = SharpnessBands(
        blurryBelow: AppSettingsDefaults.sharpnessBlurryThreshold,
        sharpFrom: AppSettingsDefaults.sharpnessAcceptableThreshold)

    public init(blurryBelow: Double, sharpFrom: Double) {
        self.blurryBelow = blurryBelow
        self.sharpFrom = max(sharpFrom, blurryBelow)
    }

    /// The bands the user has stored (defaults when none).
    public static func stored(in defaults: UserDefaults = .standard) -> SharpnessBands {
        SharpnessBands(blurryBelow: defaults.sharpnessBlurryThreshold, sharpFrom: defaults.sharpnessAcceptableThreshold)
    }

    public func category(for score: Double) -> SharpnessCategory {
        if score < blurryBelow { return .blurry }
        if score < sharpFrom { return .acceptable }
        return .sharp
    }
}

/// Photographic sharpness classification category.
public enum SharpnessCategory: String, Sendable, Codable, CaseIterable {
    case blurry = "Blurry"
    case acceptable = "Acceptable"
    case sharp = "Sharp"

    /// The category of `score` in `bands` (the standard 35 / 70 unless the user's are passed).
    public static func from(score: Double, bands: SharpnessBands = .standard) -> SharpnessCategory {
        bands.category(for: score)
    }
}

/// Photographic quality scores encompassing focus, exposure clipping, noise, and aesthetics.
public struct QualityScores: Sendable, Codable, Equatable, Hashable {
    /// Sharpness score in range 0.0 ... 100.0.
    public let sharpness: Double

    /// Alias for sharpness score (0.0 ... 100.0) used in focus/sharpness sorting and statistics.
    public var focusScore: Double { sharpness }

    /// Sensor noise metric in range 0.0 ... 100.0.
    public let noise: Double

    /// Highlight clipping percentage in range 0.0 ... 100.0 (pixels >= 254).
    public let highlightClipping: Double

    /// Shadow clipping percentage in range 0.0 ... 100.0 (pixels <= 2).
    public let shadowClipping: Double

    /// Normalized aesthetic score in range 1.0 ... 10.0 (nil if not evaluated).
    public let aesthetic: Double?

    /// Flag indicating utility content (e.g. screenshot, document, receipt).
    public let isUtility: Bool

    /// Focus categorization derived from sharpness score.
    public let focusCategory: SharpnessCategory

    public init(
        sharpness: Double,
        noise: Double,
        highlightClipping: Double,
        shadowClipping: Double,
        aesthetic: Double? = nil,
        isUtility: Bool = false,
        focusCategory: SharpnessCategory? = nil
    ) {
        self.sharpness = sharpness
        self.noise = noise
        self.highlightClipping = highlightClipping
        self.shadowClipping = shadowClipping
        self.aesthetic = aesthetic
        self.isUtility = isUtility
        self.focusCategory = focusCategory ?? SharpnessCategory.from(score: sharpness)
    }

    /// The category under the user's bands. `focusCategory` is fixed at the standard 35 / 70 when
    /// the score is made; readers that show a band use this so the Settings sliders take effect.
    public func category(in bands: SharpnessBands) -> SharpnessCategory {
        bands.category(for: sharpness)
    }

    /// Copy with the (lazily computed) Apple Vision result filled in; every technical metric
    /// is preserved.
    public func withAesthetics(_ aesthetic: Double?, isUtility: Bool) -> QualityScores {
        QualityScores(
            sharpness: sharpness,
            noise: noise,
            highlightClipping: highlightClipping,
            shadowClipping: shadowClipping,
            aesthetic: aesthetic,
            isUtility: isUtility,
            focusCategory: focusCategory
        )
    }
}
