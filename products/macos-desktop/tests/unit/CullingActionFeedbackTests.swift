import Testing
import Foundation
@testable import PhotoSelectorKit

/// Trash stand-in that moves into a folder inside the test's own directory, so no test touches the real Trash.
private struct FolderTrashService: TrashService {
    let directory: URL
    func trash(_ url: URL) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent(url.lastPathComponent)
        try FileManager.default.moveItem(at: url, to: destination)
        return destination
    }
}

/// Moving a member out of `Selection/` fails, and so does moving `failing` into it: the bundle move
/// fails half way and its rollback cannot complete (card pulled, permissions revoked).
private final class StrandingFileManager: FileManager, @unchecked Sendable {
    let failing: String
    let unrestorable: String

    init(failing: String, unrestorable: String) {
        self.failing = failing
        self.unrestorable = unrestorable
        super.init()
    }

    override func moveItem(at srcURL: URL, to dstURL: URL) throws {
        let fromSelection = srcURL.deletingLastPathComponent().lastPathComponent == "Selection"
        if srcURL.lastPathComponent == failing, !fromSelection {
            throw NSError(domain: NSCocoaErrorDomain, code: NSFileWriteNoPermissionError, userInfo: [
                NSLocalizedDescriptionKey: "Permission denied"
            ])
        }
        if fromSelection, srcURL.lastPathComponent == unrestorable {
            throw NSError(domain: NSCocoaErrorDomain, code: NSFileWriteUnknownError, userInfo: [
                NSLocalizedDescriptionKey: "The volume went away"
            ])
        }
        try super.moveItem(at: srcURL, to: dstURL)
    }
}

/// Class check "every culling action ends in feedback that names the photo and where it went" (the
/// class behind F-LJ-027, F-LJ-031, F-LJ-033). Table-driven over `CullingActionType.allCases` and over
/// both destinations, so a new action, or a path that stops naming the photo, goes red.
/// REQ-MAC-CULL.03, REQ-MAC-CULL.04, REQ-MAC-SETTINGS.02, docs/shared/FILE_OPERATIONS.md (feedback templates).
@Suite("Every culling action gives feedback that names the photo and the destination")
@MainActor
struct CullingActionFeedbackTests {

    /// A culled folder with three photos (the first has an .xmp), a trash stand-in, isolated settings and,
    /// optionally, a custom destination folder named "Picks".
    private struct Env {
        let root: URL
        let custom: URL?
        let defaults: UserDefaults
        let suite: String
        let items: [PhotoItem]
        let vm: CullingWorkspaceViewModel

        var folderName: String { custom == nil ? "Selection" : "Picks" }
        var base: URL { custom ?? root }

        func cleanUp() {
            try? FileManager.default.removeItem(at: root)
            if let custom { try? FileManager.default.removeItem(at: custom) }
            defaults.removePersistentDomain(forName: suite)
        }
    }

