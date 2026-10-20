import SwiftUI
import PhotoSelectorKit

/// Records when each photograph first reached the screen, per tier, and when its EXIF and
/// scores first became visible. Read by the UI self-test to measure time-to-first-photo and
/// EXIF latency; costs one dictionary write per displayed photo.
@MainActor
final class DisplayTelemetry {
    static let shared = DisplayTelemetry()

    private(set) var firstDisplayed: [UUID: [ThumbnailTier: Date]] = [:]
    private(set) var exifShown: [UUID: Date] = [:]
    private(set) var technicalScoresShown: [UUID: Date] = [:]
    private(set) var aestheticsShown: [UUID: Date] = [:]
    /// Number of Vision requests issued since the last `reset()` (the self-test asserts 0
    /// when AI scoring is switched off).
    private(set) var aestheticRequests = 0

    func markDisplayed(photoID: UUID, tier: ThumbnailTier) {
        if firstDisplayed[photoID]?[tier] == nil {
            firstDisplayed[photoID, default: [:]][tier] = Date()
        }
    }

    func markExif(photoID: UUID) { if exifShown[photoID] == nil { exifShown[photoID] = Date() } }
    func markTechnicalScores(photoID: UUID) { if technicalScoresShown[photoID] == nil { technicalScoresShown[photoID] = Date() } }
    func markAesthetics(photoID: UUID) { if aestheticsShown[photoID] == nil { aestheticsShown[photoID] = Date() } }
    func markAestheticRequest() { aestheticRequests += 1 }

    func firstDisplay(of photoID: UUID, tier: ThumbnailTier) -> Date? {
        firstDisplayed[photoID]?[tier]
    }

    func reset() {
        firstDisplayed.removeAll()
        exifShown.removeAll()
        technicalScoresShown.removeAll()
        aestheticsShown.removeAll()
        aestheticRequests = 0
    }
}

/// Identity of one load: a different photo **or** a flipped AI-score setting restarts it.
struct PhotoLoadKey: Hashable {
    let photoID: UUID
    let aestheticsEnabled: Bool
}

/// Runs blocking `work` on a background thread at `priority`, propagating cancellation of the
/// awaiting task into it (a bare `Task.detached` ignores its parent's cancellation, so stale
/// work for photographs the user already left kept running and queued up behind the current one).
private func runCancellable<T: Sendable>(
    priority: TaskPriority,
    _ work: @escaping @Sendable () async -> T
) async -> T {
    let task = Task.detached(priority: priority) { await work() }
    return await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
}

/// Per-view image state for a progressively loaded photograph.
@MainActor
final class ProgressivePhotoState: ObservableObject {
    @Published var image: CGImage?
    @Published var tier: ThumbnailTier?
    /// Photo the `image` belongs to — prevents showing the previous photo under a new HUD.
    @Published var photoID: UUID?
    @Published var panOffset: CGSize = .zero

    /// Loads everything the viewport shows for `photo`, **in order of cost**, none of it on
    /// the main actor:
    /// 1. EXIF — started immediately, concurrently with the image decode (header read, ms);
    /// 2. image: embedded-thumbnail placeholder → 2048 preview;
    /// 3. focus + exposure clipping (Accelerate, ms) — published as soon as they exist;
    /// 4. Apple Vision aesthetics — debounced, cancellable, and skipped entirely when the
    ///    user switched AI scoring off.
    func load(
        photo: PhotoItem,
        aestheticsEnabled: Bool,
        onUpdateExif: ((UUID, ExifData) -> Void)?,
        onUpdateScores: ((UUID, QualityScores) -> Void)?
    ) async {
        if photoID != photo.id {
            image = nil
            tier = nil
            photoID = photo.id
        }

        let url = photo.url
        let id = photo.id
        let needsExif = photo.exif == nil || photo.exif?.isFallback == true
        let exifTask: Task<Void, Never>? = needsExif ? Task { @MainActor in
            let exif = await runCancellable(priority: .userInitiated) {
                CullingWorkspaceViewModel.readMetadataWithCompanionFallback(for: url)
            }
            if let exif, !exif.isFallback, !Task.isCancelled {
                onUpdateExif?(id, exif)
                DisplayTelemetry.shared.markExif(photoID: id)
            }
        } : nil
        if !needsExif { DisplayTelemetry.shared.markExif(photoID: id) }

        await withTaskCancellationHandler {
            await loadImageAndScores(photo: photo, aestheticsEnabled: aestheticsEnabled, onUpdateScores: onUpdateScores)
            await exifTask?.value
        } onCancel: {
            exifTask?.cancel()
        }
    }

