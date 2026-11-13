import SwiftUI
import AppKit
import PhotoSelectorKit

@main
struct PhotoSelectorApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView(viewModel: appDelegate.viewModel)
                .preferredColorScheme(.dark)
        }
        .defaultSize(width: 1280, height: 840)
        .commands {
            AppCommands(viewModel: appDelegate.viewModel)
        }

        Settings {
            SettingsView(workspace: appDelegate.viewModel)
                .preferredColorScheme(.dark)
        }
    }
}

/// Owns the workspace view model and makes the process keyboard-capable at launch.
///
/// `ForegroundActivation.ensureKeyboardCapable` runs in `applicationWillFinishLaunching`:
/// without it an unbundled binary keeps activation policy `.prohibited`, the window server
/// never makes it the active app, and arrow keys go to Terminal instead (REQ-MAC-KEY.01).
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, ObservableObject {
    /// Created after the one-time settings migration so the view model's first read of
    /// `UserDefaults` already sees the preferences of the pre-rename bundle id. UI test modes
    /// skip the migration to stay hermetic.
    let viewModel: CullingWorkspaceViewModel = {
        let arguments = ProcessInfo.processInfo.arguments
        let isTestMode = arguments.contains { ["--ui-self-test", "--ui-scenario", "--screenshot", "--ui-live"].contains($0) }
        if !isTestMode {
            // Pin the file-name order for an install that predates the sort menu BEFORE the
            // migration marker is written (the marker is how an existing install is recognised).
            SettingsMigration.pinSortOrderForExistingInstall()
            SettingsMigration.pinAestheticsForExistingInstall()
            SettingsMigration.migrateIfNeeded()
        }
        #if PST_TEST_HOOKS
        if isTestMode {
            // A lab run touches only its own defaults and its own trash folder (never the
            // account's ~/.Trash or settings).
            let options = LaunchOptions(arguments: arguments)
            let parent = options.artifactsDir
                ?? FileManager.default.temporaryDirectory
                    .appendingPathComponent("lensjet-lab-\(UUID().uuidString)", isDirectory: true)
            let session = HermeticLabSession(trashParent: parent)
            HermeticLabSession.current = session
            return session.makeViewModel()
        }
        #endif
        return CullingWorkspaceViewModel()
    }()
    let launchOptions = LaunchOptions(arguments: ProcessInfo.processInfo.arguments)
    #if PST_TEST_HOOKS
    private var selfTest: UISelfTestRunner?
    #endif

    func applicationWillFinishLaunching(_ notification: Notification) {
        ForegroundActivation.ensureKeyboardCapable(NSApp)
        #if PST_TEST_HOOKS
        if launchOptions.selfTestFolder != nil || launchOptions.scenarioURL != nil {
            UITestFrameRegistry.shared.isEnabled = true
        }
        #endif
    }

    private static var isGUIRequested: Bool {
        guard let value = ProcessInfo.processInfo.environment["PST_SHOW_GUI"] else { return false }
        return value != "0" && !value.isEmpty
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        #if !PST_TEST_HOOKS
        if launchOptions.requestsTestHooks {
            // A release build must never drive itself: fail fast instead of opening a normal window
            // that a script would wait on forever (or, worse, act on).
            FileHandle.standardError.write(Data("This build has no UI test hooks (build with PST_TEST_HOOKS=1 or in debug).\n".utf8))
            exit(64)
        }
        #endif
        let isBackgroundTest = launchOptions.requestsTestHooks && !Self.isGUIRequested
        if !isBackgroundTest {
            NSApp.activate(ignoringOtherApps: true)
        }
        DispatchQueue.main.async {
            Self.makeWorkspaceWindowKey()
        }

        #if PST_TEST_HOOKS
        if let folder = launchOptions.selfTestFolder {
            let runner = UISelfTestRunner(
                viewModel: viewModel,
                folder: folder,
                reportURL: launchOptions.reportURL,
                expectedCount: launchOptions.expectedCount,
                externalKeyWait: launchOptions.externalKeyWait
            )
            selfTest = runner
            Task { @MainActor in
                let passed = await runner.run()
                HermeticLabSession.current?.cleanup()
                NSApp.terminate(passed ? 0 : 1)
                exit(passed ? 0 : 1)
            }
        } else if let controlDir = launchOptions.liveControlDir {
            if let folder = launchOptions.openFolder {
                viewModel.loadFolder(at: folder)
            }
            let artifactsDir = launchOptions.artifactsDir
                ?? controlDir.appendingPathComponent("artifacts", isDirectory: true)
            Task { @MainActor in
                let code = await UIScenarioRunner.runLive(viewModel: viewModel, controlDir: controlDir, artifactsDir: artifactsDir)
                HermeticLabSession.current?.cleanup()
                exit(code)
            }
        } else if let scenarioURL = launchOptions.scenarioURL {
            if let folder = launchOptions.openFolder {
                viewModel.loadFolder(at: folder)
            }
            let artifactsDir = launchOptions.artifactsDir
                ?? URL(fileURLWithPath: FileManager.default.currentDirectoryPath, isDirectory: true)
                    .appendingPathComponent("scenario-artifacts", isDirectory: true)
            Task { @MainActor in
                let code = await UIScenarioRunner.run(
                    viewModel: viewModel,
                    scenarioURL: scenarioURL,
                    artifactsDir: artifactsDir
                )
                HermeticLabSession.current?.cleanup()
                NSApp.terminate(nil)
                exit(code)
            }
        } else if let screenshotURL = launchOptions.screenshotURL {
            if let folder = launchOptions.openFolder {
                viewModel.loadFolder(at: folder)
            }
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                guard let window = NSApp.windows.first(where: { $0.canBecomeKey && !$0.isSheet }) ?? NSApp.windows.first else {
                    exit(1)
                }
                window.setContentSize(NSSize(width: 1440, height: 900))
                window.layoutIfNeeded()
                guard let view = window.contentView,
                      let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else {
                    exit(1)
                }
                view.cacheDisplay(in: view.bounds, to: rep)
                if let data = rep.representation(using: .png, properties: [:]) {
                    try? data.write(to: screenshotURL)
                    print("WROTE_SCREENSHOT:\(screenshotURL.path)")
                }
                NSApp.terminate(nil)
                exit(0)
            }
        } else if let folder = launchOptions.openFolder {
            viewModel.loadFolder(at: folder)
        } else {
            viewModel.resumeLastFolder()
        }
        #else
        if let folder = launchOptions.openFolder {
            viewModel.loadFolder(at: folder)
        } else {
            // No dialog: the last folder reopens at the photo that was on screen, if it still exists.
            viewModel.resumeLastFolder()
        }
        #endif
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    /// Brings the first ordinary (non-panel) window to the front and makes it key,
    /// or positions it off-screen and invisible during background test execution.
    static func makeWorkspaceWindowKey() {
        guard let window = NSApp.windows.first(where: { $0.canBecomeKey && !$0.isSheet && $0.isVisible })
                ?? NSApp.windows.first(where: { $0.canBecomeKey && !$0.isSheet }) else { return }
        let isBackgroundTest = !Self.isGUIRequested
            && (ProcessInfo.processInfo.arguments.contains("--ui-self-test")
                || ProcessInfo.processInfo.arguments.contains("--screenshot")
                || ProcessInfo.processInfo.arguments.contains("--ui-scenario")
                || ProcessInfo.processInfo.arguments.contains("--ui-live"))
        if isBackgroundTest {
            window.alphaValue = 0.0
            window.setFrame(NSRect(x: -20000, y: -20000, width: 1280, height: 840), display: false)
            window.orderBack(nil)
            window.makeKey()
        } else {
            window.makeKeyAndOrderFront(nil)
        }
    }
}

