import SwiftUI
import PhotoSelectorKit

/// Native macOS Menu Bar commands providing discovery and shortcuts for culling workflows.
public struct AppCommands: Commands {
    @ObservedObject public var viewModel: CullingWorkspaceViewModel

    public init(viewModel: CullingWorkspaceViewModel) {
        self.viewModel = viewModel
    }

    public var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("Open Folder…") {
                viewModel.presentFolderPicker()
            }
            .keyboardShortcut("o", modifiers: .command)
        }

        CommandMenu("Culling") {
            Button("Move to Selection") {
                viewModel.cullCurrentPhoto(action: .move)
            }
            .keyboardShortcut("m", modifiers: [])
            .disabled(!viewModel.hasActivePhoto)

            Button("Copy to Selection") {
                viewModel.cullCurrentPhoto(action: .copy)
            }
            .keyboardShortcut("c", modifiers: [])
            .disabled(!viewModel.hasActivePhoto)

            Button("Move to Trash") {
                viewModel.cullCurrentPhoto(action: .trash)
            }
            .keyboardShortcut(.delete, modifiers: [])
            .disabled(!viewModel.hasActivePhoto)

            Divider()

            Button("Undo Culling Action") {
                viewModel.undoLastAction()
            }
            .keyboardShortcut("z", modifiers: .command)

            Divider()

            Toggle("Auto-Advance After Culling", isOn: $viewModel.autoAdvance)
                .keyboardShortcut("a", modifiers: [.shift, .command])
        }

        CommandMenu("View") {
            Button("Previous Photo") {
                viewModel.navigateToPrevious()
            }
            .keyboardShortcut("[", modifiers: .command)
            .disabled(viewModel.photos.isEmpty)

            Button("Next Photo") {
                viewModel.navigateToNext()
            }
            .keyboardShortcut("]", modifiers: .command)
            .disabled(viewModel.photos.isEmpty)

            Divider()

            // Checkmark on the active order (inline picker). The choice is persisted.
            Menu("Sort By") {
                Picker("Sort By", selection: Binding(
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

            Button("Go to Photo…") {
                viewModel.presentGoToPhoto()
            }
            .keyboardShortcut("g", modifiers: [])
            .disabled(viewModel.photos.isEmpty)

            Divider()

            Button("Cycle Comparison Slot") {
                viewModel.advanceComparisonSlotFocus()
            }
            .keyboardShortcut(.tab, modifiers: [])
            .disabled(viewModel.comparisonMode == .single)

            Divider()

            Button("Single View (1-Up)") {
                viewModel.setComparisonMode(.single)
            }
            .keyboardShortcut("1", modifiers: [])

            Button("Side-by-Side (2-Up)") {
                viewModel.setComparisonMode(.sideBySide)
            }
            .keyboardShortcut("2", modifiers: [])

            Button("Focus View (3-Up)") {
                viewModel.setComparisonMode(.triplet)
            }
            .keyboardShortcut("3", modifiers: [])

            Divider()

            Button("Toggle 100% Zoom") {
                viewModel.toggleZoom()
            }
            .keyboardShortcut(.space, modifiers: [])

            Button("Toggle Linked Pan") {
                viewModel.toggleZoomSync()
            }
            .keyboardShortcut("s", modifiers: [])

            Divider()

            Button("Toggle Filmstrip") {
                MotionPolicy.animate(.spring(response: 0.28, dampingFraction: 0.82)) {
                    viewModel.isFilmstripVisible.toggle()
                }
            }
            .keyboardShortcut("f", modifiers: [])

            Button("Toggle Info HUD") {
                MotionPolicy.animate(.easeInOut(duration: 0.18)) {
                    viewModel.isInfoHUDVisible.toggle()
                }
            }
            .keyboardShortcut("i", modifiers: [])
        }

        CommandMenu("Tools") {
            Button("Find Duplicates…") {
                viewModel.isDuplicateFinderPresented = true
            }
            .keyboardShortcut("d", modifiers: .command)

            Button("Library Statistics…") {
                viewModel.isLibraryStatisticsPresented = true
            }
            .keyboardShortcut("l", modifiers: .command)
        }

        CommandGroup(replacing: .help) {
            Button("Keyboard Shortcuts & Guide…") {
                viewModel.presentShortcutsHelp()
            }
            .keyboardShortcut("/", modifiers: .command)
        }
    }
}
