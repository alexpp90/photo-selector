import AppKit
import Foundation

/// Clean view ([USER 2026-11-12] decision #24): `F` is full screen — the same state as `⌃⌘F` — and
/// in full screen the toolbar, the dock and the filmstrip are hidden. Every key keeps working
/// (`T` filmstrip, `I` info, `1 2 3`, `⌘Z`, `M`, `C`, Delete, Space, `?`), so a hidden toggle is never
/// out of reach; each decision shows a short pill instead of the dock's tally.
extension CullingWorkspaceViewModel {
    /// Stored once the "Esc exits" hint has been shown.
    static let cleanHintKey = "lensjetCleanViewHintShown"

    /// `F` and View ▸ Clean View: toggles the window's full screen. `isFullScreen` follows the
    /// window through `setFullScreen(_:)`, so `⌃⌘F` and the green button do the same.
    public func toggleCleanView() {
        fullScreenToggler()
    }

    /// The window entered or left full screen (the app forwards the window notifications).
    public func setFullScreen(_ on: Bool) {
        guard isFullScreen != on else { return }
        isFullScreen = on
        cleanPill = nil
        if on, !userDefaults.bool(forKey: Self.cleanHintKey) {
            userDefaults.set(true, forKey: Self.cleanHintKey)
            showToast(icon: "arrow.down.right.and.arrow.up.left", title: "Esc exits", subtitle: nil)
        }
    }

    /// Esc, after the inner layers (zoom, banner, end card) have had it: leaves full screen.
    /// Returns whether it acted.
    @discardableResult
    public func exitCleanView() -> Bool {
        guard isFullScreen else { return false }
        fullScreenToggler()
        return true
    }

    /// The 1.2 s pill that replaces the dock's feedback in full screen: "Moved · 41".
    func showCleanPill(verb: String, symbol: String) {
        guard isFullScreen else { return }
        cleanPillTask?.cancel()
        cleanPill = CleanPill(symbol: symbol, text: "\(verb) \u{00B7} \(remainingCount)")
        cleanPillTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            if !Task.isCancelled { self?.cleanPill = nil }
        }
    }
}

/// What the clean-view pill shows: a glyph and "verb · remaining".
public struct CleanPill: Equatable, Sendable {
    public let symbol: String
    public let text: String
}
