import Foundation

/// Records the exact source URL and resulting Trash URL for a trashed file.
public struct TrashedFilePair: Sendable, Equatable {
    public let originalURL: URL
    public let trashedURL: URL

    public init(originalURL: URL, trashedURL: URL) {
        self.originalURL = originalURL
        self.trashedURL = trashedURL
    }
}

/// Errors encountered during file culling operations.
public enum FileCullingError: Error, LocalizedError, Sendable {
    case fileNotFound(URL)
    case directoryCreationFailed(URL, String)
    case moveFailed(source: URL, destination: URL, reason: String)
    case copyFailed(source: URL, destination: URL, reason: String)
    case trashFailed(url: URL, reason: String)
    case partialTrashRollbackFailed(url: URL, reason: String, remainingTrashedPairs: [TrashedFilePair])
    /// A move copied (and size-verified) a member to the destination but could not remove the original.
    /// The destination copies are kept; `unremovedSources` still exist at their original paths.
    /// `rollbackFailed` lists members that were moved and could not be put back after a later member
    /// failed (D-12): they stay in the destination folder and are part of `destinations`.
    case partialMove(destinations: [URL], unremovedSources: [URL], reason: String, rollbackFailed: [URL])

    public var errorDescription: String? {
        switch self {
        case .fileNotFound(let url):
            return "File not found at path: \(url.path)"
        case .directoryCreationFailed(let url, let reason):
            return "Failed to create directory at \(url.path): \(reason)"
        case .moveFailed(let src, let dst, let reason):
            return "Failed to move file from \(src.lastPathComponent) to \(dst.lastPathComponent): \(reason)"
        case .copyFailed(let src, let dst, let reason):
            return "Failed to copy file from \(src.lastPathComponent) to \(dst.lastPathComponent): \(reason)"
        case .trashFailed(let url, let reason):
            return "Failed to move file to trash \(url.lastPathComponent): \(reason)"
        case .partialTrashRollbackFailed(let url, let reason, let remaining):
            return "Failed to move file to trash \(url.lastPathComponent): \(reason) (\(remaining.count) companion file(s) remain in Trash)"
        case .partialMove(_, let unremoved, let reason, let stranded):
            if !stranded.isEmpty {
                let names = stranded.map(\.lastPathComponent).joined(separator: ", ")
                return "Move interrupted — could not put back: \(names) (\(reason))"
            }
            let names = unremoved.map(\.lastPathComponent).joined(separator: ", ")
            return "Copied to the destination folder — original could not be removed: \(names) (\(reason))"
        }
    }
}

/// Abstraction for moving a file to the trash, enabling deterministic test injection without
/// environment-variable branches in production code.
public protocol TrashService: Sendable {
    func trash(_ url: URL) throws -> URL
}

/// Production trash implementation backed by `FileManager.default.trashItem`.
public struct SystemTrashService: TrashService, @unchecked Sendable {
    private let fileManager: FileManager

    public init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
    }

    public func trash(_ url: URL) throws -> URL {
        var resultingURL: NSURL?
        do {
            try fileManager.trashItem(at: url, resultingItemURL: &resultingURL)
        } catch {
            throw FileCullingError.trashFailed(url: url, reason: error.localizedDescription)
        }
        guard let trashedURL = resultingURL as URL? else {
            throw FileCullingError.trashFailed(
                url: url,
                reason: "System trash did not return a resulting item URL."
            )
        }
        return trashedURL
    }
}

/// Manages culling file actions (Selection folder move/copy, system trash) and companion file discovery.
public final class FileCullingManager: @unchecked Sendable {

    private let fileManager: FileManager
    public let trashService: any TrashService

    public init(
        fileManager: FileManager = .default,
        trashService: any TrashService = SystemTrashService()
    ) {
        self.fileManager = fileManager
        self.trashService = trashService
    }

    // MARK: - Companion File Discovery