/// Command-line options understood by the app (all optional):
/// `--open <folder>` loads a folder at launch;
/// `--screenshot <path>` snapshots the workspace after loading;
/// `--ui-self-test <folder> [--report <json>] [--expect-count N] [--external-key-wait S]`
/// runs the in-app UI self-test and exits 0 (pass) or 1 (fail);
/// `--ui-scenario <scenario.json> [--artifacts <dir>]` runs a declarative UI scenario;
/// `--ui-live <dir> [--artifacts <dir>]` keeps the app open and runs one `cmd-<n>.json` step at a time.
struct LaunchOptions {
    var openFolder: URL?
    var selfTestFolder: URL?
    var scenarioURL: URL?
    var artifactsDir: URL?
    var reportURL: URL?
    var expectedCount: Int?
    var externalKeyWait: TimeInterval = 0
    var screenshotURL: URL?
    /// `--ui-live <dir>`: hold the app open and execute the commands of that folder.
    var liveControlDir: URL?

    /// True when the command line asks for one of the UI test modes.
    var requestsTestHooks: Bool {
        selfTestFolder != nil || screenshotURL != nil || scenarioURL != nil || liveControlDir != nil
    }

    init(arguments: [String]) {
        var iterator = arguments.dropFirst().makeIterator()
        while let argument = iterator.next() {
            switch argument {
            case "--open":
                openFolder = iterator.next().map { URL(fileURLWithPath: $0, isDirectory: true) }
            case "--screenshot":
                screenshotURL = iterator.next().map { URL(fileURLWithPath: $0) }
            case "--ui-self-test":
                selfTestFolder = iterator.next().map { URL(fileURLWithPath: $0, isDirectory: true) }
            case "--ui-scenario":
                scenarioURL = iterator.next().map { URL(fileURLWithPath: $0) }
            case "--ui-live":
                liveControlDir = iterator.next().map { URL(fileURLWithPath: $0, isDirectory: true) }
            case "--artifacts":
                artifactsDir = iterator.next().map { URL(fileURLWithPath: $0, isDirectory: true) }
            case "--report":
                reportURL = iterator.next().map { URL(fileURLWithPath: $0) }
            case "--expect-count":
                expectedCount = iterator.next().flatMap { Int($0) }
            case "--external-key-wait":
                externalKeyWait = iterator.next().flatMap { TimeInterval($0) } ?? 0
            default:
                continue
            }
        }
    }
}
