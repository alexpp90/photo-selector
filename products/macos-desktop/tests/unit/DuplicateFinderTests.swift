import Testing
import Foundation
@testable import PhotoSelectorKit

@Suite("DuplicateFinder Unit Tests")
struct DuplicateFinderTests {

    @Test("REQ-MAC-DUPE.01: Streaming SHA256 checksum produces standard cryptographic hex digest")
    func testStreamingSHA256() throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("HashTest_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let fileURL = tempDir.appendingPathComponent("sample.txt")
        let sampleText = "hello world"
        try sampleText.data(using: .utf8)!.write(to: fileURL)

        let hash = try DuplicateFinder.computeSHA256(for: fileURL)
        // Standard SHA256 of "hello world"
        let expected = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        #expect(hash == expected)
    }

    @Test("REQ-MAC-DUPE.01, H-LJ-4: a link, a hard link and their file are one file, never a duplicate pair")
    func testLinksAreNotDuplicates() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("DupLinkTest_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let original = tempDir.appendingPathComponent("a_original.bin")
        let content = Data(repeating: 7, count: 32)
        try content.write(to: original)
        let symlink = tempDir.appendingPathComponent("b_link.bin")
        try FileManager.default.createSymbolicLink(at: symlink, withDestinationURL: original)
        let hardLink = tempDir.appendingPathComponent("c_hard.bin")
        try FileManager.default.linkItem(at: original, to: hardLink)
        let realCopy = tempDir.appendingPathComponent("d_copy.bin")
        try content.write(to: realCopy)

        // The three names of one file collapse to one entry; only the genuine copy pairs with it.
        let clusters = try await DuplicateFinder.findDuplicates(in: tempDir)
        #expect(clusters.count == 1)
        let names = clusters.first?.fileURLs.map(\.lastPathComponent) ?? []
        #expect(names == ["a_original.bin", "d_copy.bin"])

        // Without a real copy there is nothing to report at all.
        try FileManager.default.removeItem(at: realCopy)
        #expect(try await DuplicateFinder.findDuplicates(in: tempDir).isEmpty)
    }

    @Test("REQ-MAC-DUPE.01: Duplicate clustering groups files matching both size and SHA256 while isolating unique files")
    func testDuplicateClustering() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("DupTest_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        // Cluster A: 2 files with identical content
        let fileA1 = tempDir.appendingPathComponent("img_A1.bin")
        let fileA2 = tempDir.appendingPathComponent("img_A2.bin")
        let contentA = Data([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])
        try contentA.write(to: fileA1)
        try contentA.write(to: fileA2)

        // Same size as Cluster A (12 bytes), but DIFFERENT content -> should not match A!
        let fileB1 = tempDir.appendingPathComponent("img_B1.bin")
        let fileB2 = tempDir.appendingPathComponent("img_B2.bin")
        let contentB = Data([99, 98, 97, 96, 95, 94, 93, 92, 91, 90, 89, 88])
        try contentB.write(to: fileB1)
        try contentB.write(to: fileB2)

        // Unique file with different size (5 bytes)
        let fileC = tempDir.appendingPathComponent("img_C.bin")
        let contentC = Data([1, 2, 3, 4, 5])
        try contentC.write(to: fileC)

        let clusters = try await DuplicateFinder.findDuplicates(in: tempDir)

        #expect(clusters.count == 2)

        let clusterA = clusters.first(where: { $0.fileURLs.contains(fileA1) })
        #expect(clusterA != nil)
        #expect(clusterA?.fileURLs.count == 2)
        #expect(clusterA?.fileURLs.contains(fileA2) == true)
        #expect(clusterA?.fileURLs.contains(fileB1) == false)

        let clusterB = clusters.first(where: { $0.fileURLs.contains(fileB1) })
        #expect(clusterB != nil)
        #expect(clusterB?.fileURLs.count == 2)
        #expect(clusterB?.fileURLs.contains(fileB2) == true)
    }

    @Test("Directory with distinct files returns zero duplicate clusters")
    func testNoDuplicates() async throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("NoDupTest_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let file1 = tempDir.appendingPathComponent("file1.dat")
        let file2 = tempDir.appendingPathComponent("file2.dat")
        try Data([1, 2]).write(to: file1)
        try Data([1, 2, 3, 4]).write(to: file2)

        let clusters = try await DuplicateFinder.findDuplicates(in: tempDir)
        #expect(clusters.isEmpty)
    }

