import SwiftUI
import AppKit
import PhotoSelectorKit

/// ViewModel managing state and asynchronous metric calculations for LibraryStatisticsView.
@MainActor
public final class LibraryStatisticsViewModel: ObservableObject {
    @Published public private(set) var statistics: LibraryStatistics = .empty
    @Published public private(set) var isCalculating: Bool = false
    @Published public var selectedLeaderboardTab: LeaderboardTab = .sharpness

    public enum LeaderboardTab: String, CaseIterable, Identifiable {
        case sharpness = "Sharpest"
        case aesthetics = "Aesthetic"
        public var id: String { rawValue }
    }

    public init() {}

    public func refresh(from photos: [PhotoItem]) {
        self.isCalculating = true
        Task { [weak self] in
            let stats = await LibraryStatisticsEngine.calculateAsync(from: photos)
            await MainActor.run {
                self?.statistics = stats
                self?.isCalculating = false
            }
        }
    }
}

/// Native macOS sheet presenting library metrics, format distributions,
/// storage savings, quality breakdowns, and top-rated photo leaderboards.
public struct LibraryStatisticsView: View {
    @ObservedObject public var workspaceViewModel: CullingWorkspaceViewModel
    @StateObject private var statsViewModel = LibraryStatisticsViewModel()
    @Environment(\.dismiss) private var dismiss

    public init(workspaceViewModel: CullingWorkspaceViewModel) {
        self.workspaceViewModel = workspaceViewModel
    }

    public var body: some View {
        VStack(spacing: 0) {
            headerBar
            Divider()

            if statsViewModel.isCalculating && statsViewModel.statistics.totalPhotoCount == 0 {
                VStack(spacing: 12) {
                    ProgressView()
                        .scaleEffect(1.2)
                    Text("Aggregating Library Metrics…")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView(.vertical, showsIndicators: true) {
                    VStack(alignment: .leading, spacing: 20) {
                        kpiGridSection
                        cullingProgressSection

                        HStack(alignment: .top, spacing: 16) {
                            formatBreakdownCard
                                .frame(maxWidth: .infinity)

                            qualityMetricsCard
                                .frame(maxWidth: .infinity)
                        }

                        leaderboardSection
                    }
                    .padding(20)
                }
            }

            Divider()
            footerBar
        }
        .frame(minWidth: 640, idealWidth: 720, minHeight: 640, idealHeight: 740)
        .background(Color(NSColor.windowBackgroundColor))
        .preferredColorScheme(.dark)
        .onAppear {
            statsViewModel.refresh(from: workspaceViewModel.photos)
        }
    }

    // MARK: - Header
    private var headerBar: some View {
        HStack {
            Image(systemName: "chart.bar.xaxis")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(Color.accentColor)

            VStack(alignment: .leading, spacing: 2) {
                Text("Library Statistics")
                    .font(.system(size: 16, weight: .bold))
                if let root = workspaceViewModel.rootDirectoryURL {
                    Text(root.lastPathComponent)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            Button(action: { dismiss() }) {
                Text("Done").foregroundStyle(Color.lensjetOnSelection)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)
            .keyboardShortcut(.defaultAction)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .background(.ultraThinMaterial)
    }

    // MARK: - KPI Grid
    private var kpiGridSection: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 12)], spacing: 12) {
            KPICard(
                title: "Total Photos",
                value: "\(statsViewModel.statistics.totalPhotoCount)",
                icon: "photo.stack",
                tint: .blue
            )
            KPICard(
                title: "Candidates",
                value: "\(statsViewModel.statistics.candidateCount)",
                icon: "circle",
                tint: .secondary
            )
            KPICard(
                title: "Selected",
                value: "\(statsViewModel.statistics.selectedCount)",
                icon: "checkmark.circle.fill",
                tint: .green
            )
            KPICard(
                title: "Copied",
                value: "\(statsViewModel.statistics.copiedCount)",
                icon: "doc.on.doc.fill",
                tint: .cyan
            )
            KPICard(
                title: "Trashed",
                value: "\(statsViewModel.statistics.trashedCount)",
                icon: "trash.fill",
                tint: .red,
                subtitle: statsViewModel.statistics.trashedStorageBytes > 0 ? "Saved \(statsViewModel.statistics.formattedTrashedSavings)" : nil
            )
        }
    }

