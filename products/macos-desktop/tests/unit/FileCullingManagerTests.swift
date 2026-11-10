import Testing
import Foundation
import Darwin
@testable import PhotoSelectorKit

/// Test-only `TrashService` that moves trashed files into a temporary `.SimulatedTrash` directory
/// inside the test's directory, handling filename collisions safely without invoking Finder or
/// relying on environment variables in production code.
struct DirectoryTrashService: TrashService {
    let trashDirectory: URL
    private let lock = NSLock()

    init(rootDirectory: URL) {
        self.trashDirectory = rootDirectory.appendingPathComponent(".SimulatedTrash", isDirectory: true)
    }

    init(trashDirectory: URL) {
        self.trashDirectory = trashDirectory
    }

    func trash(_ url: URL) throws -> URL {
        lock.lock()
        defer { lock.unlock() }

        let fm = FileManager.default
        if !fm.fileExists(atPath: trashDirectory.path) {
            do {
                try fm.createDirectory(at: trashDirectory, withIntermediateDirectories: true)
            } catch {
                throw FileCullingError.directoryCreationFailed(trashDirectory, error.localizedDescription)
            }
        }

        let filename = url.lastPathComponent
        let stem = (filename as NSString).deletingPathExtension
        let ext = (filename as NSString).pathExtension

        var destination = trashDirectory.appendingPathComponent(filename)
        var counter = 2
        while fm.fileExists(atPath: destination.path) {
            let disambiguated = ext.isEmpty ? "\(stem) \(counter)" : "\(stem) \(counter).\(ext)"
            destination = trashDirectory.appendingPathComponent(disambiguated)
            counter += 1
        }

        do {
            try fm.moveItem(at: url, to: destination)
            return destination
        } catch {
            throw FileCullingError.trashFailed(url: url, reason: error.localizedDescription)
        }
    }
}

@Suite("FileCullingManager Unit Tests")
struct FileCullingManagerTests {

    @Test("REQ-MAC-EXIF.05, REQ-MAC-CULL.05: Companion file discovery finds RAW+JPEG, XMP sidecars, and Lightroom edits")
    func testCompanionFileDiscovery() throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("CullingTest_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let primaryURL = tempDir.appendingPathComponent("DSC0001.ARW")
        let jpegPairURL = tempDir.appendingPathComponent("DSC0001.JPG")
        let xmpURL = tempDir.appendingPathComponent("DSC0001.xmp")
        let doubleXmpURL = tempDir.appendingPathComponent("DSC0001.ARW.xmp")
        let lrEditTif = tempDir.appendingPathComponent("DSC0001-Edit.tif")
        let lrEditXmp = tempDir.appendingPathComponent("DSC0001-Edit.xmp")
        let unrelatedURL = tempDir.appendingPathComponent("DSC0002.ARW")

        let dummyData = "test".data(using: .utf8)!
        try dummyData.write(to: primaryURL)
        try dummyData.write(to: jpegPairURL)
        try dummyData.write(to: xmpURL)
        try dummyData.write(to: doubleXmpURL)
        try dummyData.write(to: lrEditTif)
        try dummyData.write(to: lrEditXmp)
        try dummyData.write(to: unrelatedURL)

        let manager = FileCullingManager()
        let companions = manager.findCompanionFiles(for: primaryURL)

        #expect(companions.first == primaryURL)
        #expect(companions.count == 6)
        #expect(companions.contains(jpegPairURL))
        #expect(companions.contains(xmpURL))
        #expect(companions.contains(doubleXmpURL))
        #expect(companions.contains(lrEditTif))
        #expect(companions.contains(lrEditXmp))
        #expect(!companions.contains(unrelatedURL))
    }

    @Test("REQ-MAC-CULL.05: Companions of a symlinked photo are its neighbours, not the link target's")
    func testCompanionsOfSymlinkedPrimary() throws {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent("CullingSymlink_\(UUID().uuidString)")
        let culled = base.appendingPathComponent("card")
        let elsewhere = base.appendingPathComponent("archive")
        try FileManager.default.createDirectory(at: culled, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: base) }

