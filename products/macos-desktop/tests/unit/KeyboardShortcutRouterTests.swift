import Testing
import AppKit
import Foundation
@testable import PhotoSelectorKit

@Suite("KeyboardShortcutRouter Unit Tests")
@MainActor
struct KeyboardShortcutRouterTests {

    @MainActor
    private func createKeyEvent(
        keyCode: UInt16,
        characters: String = "",
        modifiers: NSEvent.ModifierFlags = []
    ) -> SyntheticKeyEvent {
        SyntheticKeyEvent(
            keyCode: keyCode,
            characters: characters,
            modifierFlags: modifiers
        )
    }

    private func createTestPhotos(count: Int) -> [PhotoItem] {
        (0..<count).map { i in
            PhotoItem(
                id: UUID(),
                url: URL(fileURLWithPath: "/tmp/photo_\(i).jpg"),
                status: .candidate
            )
        }
    }

    @Test("REQ-MAC-KEY.01: Arrow keys trigger forward and backward navigation")
    @MainActor
    func testArrowKeyNavigation() {
        let router = KeyboardShortcutRouter()
        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(createTestPhotos(count: 5))

        #expect(vm.currentIndex == 0)

        // Right arrow (keyCode 124) -> next
        let rightEvent = createKeyEvent(keyCode: 124)
        let handledRight = router.handleKeyEvent(rightEvent, viewModel: vm)
        #expect(handledRight == true)
        #expect(vm.currentIndex == 1)

        // Left arrow (keyCode 123) -> previous
        let leftEvent = createKeyEvent(keyCode: 123)
        let handledLeft = router.handleKeyEvent(leftEvent, viewModel: vm)
        #expect(handledLeft == true)
        #expect(vm.currentIndex == 0)
    }

    @Test("Vim keys (j / k) trigger forward and backward navigation")
    @MainActor
    func testVimNavigation() {
        let router = KeyboardShortcutRouter()
        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(createTestPhotos(count: 5))

        // 'j' -> next
        let jEvent = createKeyEvent(keyCode: 38, characters: "j")
        let handledJ = router.handleKeyEvent(jEvent, viewModel: vm)
        #expect(handledJ == true)
        #expect(vm.currentIndex == 1)

        // 'k' -> previous
        let kEvent = createKeyEvent(keyCode: 40, characters: "k")
        let handledK = router.handleKeyEvent(kEvent, viewModel: vm)
        #expect(handledK == true)
        #expect(vm.currentIndex == 0)
    }

    @Test("Culling keys (m, c, delete) trigger appropriate actions")
    @MainActor
    func testCullingKeys() {
        let router = KeyboardShortcutRouter()
        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(createTestPhotos(count: 5))

        // 'm' -> move to selection
        let mEvent = createKeyEvent(keyCode: 46, characters: "m")
        let handledM = router.handleKeyEvent(mEvent, viewModel: vm)
        #expect(handledM == true)
        #expect(vm.photos[0].status == .selected)

        // 'c' -> copy to selection (on next photo)
        let cEvent = createKeyEvent(keyCode: 8, characters: "c")
        let handledC = router.handleKeyEvent(cEvent, viewModel: vm)
        #expect(handledC == true)
        #expect(vm.photos[1].status == .copied)

        // Delete (keyCode 51) -> trash (on next photo)
        let delEvent = createKeyEvent(keyCode: 51)
        let handledDel = router.handleKeyEvent(delEvent, viewModel: vm)
        #expect(handledDel == true)
        #expect(vm.photos[2].status == .trashed)
    }

    @Test("Mode switching keys (1, 2, 3) change comparison modes")
    @MainActor
    func testModeSwitchingKeys() {
        let router = KeyboardShortcutRouter()
        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(createTestPhotos(count: 5))

        // '2' (keyCode 19) -> 2-Up
        let twoEvent = createKeyEvent(keyCode: 19, characters: "2")
        let handled2 = router.handleKeyEvent(twoEvent, viewModel: vm)
        #expect(handled2 == true)
        #expect(vm.comparisonMode == .sideBySide)

        // '3' (keyCode 20) -> 3-Up Focus View
        let threeEvent = createKeyEvent(keyCode: 20, characters: "3")
        let handled3 = router.handleKeyEvent(threeEvent, viewModel: vm)
        #expect(handled3 == true)
        #expect(vm.comparisonMode == .triplet)

        // '1' (keyCode 18) -> 1-Up Single
        let oneEvent = createKeyEvent(keyCode: 18, characters: "1")
        let handled1 = router.handleKeyEvent(oneEvent, viewModel: vm)
        #expect(handled1 == true)
        #expect(vm.comparisonMode == .single)
    }

    @Test("Space key toggles 100% zoom, Escape resets zoom")
    @MainActor
    func testZoomAndEscapeKeys() {
        let router = KeyboardShortcutRouter()
        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(createTestPhotos(count: 3))

        #expect(vm.zoomState == .fit)

        // Space (keyCode 49) -> 100% Zoom
        let spaceEvent = createKeyEvent(keyCode: 49, characters: " ")
        let handledSpace = router.handleKeyEvent(spaceEvent, viewModel: vm)
        #expect(handledSpace == true)
        #expect(vm.zoomState == .oneToOne)

        // Escape (keyCode 53) -> resets back to fit
        let escEvent = createKeyEvent(keyCode: 53)
        let handledEsc = router.handleKeyEvent(escEvent, viewModel: vm)
        #expect(handledEsc == true)
        #expect(vm.zoomState == .fit)
    }

