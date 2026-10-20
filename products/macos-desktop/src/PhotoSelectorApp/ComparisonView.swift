import SwiftUI
import PhotoSelectorKit

@MainActor
public final class ComparisonSharedState: ObservableObject {
    @Published public var sharedPanOffset: CGSize = .zero

    public init() {}
}

/// Role of an individual comparison pane in the culling workspace.
public enum SlotRole: Sendable, Equatable {
    case previous
    case current
    case next
    case candidate(Int)

    var accessibilityIdentifier: String {
        switch self {
        case .previous: return "slot_previous"
        case .current: return "slot_current"
        case .next: return "slot_next"
        case .candidate(let index): return "slot_candidate_\(index)"
        }
    }
}

/// Multi-image side-by-side (2-Up) and Focus 3-Up comparison view.
///
/// **Focus 3-Up is one-over-two** (REQ-MAC-LAYOUT.02): the CURRENT photo spans the full
/// width on top; PREVIOUS (`currentIndex - 1`) sits bottom-left and NEXT (`currentIndex + 1`)
/// bottom-right. Geometry comes exclusively from `FocusTripletLayout` — do not re-derive it
/// here, and do not return to three columns: most photographs are landscape, and columns
/// shrink the current photo to the size of its neighbours.
public struct ComparisonView: View {
    public let mode: ComparisonMode
    public let previousPhoto: PhotoItem?
    public let currentPhoto: PhotoItem?
    public let nextPhoto: PhotoItem?
    public let photos: [PhotoItem]
    public let activeSlotIndex: Int
    public let isZoomSynced: Bool
    public let isHUDVisible: Bool
    public let zoomState: ZoomState
    public let onSelectSlot: (Int) -> Void
    public let onToggleZoom: () -> Void
    public var toast: ToastMessage?
    public var onExitZoom: (() -> Void)?
    public var onPinchEnded: ((Double) -> Void)?
    public var onUpdateExif: ((UUID, ExifData) -> Void)?
    public var onUpdateScores: ((UUID, QualityScores) -> Void)?

    @StateObject private var sharedState = ComparisonSharedState()

    private static let spacing: CGFloat = 6
    private static let padding: CGFloat = 4

    public init(
        mode: ComparisonMode = .triplet,
        previousPhoto: PhotoItem? = nil,
        currentPhoto: PhotoItem? = nil,
        nextPhoto: PhotoItem? = nil,
        photos: [PhotoItem] = [],
        activeSlotIndex: Int = 1,
        isZoomSynced: Bool = true,
        isHUDVisible: Bool = true,
        zoomState: ZoomState = .fit,
        onSelectSlot: @escaping (Int) -> Void,
        onToggleZoom: @escaping () -> Void,
        toast: ToastMessage? = nil,
        onExitZoom: (() -> Void)? = nil,
        onPinchEnded: ((Double) -> Void)? = nil,
        onUpdateExif: ((UUID, ExifData) -> Void)? = nil,
        onUpdateScores: ((UUID, QualityScores) -> Void)? = nil
    ) {
        self.mode = mode
        self.previousPhoto = previousPhoto
        self.currentPhoto = currentPhoto
        self.nextPhoto = nextPhoto
        self.photos = photos
        self.activeSlotIndex = activeSlotIndex
        self.isZoomSynced = isZoomSynced
        self.isHUDVisible = isHUDVisible
        self.zoomState = zoomState
        self.onSelectSlot = onSelectSlot
        self.onToggleZoom = onToggleZoom
        self.toast = toast
        self.onExitZoom = onExitZoom
        self.onPinchEnded = onPinchEnded
        self.onUpdateExif = onUpdateExif
        self.onUpdateScores = onUpdateScores
    }

    public var body: some View {
        GeometryReader { geometry in
            let canvas = CGSize(
                width: max(0, geometry.size.width - Self.padding * 2),
                height: max(0, geometry.size.height - Self.padding * 2)
            )
            Group {
                if mode == .triplet {
                    focusTripletView(canvas: canvas)
                } else {
                    sideBySideView()
                }
            }
            .padding(Self.padding)
        }
        .background(Color.lensjetCanvas)
        .preferredColorScheme(.dark)
        .onChange(of: zoomState) { _, newState in
            if !newState.isZoomed { sharedState.sharedPanOffset = .zero }
        }
        .onChange(of: currentPhoto?.id) { _, _ in
            sharedState.sharedPanOffset = .zero
        }
    }

