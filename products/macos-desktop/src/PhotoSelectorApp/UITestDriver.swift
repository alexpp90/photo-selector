#if PST_TEST_HOOKS
import AppKit
import Foundation

@MainActor
enum UITestDriver {
    struct Key {
        let code: UInt16
        let chars: String
        let baseModifiers: NSEvent.ModifierFlags

        init(code: UInt16, chars: String, baseModifiers: NSEvent.ModifierFlags = []) {
            self.code = code
            self.chars = chars
            self.baseModifiers = baseModifiers
        }

        static let one = Key(code: 18, chars: "1")
        static let two = Key(code: 19, chars: "2")
        static let three = Key(code: 20, chars: "3")
        static let c = Key(code: 8, chars: "c")
        static let d = Key(code: 2, chars: "d")
        static let f = Key(code: 3, chars: "f")
        static let h = Key(code: 4, chars: "h")
        static let i = Key(code: 34, chars: "i")
        static let j = Key(code: 38, chars: "j")
        static let k = Key(code: 40, chars: "k")
        static let l = Key(code: 37, chars: "l")
        static let m = Key(code: 46, chars: "m")
        static let s = Key(code: 1, chars: "s")
        static let z = Key(code: 6, chars: "z")
        static let tab = Key(code: 48, chars: "\t")
        static let space = Key(code: 49, chars: " ")
        static let escape = Key(code: 53, chars: "\u{1B}")
        static let delete = Key(code: 51, chars: "\u{7F}")
        static let slash = Key(code: 44, chars: "/")
        static let comma = Key(code: 43, chars: ",")
        static let leftArrow = Key(code: 123, chars: "\u{F702}", baseModifiers: [.numericPad, .function])
        static let rightArrow = Key(code: 124, chars: "\u{F703}", baseModifiers: [.numericPad, .function])
        static let downArrow = Key(code: 125, chars: "\u{F701}", baseModifiers: [.numericPad, .function])
        static let upArrow = Key(code: 126, chars: "\u{F700}", baseModifiers: [.numericPad, .function])

        static func from(name: String) -> Key? {
            switch name.lowercased() {
            case "1", "one": return .one
            case "2", "two": return .two
            case "3", "three": return .three
            case "c": return .c
            case "d": return .d
            case "f": return .f
            case "h": return .h
            case "i": return .i
            case "j": return .j
            case "k": return .k
            case "l": return .l
            case "m": return .m
            case "s": return .s
            case "z": return .z
            case "tab": return .tab
            case "space": return .space
            case "escape", "esc": return .escape
            case "delete", "backspace": return .delete
            case "/", "slash", "?": return .slash
            case ",", "comma": return .comma
            case "left", "leftarrow": return .leftArrow
            case "right", "rightarrow": return .rightArrow
            case "down", "downarrow": return .downArrow
            case "up", "uparrow": return .upArrow
            default: return nil
            }
        }
    }

    static func modifiers(from names: [String]?) -> NSEvent.ModifierFlags {
        guard let names else { return [] }
        var flags: NSEvent.ModifierFlags = []
        for raw in names {
            switch raw.lowercased() {
            case "command", "cmd": flags.insert(.command)
            case "shift": flags.insert(.shift)
            case "option", "alt": flags.insert(.option)
            case "control", "ctrl": flags.insert(.control)
            default: break
            }
        }
        return flags
    }

    static var workspaceWindow: NSWindow? {
        NSApp.windows.first { $0.isVisible && $0.canBecomeKey && !$0.isSheet && $0.contentView != nil && $0.frame.width >= 400 }
            ?? NSApp.windows.first { $0.canBecomeKey && !$0.isSheet && $0.contentView != nil && $0.frame.width >= 400 }
            ?? NSApp.windows.first { $0.contentView != nil && $0.frame.width >= 400 }
            ?? NSApp.keyWindow
            ?? NSApp.mainWindow
            ?? NSApp.windows.first
    }

    static func postKey(_ key: Key, modifiers: NSEvent.ModifierFlags = []) {
        guard let window = workspaceWindow else { return }
        let flags = key.baseModifiers.union(modifiers)
        for type in [NSEvent.EventType.keyDown, .keyUp] {
            if let event = NSEvent.keyEvent(
                with: type,
                location: .zero,
                modifierFlags: flags,
                timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: window.windowNumber,
                context: nil,
                characters: key.chars,
                charactersIgnoringModifiers: key.chars,
                isARepeat: false,
                keyCode: key.code
            ) {
                NSApp.postEvent(event, atStart: false)
            }
        }
    }

