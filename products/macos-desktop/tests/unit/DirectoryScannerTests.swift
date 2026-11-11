import Testing
import Foundation
@testable import PhotoSelectorKit

@Suite("DirectoryScanner Unit Tests")
struct DirectoryScannerTests {

    private func createTempDir(prefix: String) throws -> URL {
        let tempBase = FileManager.default.temporaryDirectory
            .standardizedFileURL
            .resolvingSymlinksInPath()
        let dir = tempBase.appendingPathComponent("\(prefix)_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let canonical = (try? dir.resourceValues(forKeys: [.canonicalPathKey]))?.canonicalPath ?? dir.path
        return URL(fileURLWithPath: canonical, isDirectory: true)
    }

    @Test("REQ-MAC-EXIF.04, REQ-MAC-EXIF.05: SD Card Folder Structure: RAW-priority pairing with companions")
    func testSDCardRAWPriorityPairing() async throws {
        let root = try createTempDir(prefix: "SDCardTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let dcim = root.appendingPathComponent("DCIM/100CANON", isDirectory: true)
        try FileManager.default.createDirectory(at: dcim, withIntermediateDirectories: true)

        let rawURL = dcim.appendingPathComponent("IMG_0001.CR3")
        let jpgURL = dcim.appendingPathComponent("IMG_0001.JPG")
        let xmpURL = dcim.appendingPathComponent("IMG_0001.xmp")

        try "FAKE_RAW_DATA".data(using: .utf8)!.write(to: rawURL)
        try "FAKE_JPG_DATA".data(using: .utf8)!.write(to: jpgURL)
        try "<xmpmeta/>".data(using: .utf8)!.write(to: xmpURL)

        let scanner = DirectoryScanner()
        let items = try await scanner.scanDirectory(at: root, recursive: true)

        #expect(items.count == 1, "Expected exactly 1 PhotoItem due to pairing, found \(items.count)")
        guard let item = items.first else { return }

        #expect(item.url.pathExtension.lowercased() == "cr3", "Primary URL must be RAW (.CR3), got \(item.url.pathExtension)")
        #expect(item.filename == "IMG_0001.CR3")

        let cullingManager = FileCullingManager()
        let companions = cullingManager.findCompanionFiles(for: item.url)
        #expect(companions.count == 3)
        #expect(companions.contains(rawURL))
        #expect(companions.contains(jpgURL))
        #expect(companions.contains(xmpURL))
    }

    @Test("Companion pairing without RAW: Standard image designated as primary")
    func testCompanionPairingWithoutRAW() async throws {
        let root = try createTempDir(prefix: "NoRawTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let jpgURL = root.appendingPathComponent("DSC0002.JPG")
        let xmpURL = root.appendingPathComponent("DSC0002.xmp")

        try "FAKE_JPG".data(using: .utf8)!.write(to: jpgURL)
        try "<xmpmeta/>".data(using: .utf8)!.write(to: xmpURL)

        let scanner = DirectoryScanner()
        let items = try await scanner.scanDirectory(at: root, recursive: false)

        #expect(items.count == 1)
        #expect(items.first?.filename == "DSC0002.JPG")
        #expect(items.first?.url.pathExtension.lowercased() == "jpg")
    }

    @Test("REQ-MAC-EXIF.06: Strict Selection/ exclusion: True Selection/ subfolders are skipped")
    func testStrictSelectionExclusion() async throws {
        let root = try createTempDir(prefix: "SelectionExclusionTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let photo1 = root.appendingPathComponent("PHOTO1.JPG")
        try "PHOTO_1".data(using: .utf8)!.write(to: photo1)

        // Subfolder Selection/
        let selDir = root.appendingPathComponent("Selection", isDirectory: true)
        try FileManager.default.createDirectory(at: selDir, withIntermediateDirectories: true)
        let culled1 = selDir.appendingPathComponent("CULLED1.JPG")
        try "CULLED_1".data(using: .utf8)!.write(to: culled1)

        // Nested Sub/Selection/
        let nestedSelDir = root.appendingPathComponent("Sub/Selection", isDirectory: true)
        try FileManager.default.createDirectory(at: nestedSelDir, withIntermediateDirectories: true)
        let culled2 = nestedSelDir.appendingPathComponent("CULLED2.JPG")
        try "CULLED_2".data(using: .utf8)!.write(to: culled2)

        let scanner = DirectoryScanner()
        let items = try await scanner.scanDirectory(at: root, recursive: true)

        #expect(items.count == 1)
        #expect(items.first?.filename == "PHOTO1.JPG")
        #expect(!items.contains(where: { $0.filename == "CULLED1.JPG" }))
        #expect(!items.contains(where: { $0.filename == "CULLED2.JPG" }))
    }

    @Test("Substring directory toleration: Trip_Selection_Final/ is scanned cleanly")
    func testSubstringDirectoryToleration() async throws {
        let root = try createTempDir(prefix: "SubstringDirTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let tripSelDir = root.appendingPathComponent("Trip_Selection_Final", isDirectory: true)
        let preSelDir = root.appendingPathComponent("PreSelection", isDirectory: true)
        let sel2024Dir = root.appendingPathComponent("Selection2024", isDirectory: true)

        try FileManager.default.createDirectory(at: tripSelDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: preSelDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: sel2024Dir, withIntermediateDirectories: true)

        try "A".data(using: .utf8)!.write(to: tripSelDir.appendingPathComponent("img_a.jpg"))
        try "B".data(using: .utf8)!.write(to: preSelDir.appendingPathComponent("img_b.jpg"))
        try "C".data(using: .utf8)!.write(to: sel2024Dir.appendingPathComponent("img_c.jpg"))

        let scanner = DirectoryScanner()
        let items = try await scanner.scanDirectory(at: root, recursive: true)

        #expect(items.count == 3)
        let filenames = Set(items.map { $0.filename })
        #expect(filenames.contains("img_a.jpg"))
        #expect(filenames.contains("img_b.jpg"))
        #expect(filenames.contains("img_c.jpg"))
    }

    @Test("Direct scan of Selection/ as root: Files directly inside are scanned")
    func testDirectScanOfSelectionRoot() async throws {
        let tempBase = FileManager.default.temporaryDirectory
            .standardizedFileURL
            .resolvingSymlinksInPath()
        let selRoot = tempBase.appendingPathComponent("Selection_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: selRoot, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: selRoot) }

        let file1 = selRoot.appendingPathComponent("selected_1.jpg")
        let file2 = selRoot.appendingPathComponent("selected_2.jpg")
        try "F1".data(using: .utf8)!.write(to: file1)
        try "F2".data(using: .utf8)!.write(to: file2)

        let scanner = DirectoryScanner()
        let items = try await scanner.scanDirectory(at: selRoot, recursive: true)

        #expect(items.count == 2)
    }

    @Test("Progressive streaming yields items in configured batch sizes")
    func testProgressiveStreaming() async throws {
        let root = try createTempDir(prefix: "StreamingTest")
        defer { try? FileManager.default.removeItem(at: root) }

        for i in 1...25 {
            let photo = root.appendingPathComponent(String(format: "IMG_%04d.JPG", i))
            try "PHOTO_\(i)".data(using: .utf8)!.write(to: photo)
        }

        let scanner = DirectoryScanner()
        var batchCounts: [Int] = []
        var totalDiscovered = 0

        for await batch in scanner.scanStream(at: root, recursive: false, batchSize: 10) {
            batchCounts.append(batch.count)
            totalDiscovered += batch.count
        }

        #expect(totalDiscovered == 25)
        #expect(batchCounts == [10, 10, 5])
    }

    @Test("Lightroom edits (-Edit) are paired with primary capture")
    func testLightroomEditPairing() async throws {
        let root = try createTempDir(prefix: "LREditTest")
        defer { try? FileManager.default.removeItem(at: root) }

        let rawURL = root.appendingPathComponent("DSC0005.ARW")
        let xmpURL = root.appendingPathComponent("DSC0005.xmp")
        let editURL = root.appendingPathComponent("DSC0005-Edit.tif")

        try "RAW".data(using: .utf8)!.write(to: rawURL)
        try "XMP".data(using: .utf8)!.write(to: xmpURL)
        try "EDIT".data(using: .utf8)!.write(to: editURL)

        let scanner = DirectoryScanner()
        let items = try await scanner.scanDirectory(at: root, recursive: false)

        #expect(items.count == 1)
        #expect(items.first?.filename == "DSC0005.ARW")
    }

    @Test("U-32: Custom Selection folder with .photoselector-selection marker is excluded even after destination folder setting is renamed")
    func testCustomSelectionFolderWithMarkerExcludedAfterSettingRename() async throws {
        let root = try createTempDir(prefix: "SelectionMarkerScan")
        defer { try? FileManager.default.removeItem(at: root) }

        let candidateURL = root.appendingPathComponent("CANDIDATE.JPG")
        let toMoveURL = root.appendingPathComponent("KEEPER.JPG")
        try Data("CAND".utf8).write(to: candidateURL)
        try Data("KEEP".utf8).write(to: toMoveURL)

        let suiteName = "com.photoselector.tests.marker.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        // 1. User configures custom destination folder "ClientPicks" and moves KEEPER.JPG into it
        defaults.cullingDestinationFolderName = "ClientPicks"
        let manager = FileCullingManager()
        _ = try manager.moveToSelection(
            photoURL: toMoveURL,
            rootURL: root,
            destinationFolderName: defaults.cullingDestinationFolderName
        )

        let markerURL = root
            .appendingPathComponent("ClientPicks", isDirectory: true)
            .appendingPathComponent(DirectoryScanner.selectionMarkerFilename)
        #expect(FileManager.default.fileExists(atPath: markerURL.path), "FileCullingManager must write .photoselector-selection marker")

        // 2. User later changes custom destination folder to "FinalWinners"
        defaults.cullingDestinationFolderName = "FinalWinners"

        // 3. Recursive scan must STILL skip "ClientPicks/" because of the .photoselector-selection marker!
        let scanner = DirectoryScanner(userDefaults: defaults)
        let items = try await scanner.scanDirectory(at: root, recursive: true)

        #expect(items.count == 1)
        #expect(items.first?.filename == "CANDIDATE.JPG")
        #expect(!items.contains(where: { $0.filename == "KEEPER.JPG" }))
    }

    @Test("A custom destination folder inside the culled folder is not discovered, whatever its name")
    func testCustomDestinationInsideRootIsExcluded() async throws {
        let root = try createTempDir(prefix: "CustomInsideRoot")
        defer { try? FileManager.default.removeItem(at: root) }
        try Data("c".utf8).write(to: root.appendingPathComponent("CANDIDATE.JPG"))

        // Custom base folder under a name that is neither a built-in nor the Selection name, and
        // without any marker file of its own.
        let custom = root.appendingPathComponent("Filed Here", isDirectory: true)
        try FileManager.default.createDirectory(at: custom, withIntermediateDirectories: true)
        try Data("f".utf8).write(to: custom.appendingPathComponent("FILED.JPG"))

        let suiteName = "com.photoselector.tests.custominside.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.cullingDestinationMode = .customFolder
        defaults.externalCullingDestinationURL = custom

        let items = try await DirectoryScanner(userDefaults: defaults).scanDirectory(at: root, recursive: true)
        #expect(items.map(\.filename) == ["CANDIDATE.JPG"])

        // Control: without the custom setting the same folder is scanned.
        let plain = UserDefaults(suiteName: suiteName + ".plain")!
        defer { plain.removePersistentDomain(forName: suiteName + ".plain") }
        let all = try await DirectoryScanner(userDefaults: plain).scanDirectory(at: root, recursive: true)
        #expect(Set(all.map(\.filename)) == ["CANDIDATE.JPG", "FILED.JPG"])
    }
}