    private func loadImageAndScores(
        photo: PhotoItem,
        aestheticsEnabled: Bool,
        onUpdateScores: ((UUID, QualityScores) -> Void)?
    ) async {
        let id = photo.id
        var preview: CGImage?
        for await progressive in ThumbnailLoader.shared.progressivePreview(for: photo.url) {
            guard !Task.isCancelled, photoID == id else { return }
            image = progressive.image
            tier = progressive.tier
            DisplayTelemetry.shared.markDisplayed(photoID: id, tier: progressive.tier)
            if progressive.tier == .preview { preview = progressive.image }
        }
        guard !Task.isCancelled, let preview else { return }

        let plan = ScoringPlan.make(existing: photo.scores, aestheticsEnabled: aestheticsEnabled)
        let wrapped = CGImageWrapper(preview)
        var scores = photo.scores

        if plan.runTechnical {
            let technical = await runCancellable(priority: .utility) {
                QualityScoringService.scoreTechnical(cgImage: wrapped.image)
            }
            guard !Task.isCancelled else { return }
            scores = technical
            onUpdateScores?(id, technical)
            DisplayTelemetry.shared.markTechnicalScores(photoID: id)
        }

        if plan.runAesthetics, let base = scores {
            try? await Task.sleep(for: ScoringPlan.aestheticsDebounce)
            guard !Task.isCancelled else { return }
            DisplayTelemetry.shared.markAestheticRequest()
            let evaluation = await runCancellable(priority: .utility) {
                await QualityScoringService.evaluateAesthetics(cgImage: wrapped.image)
            }
            guard !Task.isCancelled, let evaluation else { return }
            onUpdateScores?(id, base.withAesthetics(evaluation.mappedScore, isUtility: evaluation.isUtility))
            DisplayTelemetry.shared.markAesthetics(photoID: id)
        }
    }
}

/// Per-viewport interactive state (magnification, drag origin, and decoded full-res image).
@MainActor
final class ZoomableViewportState: ObservableObject {
    @Published var liveMagnification: CGFloat = 1
    @Published var dragOrigin: CGSize?
    @Published var originalPixelSize: CGSize?
    @Published var fullResolutionImage: CGImage?
}

/// Image viewport with fit / honest-100 % / pinch zoom and clamped panning.
///
/// Hit-testing rule (the cause of "no buttons work while zoomed"): gestures are attached to
/// the **untransformed** viewport with a rectangular `contentShape`, and the scaled image has
/// hit-testing disabled. `.clipped()` only clips drawing, so gestures on the scaled image
/// used to capture clicks far outside the canvas — over the toolbar and the action bar.
@MainActor
struct ZoomableImageViewport: View {
    let image: CGImage?
    let photoURL: URL
    let zoomState: ZoomState
    @Binding var panOffset: CGSize
    let onDoubleClick: () -> Void
    let onPinchEnded: (Double) -> Void

    @Environment(\.displayScale) private var displayScale
    @StateObject private var viewport = ZoomableViewportState()