    // MARK: - Focus 3-Up: CURRENT on top, PREVIOUS | NEXT below

    private func focusTripletView(canvas: CGSize) -> some View {
        let layout = FocusTripletLayout(canvas: canvas, spacing: Self.spacing)
        let previousSlot = currentPhoto.flatMap { current in photos.firstIndex(where: { $0.id == current.id }) }
            .map { max(0, $0 - 1) } ?? 0
        let currentSlot = currentPhoto.flatMap { current in photos.firstIndex(where: { $0.id == current.id }) } ?? 1
        let nextSlot = min(photos.count - 1, currentSlot + 1)

        return ZStack(alignment: .topLeading) {
            // Top: CURRENT photo (prominent focus ring, culling target)
            Group {
                if let current = currentPhoto {
                    pane(photo: current, role: .current, isActive: true, hostsOverlay: true) { onSelectSlot(currentSlot) }
                } else {
                    ProgressView().tint(.secondary)
                }
            }
            .frame(width: layout.current.width, height: layout.current.height)
            .offset(x: layout.current.minX, y: layout.current.minY)

            // Bottom-left: PREVIOUS photo
            Group {
                if let previous = previousPhoto {
                    pane(photo: previous, role: .previous, isActive: false) { onSelectSlot(previousSlot) }
                } else {
                    BoundarySlotPane(
                        icon: "photo.badge.checkmark",
                        title: "First Photograph",
                        subtitle: "No previous image in folder"
                    )
                    .uiTestFrame("slot_previous")
                }
            }
            .frame(width: layout.previous.width, height: layout.previous.height)
            .offset(x: layout.previous.minX, y: layout.previous.minY)

            // Bottom-right: NEXT photo
            Group {
                if let next = nextPhoto {
                    pane(photo: next, role: .next, isActive: false) { onSelectSlot(nextSlot) }
                } else {
                    BoundarySlotPane(
                        icon: "checkmark.circle.fill",
                        title: "Last Photograph",
                        subtitle: "No next image in folder"
                    )
                    .uiTestFrame("slot_next")
                }
            }
            .frame(width: layout.next.width, height: layout.next.height)
            .offset(x: layout.next.minX, y: layout.next.minY)
        }
        .frame(width: canvas.width, height: canvas.height, alignment: .topLeading)
        .accessibilityIdentifier("focus_triplet_one_over_two")
    }

    // MARK: - 2-Up Side-by-Side View

