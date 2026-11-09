import Testing
import CoreGraphics
import Foundation
@testable import PhotoSelectorKit

/// Class check "layout solvers hold at every window size we ship": the pure geometry the SwiftUI
/// views consume (`FocusTripletLayout`, `MetadataPanelLayout`) and the label forms of `ChromeDensity`,
/// at the minimum window (960x640) and the sizes the lab reports used (1024x768, 1280x800, 1440x900).
/// Guards the Kit half of F-LJ-021 (labels), F-LJ-022 and F-LJ-023 (overlays vs rows): the placement of the
/// toast and the zoom pill and the width fit of the labels are decided in the App target (see the hand-back:
/// no Kit seam), so those stay with `ui_smoke_test.sh` / `UISelfTestRunner.checkOverlayPlacement`.
/// REQ-MAC-LAYOUT.01, .02, .05, REQ-MAC-UI.01, .02.
@Suite("Layout solvers at every shipped window size")
struct WindowSizeLayoutMatrixTests {

    struct Window: Sendable, CustomTestStringConvertible {
        let width: CGFloat
        let height: CGFloat
        var testDescription: String { "\(Int(width))x\(Int(height))" }
        /// The canvas for the photos: the window minus an assumed chrome stack (title bar + toolbar +
        /// action dock + filmstrip). 160 pt is a deliberately generous estimate; the solvers are also
        /// run on the full window, so a wrong estimate cannot hide a defect.
        var canvasWithChrome: CGSize { CGSize(width: width, height: height - 160) }
        var full: CGSize { CGSize(width: width, height: height) }
    }

    static let windows = [
        Window(width: 960, height: 640),    // minimum window
        Window(width: 1024, height: 768),
        Window(width: 1280, height: 800),
        Window(width: 1440, height: 900),
    ]
    static let aspects: [CGFloat?] = [nil, 3.0 / 2.0, 2.0 / 3.0, 1.0, 16.0 / 9.0, 4.0 / 5.0]

    private func overlap(_ a: CGRect, _ b: CGRect) -> Bool {
        let i = a.intersection(b)
        return !i.isNull && i.width > 0.001 && i.height > 0.001
    }

    // MARK: 3-Up

    @Test("REQ-MAC-LAYOUT.02 F-LJ-010: 3-Up is one-over-two with readable cells at every window size", arguments: windows)
    func tripletAtEveryWindow(window: Window) {
        for canvas in [window.full, window.canvasWithChrome] {
            let layout = FocusTripletLayout(canvas: canvas)
            let bounds = CGRect(origin: .zero, size: canvas)
            #expect(layout.current.width == canvas.width, "current spans the width at \(canvas)")
            #expect(layout.current.maxY <= layout.previous.minY && layout.current.maxY <= layout.next.minY)
            #expect(layout.previous.maxX <= layout.next.minX && layout.previous.minX == 0)
            for rect in [layout.current, layout.previous, layout.next] {
                #expect(bounds.contains(rect), "\(rect) inside \(canvas)")
                // Room for the slot header (title + score) and a frame worth judging; a cell narrower
                // or lower than this made the CURRENT header collide with the zoom pill (F-LJ-023).
                #expect(rect.height >= 150, "cell height \(rect.height) at \(canvas)")
                #expect(rect.width >= 400, "cell width \(rect.width) at \(canvas)")
            }
            #expect(!overlap(layout.current, layout.previous) && !overlap(layout.current, layout.next) && !overlap(layout.previous, layout.next))
        }
    }

    // MARK: 1-Up metadata

