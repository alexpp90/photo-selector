import Testing
import Foundation
@testable import PhotoSelectorKit

@Suite("ChromeDensity — labels step down, never wrap (REQ-MAC-UI.01/.02)")
struct ChromeDensityTests {

    @Test("Mode labels: full keeps shortcut hint, compact drops it, minimal is icon-only")
    func modeLabels() {
        #expect(ChromeDensity.full.modeLabel(shortcut: 1) == "1-Up")
        #expect(ChromeDensity.full.modeLabel(shortcut: 3) == "Focus 3-Up")
        #expect(ChromeDensity.full.showsModeShortcut)
        #expect(ChromeDensity.compact.modeLabel(shortcut: 3) == "3-Up")
        #expect(!ChromeDensity.compact.showsModeShortcut)
        #expect(ChromeDensity.minimal.modeLabel(shortcut: 2) == nil)
        #expect(ChromeDensity.modeAccessibilityName(shortcut: 3) == "Focus 3-Up view (shortcut 3)")
    }

    @Test("Position counter shortens below full")
    func position() {
        #expect(ChromeDensity.full.positionText(index: 3, total: 120) == "Photo 3 of 120")
        #expect(ChromeDensity.compact.positionText(index: 3, total: 120) == "3 / 120")
        #expect(ChromeDensity.minimal.positionText(index: 3, total: 120) == "3 / 120")
    }

    @Test("Dock titles: full, short, then none; undo keeps its name")
    func dock() {
        #expect(ChromeDensity.full.dockTitle(.move) == "Move to Selection")
        #expect(ChromeDensity.compact.dockTitle(.move) == "Move")
        #expect(ChromeDensity.compact.dockTitle(.trash) == "Trash")
        #expect(ChromeDensity.minimal.dockTitle(.copy) == nil)
        #expect(ChromeDensity.full.dockTitle(.undo) == "Undo")
    }

    @Test("No label contains a line break")
    func singleLine() {
        for density in ChromeDensity.allCases {
            var texts: [String] = [density.positionText(index: 1, total: 99)]
            texts += ChromeDensity.DockAction.allCases.compactMap { density.dockTitle($0) }
            texts += [1, 2, 3].compactMap { density.modeLabel(shortcut: $0) }
            if let s = density.scanningText(photoCount: 5) { texts.append(s) }
            #expect(texts.allSatisfy { !$0.contains("\n") })
        }
    }

    @Test("Scanning text and folder chip step down")
    func scanning() {
        #expect(ChromeDensity.full.scanningText(photoCount: 7) == "Scanning… (7 photos)")
        #expect(ChromeDensity.compact.scanningText(photoCount: 7) == "Scanning… 7")
        #expect(ChromeDensity.minimal.scanningText(photoCount: 7) == nil)
        #expect(ChromeDensity.full.showsFolderName && ChromeDensity.compact.showsFolderName)
        #expect(!ChromeDensity.minimal.showsFolderName)
    }

    @Test("Tally accessibility text names all three counters")
    func tally() {
        #expect(ChromeDensity.tallyAccessibilityText(selected: 1, trashed: 0, remaining: 14) == "1 selected, 0 trashed, 14 remaining")
    }
}
