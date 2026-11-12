import Testing
import Foundation
import CoreGraphics
@testable import PhotoSelectorKit

/// Counts evaluator invocations; the Vision request is "run" exactly when this is called.
private actor CallCounter {
    private(set) var count = 0
    func hit() { count += 1 }
}

@Suite("Lazy, switchable AI scoring (REQ-MAC-SCORE.04)")
struct LazyScoringTests {

    private func image() -> CGImage {
        let size = 128
        var pixels = [UInt8](repeating: 0, count: size * size)
        for y in 0..<size { for x in 0..<size { pixels[y * size + x] = ((x / 8) + (y / 8)) % 2 == 0 ? 30 : 220 } }
        return SyntheticImageFactory.createGrayscaleCGImage(width: size, height: size, pixels: pixels)!
    }

    @Test("AI scoring OFF: the aesthetics evaluator is never called and the technical scores are complete")
    func disabledNeverCallsEvaluator() async {
        let counter = CallCounter()
        let scores = await QualityScoringService.score(cgImage: image(), aestheticsEnabled: false) { _ in
            await counter.hit()
            return AestheticEvaluation(overallScore: 0.5, isUtility: false)
        }
        #expect(await counter.count == 0)
        #expect(scores.aesthetic == nil)
        #expect(scores.sharpness > 0)
    }

    @Test("AI scoring ON: evaluator runs once and its result is merged without touching technical metrics")
    func enabledMergesResult() async {
        let counter = CallCounter()
        let scores = await QualityScoringService.score(cgImage: image(), aestheticsEnabled: true) { _ in
            await counter.hit()
            return AestheticEvaluation(overallScore: 1.0, isUtility: true)
        }
        #expect(await counter.count == 1)
        #expect(scores.aesthetic == 10.0)
        #expect(scores.isUtility)
        let technical = QualityScoringService.scoreTechnical(cgImage: image())
        #expect(scores.sharpness == technical.sharpness)
        #expect(scores.highlightClipping == technical.highlightClipping)
    }

    @Test("A failing evaluator degrades to the technical scores")
    func failingEvaluator() async {
        struct Boom: Error {}
        let scores = await QualityScoringService.score(cgImage: image()) { _ in throw Boom() }
        #expect(scores.aesthetic == nil)
        #expect(scores.sharpness > 0)
    }

    @Test("A cancelled task never starts the evaluator")
    func cancelledSkipsEvaluator() async {
        let counter = CallCounter()
        let img = image()
        let task = Task {
            while !Task.isCancelled { try? await Task.sleep(nanoseconds: 1_000_000) }
            return await QualityScoringService.score(cgImage: img) { _ in
                await counter.hit()
                return AestheticEvaluation(overallScore: 0, isUtility: false)
            }
        }
        task.cancel()
        _ = await task.value
        #expect(await counter.count == 0)
    }

    @Test("ScoringPlan: fresh photo runs both stages; AI off drops Vision; scored photos only add what is missing")
    func planDecisions() {
        #expect(ScoringPlan.make(existing: nil, aestheticsEnabled: true) == ScoringPlan(runTechnical: true, runAesthetics: true))
        #expect(ScoringPlan.make(existing: nil, aestheticsEnabled: false) == ScoringPlan(runTechnical: true, runAesthetics: false))
        let technicalOnly = QualityScores(sharpness: 50, noise: 1, highlightClipping: 0, shadowClipping: 0)
        #expect(ScoringPlan.make(existing: technicalOnly, aestheticsEnabled: true) == ScoringPlan(runTechnical: false, runAesthetics: true))
        #expect(ScoringPlan.make(existing: technicalOnly, aestheticsEnabled: false).isEmpty)
        let complete = technicalOnly.withAesthetics(7.0, isUtility: false)
        #expect(ScoringPlan.make(existing: complete, aestheticsEnabled: true).isEmpty)
    }

    @Test("withAesthetics keeps every technical field")
    func withAestheticsPreserves() {
        let base = QualityScores(sharpness: 61, noise: 2, highlightClipping: 3, shadowClipping: 4)
        let merged = base.withAesthetics(8.2, isUtility: true)
        #expect(merged.sharpness == 61 && merged.noise == 2 && merged.highlightClipping == 3 && merged.shadowClipping == 4)
        #expect(merged.focusCategory == base.focusCategory)
        #expect(merged.aesthetic == 8.2 && merged.isUtility)
    }

    @Test("Setting persists field-level: default OFF, toggling ON keeps unrelated keys, reset restores OFF")
    func settingPersistence() {
        let suite = "test.lazyscoring.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        #expect(defaults.aestheticScoringEnabled == false)
        defaults.cullingDestinationFolderName = "Keepers"
        defaults.aestheticScoringEnabled = true
        #expect(defaults.aestheticScoringEnabled == true)
        #expect(defaults.cullingDestinationFolderName == "Keepers")
        defaults.resetAppSettingsToDefaults()
        #expect(defaults.aestheticScoringEnabled == false)
    }
}
