import SwiftUI
import PhotoSelectorKit

/// Native macOS Menu Bar commands providing discovery and shortcuts for culling workflows.
///
/// Where a Mac user looks: File opens and reveals, Edit undoes, Go moves between photos, View
/// holds everything about how the photo is shown (added to the system View menu, which keeps
/// Enter Full Screen), Tools and Help are the app's own. Every name and key comes from the Kit's
/// `LensjetCommand` table; `MenuStructure` lists the same placement for the tests.
public struct AppCommands: Commands {
    @ObservedObject public var viewModel: CullingWorkspaceViewModel
    @AppStorage(AppSettingsKeys.rejectsDestination) private var rejectsRaw: String = RejectsDestination.trash.rawValue

    public init(viewModel: CullingWorkspaceViewModel) {
        self.viewModel = viewModel
    }

    public var body: some Commands {
        // MARK: File
        CommandGroup(replacing: .newItem) {
            Button(LensjetCommand.openFolder.name()) {
                viewModel.presentFolderPicker()
            }
            .keyboardShortcut("o", modifiers: .command)

            Menu(LensjetCommand.openRecent.name()) {
                ForEach(viewModel.recentFolders, id: \.self) { folder in
                    Button(folder.lastPathComponent) { viewModel.loadFolder(at: folder) }
                        .help(folder.path)
                }
                if !viewModel.recentFolders.isEmpty {
                    Divider()
                }
                Button("Clear Menu") { viewModel.clearRecentFolders() }
                    .disabled(viewModel.recentFolders.isEmpty)
            }

            Divider()

            Button(LensjetCommand.showInFinder.name()) {
                if let url = viewModel.currentPhoto?.url { FinderReveal.reveal(url) }
            }
            .keyboardShortcut("r", modifiers: [.shift, .command])
            .disabled(!viewModel.hasActivePhoto)

            Button(LensjetCommand.quickLook.name()) {
                if let url = viewModel.currentPhoto?.url { QuickLookPresenter.shared.toggle(url) }
            }
            .keyboardShortcut("y", modifiers: .command)
            .disabled(!viewModel.hasActivePhoto)
        }

        // MARK: Edit — after the system Undo/Redo, which keep the text fields' own undo; the
        // system item is disabled outside a text field, so Command-Z falls through to this one.
        CommandGroup(after: .undoRedo) {
            Button(viewModel.undoMenuTitle) {
                viewModel.undoLastAction()
            }
            .keyboardShortcut("z", modifiers: .command)
            .disabled(!viewModel.canUndo)
            .help(LensjetCommand.undo.tooltip())
        }

        // MARK: Culling
        CommandMenu("Culling") {
            Button(LensjetCommand.move.name(destination: viewModel.destinationName)) {
                viewModel.cullCurrentPhoto(action: .move)
            }
            .keyboardShortcut("m", modifiers: [])
            .disabled(!viewModel.hasActivePhoto)

            Button(LensjetCommand.copy.name(destination: viewModel.destinationName)) {
                viewModel.cullCurrentPhoto(action: .copy)
            }
            .keyboardShortcut("c", modifiers: [])
            .disabled(!viewModel.hasActivePhoto)

            Button(LensjetCommand.trash.name(rejects: RejectsDestination(storedRaw: rejectsRaw))) {
                viewModel.cullCurrentPhoto(action: .trash)
            }
            .keyboardShortcut(.delete, modifiers: [])
            .disabled(!viewModel.hasActivePhoto)

            Divider()

            Toggle(LensjetCommand.autoAdvance.name(), isOn: Binding(
                get: { viewModel.autoAdvance },
                set: { viewModel.setAutoAdvance($0) }
            ))
                .keyboardShortcut("a", modifiers: [.shift, .command])
        }

        // MARK: View — one View menu: these items come before the system's, which keeps Enter Full Screen.
        CommandGroup(before: .toolbar) {
            Button(LensjetCommand.singleUp.name()) {
                viewModel.setComparisonMode(.single)
            }
            .keyboardShortcut("1", modifiers: [])

            Button(LensjetCommand.twoUp.name()) {
                viewModel.setComparisonMode(.sideBySide)
            }
            .keyboardShortcut("2", modifiers: [])

            Button(LensjetCommand.threeUp.name()) {
                viewModel.setComparisonMode(.triplet)
            }
            .keyboardShortcut("3", modifiers: [])

            Divider()

            // Checkmark on the active order (inline picker). The choice is persisted.
            Menu(LensjetCommand.sortBy.name()) {
                Picker(LensjetCommand.sortBy.name(), selection: Binding(
                    get: { viewModel.sortOrder },
                    set: { viewModel.setSortOrder($0) }
                )) {
                    ForEach(WorkspaceSortOrder.allCases) { order in
                        Text(order.title).tag(order)
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }
            .disabled(viewModel.photos.isEmpty)

            Divider()

            Button(LensjetCommand.zoom100.name()) {
                viewModel.toggleZoom()
            }
            .keyboardShortcut(.space, modifiers: [])

            // S still links the pan for one release (router); the menu item has its own chord.
            Button(LensjetCommand.linkPan.name()) {
                viewModel.toggleZoomSync()
            }
            .keyboardShortcut("p", modifiers: [.option, .command])

            Button(LensjetCommand.filmstripMenuTitle(isShown: viewModel.isFilmstripVisible)) {
                MotionPolicy.animate(.spring(response: 0.28, dampingFraction: 0.82)) {
                    viewModel.isFilmstripVisible.toggle()
                }
            }
            .keyboardShortcut("t", modifiers: [])

            Button(LensjetCommand.infoMenuTitle(isShown: viewModel.isInfoHUDVisible)) {
                MotionPolicy.animate(.easeInOut(duration: 0.18)) {
                    viewModel.isInfoHUDVisible.toggle()
                }
            }
            .keyboardShortcut("i", modifiers: [])

            Button(LensjetCommand.cleanView.name()) {
                viewModel.toggleCleanView()
            }
            .keyboardShortcut("f", modifiers: [])
            .disabled(viewModel.photos.isEmpty)

            // Tab acts in 2-Up only; elsewhere the item is disabled and the key is not consumed.
            Button(LensjetCommand.switchPhoto.name()) {
                viewModel.advanceComparisonSlotFocus()
            }
            .keyboardShortcut(.tab, modifiers: [])
            .disabled(!viewModel.canSwitchPhoto)

            Divider()
        }

        // MARK: Go
        CommandMenu("Go") {
            Button(LensjetCommand.previousPhoto.name()) {
                viewModel.navigateToPrevious()
            }
            .keyboardShortcut("[", modifiers: .command)
            .disabled(viewModel.photos.isEmpty)

            Button(LensjetCommand.nextPhoto.name()) {
                viewModel.navigateToNext()
            }
            .keyboardShortcut("]", modifiers: .command)
            .disabled(viewModel.photos.isEmpty)

            Divider()

            Button(LensjetCommand.goToPhoto.name()) {
                viewModel.presentGoToPhoto()
            }
            .keyboardShortcut("g", modifiers: [])
            .disabled(viewModel.photos.isEmpty)

            Button(LensjetCommand.activity.name()) {
                viewModel.presentActivity()
            }
            .keyboardShortcut("l", modifiers: [.shift, .command])
            .disabled(viewModel.photos.isEmpty)
        }

        // MARK: Tools
        CommandMenu("Tools") {
            Button(LensjetCommand.findDuplicates.name()) {
                viewModel.isDuplicateFinderPresented = true
            }
            .keyboardShortcut("d", modifiers: .command)

            Button(LensjetCommand.statistics.name()) {
                viewModel.isLibraryStatisticsPresented = true
            }
            .keyboardShortcut("l", modifiers: .command)
        }

        // MARK: Help
        CommandGroup(replacing: .help) {
            Button(LensjetCommand.keyboardShortcuts.name()) {
                viewModel.presentShortcutsHelp()
            }
            .keyboardShortcut("/", modifiers: .command)
        }
    }
}