    // MARK: - Path Exclusion & Ancestor Collision Regressions

    @Test("Ancestor directory named 'Selection' does not prevent duplicate detection in child directories")
    func testScannedRootWithAncestorNamedSelection() async throws {
        let baseTemp = FileManager.default.temporaryDirectory
            .appendingPathComponent("AncestorTest_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        // Simulate directory tree: .../Selection/MyAlbum/
        let selectionAncestor = baseTemp.appendingPathComponent("Selection", isDirectory: true)
        let albumDir = selectionAncestor.appendingPathComponent("MyAlbum", isDirectory: true)
        try FileManager.default.createDirectory(at: albumDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: baseTemp) }

        let file1 = albumDir.appendingPathComponent("photo_1.jpg")
        let file2 = albumDir.appendingPathComponent("photo_2.jpg")
        let content = "SAMPLE_IDENTICAL_PHOTO_DATA".data(using: .utf8)!
        try content.write(to: file1)
        try content.write(to: file2)

        // Must find duplicates even though ancestor path contains "Selection"
        let clusters = try await DuplicateFinder.findDuplicates(in: albumDir, recursive: false)
        #expect(clusters.count == 1, "Ancestor named 'Selection' caused candidate files to be falsely excluded!")
        #expect(clusters.first?.fileURLs.count == 2)
    }

    @Test("Directory name containing 'Selection' as substring (e.g. Photos_Selection) is not excluded")
    func testFolderWithSelectionSubstringInName() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("Photos_Selection_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let fileA = tempDir.appendingPathComponent("pic_A.jpg")
        let fileB = tempDir.appendingPathComponent("pic_B.jpg")
        let content = "SUBSTRING_PHOTO_BYTES".data(using: .utf8)!
        try content.write(to: fileA)
        try content.write(to: fileB)

        let clusters = try await DuplicateFinder.findDuplicates(in: tempDir, recursive: false)
        #expect(clusters.count == 1, "Folder containing substring 'Selection' was falsely excluded!")
        #expect(clusters.first?.fileURLs.count == 2)
    }

    @Test("App Selection subfolder is excluded case-insensitively during recursive scan")
    func testExclusionOfAppSelectionSubfolderCaseInsensitive() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("CaseExclusionTest_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        // Root files: 2 duplicates
        let root1 = tempDir.appendingPathComponent("orig_1.jpg")
        let root2 = tempDir.appendingPathComponent("orig_2.jpg")
        let content = "DUPLICATE_DATA".data(using: .utf8)!
        try content.write(to: root1)
        try content.write(to: root2)

        // Subfolder named lowercase "selection": contains identical file
        let selectionDir = tempDir.appendingPathComponent("selection", isDirectory: true)
        try FileManager.default.createDirectory(at: selectionDir, withIntermediateDirectories: true)
        let culledCopy = selectionDir.appendingPathComponent("culled_copy.jpg")
        try content.write(to: culledCopy)

        // Recursive scan must exclude the culled copy in selection/ but group root1 and root2
        let clusters = try await DuplicateFinder.findDuplicates(in: tempDir, recursive: true)
        #expect(clusters.count == 1)
        #expect(clusters.first?.fileURLs.count == 2)
        #expect(clusters.first?.fileURLs.contains(culledCopy) == false, "File in lowercase selection/ subfolder was not excluded!")
    }

    @Test("File named 'Selection.jpg' in root directory is not falsely excluded")
    func testFileNamedSelectionIsNotExcluded() async throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("NamedFileTest_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let file1 = tempDir.appendingPathComponent("Selection.jpg")
        let file2 = tempDir.appendingPathComponent("Selection_copy.jpg")
        let content = "NAMED_SELECTION_FILE_DATA".data(using: .utf8)!
        try content.write(to: file1)
        try content.write(to: file2)

        let clusters = try await DuplicateFinder.findDuplicates(in: tempDir, recursive: false)
        #expect(clusters.count == 1, "File named 'Selection.jpg' was falsely excluded!")
        #expect(clusters.first?.fileURLs.count == 2)
    }
}