    @Test("REQ-MAC-LAYOUT.05 F-LJ-012/-022: the metadata panel never covers the photo and costs it at most ~30 % at any size or aspect",
          arguments: windows)
    func metadataPanelAtEveryWindow(window: Window) {
        for canvas in [window.full, window.canvasWithChrome] {
            for aspect in Self.aspects {
                let layout = MetadataPanelLayout(canvas: canvas, aspectRatio: aspect, isPanelVisible: true)
                let bounds = CGRect(origin: .zero, size: canvas)
                let label = "\(canvas) aspect \(String(describing: aspect))"
                #expect(layout.placement != .hidden, label)
                #expect(!overlap(layout.image, layout.panel), "panel covers the photo at \(label)")
                #expect(bounds.contains(layout.image) && bounds.contains(layout.panel), label)
                // They tile the canvas exactly: no gap that a toast could be placed over unnoticed.
                #expect(layout.image.union(layout.panel) == bounds, label)
                #expect(layout.image.width * layout.image.height + layout.panel.width * layout.panel.height
                            == canvas.width * canvas.height, label)
                // The EXIF rows stay legible: the panel keeps its two text rows / its column.
                switch layout.placement {
                case .bottomStrip: #expect(layout.panel.height >= 48, label)
                case .sideColumn: #expect(layout.panel.width >= 200, label)
                case .hidden: break
                }
                // The photo keeps nearly all of the area it would have without a panel.
                let ratio = aspect ?? MetadataPanelLayout.defaultAspectRatio
                let withPanel = MetadataPanelLayout.fittedArea(aspectRatio: ratio, in: layout.image.size)
                let without = MetadataPanelLayout.fittedArea(aspectRatio: ratio, in: canvas)
                #expect(withPanel >= without * 0.7, "photo shrinks to \(withPanel / without) at \(label)")
            }
        }
    }

    @Test("REQ-MAC-LAYOUT.05: the panel can be hidden at every size and then gives the photo the whole canvas", arguments: windows)
    func hiddenPanelAtEveryWindow(window: Window) {
        let layout = MetadataPanelLayout(canvas: window.full, aspectRatio: 1.5, isPanelVisible: false)
        #expect(layout.placement == .hidden)
        #expect(layout.image == CGRect(origin: .zero, size: window.full))
        #expect(layout.panel == .zero)
    }

    // MARK: Chrome labels

    /// Every label of a density (toolbar + dock), in the order the forms step down.
    private func labels(_ density: ChromeDensity) -> [String] {
        var out: [String] = [density.positionText(index: 120, total: 120)]
        out += [1, 2, 3].map { density.modeLabel(shortcut: $0) ?? "" }
        out += ChromeDensity.DockAction.allCases.map { density.dockTitle($0) ?? "" }
        if let scanning = density.scanningText(photoCount: 12_345) { out.append(scanning) }
        return out
    }

    @Test("REQ-MAC-UI.01 F-LJ-021: each density is no wider than the one before, so ViewThatFits always has a narrower form to fall back to")
    func densitiesStepDown() {
        let full = labels(.full), compact = labels(.compact), minimal = labels(.minimal)
        let widths = [full, compact, minimal].map { (group: [String]) -> Int in group.map(\.count).reduce(0, +) }
        #expect(widths[0] > widths[1], "compact must be shorter than full: \(widths)")
        #expect(widths[1] > widths[2], "minimal must be shorter than compact: \(widths)")
        // Icon-only form: no mode label and no dock title at all.
        #expect([1, 2, 3].allSatisfy { ChromeDensity.minimal.modeLabel(shortcut: $0) == nil })
        #expect(ChromeDensity.DockAction.allCases.allSatisfy { ChromeDensity.minimal.dockTitle($0) == nil })
        // The minimal form still names everything for VoiceOver and the tooltip.
        #expect([1, 2, 3].allSatisfy { ChromeDensity.modeAccessibilityName(shortcut: $0).contains("view") })
    }

    @Test("REQ-MAC-UI.01 F-LJ-021: no visible label is long enough to need two lines at 960 pt, in the compact form")
    func compactLabelsAreShort() {
        // The mid-word wraps of F-LJ-021 ("Foc/us/3-/Up", "1 Selecte/d") were labels a few characters
        // too wide for their cell. The compact form is what the minimum window shows; keep it brief.
        for label in labels(.compact) where !label.hasPrefix("Scanning") { #expect(label.count <= 12, "\(label)") }
    }
}
