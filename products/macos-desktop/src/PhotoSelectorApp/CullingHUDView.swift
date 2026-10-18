import SwiftUI
import PhotoSelectorKit

/// Transient confirmation toast overlay providing immediate visual feedback for actions.
public struct CullingHUDView: View {
    public let toast: ToastMessage

    public init(toast: ToastMessage) {
        self.toast = toast
    }

    public var body: some View {
        HStack(spacing: 12) {
            Image(systemName: toast.icon)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(iconColor)

            VStack(alignment: .leading, spacing: 2) {
                Text(toast.title)
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                if let subtitle = toast.subtitle {
                    Text(subtitle)
                        .font(.system(size: 11, weight: .regular))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(
            Capsule()
                .stroke(Color.white.opacity(0.12), lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.35), radius: 10, x: 0, y: 5)
    }

    private var iconColor: Color {
        if let action = toast.actionType {
            switch action {
            case .move: return Color.green
            case .copy: return Color.blue
            case .trash: return Color.red
            }
        }
        return Color.accentColor
    }
}

/// Transient feedback drawn *inside* the image rectangle of the active slot (1-Up viewport,
/// 2-Up active slot, 3-Up CURRENT): the zoom-exit pill top-centre, the action toast
/// bottom-centre. Because the host rectangle excludes every header, EXIF strip and metadata
/// panel (they reserve their own rows, REQ-MAC-LAYOUT.05) and lies inside the preview canvas
/// (above the action dock), neither element can cover metadata, the toolbar or the dock.
/// Overlapping the photograph itself briefly is allowed: transient feedback is not metadata.
struct SlotOverlayLayer: View {
    let toast: ToastMessage?
    let zoomState: ZoomState
    let onExitZoom: (() -> Void)?

    private var showsPill: Bool { zoomState.isZoomed && onExitZoom != nil }

    var body: some View {
        VStack(spacing: 0) {
            if showsPill, let onExitZoom {
                ZoomExitPill(zoomState: zoomState, onExit: onExitZoom)
                    .uiTestFrame("zoom_exit_pill")
                    .transition(.opacity)
            }
            Spacer(minLength: 0)
            if let toast {
                CullingHUDView(toast: toast)
                    .uiTestFrame("action_toast")
                    .allowsHitTesting(false)
                    .transition(.asymmetric(
                        insertion: .scale(scale: 0.9).combined(with: .opacity),
                        removal: .opacity
                    ))
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

extension View {
    /// Attaches `SlotOverlayLayer` over this image rectangle. Always attached (the layer is
    /// empty and hit-test transparent when idle) so the viewport keeps its view identity and
    /// its zoom/pan state when a toast appears or disappears.
    func slotOverlay(toast: ToastMessage?, zoomState: ZoomState, onExitZoom: (() -> Void)?) -> some View {
        overlay { SlotOverlayLayer(toast: toast, zoomState: zoomState, onExitZoom: onExitZoom) }
    }
}
