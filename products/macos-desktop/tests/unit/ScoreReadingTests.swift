import Testing
import Foundation
@testable import PhotoSelectorKit

/// Scores read as measurements, not verdicts: one scoring size, no false decimal, and the docs'
/// claim about what is scored pinned to the call the app really makes.
@Suite("Score reading")
struct ScoreReadingTests {

    @Test("The scoring size is the preview tier the viewport and the library pass decode")
    func scoringSizeIsThePreviewTier() {
        #expect(FocusMetricService.scoringLongEdge == ThumbnailTier.preview.rawValue)
        let big = FocusMetricService.scoringSize(width: 6000, height: 4000)
        #expect(big.width == 2048 && big.height == 1365)
        let portrait = FocusMetricService.scoringSize(width: 4000, height: 6000)
        #expect(portrait.width == 1365 && portrait.height == 2048)
        let small = FocusMetricService.scoringSize(width: 1620, height: 1080)
        #expect(small.width == 1620 && small.height == 1080, "never enlarged")
        let exact = FocusMetricService.scoringSize(width: 2048, height: 1365)
        #expect(exact.width == 2048 && exact.height == 1365)
    }

    @Test("A frame larger than the scoring size is scored at it and still lands in 0...100")
    func largeFrameIsScoredAtTheFixedSize() throws {
        let side = 2600
        let pixels = (0..<side * side).map { UInt8((($0 % side) / 4 + ($0 / side) / 4) % 2 == 0 ? 0 : 255) }
        let image = try #require(SyntheticImageFactory.createGrayscaleCGImage(width: side, height: side, pixels: pixels))
        let result = try #require(FocusMetricService.evaluateSync(cgImage: image))
        #expect((0...100).contains(result.score))
    }

    @Test("Sharpness shows as a whole number; aesthetics keeps its decimal")
    func wholeNumberSharpness() {
        #expect(ScoreLabels.sharpnessNumber(59.6) == "60")
        #expect(ScoreLabels.sharpnessNumber(34.4) == "34")
        #expect(ScoreLabels.sharpnessNumber(0) == "0")
        #expect(ScoreLabels.sharpnessNumber(100) == "100")
        #expect(ScoreLabels.number(8.44) == "8.4")
        let scores = QualityScores(sharpness: 59.6, noise: 0, highlightClipping: 0, shadowClipping: 0)
        #expect(!ScoreLabels.sharpnessBadge(scores).contains("."))
        #expect(!ScoreLabels.sharpness(59.6).contains("."))
    }

    @Test("Tooltip and VoiceOver say what is measured: edge detail of the whole frame")
    func sayWhatIsMeasured() {
        let scores = QualityScores(sharpness: 50, noise: 0, highlightClipping: 0, shadowClipping: 0)
        #expect(ScoreLabels.sharpnessTooltip(scores).contains("whole frame"))
        #expect(ScoreLabels.sharpnessSpoken(scores).contains(ScoreLabels.sharpnessMeasure))
    }

    @Test("The documented claim is the production call: the whole frame, no centre crop")
    func docsMatchProductionCall() throws {
        let side = 96
        // Sharp centre, flat border: a centre crop and the whole frame would score differently.
        let pixels = (0..<side * side).map { i -> UInt8 in
            let x = i % side, y = i / side
            let centre = (24..<72).contains(x) && (24..<72).contains(y)
            return centre ? UInt8(((x + y) % 2 == 0) ? 60 : 200) : 128
        }
        let image = try #require(SyntheticImageFactory.createGrayscaleCGImage(width: side, height: side, pixels: pixels))
        let whole = try #require(FocusMetricService.evaluateSync(cgImage: image, cropToCenter: false))
        let defaulted = try #require(FocusMetricService.evaluateSync(cgImage: image))
        #expect(defaulted.score == whole.score, "the default is the whole frame")
        #expect(QualityScoringService.scoreTechnical(cgImage: image).sharpness == whole.score)

        // No production file asks for the centre crop.
        let src = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("src")
        for case let url as URL in try #require(FileManager.default.enumerator(at: src, includingPropertiesForKeys: nil))
            where url.pathExtension == "swift" {
            let text = try String(contentsOf: url, encoding: .utf8)
            #expect(!text.contains("cropToCenter: true"), "\(url.lastPathComponent) scores a centre crop")
        }

        // And the shared parity doc says so for Lensjet.
        let parity = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("docs/shared/FEATURE_PARITY.md")
        if let text = try? String(contentsOf: parity, encoding: .utf8) {
            #expect(text.contains("Lensjet: whole frame, no centre crop"))
        }
    }
}