    private func sideBySideView() -> some View {
        let displayList = photos.isEmpty ? [currentPhoto].compactMap { $0 } : photos
        let hostIndex = min(max(0, activeSlotIndex), max(0, displayList.count - 1))
        return HStack(spacing: Self.spacing) {
            ForEach(Array(displayList.enumerated()), id: \.offset) { index, photo in
                pane(
                    photo: photo,
                    role: index == 0 ? .current : .candidate(index),
                    isActive: index == activeSlotIndex,
                    hostsOverlay: index == hostIndex
                ) { onSelectSlot(index) }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    /// `hostsOverlay` marks the one slot (CURRENT in 3-Up, the active slot in 2-Up) whose image
    /// rectangle carries the action toast and the zoom-exit pill.
    private func pane(photo: PhotoItem, role: SlotRole, isActive: Bool, hostsOverlay: Bool = false, onSelect: @escaping () -> Void) -> some View {
        ComparisonSlotPane(
            photo: photo,
            role: role,
            isActive: isActive,
            isZoomSynced: isZoomSynced,
            sharedState: sharedState,
            isHUDVisible: isHUDVisible,
            zoomState: zoomState,
            onSelect: onSelect,
            onDoubleTap: onToggleZoom,
            toast: hostsOverlay ? toast : nil,
            onExitZoom: hostsOverlay ? onExitZoom : nil,
            onPinchEnded: onPinchEnded,
            onUpdateExif: onUpdateExif,
            onUpdateScores: onUpdateScores
        )
    }
}

/// Boundary placeholder card shown when navigating to the first or last photo in a shoot.
public struct BoundarySlotPane: View {
    public let icon: String
    public let title: String
    public let subtitle: String

    public init(icon: String, title: String, subtitle: String) {
        self.icon = icon
        self.title = title
        self.subtitle = subtitle
    }

    public var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(Color.white.opacity(0.35))

            Text(title)
                .font(.system(size: 15, weight: .bold, design: .rounded))
                .foregroundStyle(Color.white)

            Text(subtitle)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(Color.lensjetTextSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.lensjetSurface)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .strokeBorder(Color.white.opacity(0.1), lineWidth: 1)
        )
    }
}

/// An individual comparison slot pane containing one candidate photo with header badges,
/// score readouts, filename, EXIF exposure strip, and responsive active focus ring.
public struct ComparisonSlotPane: View {
    public let photo: PhotoItem
    public let role: SlotRole
    public let isActive: Bool
    public let isZoomSynced: Bool
    @ObservedObject public var sharedState: ComparisonSharedState
    public let isHUDVisible: Bool
    public let zoomState: ZoomState
    public let onSelect: () -> Void
    public let onDoubleTap: () -> Void
    public var toast: ToastMessage?
    public var onExitZoom: (() -> Void)?
    public var onPinchEnded: ((Double) -> Void)?
    public var onUpdateExif: ((UUID, ExifData) -> Void)?
    public var onUpdateScores: ((UUID, QualityScores) -> Void)?

    @StateObject private var imageState = ProgressivePhotoState()
    @AppStorage(AppSettingsKeys.aestheticScoringEnabled)
    private var aestheticsEnabled: Bool = AppSettingsDefaults.aestheticScoringEnabled

    public init(
        photo: PhotoItem,
        role: SlotRole = .current,
        isActive: Bool,
        isZoomSynced: Bool,
        sharedState: ComparisonSharedState,
        isHUDVisible: Bool,
        zoomState: ZoomState = .fit,
        onSelect: @escaping () -> Void,
        onDoubleTap: @escaping () -> Void,
        toast: ToastMessage? = nil,
        onExitZoom: (() -> Void)? = nil,
        onPinchEnded: ((Double) -> Void)? = nil,
        onUpdateExif: ((UUID, ExifData) -> Void)? = nil,
        onUpdateScores: ((UUID, QualityScores) -> Void)? = nil
    ) {
        self.photo = photo
        self.role = role
        self.isActive = isActive
        self.isZoomSynced = isZoomSynced
        self.sharedState = sharedState
        self.isHUDVisible = isHUDVisible
        self.zoomState = zoomState
        self.onSelect = onSelect
        self.onDoubleTap = onDoubleTap
        self.toast = toast
        self.onExitZoom = onExitZoom
        self.onPinchEnded = onPinchEnded
        self.onUpdateExif = onUpdateExif
        self.onUpdateScores = onUpdateScores
    }

    private var panBinding: Binding<CGSize> {
        Binding(
            get: { isZoomSynced ? sharedState.sharedPanOffset : imageState.panOffset },
            set: { newValue in
                if isZoomSynced {
                    sharedState.sharedPanOffset = newValue
                } else {
                    imageState.panOffset = newValue
                }
            }
        )
    }

