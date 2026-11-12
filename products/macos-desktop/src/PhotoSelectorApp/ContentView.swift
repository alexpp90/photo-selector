import SwiftUI
import PhotoSelectorKit

/// Primary culling workspace window content view.
/// Coordinates the top navigation bar, full-canvas preview (>80% window area),
/// prominent tactile culling action bar, collapsible filmstrip, zero-latency keyboard routing,
/// drop target ingestion, and modal sheets.
public struct ContentView: View {
    @ObservedObject public var viewModel: CullingWorkspaceViewModel
    @StateObject private var keyboardRouter = KeyboardShortcutRouter()
    @FocusState private var isWorkspaceFocused: Bool

    /// Minimum pointer hit target (pt) for toolbar controls — the desktop floor `layout_lint` R5 enforces.
    private static let minTarget: CGFloat = 24

    public init(viewModel: CullingWorkspaceViewModel = CullingWorkspaceViewModel()) {
        self.viewModel = viewModel
    }

    public var body: some View {
        GeometryReader { geometry in
            ZStack {
                // Neutral canvas (LensjetPalette.canvas)
                Color.lensjetCanvas
                    .ignoresSafeArea()

                if viewModel.isLoading && viewModel.photos.isEmpty {
                    FolderLoadingView(
                        folderURL: viewModel.rootDirectoryURL,
                        onCancel: { viewModel.cancelLoading() }
                    )
                } else if viewModel.photos.isEmpty {
                    EmptyDropTargetView(
                        isTargeted: viewModel.isDropTargeted,
                        onOpenFolder: { viewModel.presentFolderPicker() }
                    )
                } else {
                    VStack(spacing: 0) {
                        // MARK: - 1. Top Control & Navigation Bar
                        topNavigationBar

                        // MARK: - 2. Main Preview Canvas
                        ZStack {
                            // The action toast and the zoom-exit pill are drawn by the active slot itself,
                            // inside its image rectangle (REQ-MAC-LAYOUT.05): never over a header, EXIF
                            // strip, metadata panel, the toolbar or the action dock.
                            Group {
                                switch viewModel.comparisonMode {
                                case .single:
                                    if let photo = viewModel.currentPhoto {
                                        SinglePhotoView(
                                            photo: photo,
                                            isHUDVisible: viewModel.isInfoHUDVisible,
                                            zoomState: viewModel.zoomState,
                                            onToggleZoom: { viewModel.toggleZoom() },
                                            toast: viewModel.currentToast,
                                            onExitZoom: { viewModel.resetZoomToFit() },
                                            onPinchEnded: { viewModel.setPinchMagnification($0) },
                                            onUpdateExif: { id, exif in
                                                viewModel.updatePhotoExif(photoID: id, exif: exif)
                                            },
                                            onUpdateScores: { id, scores in
                                                viewModel.updatePhotoScores(photoID: id, scores: scores)
                                            }
                                        )
                                    }
                                case .sideBySide, .triplet:
                                    ComparisonView(
                                        mode: viewModel.comparisonMode,
                                        previousPhoto: viewModel.previousPhoto,
                                        currentPhoto: viewModel.currentPhoto,
                                        nextPhoto: viewModel.nextPhoto,
                                        photos: viewModel.comparisonSlotPhotos,
                                        activeSlotIndex: viewModel.activeSlotIndex,
                                        isZoomSynced: viewModel.isZoomSynced,
                                        isHUDVisible: viewModel.isInfoHUDVisible,
                                        zoomState: viewModel.zoomState,
                                        onSelectSlot: { idx in viewModel.setActiveSlot(idx) },
                                        onToggleZoom: { viewModel.toggleZoom() },
                                        toast: viewModel.currentToast,
                                        onExitZoom: { viewModel.resetZoomToFit() },
                                        onPinchEnded: { viewModel.setPinchMagnification($0) },
                                        onUpdateExif: { id, exif in
                                            viewModel.updatePhotoExif(photoID: id, exif: exif)
                                        },
                                        onUpdateScores: { id, scores in
                                            viewModel.updatePhotoScores(photoID: id, scores: scores)
                                        }
                                    )
                                }
                            }
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        // The end of the cull sits over the preview, never over the dock below it.
                        .overlay { CullEndOverlay(viewModel: viewModel) }

                        // MARK: - 3. Prominent Culling Action Bar
                        CullingActionBar(viewModel: viewModel)

                        // MARK: - 4. Collapsible Bottom Filmstrip
                        if viewModel.isFilmstripVisible {
                            FilmstripView(
                                photos: viewModel.photos,
                                currentIndex: viewModel.currentIndex,
                                comparisonIndices: viewModel.comparisonSlotIndices,
                                onSelectPhoto: { idx in viewModel.selectPhoto(at: idx) }
                            )
                            .frame(height: min(84, geometry.size.height * 0.12))
                            .uiTestFrame("filmstrip")
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                    }
                }
            }
            .onDrop(of: [.fileURL], isTargeted: $viewModel.isDropTargeted) { providers in
                handleDrop(providers: providers)
            }
        }
        .frame(minWidth: 960, minHeight: 640)
        .preferredColorScheme(.dark)
        .navigationTitle(viewModel.rootDirectoryURL != nil ? "Lensjet — \(viewModel.rootDirectoryURL!.lastPathComponent)" : "Lensjet")
        .focusable()
        .focusEffectDisabled()
        .focused($isWorkspaceFocused)
        .defaultFocus($isWorkspaceFocused, true)
        .onAppear {
            isWorkspaceFocused = true
            keyboardRouter.attach(viewModel: viewModel)
            #if PST_TEST_HOOKS
            UISelfTestHooks.keyboardRouter = keyboardRouter
            #endif
        }
        .onDisappear {
            keyboardRouter.detach()
        }
        // Fallback SwiftUI Zero-Latency Keyboard Routing
        .onKeyPress(.rightArrow) {
            viewModel.navigateToNext()
            return .handled
        }
        .onKeyPress(.leftArrow) {
            viewModel.navigateToPrevious()
            return .handled
        }
        .onKeyPress(.upArrow) {
            viewModel.navigateToPrevious()
            return .handled
        }
        .onKeyPress(.downArrow) {
            viewModel.navigateToNext()
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "mM"), phases: .down) { _ in
            viewModel.cullCurrentPhoto(action: .move)
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "cC"), phases: .down) { _ in
            viewModel.cullCurrentPhoto(action: .copy)
            return .handled
        }
        .onKeyPress(.delete, phases: .down) { _ in
            viewModel.cullCurrentPhoto(action: .trash)
            return .handled
        }
        .onKeyPress(.deleteForward, phases: .down) { _ in
            viewModel.cullCurrentPhoto(action: .trash)
            return .handled
        }
        .onKeyPress(.space) {
            viewModel.toggleZoom()
            return .handled
        }
        .onKeyPress(.escape) {
            (viewModel.dismissStickyToast() || viewModel.resetZoomToFit()) ? .handled : .ignored
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "1")) { _ in
            viewModel.setComparisonMode(.single)
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "2")) { _ in
            viewModel.setComparisonMode(.sideBySide)
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "3")) { _ in
            viewModel.setComparisonMode(.triplet)
            return .handled
        }
        .onKeyPress(.tab) {
            viewModel.advanceComparisonSlotFocus() ? .handled : .ignored
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "fFtT\\")) { _ in
            MotionPolicy.animate(.spring(response: 0.28, dampingFraction: 0.82)) {
                viewModel.isFilmstripVisible.toggle()
            }
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "iI")) { _ in
            MotionPolicy.animate(.easeInOut(duration: 0.18)) {
                viewModel.isInfoHUDVisible.toggle()
            }
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "sS")) { _ in
            viewModel.toggleZoomSync()
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "gG")) { _ in
            viewModel.presentGoToPhoto()
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "?/")) { _ in
            viewModel.presentShortcutsHelp()
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "oO"), phases: .down) { press in
            if press.modifiers.contains(.command) {
                viewModel.presentFolderPicker()
                return .handled
            }
            return .ignored
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "zZ"), phases: .down) { press in
            if press.modifiers.contains(.command) {
                viewModel.undoLastAction()
                return .handled
            }
            return .ignored
        }
        .sheet(isPresented: $viewModel.isShortcutsHelpPresented) {
            ShortcutsHelpSheet(destination: viewModel.destinationName)
        }
        .sheet(isPresented: $viewModel.isGoToPhotoPresented) {
            GoToPhotoSheet(viewModel: viewModel)
        }
        // VoiceOver hears what a sighted user reads in the toast (REQ-MAC-A11Y.01).
        .onChange(of: viewModel.currentToast) { _, toast in
            if let toast {
                AccessibilityNotification.Announcement(toast.title).post()
            }
        }
        .sheet(isPresented: $viewModel.isDuplicateFinderPresented) {
            DuplicateFinderView(
                targetDirectoryURL: viewModel.rootDirectoryURL,
                photos: viewModel.photos,
                onPhotoTrashed: { trashedURL in
                    viewModel.markPhotoAsTrashed(url: trashedURL)
                },
                trashHandler: { url in try await viewModel.trashDuplicate(at: url) }
            )
        }
        .sheet(isPresented: $viewModel.isLibraryStatisticsPresented) {
            LibraryStatisticsView(workspaceViewModel: viewModel)
        }
        // Cloud folders: nothing reads every photo, and nothing is deleted for good, without a yes.
        .alert(
            "Download all photos?",
            isPresented: Binding(
                get: {
                    if case .lowestSharpnessSort = viewModel.pendingDownloadConfirmation?.purpose { return true }
                    return false
                },
                set: { if !$0 { viewModel.declineDownloadAll() } }
            ),
            presenting: viewModel.pendingDownloadConfirmation
        ) { _ in
            Button("Download") { viewModel.confirmDownloadAll() }
            Button("Cancel", role: .cancel) { viewModel.declineDownloadAll() }
        } message: { pending in
            Text("About \(ByteCountFormatter.string(fromByteCount: pending.estimatedBytes, countStyle: .file)) will download.")
        }
        .alert(
            "Delete permanently?",
            isPresented: Binding(
                get: { viewModel.pendingPermanentDelete != nil },
                set: { if !$0 { viewModel.cancelPermanentDelete() } }
            ),
            presenting: viewModel.pendingPermanentDelete
        ) { _ in
            Button("Delete", role: .destructive) { viewModel.confirmPermanentDelete() }
            Button("Cancel", role: .cancel) { viewModel.cancelPermanentDelete() }
        } message: { pending in
            Text(pending.message)
        }
    }

    // MARK: - Top Navigation Bar

    /// The toolbar is rendered in up to three densities (`ChromeDensity`). `ViewThatFits`
    /// measures each row's ideal width and shows the first that fits, so no label ever wraps
    /// or truncates: at narrow widths items collapse to icon-only (tooltip + accessibility
    /// label keep the full name and shortcut), per the macOS HIG.
    private var topNavigationBar: some View {
        ViewThatFits(in: .horizontal) {
            toolbarRow(.full)
            toolbarRow(.compact)
            toolbarRow(.minimal)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .background(Color(NSColor.windowBackgroundColor).opacity(0.96))
        .overlay(Divider(), alignment: .bottom)
    }

    private func toolbarRow(_ density: ChromeDensity) -> some View {
        HStack(spacing: density == .full ? 12 : 8) {
            // Folder Picker Button
            Button(action: { viewModel.presentFolderPicker() }) {
                HStack(spacing: 6) {
                    Image(systemName: "folder.badge.plus")
                        .font(.system(size: 13, weight: .semibold))
                    if density.showsOpenFolderTitle {
                        Text("Open Folder…")
                            .font(.system(size: 12, weight: .semibold))
                            .lineLimit(1)
                            .fixedSize()
                    }
                }
                .padding(.horizontal, density.showsOpenFolderTitle ? 10 : 6)
                .padding(.vertical, 5)
                .frame(minWidth: Self.minTarget, minHeight: Self.minTarget)
            }
            .buttonStyle(.bordered)
            .focusable(false)
            .accessibilityLabel("Open Folder")
            .help(LensjetCommand.openFolder.tooltip())

            // Current Directory / Volume Name
            if let rootURL = viewModel.rootDirectoryURL, density.showsFolderName {
                HStack(spacing: 5) {
                    Image(systemName: "sdcard.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(Color.accentColor)
                    Text(rootURL.lastPathComponent)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Color.white)
                        .lineLimit(1)
                        .fixedSize()
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.white.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 5))
                .help(rootURL.path)
            }

            // Cloud folder: photos download when needed (REQ-MAC-CLOUD.01); icon-only at minimal density.
            if viewModel.cloudBadgeText != nil {
                HStack(spacing: 4) {
                    Image(systemName: "icloud")
                        .font(.system(size: 12))
                    if density != .minimal {
                        Text("Cloud")
                            .font(.system(size: 11, weight: .semibold))
                            .lineLimit(1)
                            .fixedSize()
                    }
                }
                .foregroundStyle(Color.lensjetTextSecondary)
                .padding(.horizontal, 7)
                .padding(.vertical, 4)
                .background(Color.white.opacity(0.08))
                .clipShape(Capsule())
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Cloud folder, photos download when needed")
                .help("Cloud folder: photos download when needed")
                .uiTestFrame("toolbar_cloud_badge")
            }

            // Active Ingestion Scanning Pill
            if viewModel.isLoading {
                HStack(spacing: 6) {
                    ProgressView()
                        .controlSize(.small)
                    if let scanning = density.scanningText(photoCount: viewModel.photos.count) {
                        Text(scanning)
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(Color.accentColor)
                            .lineLimit(1)
                            .fixedSize()
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.accentColor.opacity(0.15))
                .clipShape(Capsule())
                .help("Scanning… (\(viewModel.photos.count) photos)")
            }

            // Scoring pass over the whole library (lowest sharpness first, Library Statistics)
            if let progress = viewModel.scoringProgress {
                HStack(spacing: 6) {
                    ProgressView()
                        .controlSize(.small)
                    if density != .minimal {
                        Text(density == .full ? "Scoring \(progress.text)" : progress.text)
                            .font(.system(size: 11, weight: .semibold).monospacedDigit())
                            .foregroundStyle(Color.accentColor)
                            .lineLimit(1)
                            .fixedSize()
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.accentColor.opacity(0.15))
                .clipShape(Capsule())
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Scoring photos, \(progress.done) of \(progress.total)")
                .help("Scoring sharpness: \(progress.text)")
                .uiTestFrame("scoring_progress")
            }

            // Active sort order, shown only when it is not the default (glyph + word, never colour alone)
            if viewModel.sortOrder != WorkspaceSortOrder.defaultOrder {
                HStack(spacing: 5) {
                    Image(systemName: viewModel.sortOrder.symbol)
                        .font(.system(size: 11, weight: .bold))
                    if density == .full {
                        Text(viewModel.sortOrder.title)
                            .font(.system(size: 11, weight: .semibold))
                            .lineLimit(1)
                            .fixedSize()
                    }
                }
                .foregroundStyle(Color.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .frame(minHeight: Self.minTarget - 4)
                .background(Color.white.opacity(0.12))
                .clipShape(Capsule())
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Sorted by \(viewModel.sortOrder.title)")
                .help("Sorted by \(viewModel.sortOrder.title) (View > Sort By)")
                .uiTestFrame("sort_indicator")
            }

            Spacer(minLength: 8)

            // Integrated Navigation Cluster (Prev / Next & Position)
            HStack(spacing: 6) {
                Button(action: { viewModel.navigateToPrevious() }) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Color.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 4)
                        .frame(minWidth: Self.minTarget, minHeight: Self.minTarget)
                }
                .buttonStyle(.bordered)
                .focusable(false)
                .disabled(viewModel.photos.isEmpty || viewModel.currentIndex == 0)
                .uiTestFrame("toolbar_previous")
                .accessibilityLabel(LensjetCommand.previousPhoto.name())
                .help("\(LensjetCommand.previousPhoto.name()) (\u{2190})")

                if !viewModel.photos.isEmpty {
                    Text(density.positionText(index: viewModel.currentIndex + 1, total: viewModel.photos.count))
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color.white.opacity(0.9))
                        .lineLimit(1)
                        .fixedSize()
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.black.opacity(0.45))
                        .clipShape(Capsule())
                        .accessibilityLabel("Photo \(viewModel.currentIndex + 1) of \(viewModel.photos.count)")
                        .uiTestFrame("toolbar_position")
                }

                Button(action: { viewModel.navigateToNext() }) {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Color.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 4)
                        .frame(minWidth: Self.minTarget, minHeight: Self.minTarget)
                }
                .buttonStyle(.bordered)
                .focusable(false)
                .disabled(viewModel.photos.isEmpty || viewModel.currentIndex >= viewModel.photos.count - 1)
                .uiTestFrame("toolbar_next")
                .accessibilityLabel(LensjetCommand.nextPhoto.name())
                .help("\(LensjetCommand.nextPhoto.name()) (\u{2192})")
            }

            Divider()
                .frame(height: 18)

            // Comparison View Mode Switcher
            HStack(spacing: 2) {
                modeSegmentButton(mode: .single, shortcut: 1, icon: "rectangle.fill", density: density)
                modeSegmentButton(mode: .sideBySide, shortcut: 2, icon: "rectangle.split.2x1.fill", density: density)
                modeSegmentButton(mode: .triplet, shortcut: 3, icon: "rectangle.split.1x2.fill", density: density)
            }
            .padding(2)
            .background(Color.black.opacity(0.3))
            .clipShape(RoundedRectangle(cornerRadius: 6))

            Divider()
                .frame(height: 18)

            // View Toggles
            HStack(spacing: 6) {
                // Pan link toggle lives in the toolbar (not floating over the photos), so it can
                // never cover a slot's score badge in 2-Up or Focus 3-Up.
                if viewModel.comparisonMode != .single {
                    Button(action: { viewModel.toggleZoomSync() }) {
                        Image(systemName: viewModel.isZoomSynced ? "link" : "link.badge.slash")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(viewModel.isZoomSynced ? Color.accentColor : Color.secondary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 4)
                            .frame(minWidth: Self.minTarget, minHeight: Self.minTarget)
                    }
                    .buttonStyle(.bordered)
                    .focusable(false)
                    .uiTestFrame("toolbar_pan_link")
                    .accessibilityLabel(viewModel.isZoomSynced ? "Link Pan on" : "Link Pan off")
                    .help("\(LensjetCommand.linkPan.tooltip()), \(viewModel.isZoomSynced ? "on" : "off")")
                }

                Button(action: { viewModel.toggleZoom() }) {
                    HStack(spacing: 3) {
                        Image(systemName: viewModel.zoomState.isZoomed ? "arrow.down.right.and.arrow.up.left" : "magnifyingglass")
                            .font(.system(size: 11, weight: .medium))
                        Text(viewModel.zoomState.isZoomed ? "Fit" : "1:1")
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .lineLimit(1)
                            .fixedSize()
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 4)
                    .frame(minHeight: Self.minTarget)
                }
                .buttonStyle(.bordered)
                .focusable(false)
                .uiTestFrame("toolbar_zoom")
                .accessibilityLabel(viewModel.zoomState.isZoomed ? "Fit" : LensjetCommand.zoom100.name())
                .help(viewModel.zoomState.isZoomed ? "Fit (Space or Esc)" : LensjetCommand.zoom100.tooltip())

                Button(action: {
                    MotionPolicy.animate(.spring(response: 0.28, dampingFraction: 0.82)) {
                        viewModel.isFilmstripVisible.toggle()
                    }
                }) {
                    Image(systemName: viewModel.isFilmstripVisible ? "film.fill" : "film")
                        .font(.system(size: 12))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 4)
                        .frame(minWidth: Self.minTarget, minHeight: Self.minTarget)
                }
                .buttonStyle(.bordered)
                .focusable(false)
                .accessibilityLabel(LensjetCommand.filmstripMenuTitle(isShown: viewModel.isFilmstripVisible))
                .help(LensjetCommand.filmstrip.tooltip())

                Button(action: {
                    MotionPolicy.animate(.easeInOut(duration: 0.18)) {
                        viewModel.isInfoHUDVisible.toggle()
                    }
                }) {
                    Image(systemName: viewModel.isInfoHUDVisible ? "info.circle.fill" : "info.circle")
                        .font(.system(size: 12))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 4)
                        .frame(minWidth: Self.minTarget, minHeight: Self.minTarget)
                }
                .buttonStyle(.bordered)
                .focusable(false)
                .accessibilityLabel(LensjetCommand.infoMenuTitle(isShown: viewModel.isInfoHUDVisible))
                .help(LensjetCommand.info.tooltip())

                Button(action: { viewModel.presentShortcutsHelp() }) {
                    Image(systemName: "questionmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Color.lensjetOnSelection)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 4)
                        .frame(minWidth: Self.minTarget, minHeight: Self.minTarget)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.accentColor)
                .focusable(false)
                .accessibilityLabel(LensjetCommand.keyboardShortcuts.name())
                .help(LensjetCommand.keyboardShortcuts.tooltip())
            }
        }
    }

    private func modeSegmentButton(mode: ComparisonMode, shortcut: Int, icon: String, density: ChromeDensity) -> some View {
        let isSelected = viewModel.comparisonMode == mode
        return Button(action: { viewModel.setComparisonMode(mode) }) {
            HStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.system(size: 11))
                if let label = density.modeLabel(shortcut: shortcut) {
                    Text(label)
                        .font(.system(size: 11, weight: isSelected ? .bold : .medium))
                        .lineLimit(1)
                        .fixedSize()
                }
                if density.showsModeShortcut {
                    Text("(\(shortcut))")
                        .font(.system(size: 9, design: .monospaced))
                        .opacity(0.7)
                        .lineLimit(1)
                        .fixedSize()
                }
            }
            .padding(.horizontal, density == .minimal ? 6 : 8)
            .padding(.vertical, 4)
            .frame(minWidth: Self.minTarget, minHeight: Self.minTarget)
            .background(isSelected ? Color.accentColor : Color.clear)
            .foregroundStyle(isSelected ? Color.lensjetOnSelection : Color.primary)
            .clipShape(RoundedRectangle(cornerRadius: 5))
        }
        .buttonStyle(.plain)
        .focusable(false)
        .uiTestFrame("toolbar_mode_\(shortcut)")
        .accessibilityLabel(ChromeDensity.modeAccessibilityName(shortcut: shortcut))
        .help(ChromeDensity.modeAccessibilityName(shortcut: shortcut))
    }

    private func handleDrop(providers: [NSItemProvider]) -> Bool {
        for provider in providers {
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                if let url {
                    Task { @MainActor in
                        _ = viewModel.handleDroppedURLs([url])
                    }
                }
            }
        }
        return true
    }
}
