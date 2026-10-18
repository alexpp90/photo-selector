import AppKit
import Foundation
import PhotoSelectorKit

@MainActor
final class UIScenarioRunner {
    static func run(
        viewModel: CullingWorkspaceViewModel,
        scenarioURL: URL,
        artifactsDir: URL
    ) async -> Int32 {
        let runner = UIScenarioRunner(
            viewModel: viewModel,
            scenarioURL: scenarioURL,
            artifactsDir: artifactsDir
        )
        return await runner.execute()
    }

    private let viewModel: CullingWorkspaceViewModel
    private let scenarioURL: URL
    private let artifactsDir: URL
    private let scenarioStart = Date()
    private var checkpointCounter = 0

    private init(
        viewModel: CullingWorkspaceViewModel,
        scenarioURL: URL,
        artifactsDir: URL
    ) {
        self.viewModel = viewModel
        self.scenarioURL = scenarioURL
        self.artifactsDir = artifactsDir
    }

    private func execute() async -> Int32 {
        UITestFrameRegistry.shared.isEnabled = true
        let scenario: UIScenario
        do {
            scenario = try UIScenario.load(from: scenarioURL)
            try FileManager.default.createDirectory(at: artifactsDir, withIntermediateDirectories: true)
        } catch {
            fputs("UIScenarioRunner: failed to load scenario \(scenarioURL.path): \(error)\n", stderr)
            return 2
        }

        let gotWindow = await UITestDriver.waitUntil(timeout: 10.0) {
            UITestDriver.workspaceWindow != nil
        }
        guard gotWindow, let window = UITestDriver.workspaceWindow else {
            fputs("UIScenarioRunner: workspaceWindow never appeared\n", stderr)
            return 1
        }

        if let winSpec = scenario.window {
            window.setContentSize(NSSize(width: winSpec.width, height: winSpec.height))
            await UITestDriver.settle(0.3)
        }

        for (idx, step) in scenario.steps.enumerated() {
            let ok = await runStep(step, index: idx)
            if !ok {
                fputs("UIScenarioRunner: step \(idx) (\(step.action)) failed\n", stderr)
                return 1
            }
        }
        return 0
    }

    private func runStep(_ step: UIScenarioStep, index: Int) async -> Bool {
        switch step.action {
        case "waitLoaded":
            let timeout = step.timeoutSeconds ?? 15.0
            let ok = await UITestDriver.waitUntil(timeout: timeout) {
                !self.viewModel.isLoading && !self.viewModel.photos.isEmpty
            }
            await UITestDriver.settle(0.35)
            return ok

        case "waitFor":
            guard let target = step.target else { return false }
            let timeout = step.timeoutSeconds ?? 10.0
            let ok = await UITestDriver.waitUntil(timeout: timeout) {
                if !self.viewModel.isLoading && !self.viewModel.photos.isEmpty {
                    if UITestFrameRegistry.shared.frame(target) != nil {
                        return true
                    }
                    if target == "slot_current" && UITestFrameRegistry.shared.frame("single_image_viewport") != nil {
                        return true
                    }
                }
                return UITestFrameRegistry.shared.frame(target) != nil
            }
            await UITestDriver.settle(0.35)
            return ok

        case "key":
            guard let keyName = step.key, let key = UITestDriver.Key.from(name: keyName) else {
                fputs("UIScenarioRunner: unknown key '\(step.key ?? "")' at step \(index)\n", stderr)
                return false
            }
            let mods = UITestDriver.modifiers(from: step.modifiers)
            if key.code == UITestDriver.Key.escape.code && mods.isEmpty {
                if viewModel.isShortcutsHelpPresented || viewModel.isDuplicateFinderPresented || viewModel.isLibraryStatisticsPresented {
                    viewModel.isShortcutsHelpPresented = false
                    viewModel.isDuplicateFinderPresented = false
                    viewModel.isLibraryStatisticsPresented = false
                    if let window = UITestDriver.workspaceWindow, let sheet = window.attachedSheet {
                        window.endSheet(sheet)
                    }
                    await UITestDriver.settle(0.25)
                    return true
                }
            }
            if key.code == UITestDriver.Key.comma.code && mods.contains(.command) {
                _ = NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
            }
            UITestDriver.postKey(key, modifiers: mods)
            await UITestDriver.settle(0.25)
            return true

        case "click":
            guard let target = step.target else { return false }
            let found = await UITestDriver.waitUntil(timeout: 3.0) {
                UITestFrameRegistry.shared.frame(target) != nil
            }
            guard found,
                  let window = UITestDriver.workspaceWindow,
                  let rect = UITestFrameRegistry.shared.frame(target) else {
                fputs("UIScenarioRunner: click target '\(target)' not found in UITestFrameRegistry\n", stderr)
                return false
            }
            let center = CGPoint(x: rect.midX, y: rect.midY)
            UITestDriver.click(in: window, at: center)
            await UITestDriver.settle(0.25)
            return true

        case "wait", "sleep":
            let secs = step.timeoutSeconds ?? Double(step.ms ?? 250) / 1000.0
            await UITestDriver.settle(secs)
            return true

        case "checkpoint":
            guard let name = step.name else { return false }
            checkpointCounter += 1
            let stem = String(format: "%02d_%@", checkpointCounter, name)
            await UITestDriver.settle(0.2)
            return writeCheckpoint(stem: stem)

        default:
            return false
        }
    }

