import Foundation
import CryptoKit

/// Represents a cluster of duplicate files sharing identical byte size and cryptographic hash.
public struct DuplicateCluster: Sendable, Equatable, Identifiable {
    public var id: String { hash }

    /// Hexadecimal SHA256 checksum of the file content.
    public let hash: String

    /// Exact file size in bytes.
    public let fileSize: Int64

    /// List of file URLs belonging to this duplicate group (>= 2 files).
    public let fileURLs: [URL]

    public init(hash: String, fileSize: Int64, fileURLs: [URL]) {
        self.hash = hash
        self.fileSize = fileSize
        self.fileURLs = fileURLs
    }
}

/// High-performance duplicate detection service using two-stage size filtering and streaming CryptoKit SHA256.
public final class DuplicateFinder: Sendable {

    /// Finds duplicate clusters among an explicit array of file URLs.
    /// - Parameter urls: Candidate file URLs to analyze.
    /// - Returns: An array of `DuplicateCluster` instances with 2 or more files.
    public static func findDuplicates(in urls: [URL]) async throws -> [DuplicateCluster] {
        // Stage 1: Group files by byte size. Files with unique sizes are skipped.
        var sizeGroups: [Int64: [URL]] = [:]

        for rawURL in urls {
            try Task.checkCancellation()

            let url = rawURL.standardizedFileURL.resolvingSymlinksInPath()

            guard let resourceValues = try? url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]),
                  resourceValues.isRegularFile == true,
                  let size = resourceValues.fileSize,
                  size > 0 else {
                continue
            }

            let int64Size = Int64(size)
            sizeGroups[int64Size, default: []].append(url)
        }

        // Keep only groups with at least 2 files sharing the exact size
        let potentialDuplicates = sizeGroups.filter { $0.value.count > 1 }
        if potentialDuplicates.isEmpty {
            return []
        }

        // Stage 2: Streaming SHA256 content hashing for files in colliding size groups
        var clusters: [DuplicateCluster] = []

        for (fileSize, collidingURLs) in potentialDuplicates {
            try Task.checkCancellation()

            var hashGroups: [String: [URL]] = [:]
            for url in collidingURLs {
                try Task.checkCancellation()

                do {
                    let hash = try computeSHA256(for: url)
                    hashGroups[hash, default: []].append(url)
                } catch {
                    // Skip files that cannot be read
                    continue
                }
            }

            for (hash, matchedURLs) in hashGroups where matchedURLs.count > 1 {
                clusters.append(DuplicateCluster(
                    hash: hash,
                    fileSize: fileSize,
                    fileURLs: matchedURLs.sorted { $0.lastPathComponent < $1.lastPathComponent }
                ))
            }
        }

        // Sort clusters by potential wasted space (descending)
        clusters.sort { cluster1, cluster2 in
            let wasted1 = cluster1.fileSize * Int64(cluster1.fileURLs.count - 1)
            let wasted2 = cluster2.fileSize * Int64(cluster2.fileURLs.count - 1)
            return wasted1 > wasted2
        }

        return clusters
    }

    /// Finds duplicate clusters in a directory.
    /// - Parameters:
    ///   - directoryURL: The directory to scan.
    ///   - recursive: Whether to scan subdirectories.
    /// - Returns: Discovered duplicate clusters.
    public static func findDuplicates(
        in directoryURL: URL,
        recursive: Bool = false
    ) async throws -> [DuplicateCluster] {
        let candidateURLs = collectURLs(in: directoryURL, recursive: recursive)
        return try await findDuplicates(in: candidateURLs)
    }

    /// Folders that hold filed photos, never scanned for duplicates: Selection and Rejected.
    private static func isOutputFolder(_ name: String) -> Bool {
        name.caseInsensitiveCompare("Selection") == .orderedSame
            || name.caseInsensitiveCompare(RejectsDestination.folderName) == .orderedSame
    }

    private static func collectURLs(in directoryURL: URL, recursive: Bool) -> [URL] {
        let fileManager = FileManager.default
        let options: FileManager.DirectoryEnumerationOptions = recursive
            ? [.skipsHiddenFiles]
            : [.skipsHiddenFiles, .skipsSubdirectoryDescendants]

        let canonicalDirectory = directoryURL.standardizedFileURL.resolvingSymlinksInPath()
        let rootComponents = canonicalDirectory.pathComponents

        guard let enumerator = fileManager.enumerator(
            at: directoryURL,
            includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey],
            options: options
        ) else {
            return []
        }

        var candidateURLs: [URL] = []
        while let item = enumerator.nextObject() as? URL {
            let canonicalItem = item.standardizedFileURL.resolvingSymlinksInPath()
            let itemComponents = canonicalItem.pathComponents

            // Determine directory path components relative to the scanned directory
            let dirComponents: [String]
            if itemComponents.starts(with: rootComponents) {
                let relative = Array(itemComponents.dropFirst(rootComponents.count))
                // If encountering a Selection directory directly under root during recursive scan, skip descendants
                if relative.count == 1, let first = relative.first, Self.isOutputFolder(first) {
                    enumerator.skipDescendants()
                    continue
                }
                dirComponents = Array(relative.dropLast())
            } else {
                dirComponents = Array(item.deletingLastPathComponent().pathComponents)
            }

            // Exclude files residing inside any "Selection" subfolder under the scanned directory
            if dirComponents.contains(where: Self.isOutputFolder) {
                continue
            }
            candidateURLs.append(item)
        }
        return candidateURLs
    }

    /// Computes streaming SHA256 checksum in 64 KB memory chunks using Apple CryptoKit.
    /// - Parameters:
    ///   - fileURL: File to hash.
    ///   - bufferSize: Chunk size (default 64 KB).
    /// - Returns: Hexadecimal lowercase SHA256 string.
    public static func computeSHA256(for fileURL: URL, bufferSize: Int = 64 * 1024) throws -> String {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }

        var hasher = SHA256()
        while true {
            let data = handle.readData(ofLength: bufferSize)
            if data.isEmpty {
                break
            }
            hasher.update(data: data)
        }

        let digest = hasher.finalize()
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