        let dummy = Data("x".utf8)
        let target = elsewhere.appendingPathComponent("DSC0001.ARW")
        try dummy.write(to: target)
        try dummy.write(to: elsewhere.appendingPathComponent("DSC0001.xmp"))
        let link = culled.appendingPathComponent("DSC0001.ARW")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: target)
        let jpeg = culled.appendingPathComponent("DSC0001.JPG")
        try dummy.write(to: jpeg)

        let companions = FileCullingManager().findCompanionFiles(for: link)
        #expect(companions.map(\.lastPathComponent) == ["DSC0001.ARW", "DSC0001.JPG"])
        #expect(companions.allSatisfy { $0.deletingLastPathComponent().lastPathComponent == "card" })
    }

    @Test("REQ-MAC-CULL.04, REQ-MAC-CULL.05: Copy to Selection copies primary and companion files preserving originals")
    func testCopyToSelection() throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("CopyTest_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let primaryURL = tempDir.appendingPathComponent("PHOTO_A.CR3")
        let companionURL = tempDir.appendingPathComponent("PHOTO_A.xmp")
        let dummy = "content".data(using: .utf8)!
        try dummy.write(to: primaryURL)
        try dummy.write(to: companionURL)

        let manager = FileCullingManager()
        let copied = try manager.copyToSelection(photoURL: primaryURL, rootURL: tempDir)

        #expect(copied.count == 2)
        let selectionDir = tempDir.appendingPathComponent("Selection", isDirectory: true)
        #expect(FileManager.default.fileExists(atPath: selectionDir.appendingPathComponent("PHOTO_A.CR3").path))
        #expect(FileManager.default.fileExists(atPath: selectionDir.appendingPathComponent("PHOTO_A.xmp").path))

        // Originals still present
        #expect(FileManager.default.fileExists(atPath: primaryURL.path))
        #expect(FileManager.default.fileExists(atPath: companionURL.path))
    }

    @Test("REQ-MAC-CULL.05: Move to Selection moves primary and companion files into Selection folder")
    func testMoveToSelection() throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("MoveTest_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let primaryURL = tempDir.appendingPathComponent("PHOTO_B.NEF")
        let companionURL = tempDir.appendingPathComponent("PHOTO_B.JPG")
        let dummy = "content".data(using: .utf8)!
        try dummy.write(to: primaryURL)
        try dummy.write(to: companionURL)

        let manager = FileCullingManager()
        let moved = try manager.moveToSelection(photoURL: primaryURL, rootURL: tempDir)

        #expect(moved.count == 2)
        let selectionDir = tempDir.appendingPathComponent("Selection", isDirectory: true)
        #expect(FileManager.default.fileExists(atPath: selectionDir.appendingPathComponent("PHOTO_B.NEF").path))
        #expect(FileManager.default.fileExists(atPath: selectionDir.appendingPathComponent("PHOTO_B.JPG").path))

        // Originals should no longer exist at original locations
        #expect(!FileManager.default.fileExists(atPath: primaryURL.path))
        #expect(!FileManager.default.fileExists(atPath: companionURL.path))
    }

    @Test("REQ-MAC-CULL.05: Move to Trash trashes primary and companion files")
    func testMoveToTrash() throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("TrashTest_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let primaryURL = tempDir.appendingPathComponent("PHOTO_C.DNG")
        let companionURL = tempDir.appendingPathComponent("PHOTO_C.xmp")
        let dummy = "content".data(using: .utf8)!
        try dummy.write(to: primaryURL)
        try dummy.write(to: companionURL)

        let manager = FileCullingManager(trashService: DirectoryTrashService(rootDirectory: tempDir))
        let trashed = try manager.moveToTrash(photoURL: primaryURL)

        #expect(trashed.count == 2)
        #expect(!FileManager.default.fileExists(atPath: primaryURL.path))
        #expect(!FileManager.default.fileExists(atPath: companionURL.path))
    }

    @Test("FileCullingManager and AsyncCullingActor default to SystemTrashService even when CI=true is set in environment")
    func testDefaultTrashServiceIgnoresCIEnvironmentVariable() throws {
        let previousCI = getenv("CI").map { String(cString: $0) }
        setenv("CI", "true", 1)
        defer {
            if let previousCI {
                setenv("CI", previousCI, 1)
            } else {
                unsetenv("CI")
            }
        }

        let defaultManager = FileCullingManager()
        #expect(defaultManager.trashService is SystemTrashService)

        let defaultActor = AsyncCullingActor()
        #expect(defaultActor.trashService is SystemTrashService)

        // Prove that with CI=true set, FileCullingManager delegates solely to its injected TrashService
        // and never writes into $TMPDIR/.SimulatedTrash on its own.
        struct SpyTrashService: TrashService {
            let destinationDir: URL
            func trash(_ url: URL) throws -> URL {
                let dst = destinationDir.appendingPathComponent(url.lastPathComponent)
                try FileManager.default.moveItem(at: url, to: dst)
                return dst
            }
        }

        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("CITrashCheck_\(UUID().uuidString)")
        let spyTrashDir = tempDir.appendingPathComponent("CustomSpyTrash", isDirectory: true)
        try FileManager.default.createDirectory(at: spyTrashDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let photoURL = tempDir.appendingPathComponent("IMG_CI_CHECK.JPG")
        try Data("ci-test".utf8).write(to: photoURL)

        let spyManager = FileCullingManager(trashService: SpyTrashService(destinationDir: spyTrashDir))
        let trashed = try spyManager.moveToTrash(photoURL: photoURL)

        #expect(trashed == [spyTrashDir.appendingPathComponent("IMG_CI_CHECK.JPG")])
        #expect(!trashed.first!.path.contains(".SimulatedTrash"))
        #expect(FileManager.default.fileExists(atPath: spyTrashDir.appendingPathComponent("IMG_CI_CHECK.JPG").path))
    }

    @Test("DirectoryTrashService disambiguates colliding filenames safely")
    func testDirectoryTrashServiceCollisionHandling() throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("TrashCollision_\(UUID().uuidString)")
        let subA = tempDir.appendingPathComponent("A", isDirectory: true)
        let subB = tempDir.appendingPathComponent("B", isDirectory: true)
        try FileManager.default.createDirectory(at: subA, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: subB, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let fileA = subA.appendingPathComponent("DUP.ARW")
        let fileB = subB.appendingPathComponent("DUP.ARW")
        try Data("FIRST".utf8).write(to: fileA)
        try Data("SECOND".utf8).write(to: fileB)

        let trashService = DirectoryTrashService(rootDirectory: tempDir)
        let manager = FileCullingManager(trashService: trashService)

        let trashedA = try manager.moveToTrash(photoURL: fileA)
        let trashedB = try manager.moveToTrash(photoURL: fileB)

        #expect(trashedA.count == 1)
        #expect(trashedB.count == 1)
        #expect(trashedA[0] != trashedB[0])
        #expect(try Data(contentsOf: trashedA[0]) == Data("FIRST".utf8))
        #expect(try Data(contentsOf: trashedB[0]) == Data("SECOND".utf8))
    }

    // MARK: - Data Safety & Re-cull Regressions

    @Test("Moving file already residing in Selection/ is a safe no-op and never deletes file")
    func testMoveToSelectionWhenFileAlreadyInSelection() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("SelfMoveTest_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let selectionDir = tempDir.appendingPathComponent("Selection", isDirectory: true)
        try FileManager.default.createDirectory(at: selectionDir, withIntermediateDirectories: true)

        let existingPhoto = selectionDir.appendingPathComponent("EXISTING_PHOTO.ARW")
        let content = "PRECIOUS_RAW_DATA".data(using: .utf8)!
        try content.write(to: existingPhoto)

        let manager = FileCullingManager()
        let result = try manager.moveToSelection(photoURL: existingPhoto, rootURL: tempDir)

        #expect(FileManager.default.fileExists(atPath: existingPhoto.path), "CRITICAL REGRESSION: File was deleted when moving a file already in Selection folder!")
        #expect(result.contains(existingPhoto))
        let remainingData = try Data(contentsOf: existingPhoto)
        #expect(remainingData == content)
    }

    @Test("Copying file already residing in Selection/ preserves file intact without deletion")
    func testCopyToSelectionWhenFileAlreadyInSelection() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("SelfCopyTest_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let selectionDir = tempDir.appendingPathComponent("Selection", isDirectory: true)
        try FileManager.default.createDirectory(at: selectionDir, withIntermediateDirectories: true)

        let existingPhoto = selectionDir.appendingPathComponent("ALREADY_COPIED.JPG")
        let content = "PRESERVED_CONTENT".data(using: .utf8)!
        try content.write(to: existingPhoto)

        let manager = FileCullingManager()
        let result = try manager.copyToSelection(photoURL: existingPhoto, rootURL: tempDir)

        #expect(FileManager.default.fileExists(atPath: existingPhoto.path))
        #expect(result.contains(existingPhoto))
        let remainingData = try Data(contentsOf: existingPhoto)
        #expect(remainingData == content)
    }

    @Test("Moving photo and companions already in Selection/ safely preserves all companion files")
    func testMoveCompanionsAlreadyInSelection() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("SelfMoveCompanionsTest_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let selectionDir = tempDir.appendingPathComponent("Selection", isDirectory: true)
        try FileManager.default.createDirectory(at: selectionDir, withIntermediateDirectories: true)

        let rawURL = selectionDir.appendingPathComponent("PHOTO_01.CR3")
        let xmpURL = selectionDir.appendingPathComponent("PHOTO_01.xmp")
        try "RAW_DATA".data(using: .utf8)!.write(to: rawURL)
        try "XMP_DATA".data(using: .utf8)!.write(to: xmpURL)

        let manager = FileCullingManager()
        let moved = try manager.moveToSelection(photoURL: rawURL, rootURL: tempDir)

        #expect(moved.count == 2)
        #expect(FileManager.default.fileExists(atPath: rawURL.path))
        #expect(FileManager.default.fileExists(atPath: xmpURL.path))
    }

    @Test("Move to Selection rolls back already-moved primary file when companion move fails")
    func testMoveToSelectionTransactionalRollbackOnCompanionFailure() throws {
        final class FailingSecondMoveFileManager: FileManager {
            var moveTargetCallCount = 0
            var failOnFilename: String = ""

            override func moveItem(at srcURL: URL, to dstURL: URL) throws {
                if srcURL.lastPathComponent == failOnFilename {
                    throw NSError(domain: NSCocoaErrorDomain, code: NSFileWriteNoPermissionError, userInfo: [NSLocalizedDescriptionKey: "Simulated companion move error"])
                }
                try super.moveItem(at: srcURL, to: dstURL)
            }
        }

        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("MoveRollbackTest_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let rawURL = tempDir.appendingPathComponent("ROLLBACK_01.CR3")
        let jpgURL = tempDir.appendingPathComponent("ROLLBACK_01.JPG")
        try "RAW_BYTES".data(using: .utf8)!.write(to: rawURL)
        try "JPG_BYTES".data(using: .utf8)!.write(to: jpgURL)

        let fm = FailingSecondMoveFileManager()
        fm.failOnFilename = "ROLLBACK_01.JPG"
        let manager = FileCullingManager(fileManager: fm)

        #expect(throws: FileCullingError.self) {
            try manager.moveToSelection(photoURL: rawURL, rootURL: tempDir)
        }

        // Primary file must be rolled back to its source location
        #expect(FileManager.default.fileExists(atPath: rawURL.path))
        #expect(FileManager.default.fileExists(atPath: jpgURL.path))
        let selectionRaw = tempDir.appendingPathComponent("Selection/ROLLBACK_01.CR3")
        #expect(!FileManager.default.fileExists(atPath: selectionRaw.path))
    }

    @Test("Copy to Selection removes newly copied primary file and restores overwritten destination when companion copy fails")
    func testCopyToSelectionTransactionalRollbackOnCompanionFailure() throws {
        final class FailingSecondCopyFileManager: FileManager {
            var failOnFilename: String = ""

            override func copyItem(at srcURL: URL, to dstURL: URL) throws {
                if srcURL.lastPathComponent == failOnFilename {
                    throw NSError(domain: NSCocoaErrorDomain, code: NSFileWriteOutOfSpaceError, userInfo: [NSLocalizedDescriptionKey: "Simulated companion copy error"])
                }
                try super.copyItem(at: srcURL, to: dstURL)
            }
        }

        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("CopyRollbackTest_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let rawURL = tempDir.appendingPathComponent("ROLLBACK_02.CR3")
        let jpgURL = tempDir.appendingPathComponent("ROLLBACK_02.JPG")
        try "NEW_RAW_BYTES".data(using: .utf8)!.write(to: rawURL)
        try "NEW_JPG_BYTES".data(using: .utf8)!.write(to: jpgURL)

        let selectionDir = tempDir.appendingPathComponent("Selection", isDirectory: true)
        try FileManager.default.createDirectory(at: selectionDir, withIntermediateDirectories: true)
        let preExistingRaw = selectionDir.appendingPathComponent("ROLLBACK_02.CR3")
        let oldContent = "EXISTING_DESTINATION_RAW".data(using: .utf8)!
        try oldContent.write(to: preExistingRaw)

        let fm = FailingSecondCopyFileManager()
        fm.failOnFilename = "ROLLBACK_02.JPG"
        let manager = FileCullingManager(fileManager: fm)

        #expect(throws: FileCullingError.self) {
            try manager.copyToSelection(photoURL: rawURL, rootURL: tempDir)
        }

        // Source files remain intact, pre-existing Selection file was never modified, and partial -2 copy is cleaned up
        #expect(FileManager.default.fileExists(atPath: rawURL.path))
        #expect(FileManager.default.fileExists(atPath: jpgURL.path))
        #expect(FileManager.default.fileExists(atPath: preExistingRaw.path))
        #expect(try Data(contentsOf: preExistingRaw) == oldContent)
        #expect(!FileManager.default.fileExists(atPath: selectionDir.appendingPathComponent("ROLLBACK_02-2.CR3").path))
    }

    @Test("REQ-MAC-CULL.03, REQ-MAC-CULL.05: Two different bundles named IMG_0001.CR3 + .JPG + .CR3.xmp + -Edit.tif moved in sequence preserve both bundles with intact pairing")
    func testSequentialBundlesWithSameNameGetSharedSuffixAndPreserveOriginal() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("SequentialBundleCollision_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        let bodyADir = tempDir.appendingPathComponent("BodyA", isDirectory: true)
        let bodyBDir = tempDir.appendingPathComponent("BodyB", isDirectory: true)
        try FileManager.default.createDirectory(at: bodyADir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: bodyBDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let bundleA: [(String, String)] = [
            ("IMG_0001.CR3", "BODY_A_RAW"),
            ("IMG_0001.JPG", "BODY_A_JPG"),
            ("IMG_0001.CR3.xmp", "BODY_A_DOUBLE_XMP"),
            ("IMG_0001-Edit.tif", "BODY_A_EDIT_TIF")
        ]
        let bundleB: [(String, String)] = [
            ("IMG_0001.CR3", "BODY_B_RAW"),
            ("IMG_0001.JPG", "BODY_B_JPG"),
            ("IMG_0001.CR3.xmp", "BODY_B_DOUBLE_XMP"),
            ("IMG_0001-Edit.tif", "BODY_B_EDIT_TIF")
        ]

        for (name, content) in bundleA {
            try Data(content.utf8).write(to: bodyADir.appendingPathComponent(name))
        }
        for (name, content) in bundleB {
            try Data(content.utf8).write(to: bodyBDir.appendingPathComponent(name))
        }

        let manager = FileCullingManager()
        let movedA = try manager.moveToSelection(
            photoURL: bodyADir.appendingPathComponent("IMG_0001.CR3"),
            rootURL: tempDir
        )
        let movedB = try manager.moveToSelection(
            photoURL: bodyBDir.appendingPathComponent("IMG_0001.CR3"),
            rootURL: tempDir
        )

        #expect(movedA.count == 4)
        #expect(movedB.count == 4)

        let selectionDir = tempDir.appendingPathComponent("Selection", isDirectory: true)
        let expectedBundleB: [(String, String)] = [
            ("IMG_0001-2.CR3", "BODY_B_RAW"),
            ("IMG_0001-2.JPG", "BODY_B_JPG"),
            ("IMG_0001-2.CR3.xmp", "BODY_B_DOUBLE_XMP"),
            ("IMG_0001-2-Edit.tif", "BODY_B_EDIT_TIF")
        ]

        for (name, expectedContent) in bundleA {
            let url = selectionDir.appendingPathComponent(name)
            #expect(FileManager.default.fileExists(atPath: url.path), "First bundle file \(name) must remain in Selection/")
            #expect(try String(contentsOf: url, encoding: .utf8) == expectedContent)
        }
        for (name, expectedContent) in expectedBundleB {
            let url = selectionDir.appendingPathComponent(name)
            #expect(FileManager.default.fileExists(atPath: url.path), "Second bundle file \(name) must be suffixed in Selection/")
            #expect(try String(contentsOf: url, encoding: .utf8) == expectedContent)
        }

        // Verify companion discovery in Selection/ still groups each bundle cleanly without cross-talk
        let discoveredA = manager.findCompanionFiles(for: selectionDir.appendingPathComponent("IMG_0001.CR3"))
        let discoveredB = manager.findCompanionFiles(for: selectionDir.appendingPathComponent("IMG_0001-2.CR3"))
        #expect(Set(discoveredA.map(\.lastPathComponent)) == Set(bundleA.map(\.0)))
        #expect(Set(discoveredB.map(\.lastPathComponent)) == Set(expectedBundleB.map(\.0)))
    }

    @Test("U-03: SystemTrashService throws trashFailed when FileManager.trashItem returns nil resultingItemURL")
    func testSystemTrashServiceNilResultingItemURLThrowsTrashFailed() throws {
        final class NilResultTrashFileManager: FileManager {
            override func trashItem(at url: URL, resultingItemURL outResultingURL: AutoreleasingUnsafeMutablePointer<NSURL?>?) throws {
                outResultingURL?.pointee = nil
            }
        }

        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("NilTrashResultTest_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let photoURL = tempDir.appendingPathComponent("IMG_NIL_TRASH.JPG")
        try Data("BYTES".utf8).write(to: photoURL)

        let service = SystemTrashService(fileManager: NilResultTrashFileManager())
        let manager = FileCullingManager(trashService: service)

        #expect(throws: FileCullingError.self) {
            try manager.moveToTrashWithPairs(photoURL: photoURL)
        }
        #expect(FileManager.default.fileExists(atPath: photoURL.path), "Original file must still exist when trashItem returned nil URL")
    }

    @Test("U-03: Injected TrashService failing on second member of RAW+JPG+XMP bundle rolls first member back to source folder")
    func testTrashRollbackOnSecondMemberFailureRestoresFirstMember() throws {
        struct FailingSecondTrashService: TrashService {
            let baseService: DirectoryTrashService
            let failOnFilename: String

            func trash(_ url: URL) throws -> URL {
                if url.lastPathComponent == failOnFilename {
                    throw FileCullingError.trashFailed(url: url, reason: "Simulated failure on companion")
                }
                return try baseService.trash(url)
            }
        }

        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("TrashRollbackSecondMember_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let rawURL = tempDir.appendingPathComponent("IMG_0010.CR3")
        let jpgURL = tempDir.appendingPathComponent("IMG_0010.JPG")
        let xmpURL = tempDir.appendingPathComponent("IMG_0010.xmp")
        try Data("RAW_PAYLOAD".utf8).write(to: rawURL)
        try Data("JPG_PAYLOAD".utf8).write(to: jpgURL)
        try Data("XMP_PAYLOAD".utf8).write(to: xmpURL)

        let trashService = FailingSecondTrashService(
            baseService: DirectoryTrashService(rootDirectory: tempDir),
            failOnFilename: "IMG_0010.JPG"
        )
        let manager = FileCullingManager(trashService: trashService)

        #expect(throws: FileCullingError.self) {
            try manager.moveToTrashWithPairs(photoURL: rawURL)
        }

        // First member must be rolled back to its original location and all 3 files present in source folder
        #expect(FileManager.default.fileExists(atPath: rawURL.path))
        #expect(FileManager.default.fileExists(atPath: jpgURL.path))
        #expect(FileManager.default.fileExists(atPath: xmpURL.path))
        #expect(try String(contentsOf: rawURL, encoding: .utf8) == "RAW_PAYLOAD")
        #expect(try String(contentsOf: jpgURL, encoding: .utf8) == "JPG_PAYLOAD")
        #expect(try String(contentsOf: xmpURL, encoding: .utf8) == "XMP_PAYLOAD")
    }

    @Test("U-32: moveToSelection and copyToSelection write .photoselector-selection marker in custom destination folders")
    func testSelectionMarkerWrittenOnMoveAndCopy() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("SelectionMarkerWrite_\(UUID().uuidString)")
            .standardizedFileURL
            .resolvingSymlinksInPath()
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let p1 = tempDir.appendingPathComponent("IMG_1.JPG")
        let p2 = tempDir.appendingPathComponent("IMG_2.JPG")
        try Data("1".utf8).write(to: p1)
        try Data("2".utf8).write(to: p2)

        let manager = FileCullingManager()
        _ = try manager.moveToSelection(photoURL: p1, rootURL: tempDir, destinationFolderName: "MoveDest")
        _ = try manager.copyToSelection(photoURL: p2, rootURL: tempDir, destinationFolderName: "CopyDest")

        let moveMarker = tempDir
            .appendingPathComponent("MoveDest", isDirectory: true)
            .appendingPathComponent(FileCullingManager.selectionMarkerFilename)
        let copyMarker = tempDir
            .appendingPathComponent("CopyDest", isDirectory: true)
            .appendingPathComponent(FileCullingManager.selectionMarkerFilename)

        #expect(FileManager.default.fileExists(atPath: moveMarker.path))
        #expect(FileManager.default.fileExists(atPath: copyMarker.path))
    }

    /// A file manager whose `copyItem` loses the race: another writer occupies the destination name
    /// first, and the copy then fails with "file exists" (EEXIST).
    private final class RacingFileManager: FileManager, @unchecked Sendable {
        let foreignContent = Data("FOREIGN".utf8)
        override func copyItem(at srcURL: URL, to dstURL: URL) throws {
            try foreignContent.write(to: dstURL)
            throw NSError(domain: NSCocoaErrorDomain, code: NSFileWriteFileExistsError,
                          userInfo: [NSUnderlyingErrorKey: NSError(domain: NSPOSIXErrorDomain, code: Int(EEXIST))])
        }
    }

    @Test("Copy cleanup never deletes a file another writer put on the destination name")
    func testCopyNeverDeletesAFileThatAppearedAtTheDestinationName() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("eexist_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let photo = root.appendingPathComponent("IMG_0001.JPG")
        try Data("PHOTO".utf8).write(to: photo)

        let racing = RacingFileManager()
        let manager = FileCullingManager(fileManager: racing)
        #expect(throws: FileCullingError.self) {
            _ = try manager.copyToSelection(photoURL: photo, rootURL: root)
        }
        let occupied = root.appendingPathComponent("Selection/IMG_0001.JPG")
        #expect(try Data(contentsOf: occupied) == racing.foreignContent)
        #expect(FileManager.default.fileExists(atPath: photo.path))
    }
}
