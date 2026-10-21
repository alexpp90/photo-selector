import SwiftUI
import PhotoSelectorKit

/// A badge that indicates the culling status of a photo (Selected, Copied, Trashed).
///
/// The fill is opaque and the text is black or white by WCAG contrast on that fill
/// (`TextContrast`), so it stays at least 4.5:1 on every semantic colour (UX-MAC-1).
public struct StatusBadge: View {
    public let status: PhotoStatus

    public init(status: PhotoStatus) {
        self.status = status
    }

    public var body: some View {
        switch status {
        case .selected:
            badge(symbol: "checkmark.circle.fill", title: "Selected", fill: LensjetPalette.moveAccent)
        case .copied:
            badge(symbol: "doc.on.doc.fill", title: "Copied", fill: LensjetPalette.copyAccent)
        case .trashed:
            badge(symbol: "trash.fill", title: "Trashed", fill: LensjetPalette.trashAccent)
        case .candidate:
            EmptyView()
        }
    }

    private func badge(symbol: String, title: String, fill: PaletteRGB) -> some View {
        HStack(spacing: 4) {
            Image(systemName: symbol)
                .font(.system(size: 10, weight: .bold))
            Text(title)
                .font(.system(size: 10, weight: .bold))
        }
        .foregroundStyle(Color(TextContrast.legibleText(on: fill)))
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .background(Color(fill))
        .clipShape(Capsule())
    }
}