    /// Finds all companion files associated with the specified primary photo file in the same directory.
    /// Includes RAW+JPEG pairs, XMP sidecars, and Lightroom edits (-Edit.*).
    /// - Parameter photoURL: The primary photo file URL.
    /// - Returns: An array of URLs including the primary photo and all discovered companions.
    public func findCompanionFiles(for photoURL: URL) -> [URL] {
        // Resolve the *folder* only: a symlinked photo's companions are its neighbours in the
        // folder being culled, not the files next to the link target.
        let parentDir = photoURL.standardizedFileURL.deletingLastPathComponent().resolvingSymlinksInPath()
        let canonicalPrimary = parentDir.appendingPathComponent(photoURL.lastPathComponent)
        guard let contents = try? fileManager.contentsOfDirectory(
            at: parentDir,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else {
            return [photoURL]
        }

        let primaryFilename = canonicalPrimary.lastPathComponent
        let primaryStem = canonicalPrimary.deletingPathExtension().lastPathComponent
        let stemLower = primaryStem.lowercased()
        // Lightroom edits and Enhanced-NR (Adobe Denoise) derivatives travel with their shot.
        let derivativePrefixes = ["-edit", "_edit", "-enhanced-nr", "_enhanced-nr"].map { stemLower + $0 }
        let dotXmp = primaryFilename.lowercased() + ".xmp"

        var companions = Set<URL>()
        companions.insert(photoURL)

        let baseDir = photoURL.deletingLastPathComponent()

        for rawCandidate in contents {
            if rawCandidate.lastPathComponent == canonicalPrimary.lastPathComponent { continue }
            let candidateName = rawCandidate.lastPathComponent
            let candidateNameLower = candidateName.lowercased()
            let candidateStemLower = rawCandidate.deletingPathExtension().lastPathComponent.lowercased()

            let candidateURL = baseDir.appendingPathComponent(candidateName)

            // 1. Same stem, different extension (e.g. DSC0001.ARW -> DSC0001.JPG, DSC0001.xmp)
            if candidateStemLower == stemLower {
                companions.insert(candidateURL)
                continue
            }

            // 2. Full filename + .xmp sidecar (e.g. DSC0001.ARW.xmp)
            if candidateNameLower == dotXmp {
                companions.insert(candidateURL)
                continue
            }

            // 3. Lightroom edits and Enhanced-NR derivatives (e.g. DSC0001-Edit.tif, DSC0001-Edit.xmp,
            //    DSC0001-Enhanced-NR.dng)
            if derivativePrefixes.contains(where: { candidateStemLower.hasPrefix($0) }) {
                companions.insert(candidateURL)
                continue
            }
        }

        // Return primary first, then companions sorted alphabetically
        let others = companions.filter { $0 != photoURL }.sorted { $0.lastPathComponent < $1.lastPathComponent }
        return [photoURL] + others
    }

    // MARK: - Collision-Free Bundle Naming

    /// Applies a numeric collision suffix (`-2`, `-3`, …) to a primary or companion filename
    /// while preserving companion pairing for standard files, double-extension `.xmp` sidecars,
    /// and Lightroom/Capture One `-Edit` / `_Edit` derivatives.
    ///
    /// Examples for `primaryStem = "IMG_0001"`, `suffix = 2`:
    /// - `"IMG_0001.CR3"` -> `"IMG_0001-2.CR3"`
    /// - `"IMG_0001.JPG"` -> `"IMG_0001-2.JPG"`
    /// - `"IMG_0001.xmp"` -> `"IMG_0001-2.xmp"`
    /// - `"IMG_0001.CR3.xmp"` -> `"IMG_0001-2.CR3.xmp"`
    /// - `"IMG_0001-Edit.tif"` -> `"IMG_0001-2-Edit.tif"`
    /// - `"IMG_0001_Edit.tif"` -> `"IMG_0001-2_Edit.tif"`
    /// - `"IMG_0001-Edit.xmp"` -> `"IMG_0001-2-Edit.xmp"`
    public static func suffixedCompanionFilename(
        _ filename: String,
        primaryStem: String,
        suffix: Int
    ) -> String {
        if !primaryStem.isEmpty,
           let prefixRange = filename.range(of: primaryStem, options: [.caseInsensitive, .anchored]) {
            let stemPart = filename[prefixRange]
            let remainder = filename[prefixRange.upperBound...]
            if remainder.isEmpty || remainder.first == "." || remainder.first == "-" || remainder.first == "_" {
                return "\(stemPart)-\(suffix)\(remainder)"
            }
        }

        let ext = (filename as NSString).pathExtension
        let base = (filename as NSString).deletingPathExtension
        return ext.isEmpty ? "\(filename)-\(suffix)" : "\(base)-\(suffix).\(ext)"
    }

    private func isSameCanonicalFile(_ a: URL, _ b: URL) -> Bool {
        let canonicalA = a.standardizedFileURL.resolvingSymlinksInPath()
        let canonicalB = b.standardizedFileURL.resolvingSymlinksInPath()
        return (a.standardizedFileURL.path == b.standardizedFileURL.path)
            || (canonicalA.path.caseInsensitiveCompare(canonicalB.path) == .orderedSame)
    }

    /// Resolves collision-free destination URLs in `selectionDir` for all existing files in `targets`.
    /// If any non-self target collides at its unsuffixed destination name, the entire bundle receives
    /// the lowest integer suffix `N >= 2` for which every member's destination path is free.
    private func resolveBundleDestinations(
        for targets: [URL],
        primaryStem: String,
        in selectionDir: URL
    ) -> [(source: URL, destination: URL, isSelfTransfer: Bool)] {
        let existingTargets = targets.filter { fileManager.fileExists(atPath: $0.path) }
        guard !existingTargets.isEmpty else { return [] }

        var initialItems: [(source: URL, unsuffixedDestination: URL, isSelfTransfer: Bool)] = []
        var hasCollision = false

        for target in existingTargets {
            let unsuffixedDest = selectionDir.appendingPathComponent(target.lastPathComponent)
            let isSelf = isSameCanonicalFile(target, unsuffixedDest)
            if !isSelf && fileManager.fileExists(atPath: unsuffixedDest.path) {
                hasCollision = true
            }
            initialItems.append((source: target, unsuffixedDestination: unsuffixedDest, isSelfTransfer: isSelf))
        }

        guard hasCollision else {
            return initialItems.map {
                (source: $0.source, destination: $0.unsuffixedDestination, isSelfTransfer: $0.isSelfTransfer)
            }
        }

        var suffix = 2
        while true {
            let allFree = initialItems.allSatisfy { item in
                if item.isSelfTransfer { return true }
                let suffixedName = Self.suffixedCompanionFilename(
                    item.source.lastPathComponent,
                    primaryStem: primaryStem,
                    suffix: suffix
                )
                let candidateDest = selectionDir.appendingPathComponent(suffixedName)
                return !fileManager.fileExists(atPath: candidateDest.path)
            }
            if allFree {
                break
            }
            suffix += 1
        }

        return initialItems.map { item in
            if item.isSelfTransfer {
                return (source: item.source, destination: item.unsuffixedDestination, isSelfTransfer: true)
            }
            let suffixedName = Self.suffixedCompanionFilename(
                item.source.lastPathComponent,
                primaryStem: primaryStem,
                suffix: suffix
            )
            let dest = selectionDir.appendingPathComponent(suffixedName)
            return (source: item.source, destination: dest, isSelfTransfer: false)
        }
    }

    // MARK: - Move to Selection

    /// Marker filename placed inside any Selection folder created or used as a destination.
    public static let selectionMarkerFilename = DirectoryScanner.selectionMarkerFilename

    /// Writes an empty `.photoselector-selection` marker file inside `selectionDir` if not already present.
    private func writeSelectionMarkerIfNeeded(in selectionDir: URL) {
        let markerURL = selectionDir.appendingPathComponent(Self.selectionMarkerFilename)
        if !fileManager.fileExists(atPath: markerURL.path) {
            try? Data().write(to: markerURL, options: .atomic)
        }
    }

    /// Moves the primary photo and all associated companion files to `<rootURL>/Selection/`.
    /// Never overwrites existing files: if any bundle member collides in `Selection/`, the entire
    /// bundle receives a shared numeric suffix (`-2`, `-3`, …).
    /// - Parameters:
    ///   - photoURL: The primary photo URL to move.
    ///   - rootURL: The root album / SD card directory.
    /// - Returns: The list of destination file URLs moved into the Selection folder.
    public func moveToSelection(
        photoURL: URL,
        rootURL: URL,
        destinationFolderName: String = AppSettingsDefaults.cullingDestinationFolderName
    ) throws -> [URL] {
        guard fileManager.fileExists(atPath: photoURL.path) else {
            throw FileCullingError.fileNotFound(photoURL)
        }

        let folder = AppSettings.sanitizeDestinationFolderName(destinationFolderName)
        let selectionDir = rootURL.appendingPathComponent(folder, isDirectory: true)
        if !fileManager.fileExists(atPath: selectionDir.path) {
            do {
                try fileManager.createDirectory(at: selectionDir, withIntermediateDirectories: true)
            } catch {
                throw FileCullingError.directoryCreationFailed(selectionDir, error.localizedDescription)
            }
        }
        writeSelectionMarkerIfNeeded(in: selectionDir)

        let targets = findCompanionFiles(for: photoURL)
        let primaryStem = photoURL.deletingPathExtension().lastPathComponent
        let resolvedPlan = resolveBundleDestinations(
            for: targets,
            primaryStem: primaryStem,
            in: selectionDir
        )

        var movedURLs: [URL] = []
        var movedPairs: [(source: URL, destination: URL)] = []
        // Members whose verified copy is at the destination while the original could not be removed.
        var unremovedPairs: [(source: URL, destination: URL)] = []
        var firstUnremovedReason: String?

        for item in resolvedPlan {
            // Guard against self-move: if target already resides at destination,
            // preserve the file without deletion or redundant filesystem move.
            if item.isSelfTransfer {
                movedURLs.append(item.destination)
                continue
            }

            do {
                try fileManager.moveItem(at: item.source, to: item.destination)
                movedURLs.append(item.destination)
                movedPairs.append((source: item.source, destination: item.destination))
            } catch {
                // A cross-volume move that copied the file but could not remove the original
                // leaves a verified copy. That copy is never deleted to force a rollback
                // (docs/shared/FILE_OPERATIONS.md §4): keep it and report a partial result.
                if hasVerifiedCopyWithSurvivingSource(source: item.source, destination: item.destination) {
                    movedURLs.append(item.destination)
                    unremovedPairs.append((source: item.source, destination: item.destination))
                    firstUnremovedReason = firstUnremovedReason ?? error.localizedDescription
                    continue
                }
                // Roll back all previously moved companion files in this transaction. A member that
                // cannot be put back stays in the destination folder; it is reported, never dropped.
                var stranded: [URL] = []
                for pair in movedPairs.reversed() {
                    if fileManager.fileExists(atPath: pair.destination.path) {
                        do {
                            try fileManager.moveItem(at: pair.destination, to: pair.source)
                        } catch {
                            stranded.append(pair.destination)
                        }
                    }
                }
                if !stranded.isEmpty || !unremovedPairs.isEmpty {
                    let keep = Set((stranded + unremovedPairs.map(\.destination)).map(\.path))
                    throw FileCullingError.partialMove(
                        destinations: movedURLs.filter { keep.contains($0.path) },
                        unremovedSources: unremovedPairs.map(\.source),
                        reason: error.localizedDescription,
                        rollbackFailed: stranded.reversed()
                    )
                }
                throw FileCullingError.moveFailed(
                    source: item.source,
                    destination: item.destination,
                    reason: error.localizedDescription
                )
            }
        }

        if !unremovedPairs.isEmpty {
            throw FileCullingError.partialMove(
                destinations: movedURLs,
                unremovedSources: unremovedPairs.map(\.source),
                reason: firstUnremovedReason ?? "the original could not be removed",
                rollbackFailed: []
            )
        }
        return movedURLs
    }

    /// True when `destination` (a name that did not exist before this move) holds a complete copy of
    /// `source` — same non-zero byte size — and `source` is still there.
    private func hasVerifiedCopyWithSurvivingSource(source: URL, destination: URL) -> Bool {
        guard fileManager.fileExists(atPath: source.path),
              fileManager.fileExists(atPath: destination.path),
              let sourceSize = (try? fileManager.attributesOfItem(atPath: source.path))?[.size] as? NSNumber,
              let destinationSize = (try? fileManager.attributesOfItem(atPath: destination.path))?[.size] as? NSNumber
        else { return false }
        return sourceSize.intValue > 0 && sourceSize == destinationSize
    }

    // MARK: - Copy to Selection

    /// Copies the primary photo and all associated companion files to `<rootURL>/Selection/`.
    /// Never overwrites existing files: if any bundle member collides in `Selection/`, the entire
    /// bundle receives a shared numeric suffix (`-2`, `-3`, …).
    /// - Parameters:
    ///   - photoURL: The primary photo URL to copy.
    ///   - rootURL: The root album / SD card directory.
    /// - Returns: The list of destination file URLs copied into the Selection folder.
    public func copyToSelection(
        photoURL: URL,
        rootURL: URL,
        destinationFolderName: String = AppSettingsDefaults.cullingDestinationFolderName
    ) throws -> [URL] {
        guard fileManager.fileExists(atPath: photoURL.path) else {
            throw FileCullingError.fileNotFound(photoURL)
        }

        let folder = AppSettings.sanitizeDestinationFolderName(destinationFolderName)
        let selectionDir = rootURL.appendingPathComponent(folder, isDirectory: true)
        if !fileManager.fileExists(atPath: selectionDir.path) {
            do {
                try fileManager.createDirectory(at: selectionDir, withIntermediateDirectories: true)
            } catch {
                throw FileCullingError.directoryCreationFailed(selectionDir, error.localizedDescription)
            }
        }
        writeSelectionMarkerIfNeeded(in: selectionDir)

        let targets = findCompanionFiles(for: photoURL)
        let primaryStem = photoURL.deletingPathExtension().lastPathComponent
        let resolvedPlan = resolveBundleDestinations(
            for: targets,
            primaryStem: primaryStem,
            in: selectionDir
        )

        var copiedURLs: [URL] = []
        var copiedDestinations: [URL] = []

        for item in resolvedPlan {
            // Guard against self-copy: if target already resides at destination,
            // preserve the file without deletion or redundant filesystem copy.
            if item.isSelfTransfer {
                copiedURLs.append(item.destination)
                continue
            }

            do {
                try fileManager.copyItem(at: item.source, to: item.destination)
                copiedURLs.append(item.destination)
                copiedDestinations.append(item.destination)
            } catch {
                if fileManager.fileExists(atPath: item.destination.path) {
                    // Clean up partial destination file created by this failed copy operation itself
                    try? fileManager.removeItem(at: item.destination)
                }
                for createdDestination in copiedDestinations.reversed() {
                    if fileManager.fileExists(atPath: createdDestination.path) {
                        // Clean up companion replica created earlier in this failed copy transaction itself
                        try? fileManager.removeItem(at: createdDestination)
                    }
                }
                throw FileCullingError.copyFailed(
                    source: item.source,
                    destination: item.destination,
                    reason: error.localizedDescription
                )
            }
        }

        return copiedURLs
    }

    // MARK: - Move to Trash

    /// Sends the primary photo and all associated companion files to the macOS system trash,
    /// returning the exact `(originalURL, trashedURL)` pairs for non-destructive undo.
    /// If trashing a companion fails mid-bundle, previously trashed members are rolled back
    /// using non-overwriting `moveItem` (with collision-free suffixing if the original path
    /// became occupied). If any rollback itself fails, throws `.partialTrashRollbackFailed`
    /// carrying the remaining trashed pairs so the caller preserves the undo entry.
    public func moveToTrashWithPairs(photoURL: URL) throws -> [TrashedFilePair] {
        guard fileManager.fileExists(atPath: photoURL.path) else {
            throw FileCullingError.fileNotFound(photoURL)
        }

        let targets = findCompanionFiles(for: photoURL)
        let primaryStem = photoURL.deletingPathExtension().lastPathComponent
        let originalDir = photoURL.deletingLastPathComponent()
        var trashedPairs: [TrashedFilePair] = []

        for target in targets {
            guard fileManager.fileExists(atPath: target.path) else { continue }
            do {
                let res = try trashService.trash(target)
                trashedPairs.append(TrashedFilePair(originalURL: target, trashedURL: res))
            } catch {
                let remainingTrashed = rollbackTrashedPairs(
                    trashedPairs,
                    originalDir: originalDir,
                    primaryStem: primaryStem
                )
                if !remainingTrashed.isEmpty {
                    let reason = (error as? FileCullingError)?.errorDescription ?? error.localizedDescription
                    throw FileCullingError.partialTrashRollbackFailed(
                        url: target,
                        reason: reason,
                        remainingTrashedPairs: remainingTrashed
                    )
                }
                if let cullingError = error as? FileCullingError {
                    throw cullingError
                }
                throw FileCullingError.trashFailed(url: target, reason: error.localizedDescription)
            }
        }

        return trashedPairs
    }

    /// Sends the primary photo and all associated companion files to the macOS system trash.
    /// - Parameter photoURL: The primary photo URL to trash.
    /// - Returns: The list of resulting URLs in the trash.
    public func moveToTrash(photoURL: URL) throws -> [URL] {
        try moveToTrashWithPairs(photoURL: photoURL).map(\.trashedURL)
    }

    /// Rolls back already-trashed companion files to `originalDir` without overwriting any
    /// newly created files at their original paths, and returns any `TrashedFilePair`s whose
    /// rollback failed (meaning those files remain in the Trash and must stay undoable).
    private func rollbackTrashedPairs(
        _ pairs: [TrashedFilePair],
        originalDir: URL,
        primaryStem: String
    ) -> [TrashedFilePair] {
        guard !pairs.isEmpty else { return [] }

        let anyOccupied = pairs.contains { fileManager.fileExists(atPath: $0.originalURL.path) }
        let targetDestinations: [URL]
        if !anyOccupied {
            targetDestinations = pairs.map(\.originalURL)
        } else {
            var suffix = 2
            while true {
                let allFree = pairs.allSatisfy { pair in
                    let suffixedName = Self.suffixedCompanionFilename(
                        pair.originalURL.lastPathComponent,
                        primaryStem: primaryStem,
                        suffix: suffix
                    )
                    let candidateDest = originalDir.appendingPathComponent(suffixedName)
                    return !fileManager.fileExists(atPath: candidateDest.path)
                }
                if allFree {
                    break
                }
                suffix += 1
            }
            targetDestinations = pairs.map { pair in
                let suffixedName = Self.suffixedCompanionFilename(
                    pair.originalURL.lastPathComponent,
                    primaryStem: primaryStem,
                    suffix: suffix
                )
                return originalDir.appendingPathComponent(suffixedName)
            }
        }

        var remainingInReverse: [TrashedFilePair] = []
        for (pair, restoreDest) in zip(pairs, targetDestinations).reversed() {
            guard fileManager.fileExists(atPath: pair.trashedURL.path) else { continue }
            do {
                try fileManager.moveItem(at: pair.trashedURL, to: restoreDest)
            } catch {
                remainingInReverse.append(pair)
            }
        }
        return remainingInReverse.reversed()
    }
}
