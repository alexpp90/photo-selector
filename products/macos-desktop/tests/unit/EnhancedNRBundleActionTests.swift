import Testing
import Foundation
@testable import PhotoSelectorKit

/// F-LJ-015: a photo's `-Enhanced-NR` derivative stayed behind when the shot was moved, copied or
/// trashed. `CompanionGroupingTableTests` pins the discovery; this pins the actions and their Undo
/// end to end, for every action, so a derivative that is found but not carried fails here.
/// REQ-MAC-CULL.05 (companion synchronisation), docs/shared/FILE_OPERATIONS.md.
@Suite("Enhanced-NR derivatives travel with their shot through every action and Undo")
struct EnhancedNRBundleActionTests {

    private let bundle = ["IMG_0003.CR3", "IMG_0003-Enhanced-NR.dng", "IMG_0003_Enhanced-NR.dng", "IMG_0003-Enhanced-NR.xmp"]
    /// Neighbours that merely share a prefix; they must never be swept along.
    private let bystanders = ["IMG_00030.JPG", "IMG_0004.CR3"]

    private func makeFolder() throws -> URL {
        let base = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
        let dir = base.appendingPathComponent("EnhancedNR_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for name in bundle + bystanders { try Data(name.utf8).write(to: dir.appendingPathComponent(name)) }
        return dir
    }

    private func names(in dir: URL) -> Set<String> {
        Set((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { !$0.hasPrefix(".") }
    }

    @Test("REQ-MAC-CULL.05 F-LJ-015: action and Undo carry all four members and leave the bystanders alone",
          arguments: CullingActionType.allCases)
    func derivativesTravelWithTheShot(action: CullingActionType) async throws {
        let root = try makeFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        let trash = root.appendingPathComponent(".trash", isDirectory: true)
        let selection = root.appendingPathComponent("Selection", isDirectory: true)
        let actor = AsyncCullingActor(trashService: DirectoryTrashService(trashDirectory: trash))
        let job = CullingJob(photoID: UUID(), actionType: action, primaryURL: root.appendingPathComponent("IMG_0003.CR3"), rootURL: root)

        let record = try #require(try await actor.enqueue(job))
        #expect(Set(record.affectedURLs.map(\.lastPathComponent)) == Set(bundle), "\(action) record must hold the whole bundle")

        switch action {
        case .move:
            #expect(names(in: selection) == Set(bundle))
            #expect(names(in: root).isDisjoint(with: Set(bundle)), "move leaves no member behind")
        case .copy:
            #expect(names(in: selection) == Set(bundle))
            #expect(Set(bundle).isSubset(of: names(in: root)), "copy leaves every original")
        case .trash:
            #expect(names(in: trash) == Set(bundle))
            #expect(names(in: root).isDisjoint(with: Set(bundle)))
        }
        #expect(Set(bystanders).isSubset(of: names(in: root)), "a longer stem or neighbour must stay")

        let restored = try await actor.undo(record: record)
        #expect(!restored.isEmpty)
        #expect(Set(bundle).isSubset(of: names(in: root)), "Undo returns every member")
        #expect(Set(bystanders).isSubset(of: names(in: root)))
        #expect(names(in: selection).isDisjoint(with: Set(bundle)), "Undo leaves no replica or moved member in Selection")
        #expect(names(in: trash).isDisjoint(with: Set(bundle)))
    }
}
