import SwiftUI
import PhotoSelectorKit

// Named colour tokens for the whole app. Values live in `LensjetPalette` (PhotoSelectorKit) so
// tests can check them; this file only turns them into SwiftUI colours.
//
// Rules:
//  - Surfaces are neutral grey (equal R, G, B); the photo is judged against them.
//  - Selection, focus and "current" follow the system accent (`Color.accentColor`); there is no
//    global orange tint (orange vs trash red is not separable under deuteranopia).
//  - The brand orange appears only on brand surfaces (welcome state, folder loading logo).
//  - The app is dark-only: `.preferredColorScheme(.dark)` is applied to the WindowGroup and
//    Settings scenes in `PhotoSelectorApp.swift` and to the sheets/empty state themselves.

extension Color {
    init(_ rgb: PaletteRGB) {
        self.init(
            .sRGB,
            red: Double(rgb.red) / 255.0,
            green: Double(rgb.green) / 255.0,
            blue: Double(rgb.blue) / 255.0,
            opacity: 1
        )
    }

    // MARK: Neutral surfaces
    static var lensjetCanvas: Color { Color(LensjetPalette.canvas) }
    static var lensjetSurface: Color { Color(LensjetPalette.surface) }
    static var lensjetPanel: Color { Color(LensjetPalette.panel) }
    static var lensjetSeparator: Color { Color(LensjetPalette.separator) }
    static var lensjetNeutralActionFill: Color { Color(LensjetPalette.neutralActionFill) }
    static var lensjetNeutralActionAccent: Color { Color(LensjetPalette.neutralActionAccent) }

    // MARK: Text
    static var lensjetTextSecondary: Color { Color(LensjetPalette.textSecondary) }
    static var lensjetTextMuted: Color { Color(LensjetPalette.textMuted) }

    // MARK: Semantic
    static var lensjetMove: Color { Color(LensjetPalette.moveAccent) }
    static var lensjetMoveFill: Color { Color(LensjetPalette.moveFill) }
    static var lensjetCopy: Color { Color(LensjetPalette.copyAccent) }
    static var lensjetCopyFill: Color { Color(LensjetPalette.copyFill) }
    static var lensjetTrash: Color { Color(LensjetPalette.trashAccent) }
    static var lensjetTrashFill: Color { Color(LensjetPalette.trashFill) }
    static var lensjetSharp: Color { Color(LensjetPalette.focusSharp) }
    static var lensjetAcceptable: Color { Color(LensjetPalette.focusAcceptable) }
    static var lensjetBlurry: Color { Color(LensjetPalette.focusBlurry) }
    static var lensjetRating: Color { Color(LensjetPalette.rating) }
    static var lensjetClippingShadow: Color { Color(LensjetPalette.clippingShadow) }
    /// Highlight clipping ("Blown") indicator.
    static var lensjetClippingHighlight: Color { .orange }
    /// Photo is part of the current comparison set.
    static var lensjetComparison: Color { .cyan }

    // MARK: Selection and focus: the system accent, never a fixed colour.
    static var lensjetSelection: Color { .accentColor }

    // MARK: Brand
    /// Afterburner orange for brand surfaces only (6.1:1 on #141414).
    static var lensjetBrand: Color { Color(LensjetPalette.brandOnDark) }
}
