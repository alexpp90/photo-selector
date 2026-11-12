import SwiftUI

/// Elegant cheat-sheet modal displaying keyboard shortcuts and workflow guide.
public struct ShortcutsHelpSheet: View {
    @Environment(\.dismiss) private var dismiss

    public init() {}

    public var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Image(systemName: "keyboard.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(Color.accentColor)

                Text("Keyboard Shortcuts & Culling Guide")
                    .font(.system(size: 16, weight: .bold))

                Spacer()

                Button("Done") {
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
            }
            .padding(16)
            .background(Color(NSColor.windowBackgroundColor))
            .overlay(Divider(), alignment: .bottom)

            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // Culling Actions Section
                    sectionView(
                        title: "Culling Actions",
                        icon: "arrow.right.doc.fill",
                        color: .green,
                        items: [
                            ("M", "Move to Selection", "Moves active photo and all companion RAW/XMP files to Selection folder"),
                            ("C", "Copy to Selection", "Copies active photo and companions to Selection folder, preserving originals"),
                            ("Delete / ⌫", "Move to Trash", "Moves active photo and companion files to macOS Trash"),
                            ("⌘Z", "Undo", "Instantly rolls back the last move, copy, or trash action")
                        ]
                    )

                    // Navigation Section
                    sectionView(
                        title: "Navigation",
                        icon: "arrow.left.and.right",
                        color: .blue,
                        items: [
                            ("← ↑ / K", "Previous Photo", "Navigates to the preceding candidate photograph"),
                            ("→ ↓ / J", "Next Photo", "Navigates to the subsequent candidate photograph"),
                            ("Tab", "Switch Photo", "In 2-Up, steps through undecided photos in the active pane; 1-Up and 3-Up leave Tab alone"),
                            ("G", "Go to Photo", "Type a position number or part of a file name"),
                            ("H / L", "Previous / Next (going away)", "Old aliases of the arrow keys, removed after the next release"),
                            ("Click Photo", "Select Slot", "Click any comparison pane directly to make it the active photo")
                        ]
                    )

                    // View Modes Section
                    sectionView(
                        title: "Comparison & View Modes",
                        icon: "rectangle.split.2x1.fill",
                        color: .orange,
                        items: [
                            ("1", "1-Up Single View", "Displays active photo filling the maximum screen area"),
                            ("2", "2-Up Side-by-Side", "Compares two photographs side-by-side (50/50 split)"),
                            ("3", "3-Up Focus Mode", "Current photo full-width on top; previous bottom-left, next bottom-right"),
                            ("Space", "100% Zoom (1:1)", "Toggles 1:1 pixel crop magnification to check critical focus"),
                            ("Esc", "Exit Zoom", "Leaves any zoom (100% or pinch) back to Fit — or click the Fit pill"),
                            ("⌥⌘P", "Link Pan", "Links panning across comparison slots (S until next release; zoom level is always shared)"),
                            ("T", "Toggle Filmstrip", "Shows or hides the bottom thumbnail scrubber strip (F until next release; F becomes full screen)"),
                            ("I", "Toggle Info Overlay", "Shows or hides floating EXIF metadata and quality scores")
                        ]
                    )

                    // Secondary Tools Section
                    sectionView(
                        title: "Tools & Preferences",
                        icon: "wrench.and.screwdriver.fill",
                        color: .purple,
                        items: [
                            ("⌘O", "Open Folder / SD Card", "Selects a new photo directory or mounted camera card"),
                            ("⌘D", "Duplicate Finder", "Finds identical files."),
                            ("⌘L", "Library Statistics", "Displays focal length, aperture, ISO, and shutter speed histograms"),
                            ("⌘,", "Settings", "Opens macOS Preferences to configure destination folder and thresholds")
                        ]
                    )
                }
                .padding(20)
            }
        }
        .frame(width: 580, height: 520)
        .preferredColorScheme(.dark)
    }

    private func sectionView(
        title: String,
        icon: String,
        color: Color,
        items: [(shortcut: String, action: String, description: String)]
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .foregroundStyle(color)
                Text(title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.primary)
            }

            VStack(spacing: 6) {
                ForEach(items, id: \.shortcut) { item in
                    HStack(alignment: .top, spacing: 12) {
                        Text(item.shortcut)
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundStyle(.primary)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color(NSColor.controlBackgroundColor))
                            .clipShape(RoundedRectangle(cornerRadius: 5))
                            .overlay(
                                RoundedRectangle(cornerRadius: 5)
                                    .stroke(Color.white.opacity(0.15), lineWidth: 0.5)
                            )
                            .frame(width: 100, alignment: .leading)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.action)
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(.primary)

                            Text(item.description)
                                .font(.system(size: 11))
                                .foregroundStyle(.secondary)
                        }

                        Spacer()
                    }
                    .padding(.vertical, 2)
                }
            }
            .padding(12)
            .background(Color(NSColor.controlBackgroundColor).opacity(0.5))
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }
}
