import SwiftUI
import PhotoSelectorKit

/// Empty state view presented when no photo directory or SD card is opened.
/// Features a native drop target and an Open Folder button.
public struct EmptyDropTargetView: View {
    public let isTargeted: Bool
    public let onOpenFolder: () -> Void

    public init(
        isTargeted: Bool,
        onOpenFolder: @escaping () -> Void
    ) {
        self.isTargeted = isTargeted
        self.onOpenFolder = onOpenFolder
    }

    public var body: some View {
        VStack(spacing: 24) {
            ZStack {
                Circle()
                    .fill(Color.white.opacity(0.04))
                    .frame(width: 140, height: 140)

                Image(systemName: "sdcard.fill")
                    .font(.system(size: 64, weight: .light))
                    .foregroundStyle(isTargeted ? Color.accentColor : Color.lensjetBrand)

                Image(systemName: "arrow.down.circle.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(Color.accentColor)
                    .offset(x: 24, y: 24)
            }
            .scaleEffect(isTargeted ? 1.08 : 1.0)
            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: isTargeted)

            VStack(spacing: 10) {
                Text(isTargeted ? LensjetWords.dropToOpen : LensjetWords.dropFolder)
                    .font(.system(size: 22, weight: .bold, design: .rounded))
                    .foregroundStyle(Color.white)

                Text(LensjetWords.supportedFormats)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Color.lensjetTextSecondary)
            }

            Button(action: onOpenFolder) {
                HStack(spacing: 8) {
                    Image(systemName: "folder.badge.plus")
                        .font(.system(size: 15, weight: .semibold))
                    Text("Open Folder…")
                        .font(.system(size: 14, weight: .bold))
                }
                .foregroundStyle(Color.lensjetOnSelection)
                .padding(.horizontal, 22)
                .padding(.vertical, 11)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.accentColor)
            .controlSize(.large)
            .keyboardShortcut("o", modifiers: .command)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .preferredColorScheme(.dark)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .strokeBorder(
                    isTargeted ? Color.accentColor : Color.white.opacity(0.15),
                    style: StrokeStyle(lineWidth: isTargeted ? 3 : 1.5, dash: isTargeted ? [10] : [6])
                )
                .padding(24)
        )
    }
}
