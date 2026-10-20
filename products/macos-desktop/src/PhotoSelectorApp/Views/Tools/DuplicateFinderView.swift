import SwiftUI
import AppKit
import PhotoSelectorKit

/// Native macOS two-column dark studio modal sheet for duplicate photo discovery, comparison, and resolution.
public struct DuplicateFinderView: View {
    @StateObject private var viewModel: DuplicateFinderViewModel
    @Environment(\.dismiss) private var dismiss

    public init(
        targetDirectoryURL: URL? = nil,
        photos: [PhotoItem] = [],
        onPhotoTrashed: ((URL) -> Void)? = nil
    ) {
        _viewModel = StateObject(wrappedValue: DuplicateFinderViewModel(
            targetDirectoryURL: targetDirectoryURL,
            photos: photos,
            onPhotoTrashed: onPhotoTrashed
        ))
    }

    public var body: some View {
        VStack(spacing: 0) {
            headerBar
            Divider()
                .background(Color.white.opacity(0.12))

            Group {
                switch viewModel.scanState {
                case .idle:
                    welcomeView
                case .scanning(let phase):
                    scanningView(phase: phase)
                case .empty:
                    emptyView
                case .error(let msg):
                    errorView(message: msg)
                case .completed:
                    completedView
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(minWidth: 880, idealWidth: 1080, maxWidth: 1400, minHeight: 620, idealHeight: 760, maxHeight: 1000)
        .background(Color.lensjetSurface)
        .preferredColorScheme(.dark)
        .onAppear {
            if viewModel.targetDirectoryURL != nil && viewModel.duplicateGroups.isEmpty {
                viewModel.startScan()
            }
        }
        .overlay(alignment: .bottom) {
            if let toast = viewModel.toastMessage {
                Text(toast)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(.ultraThinMaterial, in: Capsule())
                    .overlay(Capsule().stroke(Color.white.opacity(0.2), lineWidth: 0.5))
                    .padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .confirmationDialog(
            "Move \(viewModel.markedForTrashCount) duplicate files to Trash?",
            isPresented: $viewModel.showingTrashConfirmation,
            titleVisibility: .visible
        ) {
            Button("Move to Trash", role: .destructive) {
                viewModel.trashMarkedDuplicates()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Selected files and their associated companion files (RAW+JPEG pairs and XMP sidecars) will be moved to the macOS system trash.")
        }
    }

    // MARK: - Header Bar
    private var headerBar: some View {
        HStack(spacing: 12) {
            Image(systemName: "doc.on.doc.fill")
                .font(.system(size: 16))
                .foregroundStyle(Color.accentColor)

            VStack(alignment: .leading, spacing: 2) {
                Text("Duplicate Finder")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white)

                if let url = viewModel.targetDirectoryURL {
                    Text(url.path)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }

            Button(action: selectFolder) {
                Label("Change Folder...", systemImage: "folder")
                    .font(.system(size: 11))
            }
            .buttonStyle(.bordered)
            .controlSize(.small)

            Spacer()

            if viewModel.scanState == .completed {
                Menu {
                    Button("Keep Newest in All Groups") {
                        viewModel.keepNewest()
                    }
                    Button("Keep Highest Score in All Groups") {
                        Task { await viewModel.keepHighestScore() }
                    }
                } label: {
                    Label("Auto-Resolve...", systemImage: "sparkles")
                        .font(.system(size: 12))
                }
                .menuStyle(.borderedButton)
                .controlSize(.small)

                if viewModel.markedForTrashCount > 0 {
                    Button(role: .destructive, action: { viewModel.showingTrashConfirmation = true }) {
                        Label("Trash Marked (\(viewModel.markedForTrashCount))", systemImage: "trash.fill")
                            .font(.system(size: 12, weight: .medium))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.red)
                    .controlSize(.small)
                }
            }

            Button("Done") {
                dismiss()
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)
            .keyboardShortcut(.defaultAction)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
        .background(Color.lensjetPanel)
    }

    // MARK: - Completed Two-Column View
    private var completedView: some View {
        NavigationSplitView {
            VStack(spacing: 0) {
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(.secondary)
                    TextField("Filter duplicates...", text: $viewModel.filterQuery)
                        .textFieldStyle(.plain)
                        .font(.system(size: 12))
                }
                .padding(8)
                .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
                .padding(12)

                HStack {
                    Text("\(viewModel.duplicateGroups.count) Groups")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text("\(viewModel.formattedTotalWastedBytes) wasted")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(.orange)
                }
                .padding(.horizontal, 14)
                .padding(.bottom, 6)

                Divider().background(Color.white.opacity(0.08))

                List(selection: $viewModel.selectedGroupID) {
                    ForEach(viewModel.filteredGroups) { group in
                        GroupRowView(group: group)
                            .tag(group.id)
                    }
                }
                .listStyle(.sidebar)
            }
            .navigationSplitViewColumnWidth(min: 280, ideal: 340, max: 400)
        } detail: {
            if let group = viewModel.selectedGroup {
                GroupDetailView(group: group, viewModel: viewModel)
            } else {
                Text("Select a duplicate group to resolve")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Welcome View
    private var welcomeView: some View {
        VStack(spacing: 16) {
            Image(systemName: "folder.badge.questionmark")
                .font(.system(size: 48))
                .foregroundStyle(Color.accentColor)

            Text("Find Duplicate Photos")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)

            Text("Scan an SD card or photo folder to find identical photos\nmatching by cryptographic SHA256 checksum.")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Button(action: selectFolder) {
                Label("Select Folder to Scan...", systemImage: "folder")
                    .font(.system(size: 13, weight: .medium))
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.regular)
            .padding(.top, 8)
        }
    }

    // MARK: - Scanning View
    private func scanningView(phase: String) -> some View {
        VStack(spacing: 16) {
            ProgressView()
                .controlSize(.large)

            Text(phase)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(.white)

            Button("Cancel") {
                viewModel.cancelScan()
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
        }
    }

    // MARK: - Empty State
    private var emptyView: some View {
        VStack(spacing: 16) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 48))
                .foregroundStyle(.green)

            Text("No Duplicates Found")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)

            Text("All scanned photos have distinct content.")
                .font(.system(size: 13))
                .foregroundStyle(.secondary)

            Button(action: selectFolder) {
                Label("Scan Another Folder...", systemImage: "folder")
            }
            .buttonStyle(.bordered)
            .controlSize(.regular)
        }
    }

    // MARK: - Error View
    private func errorView(message: String) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 48))
                .foregroundStyle(.red)

            Text("Scan Failed")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)

            Text(message)
                .font(.system(size: 13))
                .foregroundStyle(.secondary)

            Button("Try Again") {
                viewModel.startScan()
            }
            .buttonStyle(.bordered)
        }
    }

