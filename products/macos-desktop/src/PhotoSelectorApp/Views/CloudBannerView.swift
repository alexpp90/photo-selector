import SwiftUI
import PhotoSelectorKit

/// The one-line, non-modal banner of a refused Delete on a cloud folder (REQ-MAC-CLOUD.01). It sits
/// top-centre over the preview for 4 s; nothing else on screen moves.
struct CloudBannerView: View {
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "icloud.slash")
                .font(.system(size: 13, weight: .semibold))
            Text(text)
                .font(.system(size: 13, weight: .semibold))
                .lineLimit(1)
        }
        .foregroundStyle(Color.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Capsule().fill(Color.black.opacity(0.78)))
        .padding(.top, 12)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(text)
        .uiTestFrame("cloud_banner")
        .transition(.opacity)
        .allowsHitTesting(false)
    }
}
