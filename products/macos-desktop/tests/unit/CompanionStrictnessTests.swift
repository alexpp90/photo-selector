import Testing
import Foundation
@testable import PhotoSelectorKit

/// H-LJ-5 and H-LJ-10: what rides along with a photo is decided by the shared companion rule,
/// not by a shared name, and an unreadable folder refuses the action instead of dropping the
/// companions silently.
@Suite("Companion discovery is strict and honest")
struct CompanionStrictnessTests {

    private func makeFolder(_ files: [String], folders: [String] = []) throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("CompanionStrict_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for name in files { try Data("x".utf8).write(to: dir.appendingPathComponent(name)) }
        for name in folders {
            try FileManager.default.createDirectory(
                at: dir.appendingPathComponent(name), withIntermediateDirectories: true)
        }
        return dir
    }

    @Test("REQ-MAC-CULL.05: a folder, a movie, a text file and an -editorial photo with the same stem do not ride along")
    func sameStemIsNotEnough() throws {
        let dir = try makeFolder(
            ["DSC0001.ARW", "DSC0001.JPG", "DSC0001.xmp", "DSC0001-Edit.tif", "DSC0001-Edit-2.tif",
             "DSC0001.MOV", "DSC0001.txt", "DSC0001-editorial.jpg", "DSC0001-Edit.mov"],
            folders: ["DSC0001"]
        )
        defer { try? FileManager.default.removeItem(at: dir) }

        let found = FileCullingManager().findCompanionFiles(for: dir.appendingPathComponent("DSC0001.ARW"))
        #expect(Set(found.map(\.lastPathComponent)) == Set([
            "DSC0001.ARW", "DSC0001.JPG", "DSC0001.xmp", "DSC0001-Edit.tif", "DSC0001-Edit-2.tif",
            "DSC0001-Edit.mov"
        ]))
    }

    @Test("REQ-MAC-CULL.05: a folder named like the shot stays where it is when the photo is moved")
    func folderIsNotMoved() throws {
        let dir = try makeFolder(["IMG_0001.CR3", "IMG_0001.JPG"], folders: ["IMG_0001", "IMG_0001.xmp"])
        defer { try? FileManager.default.removeItem(at: dir) }
        try Data("keep".utf8).write(to: dir.appendingPathComponent("IMG_0001/inside.txt"))

        let moved = try FileCullingManager().moveToSelection(
            photoURL: dir.appendingPathComponent("IMG_0001.CR3"), rootURL: dir)
        #expect(Set(moved.map(\.lastPathComponent)) == ["IMG_0001.CR3", "IMG_0001.JPG"])
        #expect(FileManager.default.fileExists(atPath: dir.appendingPathComponent("IMG_0001/inside.txt").path))
    }

    @Test("editBaseStem accepts only a marker that ends the stem, with an optional number")
    func editBaseStemIsStrict() {
        #expect(DirectoryScanner.editBaseStem(of: "dsc0001-edit") == "dsc0001")
        #expect(DirectoryScanner.editBaseStem(of: "dsc0001-edit-2") == "dsc0001")
        #expect(DirectoryScanner.editBaseStem(of: "dsc0001_enhanced-nr") == "dsc0001")
        #expect(DirectoryScanner.editBaseStem(of: "dsc0001-editorial") == nil)
        #expect(DirectoryScanner.editBaseStem(of: "dsc0001-edit-final") == nil)
        #expect(DirectoryScanner.editBaseStem(of: "-edit") == nil)
    }

    /// A file manager whose folder listing fails, as an unplugged card or a revoked permission does.
    private final class UnreadableFolderManager: FileManager, @unchecked Sendable {
        override func contentsOfDirectory(
            at url: URL, includingPropertiesForKeys keys: [URLResourceKey]?,
            options mask: FileManager.DirectoryEnumerationOptions = []
        ) throws -> [URL] {
            throw CocoaError(.fileReadNoPermission)
        }
    }

    @Test("REQ-MAC-CULL.05: when the folder cannot be listed Move, Copy and Trash refuse and change nothing")
    func unreadableFolderRefuses() throws {
        let dir = try makeFolder(["IMG_0002.CR3", "IMG_0002.JPG"])
        defer { try? FileManager.default.removeItem(at: dir) }
        let photo = dir.appendingPathComponent("IMG_0002.CR3")
        let manager = FileCullingManager(fileManager: UnreadableFolderManager())

        #expect(throws: FileCullingError.self) { try manager.moveToSelection(photoURL: photo, rootURL: dir) }
        #expect(throws: FileCullingError.self) { try manager.copyToSelection(photoURL: photo, rootURL: dir) }
        #expect(throws: FileCullingError.self) { try manager.discoverCompanions(for: photo) }

        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted()
        #expect(names == ["IMG_0002.CR3", "IMG_0002.JPG"])  // no Selection folder, nothing moved
        // The display-only lookup keeps returning the photo alone.
        #expect(manager.findCompanionFiles(for: photo) == [photo])
    }
}