    private func selectFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.prompt = "Choose Folder"
        panel.title = "Select Folder to Scan for Duplicates"

        if panel.runModal() == .OK, let selectedURL = panel.url {
            viewModel.startScan(directoryURL: selectedURL)
        }
    }
}

// MARK: - Subcomponents

private struct GroupDetailView: View {
    let group: DuplicateGroupModel
    @ObservedObject var viewModel: DuplicateFinderViewModel

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 8) {
                        Text("Duplicate Cluster")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.white)

                        Text("SHA256: \(group.hash.prefix(12))...")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 4))
                    }

                    Text("\(group.activeEntries.count) copies • \(group.formattedFileSize) each • \(group.formattedWastedBytes) recoverable")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Button("Keep Newest") {
                    viewModel.keepNewest(in: group.id)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)

                Button("Keep Highest Score") {
                    Task { await viewModel.keepHighestScore(in: group.id) }
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(Color.white.opacity(0.03))

            Divider().background(Color.white.opacity(0.08))

            ScrollView(.horizontal, showsIndicators: true) {
                HStack(spacing: 20) {
                    ForEach(group.entries) { entry in
                        DuplicateCardView(
                            entry: entry,
                            groupID: group.id,
                            viewModel: viewModel
                        )
                    }
                }
                .padding(24)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Color.lensjetCanvas)
    }
}

@MainActor
final class DuplicateCardViewState: ObservableObject {
    @Published var thumbnailImage: NSImage?
}

