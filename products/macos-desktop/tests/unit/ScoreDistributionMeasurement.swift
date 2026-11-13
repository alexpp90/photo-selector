import Testing
import Foundation
import CoreGraphics
@testable import PhotoSelectorKit

/// Opt-in measurement of the score distributions over the format corpus (backlog entry "Lensjet:
/// scores read as verdicts", step 1). Not a pass/fail test: set `PST_MEASURE_SCORES=<output file>`
/// and run `swift test --filter ScoreDistributionMeasurement`; it writes one line per sample and a
/// summary (distinct whole-number sharpness values, share clamped at 0 / 100, share per band,
/// embedded preview vs a full decode, Vision aesthetic). Without the variable it does nothing.
@Suite("Score distribution measurement (opt-in)")
struct ScoreDistributionMeasurement {
    private struct Row {
        let id: String
        let displaySharpness: Double
        let decodedSharpness: Double
        let noise: Double
        let highlight: Double
        let shadow: Double
        let aesthetic: Double?
        let displayEdge: Int
        let decodedEdge: Int
    }

    @Test("Measure sharpness, clipping, noise and aesthetics over the corpus", .enabled(if: ProcessInfo.processInfo.environment["PST_MEASURE_SCORES"] != nil))
    func measure() async throws {
        let manifest = FormatManifest.shared
        var urls: [(String, URL)] = []
        for sample in manifest.samples {
            let dir = sample.source == "corpus" ? manifest.corpusDirectory : FormatManifest.directory.appendingPathComponent("bitmaps")
            let url = dir.appendingPathComponent(sample.file)
            if FileManager.default.fileExists(atPath: url.path) { urls.append((sample.id, url)) }
        }
        var rows: [Row] = []
        for (id, url) in urls {
            guard let shown = await FormatProbe.displayImage(url),
                  let decoded = ThumbnailLoader.decodeThumbnail(from: url, maxPixelSize: ThumbnailTier.preview.rawValue, strategy: .fullDecode) else { continue }
            let a = QualityScoringService.scoreTechnical(cgImage: shown)
            let b = QualityScoringService.scoreTechnical(cgImage: decoded)
            let aesthetic = await QualityScoringService.evaluateAesthetics(cgImage: shown)?.mappedScore
            rows.append(Row(id: id, displaySharpness: a.sharpness, decodedSharpness: b.sharpness, noise: a.noise,
                            highlight: a.highlightClipping, shadow: a.shadowClipping, aesthetic: aesthetic,
                            displayEdge: max(shown.width, shown.height), decodedEdge: max(decoded.width, decoded.height)))
        }
        var out = "id | display edge | display sharpness | decoded edge | decoded sharpness | noise | highlight % | shadow % | aesthetic\n"
        for r in rows {
            out += "\(r.id) | \(r.displayEdge) | \(String(format: "%.2f", r.displaySharpness)) | \(r.decodedEdge) | "
                + "\(String(format: "%.2f", r.decodedSharpness)) | \(String(format: "%.2f", r.noise)) | "
                + "\(String(format: "%.2f", r.highlight)) | \(String(format: "%.2f", r.shadow)) | \(r.aesthetic.map { String(format: "%.2f", $0) } ?? "n/a")\n"
        }
        func summary(_ label: String, _ values: [Double]) -> String {
            let bands = SharpnessBands.standard
            let n = Double(max(1, values.count))
            let blurry = values.filter { bands.category(for: $0) == .blurry }.count
            let sharp = values.filter { bands.category(for: $0) == .sharp }.count
            let clamped = values.filter { $0 <= 0 || $0 >= 100 }.count
            let distinct = Set(values.map { Int($0.rounded()) }).count
            return "\(label): n=\(values.count) distinct whole values=\(distinct) clamped(0/100)=\(clamped) "
                + "blurry=\(Int(Double(blurry) / n * 100))% acceptable=\(Int(Double(values.count - blurry - sharp) / n * 100))% sharp=\(Int(Double(sharp) / n * 100))%\n"
        }
        out += summary("display (embedded preview where used)", rows.map(\.displaySharpness))
        out += summary("decoded (full decode at 2048)", rows.map(\.decodedSharpness))
        let flips = rows.filter { SharpnessBands.standard.category(for: $0.displaySharpness) != SharpnessBands.standard.category(for: $0.decodedSharpness) }.map(\.id)
        out += "band flips between embedded preview and full decode: \(flips.count) \(flips)\n"
        let aesthetics = rows.compactMap(\.aesthetic)
        out += "aesthetic: n=\(aesthetics.count) min=\(aesthetics.min() ?? 0) max=\(aesthetics.max() ?? 0) distinct=\(Set(aesthetics.map { ($0 * 10).rounded() }).count)\n"
        try out.write(toFile: ProcessInfo.processInfo.environment["PST_MEASURE_SCORES"]!, atomically: true, encoding: .utf8)
    }
}