    public var body: some View {
        ZStack(alignment: .topLeading) {
            // Neutral canvas (LensjetPalette.canvas)
            Color.lensjetCanvas

            // Header, image and EXIF strip stack vertically: each reserves its own rows, so
            // no metadata is ever drawn on top of the photograph (REQ-MAC-LAYOUT.05).
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    slotBadge
                    Text(photo.filename)
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color.white)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(Color.white.opacity(0.08))
                        .clipShape(RoundedRectangle(cornerRadius: 4))

                    StatusBadge(status: photo.status)

                    Spacer(minLength: 4)

                    scoreBadges
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .frame(maxWidth: .infinity, alignment: .leading)
                .uiTestFrame(role.accessibilityIdentifier + "_header")

                ZoomableImageViewport(
                    image: imageState.photoID == photo.id ? imageState.image : nil,
                    photoURL: photo.url,
                    zoomState: zoomState,
                    panOffset: panBinding,
                    onDoubleClick: onDoubleTap,
                    onPinchEnded: { onPinchEnded?($0) }
                )
                .padding(4) // gutter for the frame border / focus reticle: nothing touches the photo
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .slotOverlay(toast: toast, zoomState: zoomState, onExitZoom: onExitZoom)
                .clipped()
                .uiTestFrame(role.accessibilityIdentifier + "_image")

                if isHUDVisible {
                    exifStrip
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .uiTestFrame(role.accessibilityIdentifier + "_exif")
                }
            }

            // Base Slot Frame Border
            RoundedRectangle(cornerRadius: 6)
                .strokeBorder(
                    Color.white.opacity(role == .current ? 0.25 : 0.08),
                    lineWidth: 1.0
                )
                .allowsHitTesting(false)

            // Viewfinder Autofocus Reticle Brackets (Active Focused Photo)
            if isActive {
                FocusReticleShape(cornerLength: 28, cornerRadius: 6)
                    .stroke(
                        Color.lensjetSelection,
                        style: StrokeStyle(lineWidth: 3.0, lineCap: .round, lineJoin: .round)
                    )
                    .padding(1.5)
                    .allowsHitTesting(false)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .contentShape(Rectangle())
        .onTapGesture {
            onSelect()
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(role.accessibilityIdentifier)
        .uiTestFrame(role.accessibilityIdentifier)
        .onChange(of: photo.id) { _, _ in imageState.panOffset = .zero }
        .task(id: PhotoLoadKey(photoID: photo.id, aestheticsEnabled: aestheticsEnabled)) {
            await imageState.load(
                photo: photo,
                aestheticsEnabled: aestheticsEnabled,
                onUpdateExif: onUpdateExif,
                onUpdateScores: onUpdateScores
            )
        }
    }

    @ViewBuilder
    private var scoreBadges: some View {
        if let scores = photo.scores {
            HStack(spacing: 4) {
                Circle()
                    .fill(focusColor(for: scores.focusCategory))
                    .frame(width: 6, height: 6)
                Text("\(String(format: "%.1f", scores.sharpness))")
                    .font(.system(size: 10, weight: .bold).monospacedDigit())
                    .foregroundStyle(Color.white)
                Text(scores.focusCategory.rawValue)
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(Color.white.opacity(0.9))
            }
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Color.black.opacity(0.75))
            .clipShape(Capsule())
            .overlay(
                Capsule()
                    .stroke(focusColor(for: scores.focusCategory).opacity(0.6), lineWidth: 1)
            )

            if let aesthetic = scores.aesthetic {
                HStack(spacing: 3) {
                    Image(systemName: "star.fill")
                        .font(.system(size: 8))
                        .foregroundStyle(Color.lensjetRating)
                    Text(String(format: "%.1f", aesthetic))
                        .font(.system(size: 10, weight: .bold).monospacedDigit())
                        .foregroundStyle(Color.white)
                }
                .padding(.horizontal, 6)
                .padding(.vertical, 3)
                .background(Color.black.opacity(0.75))
                .clipShape(Capsule())
            }
        } else {
            HStack(spacing: 3) {
                ProgressView()
                    .scaleEffect(0.5)
                    .tint(.secondary)
                Text("Scoring…")
                    .font(.system(size: 9, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.85))
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.black.opacity(0.6))
            .clipShape(Capsule())
        }
    }

