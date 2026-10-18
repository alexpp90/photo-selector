import SwiftUI
import PhotoSelectorKit

/// Maximized 1-Up preview canvas. The photograph is aspect-fitted into the rectangle left
/// over after the metadata panel has reserved its own space (`MetadataPanelLayout`), so the
/// metadata **never covers the image** (REQ-MAC-LAYOUT.05). Progressive display
/// (embedded-thumbnail placeholder → 2048 preview) and honest 1:1 zoom come from
/// `ZoomableImageViewport`.
public struct SinglePhotoView: View {
    public let photo: PhotoItem
    public let isHUDVisible: Bool
    public let zoomState: ZoomState
    public let onToggleZoom: () -> Void
    public var toast: ToastMessage?
    public var onExitZoom: (() -> Void)?
    public var onPinchEnded: ((Double) -> Void)?
    public var onUpdateExif: ((UUID, ExifData) -> Void)?
    public var onUpdateScores: ((UUID, QualityScores) -> Void)?

    @StateObject private var state = ProgressivePhotoState()
    @AppStorage(AppSettingsKeys.aestheticScoringEnabled)
    private var aestheticsEnabled: Bool = AppSettingsDefaults.aestheticScoringEnabled

    public init(
        photo: PhotoItem,
        isHUDVisible: Bool,
        zoomState: ZoomState,
        onToggleZoom: @escaping () -> Void,
        toast: ToastMessage? = nil,
        onExitZoom: (() -> Void)? = nil,
        onPinchEnded: ((Double) -> Void)? = nil,
        onUpdateExif: ((UUID, ExifData) -> Void)? = nil,
        onUpdateScores: ((UUID, QualityScores) -> Void)? = nil
    ) {
        self.photo = photo
        self.isHUDVisible = isHUDVisible
        self.zoomState = zoomState
        self.onToggleZoom = onToggleZoom
        self.toast = toast
        self.onExitZoom = onExitZoom
        self.onPinchEnded = onPinchEnded
        self.onUpdateExif = onUpdateExif
        self.onUpdateScores = onUpdateScores
    }

    public var body: some View {
        GeometryReader { geometry in
            let shown = state.photoID == photo.id ? state.image : nil
            let aspect = shown.flatMap { $0.height > 0 ? CGFloat($0.width) / CGFloat($0.height) : nil }
            let layout = MetadataPanelLayout(
                canvas: geometry.size,
                aspectRatio: aspect,
                isPanelVisible: isHUDVisible
            )

            ZStack(alignment: .topLeading) {
                Color(red: 0.043, green: 0.043, blue: 0.051)

                ZoomableImageViewport(
                    image: shown,
                    photoURL: photo.url,
                    zoomState: zoomState,
                    panOffset: $state.panOffset,
                    onDoubleClick: onToggleZoom,
                    onPinchEnded: { onPinchEnded?($0) }
                )
                .frame(width: layout.image.width, height: layout.image.height)
                .slotOverlay(toast: toast, zoomState: zoomState, onExitZoom: onExitZoom)
                .clipped()
                .uiTestFrame("single_image_viewport")
                .offset(x: layout.image.minX, y: layout.image.minY)

                if layout.placement != .hidden {
                    PhotoInfoPanelView(
                        photo: photo,
                        placement: layout.placement,
                        aestheticsEnabled: aestheticsEnabled
                    )
                    .frame(width: layout.panel.width, height: layout.panel.height)
                    .uiTestFrame("single_info_panel")
                    .offset(x: layout.panel.minX, y: layout.panel.minY)
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height, alignment: .topLeading)
        }
        .clipped()
        .accessibilityIdentifier("single_photo_view")
        .onChange(of: photo.id) { _, _ in state.panOffset = .zero }
        .task(id: PhotoLoadKey(photoID: photo.id, aestheticsEnabled: aestheticsEnabled)) {
            await state.load(
                photo: photo,
                aestheticsEnabled: aestheticsEnabled,
                onUpdateExif: onUpdateExif,
                onUpdateScores: onUpdateScores
            )
        }
    }
}