    @Test("Command-Z triggers undo")
    @MainActor
    func testCommandZUndo() {
        let router = KeyboardShortcutRouter()
        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(createTestPhotos(count: 3))

        // Cull photo 0
        vm.cullCurrentPhoto(action: .move)
        #expect(vm.photos[0].status == .selected)
        #expect(vm.canUndo == true)

        // ⌘Z
        let cmdZEvent = createKeyEvent(keyCode: 6, characters: "z", modifiers: .command)
        let handledCmdZ = router.handleKeyEvent(cmdZEvent, viewModel: vm)
        #expect(handledCmdZ == true)
        #expect(vm.photos[0].status == .candidate)
    }

    // MARK: - Desk-app key alignment (KEYBOARD.md)

    private func suite() -> (UserDefaults, () -> Void) {
        let name = "com.photoselector.tests.keys.\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        return (d, { d.removePersistentDomain(forName: name) })
    }

    @Test("A held Delete, M or C acts once: auto-repeat events are consumed and do nothing")
    func heldDestructiveKeysAreOneShot() {
        let (defaults, cleanup) = suite(); defer { cleanup() }
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("held_\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let items: [PhotoItem] = (0..<4).map { i in
            let url = root.appendingPathComponent("H\(i).jpg"); try? Data("x".utf8).write(to: url)
            return PhotoItem(url: url)
        }
        let router = KeyboardShortcutRouter()
        let vm = CullingWorkspaceViewModel(userDefaults: defaults)
        vm.setPhotos(items, rootURL: root)
        vm.autoAdvance = true
        for (code, chars) in [(UInt16(46), "m"), (UInt16(8), "c"), (UInt16(51), "\u{7F}")] {
            let first = SyntheticKeyEvent(keyCode: code, characters: chars)
            #expect(router.handleKeyEvent(first, viewModel: vm))
        }
        #expect(vm.photos.filter { $0.status != .candidate }.count == 3)
        let before = vm.photos.map(\.status)
        for (code, chars) in [(UInt16(46), "m"), (UInt16(8), "c"), (UInt16(51), "\u{7F}")] {
            let repeated = SyntheticKeyEvent(keyCode: code, characters: chars, isARepeat: true)
            #expect(router.handleKeyEvent(repeated, viewModel: vm), "the repeat is consumed so it cannot leak to a button")
        }
        #expect(vm.photos.map(\.status) == before, "a held key must not cull the next photos")
    }

    @Test("Arrows keep repeating")
    func arrowsRepeat() {
        let router = KeyboardShortcutRouter()
        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(createTestPhotos(count: 5))
        _ = router.handleKeyEvent(SyntheticKeyEvent(keyCode: 124, specialKey: .rightArrow, isARepeat: true), viewModel: vm)
        _ = router.handleKeyEvent(SyntheticKeyEvent(keyCode: 124, specialKey: .rightArrow, isARepeat: true), viewModel: vm)
        #expect(vm.currentIndex == 2)
    }

    @Test("T toggles the filmstrip, F no longer does (clean view), G opens Go to Photo")
    func filmstripAndGoKeys() {
        let router = KeyboardShortcutRouter()
        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(createTestPhotos(count: 3))
        #expect(vm.isFilmstripVisible)
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 17, characters: "t"), viewModel: vm))
        #expect(!vm.isFilmstripVisible)
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 17, characters: "t"), viewModel: vm))
        #expect(vm.isFilmstripVisible)
        var toggled = 0
        vm.fullScreenToggler = { toggled += 1 }
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 3, characters: "f"), viewModel: vm))
        #expect(toggled == 1 && vm.isFilmstripVisible, "F is the clean view now, it leaves the filmstrip alone")
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 5, characters: "g"), viewModel: vm))
        #expect(vm.isGoToPhotoPresented)
        // S (linked pan) and H / L (navigation) stay for one release.
        let pan = vm.isZoomSynced
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 1, characters: "s"), viewModel: vm))
        #expect(vm.isZoomSynced != pan)
        vm.isGoToPhotoPresented = false
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 37, characters: "l"), viewModel: vm))
        #expect(vm.currentIndex == 1)
        #expect(router.handleKeyEvent(SyntheticKeyEvent(keyCode: 4, characters: "h"), viewModel: vm))
        #expect(vm.currentIndex == 0)
    }

    @Test("Link Pan is ⌥⌘P and the router leaves it to the menu")
    func linkPanChord() {
        let router = KeyboardShortcutRouter()
        let vm = CullingWorkspaceViewModel()
        vm.setPhotos(createTestPhotos(count: 3))
        let handled = router.handleKeyEvent(SyntheticKeyEvent(keyCode: 35, characters: "p", modifierFlags: [.command, .option]), viewModel: vm)
        #expect(!handled)
    }
}
