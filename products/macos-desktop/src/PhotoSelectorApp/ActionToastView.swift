import SwiftUI
import PhotoSelectorKit

/// Transient confirmation toast overlay providing immediate visual feedback for actions.
private struct DismissToastKey: EnvironmentKey {
    static let defaultValue: @MainActor (UUID) -> Void = { _ in }
}

private struct DismissAllToastsKey: EnvironmentKey {
    static let defaultValue: @MainActor () -> Void = {}
}

private struct CopiedFolderKey: EnvironmentKey {
    static let defaultValue: String? = nil
}

private struct FailureToastsKey: EnvironmentKey {
    static let defaultValue: [ToastMessage] = []
}

extension EnvironmentValues {
    /// The folder copies go to, for the info panel's "Copied to …".
    var copiedFolder: String? {
        get { self[CopiedFolderKey.self] }
        set { self[CopiedFolderKey.self] = newValue }
    }
    /// Drops one failure toast (its close button).
    var dismissToast: @MainActor (UUID) -> Void {
        get { self[DismissToastKey.self] }
        set { self[DismissToastKey.self] = newValue }
    }
    /// "Dismiss all" of the collapsed failure stack.
    var dismissAllToasts: @MainActor () -> Void {
        get { self[DismissAllToastsKey.self] }
        set { self[DismissAllToastsKey.self] = newValue }
    }
    /// The failures waiting for the user, newest first.
    var failureToasts: [ToastMessage] {
        get { self[FailureToastsKey.self] }
        set { self[FailureToastsKey.self] = newValue }
    }
}

public struct ActionToastView: View {
    public let toast: ToastMessage
    @Environment(\.dismissToast) private var dismissToast

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

            if toast.isFailure {
                Button { dismissToast(toast.id) } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .bold))
                        .frame(width: 28, height: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .focusable(false)
                .accessibilityLabel("Dismiss")
                .help("Dismiss (Esc)")
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
            case .move: return Color.lensjetMove
            case .copy: return Color.lensjetCopy
            case .trash: return Color.lensjetTrash
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
    @Environment(\.failureToasts) private var failures
    @Environment(\.dismissAllToasts) private var dismissAll

    /// How many failures are drawn; the rest collapse into "+N more" (UX L4).
    static let visibleFailures = 3

    private var showsPill: Bool { zoomState.isZoomed && onExitZoom != nil }

    var body: some View {
        VStack(spacing: 0) {
            if showsPill, let onExitZoom {
                ZoomExitPill(zoomState: zoomState, onExit: onExitZoom)
                    .uiTestFrame("zoom_exit_pill")
                    .transition(.opacity)
            }
            Spacer(minLength: 0)
            if !failures.isEmpty {
                failureStack
            }
            if let toast, !toast.isFailure {
                ActionToastView(toast: toast)
                    .uiTestFrame("action_toast")
                    .allowsHitTesting(toast.isFailure)
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

extension SlotOverlayLayer {
    /// Newest on top, at most three, the older ones as "+N more" with "Dismiss all".
    @ViewBuilder
    var failureStack: some View {
        VStack(spacing: 6) {
            ForEach(failures.prefix(Self.visibleFailures)) { failure in
                ActionToastView(toast: failure)
            }
            if failures.count > Self.visibleFailures {
                HStack(spacing: 10) {
                    Text("+\(failures.count - Self.visibleFailures) more")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Color.lensjetTextSecondary)
                    Button("Dismiss all") { dismissAll() }
                        .buttonStyle(.plain)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Color.accentColor)
                        .focusable(false)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(.ultraThinMaterial, in: Capsule())
            }
        }
        .uiTestFrame("failure_toasts")
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