    private func makeEnv(custom useCustom: Bool, files: [String] = ["A0.JPG", "A1.JPG", "A2.JPG"], makeActor: (@Sendable () -> AsyncCullingActor)? = nil) throws -> Env {
        let base = FileManager.default.temporaryDirectory.standardizedFileURL.resolvingSymlinksInPath()
        let root = base.appendingPathComponent("Feedback_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var items: [PhotoItem] = []
        for name in files {
            let url = root.appendingPathComponent(name)
            try Data("PAYLOAD".utf8).write(to: url)
            if !name.lowercased().hasSuffix(".xmp"), !items.contains(where: { $0.url.deletingPathExtension() == url.deletingPathExtension() }) {
                items.append(PhotoItem(id: UUID(), url: url, status: .candidate))
            }
        }

        let suite = "com.photoselector.tests.feedback.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        var customURL: URL?
        if useCustom {
            let dir = base.appendingPathComponent("FeedbackCustom_\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            defaults.externalCullingDestinationURL = dir
            defaults.cullingDestinationMode = .customFolder
            defaults.cullingDestinationFolderName = "Picks"
            customURL = dir
        }

        let actor: AsyncCullingActor
        if let makeActor {
            actor = makeActor()
        } else {
            actor = AsyncCullingActor(trashService: FolderTrashService(directory: root.appendingPathComponent(".trash", isDirectory: true)))
        }
        let vm = CullingWorkspaceViewModel(cullingActor: actor, userDefaults: defaults)
        vm.setPhotos(items, rootURL: root)
        return Env(root: root, custom: customURL, defaults: defaults, suite: suite, items: items, vm: vm)
    }

    private func waitUntil(_ budget: TimeInterval = 10, _ condition: @MainActor () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(budget)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return condition()
    }

    /// Every distinct toast text the view model shows within `budget` (toasts last 1.2 s; polled every 10 ms).
    private func toastTexts(_ vm: CullingWorkspaceViewModel, budget: TimeInterval, until stop: @MainActor ([String]) -> Bool = { _ in false }) async -> [String] {
        var seen: [String] = []
        let deadline = Date().addingTimeInterval(budget)
        while Date() < deadline {
            if let toast = vm.currentToast {
                let text = [toast.title, toast.subtitle].compactMap { $0 }.joined(separator: " | ")
                if seen.last != text { seen.append(text) }
                if stop(seen) { break }
            }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return seen
    }

    // MARK: - The class check

    @Test("REQ-MAC-CULL.03 F-LJ-027/-031/-033 (class): the toast of every action names the photo, where it went and the action",
          arguments: CullingActionType.allCases, [false, true])
    func toastOfEveryActionNamesPhotoAndDestination(action: CullingActionType, custom: Bool) async throws {
        let env = try makeEnv(custom: custom)
        defer { env.cleanUp() }
        let name = env.items[0].filename

        env.vm.cullCurrentPhoto(action: action)
        // Synchronous: the feedback is optimistic, it does not wait for the disk.
        let toast = try #require(env.vm.currentToast, "\(action) gave no feedback at all")
        #expect(toast.subtitle == name, "\(action): the toast must name the photo")
        #expect(toast.actionType == action)
        #expect(!toast.icon.isEmpty)
        switch action {
        case .move: #expect(toast.title == "Moved to \(env.folderName)")
        case .copy: #expect(toast.title == "Copied to \(env.folderName)")
        case .trash: #expect(toast.title == "Moved to Trash")
        }
        #expect(await waitUntil { env.vm.cardCullSummary.inFlightCount == 0 })

        // Where the files went is what the toast said.
        let fm = FileManager.default
        switch action {
        case .move:
            #expect(fm.fileExists(atPath: env.base.appendingPathComponent(env.folderName).appendingPathComponent(name).path))
            #expect(!fm.fileExists(atPath: env.items[0].url.path))
        case .copy:
            #expect(fm.fileExists(atPath: env.base.appendingPathComponent(env.folderName).appendingPathComponent(name).path))
            #expect(fm.fileExists(atPath: env.items[0].url.path))
        case .trash:
            #expect(!fm.fileExists(atPath: env.items[0].url.path))
        }
    }

    @Test("REQ-MAC-CULL.04 (class): Undo of every action says so and names the photo, and puts it back",
          arguments: CullingActionType.allCases)
    func undoOfEveryActionNamesThePhoto(action: CullingActionType) async throws {
        let env = try makeEnv(custom: false)
        defer { env.cleanUp() }
        let name = env.items[0].filename

        env.vm.cullCurrentPhoto(action: action)
        #expect(await waitUntil { env.vm.cardCullSummary.inFlightCount == 0 })
        env.vm.undoLastAction()

        let toast = try #require(env.vm.currentToast, "Undo of \(action) gave no feedback")
        #expect(toast.title == "Action Undone")
        #expect(toast.subtitle == name)
        let copyInSelection = env.root.appendingPathComponent("Selection").appendingPathComponent(name)
        // Undo runs on the actor: wait for its end state (original home; for Copy the replica gone).
        #expect(await waitUntil {
            FileManager.default.fileExists(atPath: env.items[0].url.path)
                && (action != .copy || !FileManager.default.fileExists(atPath: copyInSelection.path))
        })
    }

    @Test("REQ-MAC-CULL.03 (class): a failed action says it failed, names the photo, and gives the photo back",
          arguments: CullingActionType.allCases)
    func failureOfEveryActionNamesThePhoto(action: CullingActionType) async throws {
        let env = try makeEnv(custom: false)
        defer { env.cleanUp() }
        let victim = env.items[0]
        try FileManager.default.removeItem(at: victim.url) // the card was pulled / the file moved away

        env.vm.cullCurrentPhoto(action: action)
        let texts = await toastTexts(env.vm, budget: 5) { $0.contains { $0.hasPrefix("Action Failed") } }
        let failure = try #require(texts.first { $0.hasPrefix("Action Failed") }, "no failure feedback for \(action); saw \(texts)")
        #expect(failure.contains(victim.filename), "the failure must name the photo: \(failure)")
        #expect(env.vm.photos.first { $0.id == victim.id }?.status == .candidate, "the photo returns to the queue")
        #expect(!env.vm.canUndo, "a failed action leaves nothing to undo")
    }

    // MARK: - F-LJ-027

    @Test("REQ-MAC-CULL.04 F-LJ-027: a bundle move whose rollback fails says which photo's files stayed in which folder")
    func rollbackFailureNamesPhotoAndFolder() async throws {
        let env = try makeEnv(custom: false, files: ["R0.CR3", "R0.JPG"]) {
            let fm = StrandingFileManager(failing: "R0.JPG", unrestorable: "R0.CR3")
            return AsyncCullingActor(cullingManager: FileCullingManager(fileManager: fm), fileManager: fm)
        }
        defer { env.cleanUp() }
        let photo = try #require(env.items.first)
        #expect(photo.filename == "R0.CR3")

        env.vm.cullCurrentPhoto(action: .move)
        let texts = await toastTexts(env.vm, budget: 5) { $0.contains { $0.hasPrefix("Move interrupted") } }
        let message = try #require(texts.first { $0.hasPrefix("Move interrupted") }, "no rollback message; saw \(texts)")
        #expect(message.contains("R0.CR3"), "names the photo: \(message)")
        #expect(message.contains("Selection"), "names the folder the files stayed in: \(message)")
        // The stranded member stays undoable (F-LJ-019) and the photo is back in the queue.
        #expect(env.vm.canUndo)
        #expect(env.vm.photos[0].status == .candidate)
    }

    // MARK: - Open rows, strict known failures

    @Test("REQ-MAC-CULL.05 F-LJ-031: a RAW + JPEG + sidecar bundle is named as a bundle in the feedback (open)")
    func bundleIsNamedInFeedback() async throws {
        let env = try makeEnv(custom: false, files: ["B0.CR3", "B0.JPG", "B0.xmp", "B1.JPG"])
        defer { env.cleanUp() }
        #expect(env.items[0].filename == "B0.CR3")

        env.vm.cullCurrentPhoto(action: .move)
        let texts = await toastTexts(env.vm, budget: 1.5)

        await withKnownIssue("F-LJ-031: toasts name the primary file only; no screen says that three files moved together (CullingWorkspaceViewModel.swift: toastTitle / subtitle: photo.filename)") {
            #expect(texts.contains { $0.contains("+2") || $0.localizedCaseInsensitiveContains("related") || $0.contains("3 files") },
                    "no feedback mentions the two companions; saw \(texts)")
        }
    }

    @Test("REQ-MAC-CULL.03 F-LJ-033: a Copy leaves a lasting count in the bottom bar tally (open)")
    func copyLeavesALastingCount() async throws {
        let env = try makeEnv(custom: false)
        defer { env.cleanUp() }

        env.vm.cullCurrentPhoto(action: .copy)
        #expect(await waitUntil { env.vm.cardCullSummary.inFlightCount == 0 })
        #expect(env.vm.photos[0].status == .copied)

        // The bar's "N Selected" reads `selectedCount` (CullingActionBar.swift: viewModel.selectedCount).
        await withKnownIssue("F-LJ-033: selectedCount ignores .copied, so after a Copy the bar shows 0 Selected; only the 1.2 s toast remains (CullingWorkspaceViewModel.swift: selectedCount)") {
            #expect(env.vm.selectedCount == 1)
        }
    }
}