    var body: some View {
        GeometryReader { geometry in
            let viewportSize = geometry.size
            let contentSize = viewport.originalPixelSize ?? image.map { CGSize(width: $0.width, height: $0.height) }
            let baseScale = ZoomGeometry.scale(
                for: zoomState,
                originalPixelSize: contentSize,
                viewport: viewportSize,
                backingScale: displayScale
            )
            let scale = max(1, baseScale * viewport.liveMagnification)
            let fitted = contentSize.map { ZoomGeometry.fittedSize(content: $0, in: viewportSize) } ?? viewportSize

            ZStack {
                if let displayed = (zoomState.isZoomed ? viewport.fullResolutionImage : nil) ?? image {
                    Image(decorative: displayed, scale: 1.0)
                        .resizable()
                        .interpolation(.high)
                        .aspectRatio(contentMode: .fit)
                        .frame(width: viewportSize.width, height: viewportSize.height)
                        .scaleEffect(scale)
                        .offset(ZoomGeometry.clampedPan(panOffset, scale: scale, fittedSize: fitted, viewport: viewportSize))
                        .allowsHitTesting(false)
                } else {
                    ProgressView()
                        .scaleEffect(1.1)
                        .tint(.secondary)
                }
            }
            .frame(width: viewportSize.width, height: viewportSize.height)
            .clipped()
            .contentShape(Rectangle())
            .gesture(
                MagnifyGesture()
                    .onChanged { value in
                        viewport.liveMagnification = value.magnification
                    }
                    .onEnded { value in
                        let final = Double(baseScale * value.magnification)
                        viewport.liveMagnification = 1
                        onPinchEnded(final)
                    }
            )
            .simultaneousGesture(
                DragGesture(minimumDistance: 2)
                    .onChanged { value in
                        guard scale > 1 else { return }
                        let origin = viewport.dragOrigin ?? panOffset
                        viewport.dragOrigin = origin
                        panOffset = ZoomGeometry.clampedPan(
                            CGSize(width: origin.width + value.translation.width,
                                   height: origin.height + value.translation.height),
                            scale: scale,
                            fittedSize: fitted,
                            viewport: viewportSize
                        )
                    }
                    .onEnded { _ in viewport.dragOrigin = nil }
            )
            .onTapGesture(count: 2) { onDoubleClick() }
            .onHover { inside in
                guard zoomState.isZoomed else { return }
                if inside { NSCursor.openHand.push() } else { NSCursor.pop() }
            }
        }
        .onChange(of: zoomState) { _, newState in
            if !newState.isZoomed {
                panOffset = .zero
                viewport.fullResolutionImage = nil // release the large decode immediately
            }
        }
        .onChange(of: photoURL) { _, _ in
            viewport.fullResolutionImage = nil
            viewport.originalPixelSize = nil
        }
        .task(id: FullResolutionRequest(url: photoURL, zoomed: zoomState.isZoomed)) {
            if viewport.originalPixelSize == nil {
                let url = photoURL
                viewport.originalPixelSize = await Task.detached(priority: .utility) {
                    ThumbnailLoader.originalPixelSize(of: url)
                }.value
            }
            guard zoomState.isZoomed, viewport.fullResolutionImage == nil else { return }
            let decoded = await ThumbnailLoader.shared.loadFullResolution(for: photoURL)
            if !Task.isCancelled, zoomState.isZoomed {
                viewport.fullResolutionImage = decoded
            }
        }
    }
}

private struct FullResolutionRequest: Hashable {
    let url: URL
    let zoomed: Bool
}

/// Floating banner shown whenever any zoom is active: states the zoom and offers a visible
/// way back to Fit (in addition to Esc, Space, double-click and the toolbar button).
struct ZoomExitPill: View {
    let zoomState: ZoomState
    let onExit: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "plus.magnifyingglass")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Color.white)
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Color.white)
            Text("Drag to pan")
                .font(.system(size: 11))
                .foregroundStyle(Color.lensjetTextSecondary)
            Button(action: onExit) {
                HStack(spacing: 4) {
                    Image(systemName: "arrow.down.right.and.arrow.up.left")
                    Text("Fit (Esc)")
                }
                .font(.system(size: 11, weight: .bold))
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .frame(minHeight: 24)
            }
            .buttonStyle(.borderedProminent)
            .focusable(false)
            .uiTestFrame("zoom_exit_button")
            .help("Return to Fit (Esc or Space)")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().stroke(Color.white.opacity(0.15), lineWidth: 0.5))
        .shadow(color: .black.opacity(0.35), radius: 6, y: 2)
    }

    private var label: String {
        switch zoomState {
        case .fit: return "Fit"
        case .oneToOne: return "100% (1:1)"
        case .magnified(let factor): return String(format: "Zoom %.1f×", factor)
        }
    }
}
