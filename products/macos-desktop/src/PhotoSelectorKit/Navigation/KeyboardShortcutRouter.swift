import SwiftUI
import AppKit

/// Protocol abstracting sheet presentation checks so testing does not require live AppKit NSWindow instances.
@MainActor
public protocol SheetContextInspectable: AnyObject {
    var isSheet: Bool { get }
    var hasSheetParent: Bool { get }
    var hasAttachedSheet: Bool { get }
}

@MainActor
extension NSWindow: SheetContextInspectable {
    public var hasSheetParent: Bool { sheetParent != nil }
    public var hasAttachedSheet: Bool { attachedSheet != nil }
}

/// Protocol abstracting key event data so routing is unit-testable without WindowServer event creation.
@MainActor
public protocol KeyEventRepresentable {
    var sheetContextInspectableWindow: (any SheetContextInspectable)? { get }
    var isTextInputActive: Bool { get }
    var modifierFlags: NSEvent.ModifierFlags { get }
    var charactersIgnoringModifiers: String? { get }
    var keyCode: UInt16 { get }
    var specialKey: NSEvent.SpecialKey? { get }
    /// True for an auto-repeat of a held key (destructive keys act once).
    var isARepeat: Bool { get }
}

@MainActor
extension NSEvent: KeyEventRepresentable {
    public var sheetContextInspectableWindow: (any SheetContextInspectable)? {
        window
    }

    public var isTextInputActive: Bool {
        if let firstResponder = window?.firstResponder {
            return firstResponder is NSTextView || firstResponder is NSTextField
        }
        return false
    }
}

/// Lightweight test double for simulating key events in headless environments.
public struct SyntheticKeyEvent: KeyEventRepresentable {
    public var sheetContextInspectableWindow: (any SheetContextInspectable)?
    public var isTextInputActive: Bool
    public var modifierFlags: NSEvent.ModifierFlags
    public var charactersIgnoringModifiers: String?
    public var keyCode: UInt16
    public var specialKey: NSEvent.SpecialKey?
    public var isARepeat: Bool

    public init(
        keyCode: UInt16,
        characters: String = "",
        modifierFlags: NSEvent.ModifierFlags = [],
        specialKey: NSEvent.SpecialKey? = nil,
        isTextInputActive: Bool = false,
        isARepeat: Bool = false,
        window: (any SheetContextInspectable)? = nil
    ) {
        self.keyCode = keyCode
        self.charactersIgnoringModifiers = characters
        self.modifierFlags = modifierFlags
        self.specialKey = specialKey
        self.isTextInputActive = isTextInputActive
        self.isARepeat = isARepeat
        self.sheetContextInspectableWindow = window
    }
}

/// Animation that honours System Settings > Accessibility > Display > Reduce motion: with it on the
/// change is applied at once (REQ-MAC-A11Y.01). Lives in this adapter file because it reads AppKit.
public enum MotionPolicy {
    public static var reduceMotion: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }

    /// `withAnimation(animation, body)`, or `body()` alone under Reduce Motion.
    @MainActor
    public static func animate(_ animation: Animation, _ body: () -> Void) {
        if reduceMotion { body() } else { withAnimation(animation, body) }
    }
}

/// Dedicated window-level keyboard event interceptor.
/// Uses `NSEvent.addLocalMonitorForEvents(matching: .keyDown)` to capture navigation
/// and culling hotkeys (←, →, ↑, ↓, M, C, Delete, Space, 1, 2, 3, Tab, ⌘Z, J, K) before SwiftUI focus
/// or button responders can swallow them.
@MainActor
public final class KeyboardShortcutRouter: ObservableObject {
    private var monitor: Any?
    private weak var viewModel: CullingWorkspaceViewModel?

    public init() {}

