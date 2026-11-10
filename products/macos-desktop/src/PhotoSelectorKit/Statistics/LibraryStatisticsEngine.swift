import Foundation

/// High-performance aggregation engine computing storage, format distributions,
/// and quality leaderboards with linear O(N) complexity.
public final class LibraryStatisticsEngine: Sendable {

    /// Normalizes file extensions so synonymous formats (e.g., JPG and JPEG) group cleanly:
    /// every extension maps to its format's canonical (first) extension in `SupportedFormats`.
    public static func normalizeExtension(_ ext: String) -> String {
        SupportedFormats.format(forExtension: ext)?.extensions.first ?? ext.lowercased()
    }

    /// Professional display name of a file extension's format (e.g. "arw" → "Sony ARW").
    public static func canonicalFormatName(for pathExtension: String) -> String {
        SupportedFormats.format(forExtension: pathExtension)?.displayName ?? pathExtension.uppercased()
    }

    /// Calculates library statistics synchronously from an in-memory array of PhotoItems.
    public static func calculate(from photos: [PhotoItem]) -> LibraryStatistics {
        guard !photos.isEmpty else { return .empty }

        var candidateCount = 0
        var selectedCount = 0
        var copiedCount = 0
        var trashedCount = 0

        var totalBytes: Int64 = 0
        var selectedBytes: Int64 = 0
        var trashedBytes: Int64 = 0

        var formatCounts: [String: (count: Int, bytes: Int64)] = [:]
        var focusCounts: [SharpnessCategory: Int] = [.blurry: 0, .acceptable: 0, .sharp: 0]
        var utilityCount = 0

        var totalSharpness: Double = 0
        var sharpnessCount: Int = 0

        var totalAesthetic: Double = 0
        var aestheticCount: Int = 0

        var focalLengthSums: [String: (sum: Double, count: Int)] = [:]
        var apertureSums: [String: (sum: Double, count: Int)] = [:]
        var shutterSpeedSums: [String: (sum: Double, count: Int)] = [:]

        for item in photos {
            // 1. Status breakdown
            switch item.status {
            case .candidate: candidateCount += 1
            case .selected: selectedCount += 1
            case .copied: copiedCount += 1
            case .trashed: trashedCount += 1
            }

            // 2. Storage size inspection
            let itemBytes: Int64 = (try? item.url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
            totalBytes += itemBytes

            if item.status == .selected || item.status == .copied {
                selectedBytes += itemBytes
            } else if item.status == .trashed {
                trashedBytes += itemBytes
            }

            // 3. Format categorization (normalized)
            let rawExt = item.url.pathExtension.lowercased()
            let normalizedExt = normalizeExtension(rawExt)
            let existing = formatCounts[normalizedExt, default: (count: 0, bytes: 0)]
            formatCounts[normalizedExt] = (count: existing.count + 1, bytes: existing.bytes + itemBytes)

            // 4. Quality distributions & EXIF × sharpness buckets
            if let scores = item.scores {
                totalSharpness += scores.sharpness
                sharpnessCount += 1
                focusCounts[scores.focusCategory, default: 0] += 1

                if let aesthetic = scores.aesthetic {
                    totalAesthetic += aesthetic
                    aestheticCount += 1
                }

                if scores.isUtility {
                    utilityCount += 1
                }

                if let exif = item.exif {
                    let focus = scores.focusScore
                    if let flKey = exif.formattedFocalLengthOrNil {
                        let cur = focalLengthSums[flKey, default: (sum: 0, count: 0)]
                        focalLengthSums[flKey] = (sum: cur.sum + focus, count: cur.count + 1)
                    }
                    if let apKey = exif.formattedApertureOrNil {
                        let cur = apertureSums[apKey, default: (sum: 0, count: 0)]
                        apertureSums[apKey] = (sum: cur.sum + focus, count: cur.count + 1)
                    }
                    if let ssKey = exif.formattedShutterSpeedOrNil {
                        let cur = shutterSpeedSums[ssKey, default: (sum: 0, count: 0)]
                        shutterSpeedSums[ssKey] = (sum: cur.sum + focus, count: cur.count + 1)
                    }
                }
            }
        }

        let focalLengthSharpness = focalLengthSums.mapValues { $0.sum / Double($0.count) }
        let apertureSharpness = apertureSums.mapValues { $0.sum / Double($0.count) }
        let shutterSpeedSharpness = shutterSpeedSums.mapValues { $0.sum / Double($0.count) }

        // 5. Format statistics list
        let totalCount = photos.count
        let formatStats: [FormatStatistic] = formatCounts.map { ext, data in
            let percentage = totalCount > 0 ? (Double(data.count) / Double(totalCount)) * 100.0 : 0.0
            return FormatStatistic(
                displayName: canonicalFormatName(for: ext),
                rawExtension: ext,
                count: data.count,
                totalBytes: data.bytes,
                percentageOfTotal: percentage
            )
        }.sorted {
            if $0.count != $1.count {
                return $0.count > $1.count
            }
            return $0.displayName < $1.displayName
        }

        // 6. Score averages (guarded against division by zero)
        let avgSharpness = sharpnessCount > 0 ? (totalSharpness / Double(sharpnessCount)) : nil
        let avgAesthetic = aestheticCount > 0 ? (totalAesthetic / Double(aestheticCount)) : nil

        // 7. Top 5 Sharpest Photos (descending order)
        let topSharpest = photos
            .filter { $0.scores != nil }
            .sorted { ($0.scores?.sharpness ?? 0) > ($1.scores?.sharpness ?? 0) }
            .prefix(5)

        // 8. Top 5 Aesthetic Photos (descending order)
        let topAesthetic = photos
            .filter { $0.scores?.aesthetic != nil }
            .sorted { ($0.scores?.aesthetic ?? 0) > ($1.scores?.aesthetic ?? 0) }
            .prefix(5)

        return LibraryStatistics(
            totalPhotoCount: totalCount,
            candidateCount: candidateCount,
            selectedCount: selectedCount,
            copiedCount: copiedCount,
            trashedCount: trashedCount,
            totalStorageBytes: totalBytes,
            selectedStorageBytes: selectedBytes,
            trashedStorageBytes: trashedBytes,
            formatStatistics: formatStats,
            averageSharpness: avgSharpness,
            averageAesthetic: avgAesthetic,
            focusDistribution: focusCounts,
            utilityPhotoCount: utilityCount,
            topSharpestPhotos: Array(topSharpest),
            topAestheticPhotos: Array(topAesthetic),
            focalLengthSharpness: focalLengthSharpness,
            apertureSharpness: apertureSharpness,
            shutterSpeedSharpness: shutterSpeedSharpness,
            sharpnessScoredCount: sharpnessCount,
            aestheticScoredCount: aestheticCount
        )
    }

    /// Asynchronous computation offloaded to a detached background task.
    public static func calculateAsync(from photos: [PhotoItem]) async -> LibraryStatistics {
        await Task.detached(priority: .userInitiated) {
            calculate(from: photos)
        }.value
    }
}
