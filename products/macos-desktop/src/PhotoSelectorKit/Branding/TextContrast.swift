import Foundation

/// WCAG 2.x contrast maths for text drawn on a solid fill (selected segment, CURRENT badge).
/// Headless and pure: the SwiftUI token `Color.lensjetOnSelection` resolves the current system
/// accent at draw time and asks `TextContrast.legibleText(on:)` which of the two text colours
/// to use, so the label stays readable for every macOS accent colour.
public enum TextContrast {
    /// Minimum contrast ratio for normal-size text (WCAG 2.x level AA).
    public static let minimumRatio = 4.5

    /// Black text colour for light and mid-tone fills (yellow, orange, light blue). Pure black,
    /// because near-black would dip below 4.5:1 on mid greys where black and white tie.
    public static let darkText = PaletteRGB.grey(0)
    /// White text colour used on dark fills.
    public static let lightText = PaletteRGB.grey(255)

    /// WCAG relative luminance of an sRGB colour, 0 (black) ... 1 (white).
    public static func relativeLuminance(_ colour: PaletteRGB) -> Double {
        func linear(_ component: UInt8) -> Double {
            let c = Double(component) / 255.0
            return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(colour.red) + 0.7152 * linear(colour.green) + 0.0722 * linear(colour.blue)
    }

    /// WCAG contrast ratio between two colours, 1 ... 21, symmetric in its arguments.
    public static func contrastRatio(_ a: PaletteRGB, _ b: PaletteRGB) -> Double {
        let la = relativeLuminance(a)
        let lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// The text colour (`darkText` or `lightText`) with the higher contrast ratio on `fill`.
    public static func legibleText(on fill: PaletteRGB) -> PaletteRGB {
        contrastRatio(darkText, fill) >= contrastRatio(lightText, fill) ? darkText : lightText
    }
}
