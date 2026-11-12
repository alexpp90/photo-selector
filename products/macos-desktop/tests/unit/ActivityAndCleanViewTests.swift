import Testing
import Foundation
@testable import PhotoSelectorKit

/// [USER 2026-11-12] decision #23 (Activity list), #24 (clean view on F) and #33 item 5 (the visible
/// Statistics entry point).
@Suite("Activity list, clean view and the Statistics entry")
@MainActor
struct ActivityAndCleanViewTests {
    private func makeVM(count: Int = 3) -> CullingWorkspaceViewModel {
        let suite = "com.photoselector.tests.activity.\(UUID().uuidString)"
        let vm = CullingWorkspaceViewModel(userDefaults: UserDefaults(suiteName: suite)!)
        vm.autoAdvance = false
        vm.setPhotos((0..<count).map { PhotoItem(url: URL(fileURLWithPath: "/tmp/ACT_\(UUID().uuidString)_\($0).jpg")) })
        return vm
    }

    // MARK: Activity

    @Test("Rows come newest first from the log, with the combined spoken label")
    func rowsNewestFirst() {
        let now = Date()
        let log = [
            CullingDecisionLogEntry(timestamp: now.addingTimeInterval(-300), filename: "IMG_0001.CR3", action: .move, destination: "Selection"),
            CullingDecisionLogEntry(timestamp: now.addingTimeInterval(-120), filename: "IMG_0002.CR3", action: .copy, destination: "Selection"),
            CullingDecisionLogEntry(timestamp: now.addingTimeInterval(-1), filename: "IMG_0003.CR3", action: .trash, destination: "Trash"),
        ]
        let rows = ActivityList.rows(from: log, now: now)
        #expect(rows.map(\.filename) == ["IMG_0003.CR3", "IMG_0002.CR3", "IMG_0001.CR3"])
        #expect(rows.map(\.symbol) == ["trash", "doc.on.doc", "arrow.right.circle"])
        #expect(rows[2].spokenLabel == "Moved IMG_0001.CR3 to Selection, 5 minutes ago")
        #expect(rows[1].spokenLabel == "Copied IMG_0002.CR3 to Selection, 2 minutes ago")
        #expect(rows[0].spokenLabel == "Trashed IMG_0003.CR3 to Trash, just now")
        let rejected = ActivityList.rows(from: [CullingDecisionLogEntry(filename: "X.JPG", action: .trash, destination: "Rejected")], now: now)
        #expect(rejected[0].verb == "Rejected")
    }

    @Test("At most 100 rows, the newest ones")
    func capped() {
        let log = (0..<130).map { CullingDecisionLogEntry(filename: "P\($0).JPG", action: .move, destination: "Selection") }
        let rows = ActivityList.rows(from: log)
        #expect(rows.count == 100)
        #expect(rows.first?.filename == "P129.JPG" && rows.last?.filename == "P30.JPG")
        #expect(ActivityList.emptyText == "No decisions yet")
        #expect(ActivityList.rows(from: []).isEmpty)
    }

    @Test("A decision lands in the list with its destination, a click selects the photo, Undo takes it out")
    func viewModelFeedsTheList() throws {
        let vm = makeVM()
        vm.selectPhoto(at: 1)
        vm.cullCurrentPhoto(action: .copy)
        let rows = vm.activityRows
        #expect(rows.count == 1)
        #expect(rows[0].verb == "Copied" && rows[0].destination == "Selection")
        vm.selectPhoto(at: 0)
        #expect(vm.selectActivity(rows[0]))
        #expect(vm.currentIndex == 1)
        vm.undoLastAction()
        #expect(vm.activityRows.isEmpty)
    }

    @Test("A photo that is gone from the folder is not selected")
    func goneRow() {
        let vm = makeVM(count: 1)
        let row = ActivityRow(id: UUID(), symbol: "trash", verb: "Trashed", filename: "x", destination: nil,
                              relativeTime: "now", spokenLabel: "x", photoID: UUID())
        #expect(vm.selectActivity(row) == false)
    }

    @Test("The menu and the toolbar button open the same list, and ⇧⌘L is its key")
    func opensFromMenuAndKey() {
        let vm = makeVM()
        vm.presentActivity()
        #expect(vm.isActivityPresented)
        vm.isActivityPresented = false
        let router = KeyboardShortcutRouter()
        let event = SyntheticKeyEvent(keyCode: 37, characters: "l", modifierFlags: [.command, .shift])
        #expect(router.handleKeyEvent(event, viewModel: vm))
        #expect(vm.isActivityPresented && !vm.isLibraryStatisticsPresented)
        #expect(LensjetCommand.activity.key == "\u{21E7}\u{2318}L")
        #expect(LensjetMenu.go.commands.contains(.activity))
        #expect(LensjetCommand.activity.name() == "Activity")
    }

