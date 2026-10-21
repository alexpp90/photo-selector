import SwiftUI

/// Active loading view presented when a photo folder or SD card is being scanned.
/// Provides immediate, clear visual feedback with a spinner, folder name, and cancel button.
public struct FolderLoadingView: View {
    public let folderURL: URL?
    public let onCancel: () -> Void

    public init(folderURL: URL?, onCancel: @escaping () -> Void) {
        self.folderURL = folderURL
        self.onCancel = onCancel
    }

    public var body: some View {
        VStack(spacing: 24) {
            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.12))
                    .frame(width: 120, height: 120)

                Image(systemName: "folder.fill.badge.gearshape")
                    .font(.system(size: 54, weight: .light))
                    .foregroundStyle(Color.accentColor)

                ProgressView()
                    .controlSize(.large)
                    .offset(y: 46)
            }

            VStack(spacing: 10) {
                Text("Opening Folder…")
                    .font(.system(size: 22, weight: .bold, design: .rounded))
                    .foregroundStyle(Color.white)

                if let folderURL {
                    HStack(spacing: 6) {
                        Image(systemName: "sdcard.fill")
                            .font(.system(size: 13))
                            .foregroundStyle(Color.accentColor)
                        Text(folderURL.lastPathComponent)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Color.white)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.white.opacity(0.1), in: Capsule())
                }

                Text("Scanning files, extracting EXIF metadata, and caching previews…")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Color.lensjetTextSecondary)
                    .multilineTextAlignment(.center)
            }

            Button(role: .cancel, action: onCancel) {
                HStack(spacing: 6) {
                    Image(systemName: "xmark.circle")
                    Text("Cancel Scan")
                }
                .font(.system(size: 13, weight: .medium))
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
            }
            .buttonStyle(.bordered)
            .controlSize(.regular)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.lensjetSurface)
        .preferredColorScheme(.dark)
    }
}
