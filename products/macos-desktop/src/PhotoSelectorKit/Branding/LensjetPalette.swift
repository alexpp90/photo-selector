import Foundation

/// An sRGB colour as 8-bit components. Headless on purpose: the SwiftUI tokens in
/// `PhotoSelectorApp/Theme/LensjetColors.swift` are built from these values, and the unit
/// tests check the rules (neutral surfaces, no value shared by two meanings) here.
public struct PaletteRGB: Sendable, Equatable, Hashable {
    public let red: UInt8
    public let green: UInt8
    public let blue: UInt8

    public init(_ red: UInt8, _ green: UInt8, _ blue: UInt8) {
        self.red = red
        self.green = green
        self.blue = blue
    }

    /// Equal R, G and B.
    public var isNeutral: Bool { red == green && green == blue }

    public static func grey(_ level: UInt8) -> PaletteRGB { PaletteRGB(level, level, level) }
}

/// Named colour values of the Lensjet UI. The app is dark-only; viewer surrounds and large
/// surfaces are neutral grey so they do not tint the photo being judged.
public enum LensjetPalette {
    // MARK: Neutral surfaces (equal R, G, B)
    /// Viewer surround behind the photo.
    public static let canvas = PaletteRGB.grey(12)
    /// Sheets and wide panels.
    public static let surface = PaletteRGB.grey(16)
    /// Cards, filmstrip cells, info panel.
    public static let panel = PaletteRGB.grey(21)
    /// Hairlines and dock outlines.
    public static let separator = PaletteRGB.grey(58)
    /// Fill of the neutral action-dock button (Undo).
    public static let neutralActionFill = PaletteRGB.grey(34)
    /// Accent of the neutral action-dock button.
    public static let neutralActionAccent = PaletteRGB.grey(228)

    // MARK: Text (neutral)
    public static let textPrimary = PaletteRGB.grey(255)
    /// #D9D9D9, the high-contrast secondary text.
    public static let textSecondary = PaletteRGB.grey(217)
    public static let textMuted = PaletteRGB.grey(166)

    // MARK: Semantic colours (value and meaning unchanged by the rebrand)
    public static let moveAccent = PaletteRGB(16, 185, 129)
    public static let moveFill = PaletteRGB(8, 47, 30)
    public static let copyAccent = PaletteRGB(59, 130, 246)
    public static let copyFill = PaletteRGB(17, 34, 64)
    public static let trashAccent = PaletteRGB(239, 68, 68)
    public static let trashFill = PaletteRGB(59, 13, 17)
    public static let focusSharp = PaletteRGB(52, 211, 153)
    public static let focusAcceptable = PaletteRGB(245, 158, 11)
    public static let focusBlurry = PaletteRGB(244, 63, 94)
    public static let rating = PaletteRGB(251, 191, 36)
    public static let clippingShadow = PaletteRGB(191, 153, 255)

    // MARK: Brand (Afterburner orange). Not a global tint: selection and focus follow the
    // system accent, because orange is indistinguishable from the trash red under deuteranopia.
    /// App icon tile.
    public static let brandTile = PaletteRGB(0xD7, 0x65, 0x2D)
    /// Brand orange on the dark UI (6.1:1 on #141414).
    public static let brandOnDark = PaletteRGB(0xE8, 0x74, 0x3A)
    /// Icon mark.
    public static let brandMark = PaletteRGB(0xFF, 0xF6, 0xEC)
    /// Icon accent.
    public static let brandInk = PaletteRGB(0x24, 0x1A, 0x14)

    public static let neutralSurfaces: [(name: String, value: PaletteRGB)] = [
        ("canvas", canvas), ("surface", surface), ("panel", panel),
        ("separator", separator), ("neutralActionFill", neutralActionFill),
        ("neutralActionAccent", neutralActionAccent),
        ("textPrimary", textPrimary), ("textSecondary", textSecondary), ("textMuted", textMuted)
    ]

    public static let semanticAndBrand: [(name: String, value: PaletteRGB)] = [
        ("moveAccent", moveAccent), ("moveFill", moveFill),
        ("copyAccent", copyAccent), ("copyFill", copyFill),
        ("trashAccent", trashAccent), ("trashFill", trashFill),
        ("focusSharp", focusSharp), ("focusAcceptable", focusAcceptable),
        ("focusBlurry", focusBlurry), ("rating", rating), ("clippingShadow", clippingShadow),
        ("brandTile", brandTile), ("brandOnDark", brandOnDark),
        ("brandMark", brandMark), ("brandInk", brandInk)
    ]
}