private struct DuplicateCardView: View {
    let entry: DuplicateFileEntry
    let groupID: String
    @ObservedObject var viewModel: DuplicateFinderViewModel
    @StateObject private var state = DuplicateCardViewState()

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack(alignment: .topTrailing) {
                Group {
                    if let img = state.thumbnailImage {
                        Image(nsImage: img)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                    } else {
                        Rectangle()
                            .fill(Color.white.opacity(0.05))
                            .overlay(ProgressView().controlSize(.small))
                    }
                }
                .frame(width: 280, height: 210)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .background(Color.black)

                if entry.status == .trashed {
                    Text("TRASHED")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.gray, in: Capsule())
                        .padding(8)
                } else if entry.isMarkedForTrash {
                    Text("WILL TRASH")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.red, in: Capsule())
                        .padding(8)
                } else {
                    Text("KEEP")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.green, in: Capsule())
                        .padding(8)
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(entry.filename)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)

                Text(entry.relativePath)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)

                HStack(spacing: 8) {
                    Label(entry.formattedDate, systemImage: "calendar")
                    Spacer()
                    Text(entry.formattedFileSize)
                }
                .font(.system(size: 11))
                .foregroundStyle(.secondary)

                if let scores = entry.scores {
                    HStack(spacing: 6) {
                        Text("Focus: \(String(format: "%.1f", scores.sharpness))")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundStyle(scores.sharpness >= 70 ? .green : (scores.sharpness >= 35 ? .orange : .red))
                        if let aesthetic = scores.aesthetic {
                            Text("Aesthetic: \(String(format: "%.1f", aesthetic))")
                                .font(.system(size: 10, weight: .medium))
                                .foregroundStyle(.cyan)
                        }
                    }
                    .padding(.top, 2)
                }
            }

            Divider().background(Color.white.opacity(0.1))

            HStack(spacing: 8) {
                if entry.status != .trashed {
                    Button(action: { viewModel.markToKeep(entryID: entry.url, in: groupID) }) {
                        Label("Keep This", systemImage: entry.isMarkedForTrash ? "circle" : "checkmark.circle.fill")
                            .font(.system(size: 11))
                    }
                    .buttonStyle(.bordered)
                    .tint(entry.isMarkedForTrash ? .gray : .green)
                    .controlSize(.small)

                    Button(action: { viewModel.trashSingleEntry(entry: entry, in: groupID) }) {
                        Image(systemName: "trash")
                            .font(.system(size: 11))
                    }
                    .buttonStyle(.bordered)
                    .tint(.red)
                    .controlSize(.small)
                    .help("Move this duplicate to Trash immediately")
                }

                Spacer()

                Button(action: { NSWorkspace.shared.activateFileViewerSelecting([entry.url]) }) {
                    Image(systemName: "arrow.up.forward.app")
                        .font(.system(size: 11))
                }
                .buttonStyle(.borderless)
                .help("Reveal in Finder")
            }
        }
        .padding(14)
        .frame(width: 308)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.lensjetPanel)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(
                    entry.isMarkedForTrash ? Color.red.opacity(0.6) : (entry.status == .trashed ? Color.gray.opacity(0.3) : Color.white.opacity(0.12)),
                    lineWidth: entry.isMarkedForTrash ? 1.5 : 0.8
                )
        )
        .task {
            if let cgImage = await ThumbnailLoader.shared.loadThumbnail(for: entry.url, maxPixelSize: ThumbnailTier.filmstrip.rawValue) {
                self.state.thumbnailImage = NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
            }
        }
    }
}

private struct GroupRowView: View {
    let group: DuplicateGroupModel

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "photo.stack.fill")
                .font(.system(size: 14))
                .foregroundStyle(Color.accentColor)

            VStack(alignment: .leading, spacing: 2) {
                Text(group.entries.first?.filename ?? "Duplicate Group")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.white)
                    .lineLimit(1)

                Text("\(group.activeEntries.count) files • \(group.formattedWastedBytes) wasted")
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
            }

            Spacer()

            if group.isResolved {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(.green)
            } else if group.entries.contains(where: { $0.isMarkedForTrash }) {
                Image(systemName: "trash.circle.fill")
                    .font(.system(size: 12))
                    .foregroundStyle(.red)
            }
        }
        .padding(.vertical, 4)
    }
}