    /// Registers the local key-down event monitor.
    public func attach(viewModel: CullingWorkspaceViewModel) {
        self.viewModel = viewModel
        guard monitor == nil else { return }

        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self, let vm = self.viewModel else { return event }
            if self.handleKeyEvent(event, viewModel: vm) {
                return nil // Event handled and consumed; prevent button focus routing
            }
            return event
        }
    }

    /// Unregisters the local key-down event monitor.
    public func detach() {
        if let monitor {
            NSEvent.removeMonitor(monitor)
            self.monitor = nil
        }
        self.viewModel = nil
    }

    /// Whether a live key-down event monitor is registered (asserted by the UI self-test).
    public var isAttached: Bool { monitor != nil }

    /// `true` when the window is a sheet, or a window currently presenting a sheet.
    public static func isSheetContext(_ window: any SheetContextInspectable) -> Bool {
        window.isSheet || window.hasSheetParent || window.hasAttachedSheet
    }

    /// Evaluates whether a key event corresponds to a culling or navigation shortcut.
    /// Returns `true` if handled, or `false` to let the event proceed to standard responders.
    public func handleKeyEvent(_ event: any KeyEventRepresentable, viewModel: CullingWorkspaceViewModel) -> Bool {
        // Do not intercept keystrokes if the user is typing into an editable text field
        if event.isTextInputActive {
            return false
        }

        // Sheets (Guide, Duplicate Finder, Statistics) own the keyboard while presented:
        // arrows must not move the workspace hidden behind them.
        if let window = event.sheetContextInspectableWindow, Self.isSheetContext(window) {
            return false
        }

        let isCmd = event.modifierFlags.contains(.command)
        let characters = event.charactersIgnoringModifiers?.lowercased() ?? ""

        // 1. Command Shortcuts (⌘Z, ⌘O, ⌘D, ⌘L, ⌘?, ⌘[, ⌘])
        if isCmd {
            switch characters {
            case "z":
                viewModel.undoLastAction()
                return true
            case "o":
                viewModel.presentFolderPicker()
                return true
            case "d":
                viewModel.presentDuplicateFinder()
                return true
            case "l":
                viewModel.presentLibraryStatistics()
                return true
            case "?", "/":
                viewModel.presentShortcutsHelp()
                return true
            case "[":
                viewModel.navigateToPrevious()
                return true
            case "]":
                viewModel.navigateToNext()
                return true
            default:
                return false
            }
        }

        // 2. Navigation Keys (Arrow keys, Space, Delete, Tab, 1, 2, 3, J, K, H, L)
        let isLeft: Bool = {
            if event.keyCode == 123 || event.keyCode == 126 { return true }
            if event.specialKey == .leftArrow || event.specialKey == .upArrow { return true }
            if let chars = event.charactersIgnoringModifiers,
               let scalar = chars.unicodeScalars.first?.value,
               scalar == 0xF702 || scalar == 0xF700 { return true }
            return false
        }()

        let isRight: Bool = {
            if event.keyCode == 124 || event.keyCode == 125 { return true }
            if event.specialKey == .rightArrow || event.specialKey == .downArrow { return true }
            if let chars = event.charactersIgnoringModifiers,
               let scalar = chars.unicodeScalars.first?.value,
               scalar == 0xF703 || scalar == 0xF701 { return true }
            return false
        }()

        let isDelete = event.keyCode == 51 || event.keyCode == 117 || event.specialKey == .delete || event.specialKey == .deleteForward

        if isLeft {
            viewModel.navigateToPrevious()
            return true
        }

        if isRight {
            viewModel.navigateToNext()
            return true
        }

        // Destructive keys are one-shot: a held key acts once, its auto-repeats are consumed.
        if isDelete {
            if !event.isARepeat { viewModel.cullCurrentPhoto(action: .trash) }
            return true
        }

        switch event.keyCode {
        case 49: // Spacebar -> Toggle 100% Zoom
            viewModel.toggleZoom()
            return true
        case 48: // Tab -> Switch Photo; only 2-Up has a second photo, elsewhere the key is not consumed
            return viewModel.advanceComparisonSlotFocus()
        case 53: // Escape -> dismiss the sharpness banner, else leave any zoom back to Fit
            if viewModel.dismissStickyToast() { return true }
            return viewModel.resetZoomToFit()
        case 18: // Number 1 -> 1-Up Single
            viewModel.setComparisonMode(.single)
            return true
        case 19: // Number 2 -> 2-Up Side-by-Side
            viewModel.setComparisonMode(.sideBySide)
            return true
        case 20: // Number 3 -> 3-Up Focus Mode
            viewModel.setComparisonMode(.triplet)
            return true
        default:
            break
        }

        // 3. Single-Character Action Shortcuts (M, C, F, I, S, G, J, K, H, L, ?, /)
        switch characters {
        case "m":
            if !event.isARepeat { viewModel.cullCurrentPhoto(action: .move) }
            return true
        case "c":
            if !event.isARepeat { viewModel.cullCurrentPhoto(action: .copy) }
            return true
        case "j", "l": // Vim Down / Right -> Next
            viewModel.navigateToNext()
            return true
        case "k", "h": // Vim Up / Left -> Previous
            viewModel.navigateToPrevious()
            return true
        case "t", "f": // T filmstrip; F stays as an alias for one release (becomes full screen next)
            MotionPolicy.animate(.spring(response: 0.28, dampingFraction: 0.82)) {
                viewModel.isFilmstripVisible.toggle()
            }
            return true
        case "i":
            MotionPolicy.animate(.easeInOut(duration: 0.18)) {
                viewModel.isInfoHUDVisible.toggle()
            }
            return true
        case "s":
            viewModel.toggleZoomSync()
            return true
        case "g": // Go to Photo (number or file name)
            viewModel.presentGoToPhoto()
            return true
        case "?", "/":
            viewModel.presentShortcutsHelp()
            return true
        default:
            return false
        }
    }
}
