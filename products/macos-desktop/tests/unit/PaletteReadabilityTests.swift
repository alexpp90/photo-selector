import Testing
@testable import PhotoSelectorKit

/// F-LJ-005: the start screen's text was barely readable (dark text on dark grey). Class check: every
/// text colour the UI draws on the dark surfaces, and the brand orange used as text/glyph, reaches the
/// WCAG AA ratio on every surface it can sit on. `TextContrastTests` covers text on accent *fills*
/// (F-LJ-029/-030); this covers text on the neutral surfaces. REQ-MAC-THEME.01, REQ-MAC-THEME.02.
@Suite("Text on the dark surfaces is readable (WCAG AA)")
struct PaletteReadabilityTests {
    private static let surfaces: [(name: String, value: PaletteRGB)] = [
        ("canvas", LensjetPalette.canvas),
        ("surface", LensjetPalette.surface),
        ("panel", LensjetPalette.panel),
        ("neutralActionFill", LensjetPalette.neutralActionFill),
    ]
    private static let texts: [(name: String, value: PaletteRGB)] = [
        ("textPrimary", LensjetPalette.textPrimary),
        ("textSecondary", LensjetPalette.textSecondary),
        ("textMuted", LensjetPalette.textMuted),
        ("brandOnDark", LensjetPalette.brandOnDark),
        ("neutralActionAccent", LensjetPalette.neutralActionAccent),
    ]

    @Test("REQ-MAC-THEME.01 F-LJ-005: every text colour reaches 4.5:1 on every dark surface")
    func textOnSurfaces() {
        for surface in Self.surfaces {
            for text in Self.texts {
                let ratio = TextContrast.contrastRatio(text.value, surface.value)
                #expect(ratio >= TextContrast.minimumRatio, "\(text.name) on \(surface.name): \(ratio)")
            }
        }
    }

    @Test("REQ-MAC-THEME.01: the surfaces are dark and the primary text is pure white, so dark-on-dark cannot come back")
    func darkSurfacesLightText() {
        for surface in Self.surfaces where surface.name != "neutralActionFill" {
            #expect(TextContrast.relativeLuminance(surface.value) < 0.02, "\(surface.name) must stay dark")
        }
        #expect(LensjetPalette.textPrimary == PaletteRGB(255, 255, 255))
        // Every text colour is lighter than every surface.
        for text in Self.texts {
            for surface in Self.surfaces {
                #expect(TextContrast.relativeLuminance(text.value) > TextContrast.relativeLuminance(surface.value), "\(text.name) vs \(surface.name)")
            }
        }
    }
}
