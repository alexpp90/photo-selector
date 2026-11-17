import Testing
import Foundation
@testable import PhotoSelectorKit

/// L1 / L2 / L3 of the 2026-11-16 UX pass: one name per thing, a filmstrip tile VoiceOver can read,
/// a sharpness cue that is not colour alone.
@Suite("Filmstrip labels, sharpness shapes and the one-name vocabulary")
struct FilmstripAndWordsTests {

    private func photo(_ name: String, status: PhotoStatus, sharpness: Double?) -> PhotoItem {
        let scores = sharpness.map { QualityScores(sharpness: $0, noise: 0, highlightClipping: 0, shadowClipping: 0) }
        return PhotoItem(url: URL(fileURLWithPath: "/tmp/\(name)"), scores: scores, status: status)
    }

    @Test("REQ-MAC-A11Y.01: a filmstrip tile reads file, decision, sharpness and comparison")
    func tileLabel() {
        #expect(ScoreLabels.filmstripTileLabel(photo: photo("IMG_0412.CR3", status: .candidate, sharpness: 90))
                == "IMG_0412, undecided, Sharp")
        #expect(ScoreLabels.filmstripTileLabel(photo: photo("A.JPG", status: .copied, sharpness: 50), inComparison: true)
                == "A, copied, Acceptable, in comparison")
        #expect(ScoreLabels.filmstripTileLabel(photo: photo("B.JPG", status: .trashed, sharpness: nil)) == "B, trashed")
        #expect(ScoreLabels.filmstripTileLabel(photo: photo("C.JPG", status: .selected, sharpness: 5)) == "C, moved, Blurry")
    }

    @Test("REQ-MAC-A11Y.01: the three sharpness bands have three different shapes")
    func sharpnessShapes() {
        let symbols = SharpnessCategory.allCases.map(ScoreLabels.sharpnessSymbol)
        #expect(Set(symbols).count == 3)
        #expect(ScoreLabels.sharpnessSymbol(.blurry) == "circle")
    }

    @Test("Clipping reads the same everywhere and VoiceOver says what it means")
    func clippingWords() {
        #expect(ScoreLabels.blown(3.4) == "Blown 3%")
        #expect(ScoreLabels.crushed(12.9) == "Crushed 12%")
        #expect(ScoreLabels.blownSpoken(3) == "Highlights blown, 3 percent")
    }

    @Test("Undo names the verb it undid; the retired words are banned")
    func undoneTitles() {
        #expect(LensjetCommand.undoneToastTitle(action: .move) == "Undone: Move")
        #expect(LensjetCommand.undoneToastTitle(action: .trash) == "Undone: Trash")
        #expect(LensjetCommand.undoneToastTitle(action: .trash, rejected: true) == "Undone: Reject")
        #expect(LensjetWords.banned.contains("AI score"))
    }
}

@Suite("Copied photos say where they went")
struct CopiedRowTests {
    @Test("REQ-MAC-CULL.09: the info panel row reads Copied to «folder»")
    func copiedTitle() {
        #expect(LensjetWords.copiedTo("Selection") == "Copied to Selection")
        #expect(LensjetWords.copiedTo(nil) == "Copied")
        #expect(LensjetWords.copiedTo("") == "Copied")
    }

    @Test("REQ-MAC-KEY.01: S is the sort choice in the command table")
    func sortKey() {
        #expect(LensjetCommand.sortBy.key == "S")
        #expect(LensjetCommand.linkPan.key == "\u{2325}\u{2318}P")
    }
}