    // MARK: - Progress & Storage
    private var cullingProgressSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Culling Progress")
                    .font(.system(size: 13, weight: .semibold))
                Spacer()
                Text("\(Int(statsViewModel.statistics.cullingProgressPercentage))% Reviewed")
                    .font(.system(size: 12, weight: .medium).monospacedDigit())
                    .foregroundStyle(.secondary)
            }

            GeometryReader { geo in
                let total = max(1, statsViewModel.statistics.totalPhotoCount)
                let selectedWidth = (CGFloat(statsViewModel.statistics.selectedCount) / CGFloat(total)) * geo.size.width
                let copiedWidth = (CGFloat(statsViewModel.statistics.copiedCount) / CGFloat(total)) * geo.size.width
                let trashedWidth = (CGFloat(statsViewModel.statistics.trashedCount) / CGFloat(total)) * geo.size.width

                HStack(spacing: 2) {
                    Rectangle().fill(Color.green).frame(width: selectedWidth)
                    Rectangle().fill(Color.cyan).frame(width: copiedWidth)
                    Rectangle().fill(Color.red).frame(width: trashedWidth)
                    Rectangle().fill(Color.secondary.opacity(0.2)).frame(maxWidth: .infinity)
                }
                .clipShape(RoundedRectangle(cornerRadius: 4))
            }
            .frame(height: 8)

            HStack {
                Text("Total Footprint: \(statsViewModel.statistics.formattedTotalStorage)")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                Spacer()
                if statsViewModel.statistics.trashedStorageBytes > 0 {
                    Text("Recoverable Savings: \(statsViewModel.statistics.formattedTrashedSavings)")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.red)
                }
            }
        }
        .padding(14)
        .background(Color(NSColor.controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - Format Breakdown
    private var formatBreakdownCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("File Formats")
                .font(.system(size: 13, weight: .semibold))

            if statsViewModel.statistics.formatStatistics.isEmpty {
                Text("No files detected")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            } else {
                VStack(spacing: 8) {
                    ForEach(statsViewModel.statistics.formatStatistics) { format in
                        VStack(spacing: 4) {
                            HStack {
                                Text(format.displayName)
                                    .font(.system(size: 12, weight: .medium))
                                Spacer()
                                Text("\(format.count) (\(String(format: "%.1f", format.percentageOfTotal))%)")
                                    .font(.system(size: 11).monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }

                            GeometryReader { g in
                                ZStack(alignment: .leading) {
                                    Rectangle().fill(Color.secondary.opacity(0.15))
                                    Rectangle()
                                        .fill(Color.accentColor)
                                        .frame(width: max(2, (CGFloat(format.percentageOfTotal) / 100.0) * g.size.width))
                                }
                                .clipShape(Capsule())
                            }
                            .frame(height: 4)
                        }
                    }
                }
            }
        }
        .padding(14)
        .background(Color(NSColor.controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - Quality Metrics
    private var qualityMetricsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Quality Distribution")
                .font(.system(size: 13, weight: .semibold))

            HStack(spacing: 20) {
                VStack(spacing: 4) {
                    Text(statsViewModel.statistics.averageSharpness.map { String(format: "%.1f", $0) } ?? "–")
                        .font(.system(size: 24, weight: .bold, design: .rounded))
                        .foregroundStyle(Color.green)
                    Text("Avg Sharpness")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)

                Divider()
                    .frame(height: 36)

                VStack(spacing: 4) {
                    HStack(spacing: 2) {
                        Image(systemName: "star.fill")
                            .font(.system(size: 14))
                            .foregroundStyle(Color.yellow)
                        Text(statsViewModel.statistics.averageAesthetic.map { String(format: "%.1f", $0) } ?? "–")
                            .font(.system(size: 24, weight: .bold, design: .rounded))
                    }
                    Text("Avg Aesthetics")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
            }
            .padding(.vertical, 4)

            Divider()

            VStack(spacing: 6) {
                focusCategoryRow(category: .sharp, count: statsViewModel.statistics.focusDistribution[.sharp] ?? 0, color: .green)
                focusCategoryRow(category: .acceptable, count: statsViewModel.statistics.focusDistribution[.acceptable] ?? 0, color: .orange)
                focusCategoryRow(category: .blurry, count: statsViewModel.statistics.focusDistribution[.blurry] ?? 0, color: .red)
            }
        }
        .padding(14)
        .background(Color(NSColor.controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private func focusCategoryRow(category: SharpnessCategory, count: Int, color: Color) -> some View {
        HStack {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(category.rawValue.capitalized)
                .font(.system(size: 11))
            Spacer()
            Text("\(count)")
                .font(.system(size: 11, weight: .medium).monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Leaderboard
    private var leaderboardSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Leaderboards")
                    .font(.system(size: 13, weight: .semibold))

                Spacer()

                Picker("", selection: $statsViewModel.selectedLeaderboardTab) {
                    ForEach(LibraryStatisticsViewModel.LeaderboardTab.allCases) { tab in
                        Text(tab.rawValue).tag(tab)
                    }
                }
                .pickerStyle(.segmented)
                .frame(width: 180)
            }

            let items = (statsViewModel.selectedLeaderboardTab == .sharpness)
                ? statsViewModel.statistics.topSharpestPhotos
                : statsViewModel.statistics.topAestheticPhotos

            if items.isEmpty {
                Text("No scored photos available")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 16)
            } else {
                VStack(spacing: 8) {
                    ForEach(Array(items.enumerated()), id: \.element.id) { index, photo in
                        LeaderboardRow(
                            rank: index + 1,
                            photo: photo,
                            mode: statsViewModel.selectedLeaderboardTab,
                            onSelect: {
                                if let idx = workspaceViewModel.photos.firstIndex(where: { $0.id == photo.id }) {
                                    workspaceViewModel.selectPhoto(at: idx)
                                    dismiss()
                                }
                            }
                        )
                    }
                }
            }
        }
        .padding(14)
        .background(Color(NSColor.controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - Footer
    private var footerBar: some View {
        HStack {
            Text("Photographic Analysis Engine · Apple Vision & Accelerate")
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
            Spacer()
            Button("Close") {
                dismiss()
            }
            .controlSize(.small)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.ultraThinMaterial)
    }
}

// MARK: - Subviews

struct KPICard: View {
    let title: String
    let value: String
    let icon: String
    let tint: Color
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: icon)
                    .foregroundStyle(tint)
                    .font(.system(size: 12, weight: .semibold))
                Spacer()
            }
            Text(value)
                .font(.system(size: 20, weight: .bold, design: .rounded))
            Text(title)
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
            if let sub = subtitle {
                Text(sub)
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(tint)
                    .lineLimit(1)
            }
        }
        .padding(10)
        .background(Color(NSColor.controlBackgroundColor))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

struct LeaderboardRow: View {
    let rank: Int
    let photo: PhotoItem
    let mode: LibraryStatisticsViewModel.LeaderboardTab
    let onSelect: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text("#\(rank)")
                .font(.system(size: 12, weight: .bold, design: .monospaced))
                .foregroundStyle(rank == 1 ? Color.yellow : Color.secondary)
                .frame(width: 24, alignment: .leading)

            VStack(alignment: .leading, spacing: 2) {
                Text(photo.filename)
                    .font(.system(size: 12, weight: .medium))
                    .lineLimit(1)

                if let exif = photo.exif {
                    Text("\(exif.formattedShutterSpeed) · \(exif.formattedAperture) · \(exif.formattedISO)")
                        .font(.system(size: 10).monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            if mode == .sharpness, let sharpness = photo.scores?.sharpness {
                HStack(spacing: 4) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(Color.green)
                        .font(.system(size: 11))
                    Text(String(format: "%.1f", sharpness))
                        .font(.system(size: 12, weight: .bold).monospacedDigit())
                }
            } else if mode == .aesthetics, let aesthetic = photo.scores?.aesthetic {
                HStack(spacing: 4) {
                    Image(systemName: "star.fill")
                        .foregroundStyle(Color.yellow)
                        .font(.system(size: 11))
                    Text(String(format: "%.1f", aesthetic))
                        .font(.system(size: 12, weight: .bold).monospacedDigit())
                }
            }

            Button("Jump to Photo") {
                onSelect()
            }
            .buttonStyle(.link)
            .font(.system(size: 11))
        }
        .padding(.vertical, 4)
        .padding(.horizontal, 8)
        .background(Color.white.opacity(0.04))
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}