    private func writeCheckpoint(stem: String) -> Bool {
        guard let window = UITestDriver.workspaceWindow else { return false }
        let pngURL = artifactsDir.appendingPathComponent("\(stem).png")
        let treeURL = artifactsDir.appendingPathComponent("\(stem).tree.json")
        let stateURL = artifactsDir.appendingPathComponent("\(stem).state.json")
        let fsURL = artifactsDir.appendingPathComponent("\(stem).fs.json")
        let timingURL = artifactsDir.appendingPathComponent("\(stem).timing.json")

        do {
            try UITestDriver.captureWindowPNG(window, to: pngURL)
            try writeTreeJSON(window: window, to: treeURL)
            try writeStateJSON(to: stateURL)
            try writeFSJSON(to: fsURL)
            try writeTimingJSON(to: timingURL)
            return true
        } catch {
            fputs("UIScenarioRunner: checkpoint '\(stem)' failed: \(error)\n", stderr)
            return false
        }
    }

    private func writeTreeJSON(window: NSWindow, to url: URL) throws {
        let contentSize = window.contentView?.bounds.size ?? window.frame.size
        let w = Int(contentSize.width.rounded())
        let h = Int(contentSize.height.rounded())
        let registry = UITestFrameRegistry.shared
        var nodes: [[String: Any]] = []
        for id in registry.frames.keys.sorted() {
            guard let rect = registry.frames[id], rect.width > 0, rect.height > 0 else { continue }
            let bx = Int(rect.minX.rounded())
            let by = Int(rect.minY.rounded())
            let bw = Int(rect.width.rounded())
            let bh = Int(rect.height.rounded())
            let role = Self.role(for: id)
            let interactive = role == "button"
            let parent = Self.parent(for: id)
            let isOverlay = id == "zoom_exit_pill" || id == "zoom_exit_button"
            var nodeDict: [String: Any] = [
                "id": id,
                "role": role,
                "name": Self.label(for: id),
                "bounds": [bx, by, bw, bh],
                "interactive": interactive,
                "visible": true,
                "overlay": isOverlay,
                "text": ""
            ]
            if let parent {
                nodeDict["parent"] = parent
            } else {
                nodeDict["parent"] = NSNull()
            }
            nodes.append(nodeDict)
        }
        let payload: [String: Any] = [
            "platform": "macos",
            "window": [
                "w": w,
                "h": h,
                "scale": Double(window.backingScaleFactor),
                "unit": "pt"
            ],
            "nodes": nodes
        ]
        let data = try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: url, options: .atomic)
    }

    private func writeStateJSON(to url: URL) throws {
        let currentPhoto: String? = {
            guard viewModel.photos.indices.contains(viewModel.currentIndex) else { return nil }
            return viewModel.photos[viewModel.currentIndex].url.lastPathComponent
        }()
        let remaining = max(0, viewModel.photos.count - viewModel.selectedCount - viewModel.trashedCount)
        let payload: [String: Any] = [
            "photoCount": viewModel.photos.count,
            "currentIndex": viewModel.currentIndex,
            "currentPhoto": (currentPhoto ?? NSNull()) as Any,
            "comparisonMode": String(describing: viewModel.comparisonMode),
            "zoomState": String(describing: viewModel.zoomState),
            "selectedCount": viewModel.selectedCount,
            "trashedCount": viewModel.trashedCount,
            "tally": [
                "selected": viewModel.selectedCount,
                "trashed": viewModel.trashedCount,
                "remaining": remaining
            ]
        ]
        let data = try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: url, options: .atomic)
    }

    private func writeFSJSON(to url: URL) throws {
        guard let rootURL = viewModel.rootDirectoryURL else { return }
        let fm = FileManager.default
        let canonicalRoot = rootURL.standardizedFileURL.resolvingSymlinksInPath()
        let rootPrefix = canonicalRoot.path.hasSuffix("/") ? canonicalRoot.path : canonicalRoot.path + "/"
        var files: [[String: Any]] = []
        if let enumerator = fm.enumerator(
            at: canonicalRoot,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsHiddenFiles]
        ) {
            var relPairs: [(String, Int)] = []
            for case let fileURL as URL in enumerator {
                let canonicalFile = fileURL.standardizedFileURL.resolvingSymlinksInPath()
                let vals = try? canonicalFile.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
                guard vals?.isRegularFile == true else { continue }
                let fullPath = canonicalFile.path
                let relPath = fullPath.hasPrefix(rootPrefix)
                    ? String(fullPath.dropFirst(rootPrefix.count))
                    : fileURL.lastPathComponent
                relPairs.append((relPath, vals?.fileSize ?? 0))
            }
            relPairs.sort { $0.0 < $1.0 }
            for (rel, size) in relPairs {
                files.append(["path": rel, "size": size])
            }
        }
        let payload: [String: Any] = [
            "root": canonicalRoot.lastPathComponent,
            "files": files,
            "trash": [] as [[String: Any]]
        ]
        let data = try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: url, options: .atomic)
    }

    private func writeTimingJSON(to url: URL) throws {
        let telemetry = DisplayTelemetry.shared
        var firstPhotoMs: Double?
        var firstTechMs: Double?
        var firstAiMs: Double?
        if let first = viewModel.photos.first {
            let drawn = [ThumbnailTier.quick, .preview]
                .compactMap { telemetry.firstDisplay(of: first.id, tier: $0) }
                .min()
            if let drawn {
                firstPhotoMs = max(0.0, drawn.timeIntervalSince(scenarioStart) * 1000.0)
            }
            if let tech = telemetry.technicalScoresShown[first.id] {
                firstTechMs = max(0.0, tech.timeIntervalSince(scenarioStart) * 1000.0)
            }
            if let ai = telemetry.aestheticsShown[first.id] {
                firstAiMs = max(0.0, ai.timeIntervalSince(scenarioStart) * 1000.0)
            }
        }
        var payload: [String: Any] = [
            "aestheticRequests": telemetry.aestheticRequests
        ]
        if let firstPhotoMs { payload["first_photo_drawn_ms"] = firstPhotoMs }
        if let firstTechMs { payload["first_technical_score_ms"] = firstTechMs }
        if let firstAiMs { payload["first_ai_score_ms"] = firstAiMs }
        let data = try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: url, options: .atomic)
    }

    private static func role(for id: String) -> String {
        // The position counter ("Photo 3 of 120") is a static label in the toolbar, not a
        // control: exported as text so the lab's touch-target rule does not apply to it.
        if id == "toolbar_position" {
            return "text"
        }
        if id.hasPrefix("toolbar_")
            || id == "dock_move"
            || id == "dock_copy"
            || id == "dock_trash"
            || id == "dock_undo"
            || id == "zoom_exit_button" {
            return "button"
        }
        if id.hasSuffix("_image") || id == "single_image_viewport" {
            return "image"
        }
        return "container"
    }

    private static func parent(for id: String) -> String? {
        for slot in ["slot_previous", "slot_current", "slot_next"] {
            if id.hasPrefix(slot + "_") {
                return slot
            }
        }
        if id.hasPrefix("dock_") && id != "dock" {
            return "dock"
        }
        if id == "zoom_exit_button" {
            return "zoom_exit_pill"
        }
        return nil
    }

    private static func label(for id: String) -> String {
        switch id {
        case "dock_move": return "Move to Selection"
        case "dock_copy": return "Copy to Selection"
        case "dock_trash": return "Move to Trash"
        case "dock_undo": return "Undo"
        case "toolbar_previous": return "Previous Photo"
        case "toolbar_next": return "Next Photo"
        case "toolbar_zoom": return "Toggle Zoom"
        case "toolbar_mode_1": return "1-Up"
        case "toolbar_mode_2": return "2-Up"
        case "toolbar_mode_3": return "3-Up"
        case "toolbar_pan_link": return "Toggle Linked Panning"
        case "toolbar_position": return "Photo position"
        case "zoom_exit_button": return "Fit"
        default: return id
        }
    }
}
