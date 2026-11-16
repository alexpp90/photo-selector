import Foundation

/// The words of the two scores, with their scales (REQ-MAC-SCORE.05). Sharpness runs 0...100
/// (`FocusMetricService` clamps to it) and the aesthetic score 1...10 (`AestheticEvaluation` clamps
/// to it), so "/ 100" and "/ 10" are the real ranges. One place builds every string so the slot
/// badge, the info panel, the tooltip, the spoken label and the statistics cannot disagree.
public enum ScoreLabels {

    /// Filmstrip tile dot: a shape as well as a colour, so the band is not read from colour alone.
    /// Filled circle = sharp, half-filled = acceptable, hollow ring = blurry.
    public static func sharpnessSymbol(_ category: SharpnessCategory) -> String {
        switch category {
        case .sharp: return "circle.fill"
        case .acceptable: return "circle.lefthalf.filled"
        case .blurry: return "circle"
        }
    }

    /// What VoiceOver reads for a filmstrip tile: "IMG_0412, undecided, Sharp". The status says what
    /// was decided ("copied", "moved", "trashed"); "in comparison" is added for the tiles on screen.
    public static func filmstripTileLabel(
        photo: PhotoItem, inComparison: Bool = false, bands: SharpnessBands = .standard
    ) -> String {
        let status: String
        switch photo.status {
        case .candidate: status = "undecided"
        case .selected: status = "moved"
        case .copied: status = "copied"
        case .trashed: status = "trashed"
        }
        var parts = [photo.url.deletingPathExtension().lastPathComponent, status]
        if let scores = photo.scores { parts.append(scores.category(in: bands).rawValue) }
        if inComparison { parts.append("in comparison") }
        return parts.joined(separator: ", ")
    }

    /// Clipping on every surface: "Blown 3%" (highlights) and "Crushed 12%" (shadows).
    public static func blown(_ percent: Double) -> String { "Blown \(Int(percent))%" }
    public static func crushed(_ percent: Double) -> String { "Crushed \(Int(percent))%" }
    public static func blownSpoken(_ percent: Double) -> String { "Highlights blown, \(Int(percent)) percent" }
    public static func crushedSpoken(_ percent: Double) -> String { "Shadows crushed, \(Int(percent)) percent" }

    /// "8.4": the aesthetics score keeps its decimal (a 1 to 10 scale).
    public static func number(_ value: Double) -> String { String(format: "%.1f", value) }

    /// "60": sharpness is shown as a whole number. The measure is edge density of the whole frame,
    /// not a lab-grade quantity, so a decimal would claim a precision it does not have.
    public static func sharpnessNumber(_ value: Double) -> String { String(Int(value.rounded())) }

    /// What the sharpness score measures, in the words of the tooltip and of VoiceOver.
    public static let sharpnessMeasure = "edge detail of the whole frame"

    /// Slot badge: compact, the value and the band word ("60 Acceptable").
    public static func sharpnessBadge(_ scores: QualityScores, bands: SharpnessBands = .standard) -> String {
        "\(sharpnessNumber(scores.sharpness)) \(scores.category(in: bands).rawValue)"
    }

    /// Info panel and tooltip: "Sharpness 60 / 100".
    public static func sharpness(_ value: Double) -> String { "Sharpness \(sharpnessNumber(value)) / 100" }

    /// Info panel and tooltip: "Aesthetics 8.4 / 10".
    public static func aesthetics(_ value: Double) -> String { "Aesthetics \(number(value)) / 10" }

    /// Where the bands are, from the user's thresholds (defaults 35 and 70).
    public static func bands(blurryBelow: Double = AppSettingsDefaults.sharpnessBlurryThreshold,
                             sharpFrom: Double = AppSettingsDefaults.sharpnessAcceptableThreshold) -> String {
        "Blurry below \(Int(blurryBelow)), sharp from \(Int(sharpFrom))"
    }

    /// Tooltip of the sharpness badge.
    public static func sharpnessTooltip(_ scores: QualityScores, bands: SharpnessBands = .standard) -> String {
        "\(sharpness(scores.sharpness)) · \(scores.category(in: bands).rawValue). Edge detail of the whole frame; \(Self.bands(blurryBelow: bands.blurryBelow, sharpFrom: bands.sharpFrom))."
    }

    /// Tooltip of the aesthetics badge.
    public static func aestheticsTooltip(_ value: Double) -> String {
        "\(aesthetics(value)). Apple Vision's rating of how good the picture looks, 1 to 10. Not a star rating."
    }

    /// VoiceOver: a full sentence, and "aesthetics score" so it is never heard as a star rating.
    public static func sharpnessSpoken(_ scores: QualityScores, bands: SharpnessBands = .standard) -> String {
        "Sharpness \(sharpnessNumber(scores.sharpness)) out of 100, \(scores.category(in: bands).rawValue), \(sharpnessMeasure)"
    }

    public static func aestheticsSpoken(_ value: Double) -> String {
        "Aesthetics score \(number(value)) out of 10, not a star rating"
    }
}
