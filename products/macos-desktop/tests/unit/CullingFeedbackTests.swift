import Testing
import Foundation
@testable import PhotoSelectorKit

/// The feedback templates of docs/shared/FILE_OPERATIONS.md (finding D-8): a collision-suffixed
/// transfer or restore tells the user which name the file got.
@Suite("Culling feedback templates")
struct CullingFeedbackTests {
    private let root = URL(fileURLWithPath: "/shoot", isDirectory: true)
    private var selection: URL { root.appendingPathComponent("Selection", isDirectory: true) }

    @Test("A collision-suffixed move names the new file and counts the companions")
    func suffixedMove() {
        let title = CullingFeedback.transferTitle(
            action: .move,
            destinationFolder: "Selection",
            originalPrimaryURL: root.appendingPathComponent("IMG_0001.CR3"),
            destinationURLs: [
                selection.appendingPathComponent("IMG_0001-2.CR3"),
                selection.appendingPathComponent("IMG_0001-2.xmp"),
            ]
        )
        #expect(title == "Moved to Selection as IMG_0001-2.CR3 (+1 related)")
    }

    @Test("A collision-suffixed copy names the new file")
    func suffixedCopy() {
        let title = CullingFeedback.transferTitle(
            action: .copy,
            destinationFolder: "Picks",
            originalPrimaryURL: root.appendingPathComponent("IMG_0001.JPG"),
            destinationURLs: [URL(fileURLWithPath: "/shoot/Picks/IMG_0001-3.JPG")]
        )
        #expect(title == "Copied to Picks as IMG_0001-3.JPG")
    }

    @Test("A transfer that kept its name needs no extra message")
    func plainTransfer() {
        let title = CullingFeedback.transferTitle(
            action: .move,
            destinationFolder: "Selection",
            originalPrimaryURL: root.appendingPathComponent("IMG_0001.CR3"),
            destinationURLs: [selection.appendingPathComponent("IMG_0001.CR3")]
        )
        #expect(title == nil)
    }

    @Test("Trash has no destination folder name to report")
    func trashIsNotATransfer() {
        let title = CullingFeedback.transferTitle(
            action: .trash,
            destinationFolder: "Selection",
            originalPrimaryURL: root.appendingPathComponent("IMG_0001.CR3"),
            destinationURLs: [URL(fileURLWithPath: "/Users/x/.Trash/IMG_0001 2.CR3")]
        )
        #expect(title == nil)
    }

    @Test("An undo that had to suffix the original name says so")
    func suffixedRestore() {
        let title = CullingFeedback.restoredTitle(
            originalPrimaryURL: root.appendingPathComponent("IMG_0001.CR3"),
            restoredURLs: [
                root.appendingPathComponent("IMG_0001-2.CR3"),
                root.appendingPathComponent("IMG_0001-2.xmp"),
            ]
        )
        #expect(title == "Restored as IMG_0001-2.CR3 (original name was in use)")
    }

    @Test("An undo that restored the original names needs no extra message")
    func plainRestore() {
        let title = CullingFeedback.restoredTitle(
            originalPrimaryURL: root.appendingPathComponent("IMG_0001.CR3"),
            restoredURLs: [root.appendingPathComponent("IMG_0001.CR3"), root.appendingPathComponent("IMG_0001.xmp")]
        )
        #expect(title == nil)
    }

    @Test("A partial move names the problem: an original that stayed, or members left in the folder")
    func partialMoveTitles() {
        #expect(CullingFeedback.partialMoveTitle(destinationFolder: "Selection", rollbackFailed: false)
            == "Copied to Selection — original could not be removed")
        #expect(CullingFeedback.partialMoveTitle(destinationFolder: "Selection", rollbackFailed: true)
            == "Move interrupted — some files stayed in Selection and could not be put back")
    }
}
