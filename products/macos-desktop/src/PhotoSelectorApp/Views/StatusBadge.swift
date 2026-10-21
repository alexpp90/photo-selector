import SwiftUI
import PhotoSelectorKit

/// A badge that indicates the culling status of a photo (Selected, Copied, Trashed).
public struct StatusBadge: View {
    public let status: PhotoStatus

    public init(status: PhotoStatus) {
        self.status = status
    }

    public var body: some View {
        switch status {
        case .selected:
            HStack(spacing: 4) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 10, weight: .bold))
                Text("Selected")
                    .font(.system(size: 10, weight: .bold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Color.lensjetMove.opacity(0.85))
            .clipShape(Capsule())

        case .copied:
            HStack(spacing: 4) {
                Image(systemName: "doc.on.doc.fill")
                    .font(.system(size: 10, weight: .bold))
                Text("Copied")
                    .font(.system(size: 10, weight: .bold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Color.lensjetCopy.opacity(0.85))
            .clipShape(Capsule())

        case .trashed:
            HStack(spacing: 4) {
                Image(systemName: "trash.fill")
                    .font(.system(size: 10, weight: .bold))
                Text("Trashed")
                    .font(.system(size: 10, weight: .bold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Color.lensjetTrash.opacity(0.85))
            .clipShape(Capsule())

        case .candidate:
            EmptyView()
        }
    }
}
