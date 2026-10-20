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
        .onKeyPress(characters: CharacterSet(charactersIn: "mM")) { _ in
            viewModel.cullCurrentPhoto(action: .move)
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "cC")) { _ in
            viewModel.cullCurrentPhoto(action: .copy)
            return .handled
        }
        .onKeyPress(.delete) {
            viewModel.cullCurrentPhoto(action: .trash)
            return .handled
        }
        .onKeyPress(.deleteForward) {
            viewModel.cullCurrentPhoto(action: .trash)
            return .handled
        }
        .onKeyPress(.space) {
            viewModel.toggleZoom()
            return .handled
        }
        .onKeyPress(.escape) {
            viewModel.resetZoomToFit() ? .handled : .ignored
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
            viewModel.advanceComparisonSlotFocus()
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "fF\\")) { _ in
            withAnimation(.spring(response: 0.28, dampingFraction: 0.82)) {
                viewModel.isFilmstripVisible.toggle()
            }
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "iI")) { _ in
            withAnimation(.easeInOut(duration: 0.18)) {
                viewModel.isInfoHUDVisible.toggle()
            }
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "sS")) { _ in
            viewModel.toggleZoomSync()
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
            ShortcutsHelpSheet()
        }
        .sheet(isPresented: $viewModel.isDuplicateFinderPresented) {
            DuplicateFinderView(
                targetDirectoryURL: viewModel.rootDirectoryURL,
                photos: viewModel.photos,
                onPhotoTrashed: { trashedURL in
                    viewModel.markPhotoAsTrashed(url: trashedURL)
                }
            )
        }
        .sheet(isPresented: $viewModel.isLibraryStatisticsPresented) {
            LibraryStatisticsView(workspaceViewModel: viewModel)
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
            .help("Open photo folder or SD card (⌘O)")

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
                .accessibilityLabel("Previous photo")
                .help("Navigate to previous photo (Left Arrow)")

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
                .accessibilityLabel("Next photo")
                .help("Navigate to next photo (Right Arrow)")
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
                    .accessibilityLabel(viewModel.isZoomSynced ? "Pan Linked" : "Pan Free")
                    .help(viewModel.isZoomSynced ? "Synchronized Pan Active (Press S)" : "Independent Pan Active (Press S)")
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
                .accessibilityLabel(viewModel.zoomState.isZoomed ? "Return to Fit" : "Zoom to 1:1")
                .help(viewModel.zoomState.isZoomed ? "Return to Fit (Space or Esc)" : "Zoom to 1:1 (100% pixel-to-pixel, Space)")

                Button(action: {
                    withAnimation(.spring(response: 0.28, dampingFraction: 0.82)) {
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
                .accessibilityLabel("Toggle Filmstrip")
                .help("Toggle Filmstrip (F)")

                Button(action: {
                    withAnimation(.easeInOut(duration: 0.18)) {
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
                .accessibilityLabel("Toggle EXIF and Scores")
                .help("Toggle EXIF & Scores HUD (I)")

                Button(action: { viewModel.presentShortcutsHelp() }) {
                    Image(systemName: "questionmark")
                        .font(.system(size: 11, weight: .bold))
                        .padding(.horizontal, 7)
                        .padding(.vertical, 4)
                        .frame(minWidth: Self.minTarget, minHeight: Self.minTarget)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.accentColor.opacity(0.85))
                .focusable(false)
                .accessibilityLabel("Keyboard Shortcuts")
                .help("Keyboard Shortcuts & Culling Guide (?)")
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
            .foregroundStyle(isSelected ? Color.white : Color.primary)
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