    // MARK: Statistics entry

    @Test("The toolbar's Statistics button runs the menu command and is named")
    func statisticsEntry() throws {
        let vm = makeVM()
        vm.presentLibraryStatistics()
        #expect(vm.isLibraryStatisticsPresented)
        let text = try String(contentsOf: Self.source("PhotoSelectorApp/ContentView.swift"), encoding: .utf8)
        #expect(text.contains("viewModel.presentLibraryStatistics()"))
        #expect(text.contains("LensjetCommand.statistics.name()"))
        #expect(text.contains("Open a folder first"), "disabled without a folder, and the tooltip says why")
        #expect(text.contains("toolbar_statistics") && text.contains("toolbar_activity"))
        #expect(LensjetCommand.statistics.tooltip() == "Statistics (\u{2318}L)")
    }

    private static func source(_ relative: String) -> URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("src").appendingPathComponent(relative)
    }

    // MARK: Clean view

    @Test("F toggles full screen, the window's state hides the chrome, Esc leaves it after zoom")
    func cleanViewState() {
        let vm = makeVM()
        var toggled = 0
        vm.fullScreenToggler = { toggled += 1; vm.setFullScreen(!vm.isFullScreen) }
        let router = KeyboardShortcutRouter()
        #expect(vm.isFullScreen == false)
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 3, characters: "f"), viewModel: vm))
        #expect(vm.isFullScreen && toggled == 1)
        // A zoom is left first.
        vm.toggleZoom()
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 53, characters: "\u{1B}"), viewModel: vm))
        #expect(vm.zoomState == .fit && vm.isFullScreen)
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 53, characters: "\u{1B}"), viewModel: vm))
        #expect(vm.isFullScreen == false)
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 53, characters: "\u{1B}"), viewModel: vm) == false, "Esc outside full screen is not consumed")
    }

    @Test("Every key keeps working in clean view and each decision shows a pill with the remaining count")
    func keysAndPill() {
        let vm = makeVM(count: 4)
        let router = KeyboardShortcutRouter()
        vm.setFullScreen(true)
        for (code, chars) in [(UInt16(17), "t"), (UInt16(34), "i"), (UInt16(19), "2"), (UInt16(18), "1")] {
            #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: code, characters: chars), viewModel: vm))
        }
        #expect(vm.isFilmstripVisible == false, "T still toggles the filmstrip state")
        #expect(vm.comparisonMode == .single)
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 46, characters: "m"), viewModel: vm))
        #expect(vm.cleanPill?.text == "Moved \u{00B7} 3")
        #expect(vm.cleanPill?.symbol == "checkmark.circle.fill")
        vm.selectPhoto(at: 1)
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 8, characters: "c"), viewModel: vm))
        #expect(vm.cleanPill?.text == "Copied \u{00B7} 2")
        vm.setFullScreen(false)
        #expect(vm.cleanPill == nil)
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.cleanPill == nil, "no pill outside clean view")
    }

    @Test("\"Esc exits\" shows once per install")
    func hintOnce() {
        let vm = makeVM()
        vm.setFullScreen(true)
        #expect(vm.currentToast?.title == "Esc exits")
        vm.setFullScreen(false)
        vm.currentToast = nil
        vm.setFullScreen(true)
        #expect(vm.currentToast == nil, "the hint is not shown a second time")
    }

    @Test("The chrome is hidden in full screen, in the source the window uses")
    func chromeFlags() throws {
        let text = try String(contentsOf: Self.source("PhotoSelectorApp/ContentView.swift"), encoding: .utf8)
        #expect(text.contains("if !viewModel.isFullScreen { topNavigationBar }"))
        #expect(text.contains("if !viewModel.isFullScreen { CullingActionBar(viewModel: viewModel) }"))
        #expect(text.contains("viewModel.isFilmstripVisible && !viewModel.isFullScreen"))
        #expect(text.contains("willEnterFullScreenNotification") && text.contains("willExitFullScreenNotification"))
        #expect(LensjetCommand.cleanView.key == "F")
        #expect(LensjetCommand.filmstrip.key == "T")
        #expect(LensjetMenu.view.commands.contains(.cleanView))
    }
}
