import Foundation

/// The words of the two scores, with their scales (REQ-MAC-SCORE.05). Sharpness runs 0...100
/// (`FocusMetricService` clamps to it) and the aesthetic score 1...10 (`AestheticEvaluation` clamps
/// to it), so "/ 100" and "/ 10" are the real ranges. One place builds every string so the slot
/// badge, the info panel, the tooltip, the spoken label and the statistics cannot disagree.
public enum ScoreLabels {

    /// "59.6"
    public static func number(_ value: Double) -> String { String(format: "%.1f", value) }

    /// Slot badge: compact, the value and the band word ("59.6 Acceptable").
    public static func sharpnessBadge(_ scores: QualityScores, bands: SharpnessBands = .standard) -> String {
        "\(number(scores.sharpness)) \(scores.category(in: bands).rawValue)"
    }

    /// Info panel and tooltip: "Sharpness 59.6 / 100".
    public static func sharpness(_ value: Double) -> String { "Sharpness \(number(value)) / 100" }

    /// Info panel and tooltip: "Aesthetics 8.4 / 10".
    public static func aesthetics(_ value: Double) -> String { "Aesthetics \(number(value)) / 10" }

    /// Where the bands are, from the user's thresholds (defaults 35 and 70).
    public static func bands(blurryBelow: Double = AppSettingsDefaults.sharpnessBlurryThreshold,
                             sharpFrom: Double = AppSettingsDefaults.sharpnessAcceptableThreshold) -> String {
        "Blurry below \(Int(blurryBelow)), sharp from \(Int(sharpFrom))"
    }

    /// Tooltip of the sharpness badge.
    public static func sharpnessTooltip(_ scores: QualityScores, bands: SharpnessBands = .standard) -> String {
        "\(sharpness(scores.sharpness)) · \(scores.category(in: bands).rawValue). Edge detail of the frame; \(Self.bands(blurryBelow: bands.blurryBelow, sharpFrom: bands.sharpFrom))."
    }

    /// Tooltip of the aesthetics badge.
    public static func aestheticsTooltip(_ value: Double) -> String {
        "\(aesthetics(value)). Apple Vision's rating of how good the picture looks, 1 to 10. Not a star rating."
    }

    /// VoiceOver: a full sentence, and "aesthetics score" so it is never heard as a star rating.
    public static func sharpnessSpoken(_ scores: QualityScores, bands: SharpnessBands = .standard) -> String {
        "Sharpness \(number(scores.sharpness)) out of 100, \(scores.category(in: bands).rawValue)"
    }

    public static func aestheticsSpoken(_ value: Double) -> String {
        "Aesthetics score \(number(value)) out of 10, not a star rating"
    }
}
