import SwiftUI
import PhotoSelectorKit

/// Floating glassmorphic culling action dock with Obsidian Studio palette,
/// tactile embedded hotkey badges, and real-time triage counters.
public struct CullingActionBar: View {
    @ObservedObject public var viewModel: CullingWorkspaceViewModel

    public init(viewModel: CullingWorkspaceViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        // Three densities; ViewThatFits shows the first whose ideal width fits, so no title or
        // counter ever wraps or truncates (REQ-MAC-UI.02) -- the dock steps down to short
        // titles, then to icon + shortcut badge, instead.
        ViewThatFits(in: .horizontal) {
            dockRow(.full)
            dockRow(.compact)
            dockRow(.minimal)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(Color.lensjetSeparator.opacity(0.85), lineWidth: 1)
        )
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .preferredColorScheme(.dark)
        .uiTestFrame("dock")
    }

    private func dockRow(_ density: ChromeDensity) -> some View {
        HStack(spacing: density == .full ? 10 : 8) {
            // MARK: - 1. Move to Selection (M)
            ObsidianDockButton(
                action: .move,
                density: density,
                icon: "arrow.right.doc.fill",
                shortcut: "M",
                fillColor: Color.lensjetMoveFill,
                accentColor: Color.lensjetMove,
                isEnabled: !viewModel.photos.isEmpty,
                perform: { viewModel.cullCurrentPhoto(action: .move) }
            )
            .uiTestFrame("dock_move")
            .help("Move active photo and companion RAW/XMP files to Selection folder (Press M)")

            // MARK: - 2. Copy to Selection (C)
            ObsidianDockButton(
                action: .copy,
                density: density,
                icon: "doc.on.doc.fill",
                shortcut: "C",
                fillColor: Color.lensjetCopyFill,
                accentColor: Color.lensjetCopy,
                isEnabled: !viewModel.photos.isEmpty,
                perform: { viewModel.cullCurrentPhoto(action: .copy) }
            )
            .uiTestFrame("dock_copy")
            .help("Copy active photo and companion files to Selection folder (Press C)")

            // MARK: - 3. Move to Trash (⌫)
            ObsidianDockButton(
                action: .trash,
                density: density,
                icon: "trash.fill",
                shortcut: "⌫",
                fillColor: Color.lensjetTrashFill,
                accentColor: Color.lensjetTrash,
                isDestructive: true,
                isEnabled: !viewModel.photos.isEmpty,
                perform: { viewModel.cullCurrentPhoto(action: .trash) }
            )
            .uiTestFrame("dock_trash")
            .help("Move active photo and companion files to macOS Trash (Press Delete)")

            Divider()
                .frame(height: 22)
                .background(Color.white.opacity(0.15))

            // MARK: - 4. Undo (⌘Z)
            ObsidianDockButton(
                action: .undo,
                density: density,
                icon: "arrow.uturn.backward",
                shortcut: "⌘Z",
                fillColor: Color.lensjetNeutralActionFill,
                accentColor: Color.lensjetNeutralActionAccent,
                isEnabled: viewModel.canUndo,
                perform: { viewModel.undoLastAction() }
            )
            .uiTestFrame("dock_undo")
            .help("Undo the last move, copy, or trash action (⌘Z)")

            Spacer(minLength: 8)

            tally(density)

            // Guide / Shortcuts Help Button
            Button(action: {
                viewModel.presentShortcutsHelp()
            }) {
                Image(systemName: "questionmark.circle")
                    .font(.system(size: 15))
                    .foregroundStyle(Color.white.opacity(0.75))
                    .frame(minWidth: 24, minHeight: 24)
            }
            .buttonStyle(.plain)
            .focusable(false)
            .accessibilityLabel("Keyboard Shortcuts")
            .help("Show Keyboard Shortcuts & Guide (?)")
        }
    }

    // MARK: - Live Triage Counters HUD

    private func tally(_ density: ChromeDensity) -> some View {
        let selected = viewModel.selectedCount
        let trashed = viewModel.trashedCount
        let remaining = max(0, viewModel.photos.count - selected - trashed)
        let green = Color.lensjetMove
        let red = Color.lensjetTrash
        return HStack(spacing: 8) {
            HStack(spacing: 5) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(green)
                    .font(.system(size: 11))
                Text(density.tallyUsesWords ? "\(selected) Selected" : "\(selected)")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Color.white)
                    .lineLimit(1)
                    .fixedSize()
            }

            Text("·")
                .foregroundStyle(Color.white.opacity(0.35))

            HStack(spacing: 5) {
                Image(systemName: "trash.fill")
                    .foregroundStyle(red)
                    .font(.system(size: 11))
                Text(density.tallyUsesWords ? "\(trashed) Trashed" : "\(trashed)")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(Color.white)
                    .lineLimit(1)
                    .fixedSize()
            }

            Text("·")
                .foregroundStyle(Color.white.opacity(0.35))

            HStack(spacing: 5) {
                if !density.tallyUsesWords {
                    Image(systemName: "photo.stack")
                        .foregroundStyle(Color.white.opacity(0.75))
                        .font(.system(size: 11))
                }
                Text(density.tallyUsesWords ? "\(remaining) Remaining" : "\(remaining)")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Color.white.opacity(0.75))
                    .lineLimit(1)
                    .fixedSize()
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color.black.opacity(0.45))
        .clipShape(Capsule())
        .overlay(
            Capsule()
                .stroke(Color.white.opacity(0.12), lineWidth: 0.5)
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(ChromeDensity.tallyAccessibilityText(selected: selected, trashed: trashed, remaining: remaining))
        .help(ChromeDensity.tallyAccessibilityText(selected: selected, trashed: trashed, remaining: remaining))
        .uiTestFrame("dock_tally")
    }
}

/// Tactile button style for Obsidian Studio dock buttons with responsive click feedback.
private struct ObsidianDockButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1.0)
            .opacity(configuration.isPressed ? 0.85 : 1.0)
            .animation(.easeOut(duration: 0.1), value: configuration.isPressed)
    }
}

/// A tactile Obsidian Studio action button with embedded shortcut badge. At `.minimal`
/// density the title is dropped (icon + badge) and kept as the accessibility label.
private struct ObsidianDockButton: View {
    let action: ChromeDensity.DockAction
    let density: ChromeDensity
    let icon: String
    let shortcut: String
    let fillColor: Color
    let accentColor: Color
    var isDestructive: Bool = false
    var isEnabled: Bool = true
    let perform: () -> Void

    var body: some View {
        Button(action: perform) {
            HStack(spacing: 7) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(accentColor)

                if let title = density.dockTitle(action) {
                    Text(title)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(Color.white)
                        .lineLimit(1)
                        .fixedSize()
                }

                Text(shortcut)
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundStyle(accentColor)
                    .lineLimit(1)
                    .fixedSize()
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(Color.black.opacity(0.4))
                    .clipShape(RoundedRectangle(cornerRadius: 4))
            }
            .padding(.horizontal, density == .full ? 14 : 10)
            .padding(.vertical, 7)
            .frame(minWidth: 24, minHeight: 24)
            .background(fillColor)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(accentColor.opacity(0.35), lineWidth: 1)
            )
        }
        .buttonStyle(ObsidianDockButtonStyle())
        .focusable(false)
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1.0 : 0.45)
        .accessibilityLabel(action.fullTitle)
    }
}
