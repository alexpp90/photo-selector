import Testing
@testable import PhotoSelectorKit

@Suite("Lensjet colour tokens")
struct LensjetPaletteTests {
    @Test("Surfaces and text are neutral grey (equal R, G, B)")
    func surfacesAreNeutral() {
        for (name, value) in LensjetPalette.neutralSurfaces {
            #expect(value.isNeutral, "\(name) must have equal R, G, B")
        }
    }

    @Test("Dark surfaces stay dark and secondary text is #D9D9D9")
    func darkSurfacesAndTextContract() {
        #expect(LensjetPalette.canvas.red < 40)
        #expect(LensjetPalette.surface.red < 40)
        #expect(LensjetPalette.panel.red < 40)
        #expect(LensjetPalette.textPrimary == PaletteRGB(255, 255, 255))
        #expect(LensjetPalette.textSecondary == PaletteRGB(0xD9, 0xD9, 0xD9))
    }

    @Test("No colour value is shared by two different meanings")
    func noDuplicateValues() {
        let all = LensjetPalette.neutralSurfaces + LensjetPalette.semanticAndBrand
        var seen: [PaletteRGB: String] = [:]
        for (name, value) in all {
            if let other = seen[value] {
                Issue.record("\(name) and \(other) share the value \(value)")
            }
            seen[value] = name
        }
    }

    @Test("Brand orange values match the brand sheet")
    func brandValues() {
        #expect(LensjetPalette.brandTile == PaletteRGB(0xD7, 0x65, 0x2D))
        #expect(LensjetPalette.brandOnDark == PaletteRGB(0xE8, 0x74, 0x3A))
        #expect(!LensjetPalette.brandOnDark.isNeutral)
    }

    @Test("Brand orange is distinct from the trash red")
    func brandIsNotTrash() {
        #expect(LensjetPalette.brandOnDark != LensjetPalette.trashAccent)
        #expect(LensjetPalette.brandTile != LensjetPalette.trashAccent)
    }
}
