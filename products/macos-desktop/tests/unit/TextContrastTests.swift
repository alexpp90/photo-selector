import Testing
@testable import PhotoSelectorKit

@Suite("Text on solid accent fills (WCAG AA)")
struct TextContrastTests {
    /// Apple's dark-mode system colours, which the macOS accent colours are drawn from.
    private static let appleAccents: [(name: String, value: PaletteRGB)] = [
        ("blue", PaletteRGB(10, 132, 255)),
        ("purple", PaletteRGB(191, 90, 242)),
        ("pink", PaletteRGB(255, 55, 95)),
        ("red", PaletteRGB(255, 69, 58)),
        ("orange", PaletteRGB(255, 159, 10)),
        ("yellow", PaletteRGB(255, 214, 10)),
        ("green", PaletteRGB(48, 209, 88)),
        ("graphite", PaletteRGB(152, 152, 157))
    ]

    /// Accent fills as rendered in the running app (measured, off-screen).
    private static let renderedAccents: [(name: String, value: PaletteRGB)] = [
        ("blue", PaletteRGB(0x41, 0x8F, 0xF8)),
        ("orange", PaletteRGB(0xEE, 0x9A, 0x49)),
        ("yellow", PaletteRGB(0xF8, 0xD0, 0x54))
    ]

    @Test("Luminance and ratio match the WCAG reference values")
    func reference() {
        #expect(TextContrast.relativeLuminance(PaletteRGB(0, 0, 0)) == 0)
        #expect(abs(TextContrast.relativeLuminance(PaletteRGB(255, 255, 255)) - 1) < 1e-9)
        #expect(abs(TextContrast.contrastRatio(.grey(0), .grey(255)) - 21) < 1e-9)
        #expect(TextContrast.contrastRatio(.grey(255), .grey(0)) == TextContrast.contrastRatio(.grey(0), .grey(255)))
        #expect(abs(TextContrast.contrastRatio(.grey(119), .grey(255)) - 4.48) < 0.01)
    }

    @Test("Black text on the rendered yellow, orange and blue accents")
    func darkOnRenderedAccents() {
        for (name, value) in Self.renderedAccents {
            #expect(TextContrast.legibleText(on: value) == TextContrast.darkText, "\(name)")
        }
        // The measured failures with white text, now fixed.
        #expect(TextContrast.contrastRatio(.grey(255), PaletteRGB(0x41, 0x8F, 0xF8)) < 4.5)
        #expect(TextContrast.contrastRatio(.grey(255), PaletteRGB(0xEE, 0x9A, 0x49)) < 4.5)
        #expect(TextContrast.contrastRatio(.grey(255), PaletteRGB(0xF8, 0xD0, 0x54)) < 4.5)
    }

    @Test("White text on a dark fill")
    func lightOnDark() {
        #expect(TextContrast.legibleText(on: PaletteRGB(0x1A, 0x1A, 0x1A)) == TextContrast.lightText)
        #expect(TextContrast.legibleText(on: .grey(0)) == TextContrast.lightText)
        #expect(TextContrast.legibleText(on: .grey(255)) == TextContrast.darkText)
    }

    @Test("Chosen text meets 4.5:1 on every macOS accent")
    func meetsAAOnEveryAccent() {
        for (name, value) in Self.appleAccents + Self.renderedAccents {
            let text = TextContrast.legibleText(on: value)
            let ratio = TextContrast.contrastRatio(text, value)
            #expect(ratio >= TextContrast.minimumRatio, "\(name) \(value): \(ratio)")
        }
    }

    @Test("Chosen text meets 4.5:1 on every grey level")
    func meetsAAOnGreys() {
        // Worst case is where black and white tie (about 4.58:1).
        for level in 0...255 {
            let fill = PaletteRGB.grey(UInt8(level))
            let ratio = TextContrast.contrastRatio(TextContrast.legibleText(on: fill), fill)
            #expect(ratio >= TextContrast.minimumRatio, "grey \(level): \(ratio)")
        }
    }
}
