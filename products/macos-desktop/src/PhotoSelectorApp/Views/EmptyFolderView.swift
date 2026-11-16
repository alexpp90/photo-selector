import SwiftUI
import PhotoSelectorKit

/// A folder was opened and the scan found no photos (REQ-MAC-CULL.01). Shown only after the scan
/// completed, so it never flashes while photos are still streaming in; a window with no folder
/// open keeps the drop view.
struct EmptyFolderView: View {
    let folderName: String
    let onOpenFolder: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "photo.on.rectangle.angled")
                .font(.system(size: 56, weight: .light))
                .foregroundStyle(Color.lensjetBrand)
            VStack(spacing: 6) {
                Text(LensjetWords.noPhotos)
                    .font(.system(size: 22, weight: .bold, design: .rounded))
                    .foregroundStyle(Color.white)
                Text(folderName)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Color.lensjetTextSecondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(LensjetWords.dropFolder)
                    .font(.system(size: 12))
                    .foregroundStyle(Color.lensjetTextSecondary)
            }
            Button(action: onOpenFolder) {
                Text(LensjetWords.openAnotherFolder)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Color.lensjetOnSelection)
                    .padding(.horizontal, 22)
                    .padding(.vertical, 11)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.accentColor)
            .controlSize(.large)
            .accessibilityLabel("\(LensjetWords.noPhotos) in \(folderName). \(LensjetWords.openAnotherFolder)")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .preferredColorScheme(.dark)
        .uiTestFrame("empty_folder")
    }
}