    static func click(in window: NSWindow, at windowTopDownPoint: CGPoint) {
        guard let contentView = window.contentView else { return }
        // SwiftUI's `.global` coordinate space has y=0 at the top of the content view;
        // AppKit's window space has y=0 at the bottom.
        let point = CGPoint(x: windowTopDownPoint.x, y: contentView.frame.height - windowTopDownPoint.y)
        let hitView = contentView.hitTest(point)
        if NSApp.isActive {
            for type in [NSEvent.EventType.leftMouseDown, .leftMouseUp] {
                if let event = NSEvent.mouseEvent(
                    with: type,
                    location: point,
                    modifierFlags: [],
                    timestamp: ProcessInfo.processInfo.systemUptime,
                    windowNumber: window.windowNumber,
                    context: nil,
                    eventNumber: 0,
                    clickCount: 1,
                    pressure: type == .leftMouseDown ? 1 : 0
                ) {
                    NSApp.postEvent(event, atStart: false)
                }
            }
        } else if let button = hitView?.nearestButton() {
            button.performClick(nil)
        } else if let hitView {
            for type in [NSEvent.EventType.leftMouseDown, .leftMouseUp] {
                if let event = NSEvent.mouseEvent(
                    with: type,
                    location: point,
                    modifierFlags: [],
                    timestamp: ProcessInfo.processInfo.systemUptime,
                    windowNumber: window.windowNumber,
                    context: nil,
                    eventNumber: 0,
                    clickCount: 1,
                    pressure: type == .leftMouseDown ? 1 : 0
                ) {
                    if type == .leftMouseDown {
                        hitView.mouseDown(with: event)
                    } else {
                        hitView.mouseUp(with: event)
                    }
                }
            }
        }
    }

    static func settle(_ seconds: TimeInterval = 0.25) async {
        let nanos = UInt64(max(0, seconds) * 1_000_000_000)
        try? await Task.sleep(nanoseconds: nanos)
    }

    @discardableResult
    static func waitUntil(
        timeout: TimeInterval,
        condition: @MainActor () -> Bool
    ) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            await settle(0.05)
        }
        return condition()
    }

    static func captureWindowPNG(_ window: NSWindow, to url: URL) throws {
        guard let contentView = window.contentView else {
            throw NSError(domain: "UITestDriver", code: 1, userInfo: [NSLocalizedDescriptionKey: "window has no contentView"])
        }
        contentView.layoutSubtreeIfNeeded()
        contentView.displayIfNeeded()
        let bounds = contentView.bounds
        guard let rep = contentView.bitmapImageRepForCachingDisplay(in: bounds) else {
            throw NSError(domain: "UITestDriver", code: 2, userInfo: [NSLocalizedDescriptionKey: "bitmapImageRepForCachingDisplay returned nil"])
        }
        contentView.cacheDisplay(in: bounds, to: rep)
        if let sheet = window.attachedSheet, let sheetView = sheet.contentView {
            sheetView.layoutSubtreeIfNeeded()
            sheetView.displayIfNeeded()
            let sBounds = sheetView.bounds
            if sBounds.width > 0, sBounds.height > 0,
               let sheetRep = sheetView.bitmapImageRepForCachingDisplay(in: sBounds) {
                sheetView.cacheDisplay(in: sBounds, to: sheetRep)
                NSGraphicsContext.saveGraphicsState()
                if let ctx = NSGraphicsContext(bitmapImageRep: rep) {
                    NSGraphicsContext.current = ctx
                    let originX = (bounds.width - sBounds.width) / 2.0
                    let originY = (bounds.height - sBounds.height) / 2.0
                    let destRect = NSRect(x: originX, y: originY, width: sBounds.width, height: sBounds.height)
                    NSColor.black.withAlphaComponent(0.35).setFill()
                    bounds.fill()
                    sheetRep.draw(in: destRect)
                }
                NSGraphicsContext.restoreGraphicsState()
            }
        }
        guard let png = rep.representation(using: .png, properties: [:]) else {
            throw NSError(domain: "UITestDriver", code: 3, userInfo: [NSLocalizedDescriptionKey: "PNG encoding failed"])
        }
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try png.write(to: url, options: .atomic)
    }
}

private extension NSView {
    func nearestButton() -> NSButton? {
        var current: NSView? = self
        while let view = current {
            if let button = view as? NSButton {
                return button
            }
            current = view.superview
        }
        return nil
    }
}
#endif
