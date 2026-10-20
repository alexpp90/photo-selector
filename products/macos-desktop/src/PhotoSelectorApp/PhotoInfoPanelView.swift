import SwiftUI
import PhotoSelectorKit

/// Metadata panel of the single-photo view. It occupies its **own** rectangle (reserved by
/// `MetadataPanelLayout`) beside or below the image and never overlaps the photograph
/// (REQ-MAC-LAYOUT.05).
///
/// Everything slow fills in later: EXIF shows as soon as the header is read (a few ms),
/// focus/clipping follow within milliseconds, and the Apple Vision aesthetic score — which
/// can be switched off in Settings — arrives last. None of them reserve or change layout.
struct PhotoInfoPanelView: View {
    let photo: PhotoItem
    let placement: MetadataPanelLayout.Placement
    let aestheticsEnabled: Bool

    private static let primary = Color.white
    private static let secondary = Color.lensjetTextSecondary
    private static let surface = Color.lensjetPanel
    private static let border = Color.lensjetSeparator

    var body: some View {
        Group {
            if placement == .sideColumn {
                VStack(alignment: .leading, spacing: 10) {
                    identityRow
                    cameraRow
                    exposureRow
                    Divider().overlay(Self.border)
                    qualityRow
                    Spacer(minLength: 0)
                }
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 10) {
                        identityRow
                        Spacer(minLength: 8)
                        cameraRow
                    }
                    HStack(spacing: 14) {
                        exposureRow
                        Spacer(minLength: 8)
                        qualityRow
                    }
                }
                .frame(maxHeight: .infinity, alignment: .center)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, placement == .sideColumn ? 12 : 6)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Self.surface)
        .overlay(alignment: placement == .sideColumn ? .leading : .top) {
            Rectangle()
                .fill(Self.border)
                .frame(width: placement == .sideColumn ? 1 : nil, height: placement == .sideColumn ? nil : 1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("photo_info_panel")
    }

    // MARK: - Rows

    private var identityRow: some View {
        HStack(spacing: 8) {
            Text(photo.filename)
                .font(.system(size: 13, weight: .semibold, design: .rounded))
                .foregroundStyle(Self.primary)
                .lineLimit(1)
                .truncationMode(.middle)
            Text(photo.url.pathExtension.uppercased())
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(Color.accentColor)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(Color.accentColor.opacity(0.18))
                .clipShape(Capsule())
            StatusBadge(status: photo.status)
        }
    }

    @ViewBuilder
    private var cameraRow: some View {
        if let exif = photo.exif, !exif.isFallback,
           let camera = exif.cameraModel, !camera.isEmpty {
            HStack(spacing: 5) {
                Image(systemName: "camera.fill")
                    .font(.system(size: 10))
                    .foregroundStyle(Color.accentColor)
                Text(camera)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Self.primary)
                    .lineLimit(1)
                if !exif.lens.isEmpty && exif.lens != "Unknown" {
                    Text("· \(exif.lens)")
                        .font(.system(size: 11))
                        .foregroundStyle(Self.secondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
        }
    }

    @ViewBuilder
    private var exposureRow: some View {
        HStack(spacing: 6) {
            Image(systemName: "slider.horizontal.3")
                .font(.system(size: 10))
                .foregroundStyle(Self.secondary)
            if let exif = photo.exif {
                Text(exif.isFallback ? "No EXIF metadata" : exif.formattedSummary)
                    .font(.system(size: 11, weight: .semibold).monospacedDigit())
                    .foregroundStyle(exif.isFallback ? Self.secondary : Self.primary)
                    .lineLimit(1)
            } else {
                Text("Reading EXIF…")
                    .font(.system(size: 11))
                    .foregroundStyle(Self.secondary)
            }
        }
    }

    @ViewBuilder
    private var qualityRow: some View {
        let stack = placement == .sideColumn
        let layout = stack ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6)) : AnyLayout(HStackLayout(spacing: 12))
        layout {
            if let scores = photo.scores {
                HStack(spacing: 4) {
                    Image(systemName: focusIcon(for: scores.focusCategory))
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(focusColor(for: scores.focusCategory))
                    Text("Focus \(String(format: "%.1f", scores.sharpness))")
                        .font(.system(size: 11, weight: .bold).monospacedDigit())
                        .foregroundStyle(Self.primary)
                    Text("[\(scores.focusCategory.rawValue)]")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(focusColor(for: scores.focusCategory))
                }
                if scores.highlightClipping > 5.0 {
                    Text("Blown \(Int(scores.highlightClipping))%")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(Color.lensjetClippingHighlight)
                }
                if scores.shadowClipping > 15.0 {
                    Text("Crushed \(Int(scores.shadowClipping))%")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(Color.lensjetClippingShadow)
                }
            } else {
                HStack(spacing: 4) {
                    ProgressView().controlSize(.mini)
                    Text("Analyzing focus…")
                        .font(.system(size: 10))
                        .foregroundStyle(Self.secondary)
                }
            }
            aestheticItem
        }
    }

    @ViewBuilder
    private var aestheticItem: some View {
        if let aesthetic = photo.scores?.aesthetic {
            HStack(spacing: 4) {
                Image(systemName: "star.fill")
                    .font(.system(size: 10))
                    .foregroundStyle(Color.lensjetRating)
                Text(String(format: "%.1f", aesthetic))
                    .font(.system(size: 11, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Self.primary)
                if photo.scores?.isUtility == true {
                    Text("Utility")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(Color.cyan)
                }
            }
        } else if aestheticsEnabled {
            HStack(spacing: 4) {
                Image(systemName: "star")
                    .font(.system(size: 10))
                    .foregroundStyle(Self.secondary)
                Text("AI score…")
                    .font(.system(size: 10))
                    .foregroundStyle(Self.secondary)
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

    private func focusIcon(for category: SharpnessCategory) -> String {
        switch category {
        case .sharp: return "checkmark.circle.fill"
        case .acceptable: return "eye.fill"
        case .blurry: return "exclamationmark.triangle.fill"
        }
    }
}