    @ViewBuilder
    private var exifStrip: some View {
        if let exif = photo.exif, !exif.isFallback {
            HStack(spacing: 6) {
                Image(systemName: "camera.fill")
                    .font(.system(size: 9))
                    .foregroundStyle(Color.accentColor)

                if let camera = exif.cameraModel, !camera.isEmpty {
                    Text(camera)
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(Color.white)
                }

                Text(exif.formattedSummary)
                    .font(.system(size: 10, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Color.white)

                if !exif.lens.isEmpty && exif.lens != "Unknown" {
                    Text("· \(exif.lens)")
                        .font(.system(size: 9, weight: .regular))
                        .foregroundStyle(Color.white.opacity(0.85))
                        .lineLimit(1)
                        .truncationMode(.tail)
                }

                if let scores = photo.scores {
                    if scores.highlightClipping > 5.0 {
                        Text("⚠️ Blown (\(Int(scores.highlightClipping))%)")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(Color.lensjetClippingHighlight)
                    }
                    if scores.shadowClipping > 15.0 {
                        Text("⚠️ Crushed (\(Int(scores.shadowClipping))%)")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(Color.lensjetClippingShadow)
                    }
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.black.opacity(0.8))
            .clipShape(RoundedRectangle(cornerRadius: 4))
        } else {
            HStack(spacing: 4) {
                Image(systemName: "camera")
                    .font(.system(size: 9))
                    .foregroundStyle(Color.lensjetTextSecondary)
                Text(photo.exif?.isFallback == true ? "No EXIF" : "Reading EXIF…")
                    .font(.system(size: 9, weight: .medium))
                    .foregroundStyle(Color.lensjetTextSecondary)
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.black.opacity(0.6))
            .clipShape(RoundedRectangle(cornerRadius: 4))
        }
    }

    private var slotBadge: some View {
        Group {
            switch role {
            case .previous:
                Button(action: onSelect) {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 9, weight: .bold))
                        Text("PREVIOUS")
                            .font(.system(size: 10, weight: .bold, design: .rounded))
                    }
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.white.opacity(0.18))
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .focusable(false)
                .help("Jump to previous photo (Left Arrow)")

            case .current:
                HStack(spacing: 4) {
                    Image(systemName: "viewfinder")
                        .font(.system(size: 9, weight: .bold))
                    Text("CURRENT")
                        .font(.system(size: 10, weight: .black, design: .rounded))
                }
                .foregroundStyle(Color.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(Color.lensjetSelection)
                .clipShape(Capsule())

            case .next:
                Button(action: onSelect) {
                    HStack(spacing: 4) {
                        Text("NEXT")
                            .font(.system(size: 10, weight: .bold, design: .rounded))
                        Image(systemName: "chevron.right")
                            .font(.system(size: 9, weight: .bold))
                    }
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.white.opacity(0.18))
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .focusable(false)
                .help("Jump to next photo (Right Arrow)")

            case .candidate(let idx):
                Text("Candidate \(idx + 1)")
                    .font(.system(size: 10, weight: .bold, design: .rounded))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.white.opacity(0.15))
                    .clipShape(Capsule())
            }
        }
    }

    private func focusColor(for category: SharpnessCategory) -> Color {
        switch category {
        case .sharp: return Color.lensjetSharp
        case .acceptable: return Color.lensjetAcceptable
        case .blurry: return Color.lensjetBlurry
        }
    }
}

/// Camera viewfinder autofocus reticle corner brackets.
struct FocusReticleShape: Shape {
    var cornerLength: CGFloat = 28
    var cornerRadius: CGFloat = 6

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let cl = min(cornerLength, min(rect.width, rect.height) / 3)
        let r = min(cornerRadius, cl / 2)

        // Top-Left corner [
        path.move(to: CGPoint(x: rect.minX, y: rect.minY + cl))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        path.addQuadCurve(to: CGPoint(x: rect.minX + r, y: rect.minY), control: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.minX + cl, y: rect.minY))

        // Top-Right corner ]
        path.move(to: CGPoint(x: rect.maxX - cl, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        path.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY + r), control: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + cl))

        // Bottom-Right corner ]
        path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - cl))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        path.addQuadCurve(to: CGPoint(x: rect.maxX - r, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX - cl, y: rect.maxY))

        // Bottom-Left corner [
        path.move(to: CGPoint(x: rect.minX + cl, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        path.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY - r), control: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - cl))

        return path
    }
}
