import SwiftUI
import PhotoSelectorKit

/// Elegant cheat-sheet modal displaying keyboard shortcuts and workflow guide.
public struct ShortcutsHelpSheet: View {
    @Environment(\.dismiss) private var dismiss

    /// The configured Selection folder name, so the sheet says "Keepers" where the user renamed it.
    private let destination: String

    public init(destination: String = AppSettingsDefaults.cullingDestinationFolderName) {
        self.destination = destination
    }

    /// One row, its name and key from the Kit's table (`LensjetCommand`).
    private func row(_ command: LensjetCommand, _ description: String, key: String? = nil, name: String? = nil)
        -> (shortcut: String, action: String, description: String) {
        (key ?? command.key ?? "", name ?? command.name(destination: destination), description)
    }

    public var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Image(systemName: "keyboard.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(Color.accentColor)

                Text(LensjetCommand.keyboardShortcuts.name())
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
                            row(.move, "Moves the photo and its companion RAW/XMP files to the \(destination) folder"),
                            row(.copy, "Copies the photo and its companions to the \(destination) folder; the originals stay"),
                            row(.trash, "Moves the photo and its companions to the macOS Trash", key: "Delete / \u{232B}"),
                            row(.undo, "Takes back the last move, copy or trash"),
                            row(.autoAdvance, "Steps to the next photo after Move, Copy or Trash")
                        ]
                    )

                    // Navigation Section
                    sectionView(
                        title: "Navigation",
                        icon: "arrow.left.and.right",
                        color: .blue,
                        items: [
                            row(.previousPhoto, "Goes to the photo before", key: "\u{2190} \u{2191} / K"),
                            row(.nextPhoto, "Goes to the photo after", key: "\u{2192} \u{2193} / J"),
                            row(.switchPhoto, "Steps through undecided photos (2-Up only)"),
                            row(.goToPhoto, "Type a position number or part of a file name"),
                            ("H / L", "Previous / Next (going away)", "Old aliases of the arrow keys, removed after the next release"),
                            ("Click Photo", "Select Photo", "Click a photo in 2-Up or 3-Up to make it the current one")
                        ]
                    )

                    // View Modes Section
                    sectionView(
                        title: "Views",
                        icon: "rectangle.split.2x1.fill",
                        color: .orange,
                        items: [
                            row(.singleUp, "One photo, as large as the window allows"),
                            row(.twoUp, "Two photos side by side"),
                            row(.threeUp, "Current photo full-width on top; previous bottom-left, next bottom-right"),
                            row(.zoom100, "Shows one pixel per pixel to check sharpness"),
                            ("Esc", "Fit", "Leaves any zoom (100% or pinch) back to Fit, or click the Fit pill"),
                            row(.linkPan, "Pans all photos together (S until next release; zoom is always shared)"),
                            row(.filmstrip, "Shows or hides the thumbnail strip (F until next release; F becomes full screen)"),
                            row(.info, "Shows or hides EXIF and the Sharpness and Aesthetics scores", key: "I", name: LensjetCommand.infoMenuTitle(isShown: true))
                        ]
                    )

                    // Secondary Tools Section
                    sectionView(
                        title: "Files & Tools",
                        icon: "wrench.and.screwdriver.fill",
                        color: .purple,
                        items: [
                            row(.openFolder, "Opens a photo folder or a camera card"),
                            row(.showInFinder, "Shows the photo in Finder"),
                            row(.quickLook, "Previews the photo with Quick Look"),
                            row(.findDuplicates, LensjetWords.findDuplicatesCaption),
                            row(.statistics, LensjetWords.statisticsCaption),
                            ("\u{2318},", "Settings", "Destination folder, Sharpness bands and cache")
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
