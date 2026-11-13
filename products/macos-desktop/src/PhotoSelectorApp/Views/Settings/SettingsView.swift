import SwiftUI
import AppKit
import PhotoSelectorKit

/// Native 3-tab macOS Settings view accessible via Preferences / Settings (⌘,).
public struct SettingsView: View {
    private enum Tab: Hashable {
        case general
        case scoring
        case performance
    }

    /// The workspace is observed only for the folder that is open, so the Selection destination
    /// can be shown as a real path (Settings is a separate scene and has no other way to know it).
    @ObservedObject private var workspace: CullingWorkspaceViewModel

    public init(workspace: CullingWorkspaceViewModel) {
        self.workspace = workspace
    }

    public var body: some View {
        TabView {
            GeneralSettingsTab(openFolderURL: workspace.rootDirectoryURL)
                .tabItem {
                    Label("General", systemImage: "gearshape")
                }
                .tag(Tab.general)

            ScoringSettingsTab()
                .tabItem {
                    Label(LensjetWords.scoresSettingsTab, systemImage: "wand.and.stars")
                }
                .tag(Tab.scoring)

            CachePerformanceSettingsTab()
                .tabItem {
                    Label("Cache & Performance", systemImage: "speedometer")
                }
                .tag(Tab.performance)
        }
        .frame(width: 540)
    }
}

// MARK: - Tab 1: General

/// Writes the destination choice to `UserDefaults` and runs the folder picker. The Picker and the
/// path text read the same keys through `@AppStorage`, so a write re-renders them; `objectWillChange`
/// is sent as well so that a cancelled picker puts the radio button back.
@MainActor
final class DestinationSettingsModel: ObservableObject {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func select(_ mode: CullingDestinationMode) {
        switch mode {
        case .workingFolder:
            // The custom folder stays stored, so switching back to Custom needs no new pick.
            defaults.cullingDestinationMode = .workingFolder
        case .customFolder:
            if defaults.externalCullingDestinationURL != nil {
                defaults.cullingDestinationMode = .customFolder
            } else {
                // No folder yet: ask for one; cancelling leaves Working folder selected.
                Task { @MainActor [weak self] in self?.chooseCustomFolder() }
            }
        }
        objectWillChange.send()
    }

    func chooseCustomFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.canCreateDirectories = true
        panel.prompt = "Choose"
        panel.title = "Choose the Folder for Moved and Copied Photos"
        panel.directoryURL = defaults.externalCullingDestinationURL

        if panel.runModal() == .OK, let url = panel.url {
            defaults.externalCullingDestinationURL = url
            defaults.cullingDestinationMode = .customFolder
        }
        objectWillChange.send()
    }
}

struct GeneralSettingsTab: View {
    /// The folder being culled, nil when none is open.
    let openFolderURL: URL?

    @AppStorage(AppSettingsKeys.cullingDestinationFolderName)
    private var cullingDestinationFolderName: String = AppSettingsDefaults.cullingDestinationFolderName

    @AppStorage(AppSettingsKeys.cullingDestinationMode)
    private var storedDestinationMode: String = ""

    @AppStorage(AppSettingsKeys.externalCullingDestinationURL)
    private var storedCustomFolderPath: String = ""

    @AppStorage(AppSettingsKeys.autoAdvance)
    private var autoAdvance: Bool = AppSettingsDefaults.autoAdvance

    @AppStorage(AppSettingsKeys.rejectsDestination)
    private var rejectsRaw: String = RejectsDestination.trash.rawValue

    @StateObject private var destinationModel = DestinationSettingsModel()

    var body: some View {
        let destination = resolvedDestination
        let summary = CullingDestinationResolver.summary(of: destination)
        Form {
            Section("Culling Destination") {
                Picker("Photos go to", selection: modeBinding) {
                    Text("Working folder").tag(CullingDestinationMode.workingFolder)
                    Text("Custom folder").tag(CullingDestinationMode.customFolder)
                }
                .pickerStyle(.radioGroup)

                if destination.mode == .customFolder {
                    LabeledContent("Custom folder") {
                        HStack(spacing: 8) {
                            PathText(text: summary.customFolderPathText ?? "")
                            Button("Change\u{2026}") {
                                destinationModel.chooseCustomFolder()
                            }
                            .focusable(false)
                            .accessibilityLabel("Change custom folder")
                        }
                    }
                }

                LabeledContent("Subfolder") {
                    TextField("Folder Name", text: $cullingDestinationFolderName)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 180)
                }

                destinationLines(summary)

                Picker("Rejects", selection: $rejectsRaw) {
                    ForEach(RejectsDestination.allCases, id: \.rawValue) { choice in
                        Text(choice.title).tag(choice.rawValue)
                    }
                }
                .pickerStyle(.segmented)
                .accessibilityLabel("Rejects go to")
                if RejectsDestination(storedRaw: rejectsRaw) == .rejectedFolder {
                    Text("Inside the photo folder.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Section("Navigation & Culling") {
                Toggle("Auto-Advance", isOn: $autoAdvance)
                    .help("Steps to the next photo after Move, Copy or Delete. Also in the Culling menu.")
            }
        }
        .formStyle(.grouped)
        .padding(16)
    }

    // The same resolution `CullingWorkspaceViewModel.cullCurrentPhoto` uses to pick the folder.
    private var resolvedDestination: ResolvedCullingDestination {
        let custom = storedCustomFolderPath.trimmingCharacters(in: .whitespacesAndNewlines)
        let customURL = custom.isEmpty ? nil : URL(fileURLWithPath: custom, isDirectory: true)
        return CullingDestinationResolver.resolve(
            mode: CullingDestinationMode.effective(
                storedRaw: storedDestinationMode.isEmpty ? nil : storedDestinationMode,
                hasCustomFolder: customURL != nil
            ),
            folderName: cullingDestinationFolderName,
            customFolderURL: customURL,
            openFolderURL: openFolderURL
        )
    }

    private var modeBinding: Binding<CullingDestinationMode> {
        Binding(
            get: { resolvedDestination.mode },
            set: { destinationModel.select($0) }
        )
    }

    @ViewBuilder
    private func destinationLines(_ summary: CullingDestinationSummary) -> some View {
        if let path = summary.selectionPathText {
            LabeledContent("Destination") {
                PathText(text: path)
            }
        }
        if let placeholder = summary.placeholderText {
            Text(placeholder)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        if let warning = summary.warningText {
            Label(warning, systemImage: "exclamationmark.triangle.fill")
                .font(.caption)
                .foregroundStyle(.orange)
                .accessibilityLabel("Warning: \(warning)")
        }
    }
}

/// A full path the user can read and select; the middle is elided when it does not fit, and the
/// whole path is in the tooltip.
private struct PathText: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(.callout, design: .monospaced))
            .lineLimit(1)
            .truncationMode(.middle)
            .textSelection(.enabled)
            .help(text)
    }
}

// MARK: - Tab 2: Scores (Sharpness and Aesthetics)
struct ScoringSettingsTab: View {
    @AppStorage(AppSettingsKeys.sharpnessBlurryThreshold)
    private var sharpnessBlurryThreshold: Double = AppSettingsDefaults.sharpnessBlurryThreshold

    @AppStorage(AppSettingsKeys.sharpnessAcceptableThreshold)
    private var sharpnessAcceptableThreshold: Double = AppSettingsDefaults.sharpnessAcceptableThreshold

    @AppStorage(AppSettingsKeys.aestheticScoringEnabled)
    private var aestheticScoringEnabled: Bool = AppSettingsDefaults.aestheticScoringEnabled

    @AppStorage(AppSettingsKeys.sharpnessStopThreshold)
    private var sharpnessStopThreshold: Double = AppSettingsDefaults.sharpnessStopThreshold

    var body: some View {
        Form {
            Section("Lowest Sharpness First") {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Stop at sharpness:")
                        Spacer()
                        Text(sharpnessStopThreshold <= 0 ? "Off" : "\(Int(sharpnessStopThreshold)) / 100")
                            .monospacedDigit()
                            .foregroundStyle(.secondary)
                            .accessibilityIdentifier("stop_threshold_value")
                    }
                    Slider(value: $sharpnessStopThreshold, in: 0.0...100.0, step: 1.0)
                        .accessibilityLabel("Stop at sharpness")
                        .accessibilityValue(sharpnessStopThreshold <= 0 ? "Off" : "\(Int(sharpnessStopThreshold)) out of 100")
                        .help("0 turns it off. While culling in View > Sort By > Lowest Sharpness First, a banner stays on the photo until your next key when you reach a photo at or above this sharpness.")
                }
                Text("0 = off. Shows a banner at this Sharpness, with View > Sort By > Lowest Sharpness First.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Sharpness bands") {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Blurry below")
                        Spacer()
                        Text("\(Int(sharpnessBlurryThreshold))")
                            .monospacedDigit()
                            .foregroundStyle(.orange)
                    }
                    Slider(value: $sharpnessBlurryThreshold, in: 10.0...50.0, step: 1.0)
                        .accessibilityLabel("Blurry below")
                        .accessibilityValue("\(Int(sharpnessBlurryThreshold)) out of 100")

                    HStack {
                        Text("Sharp above")
                        Spacer()
                        Text("\(Int(sharpnessAcceptableThreshold))")
                            .monospacedDigit()
                            .foregroundStyle(.green)
                    }
                    Slider(value: $sharpnessAcceptableThreshold, in: 50.0...95.0, step: 1.0)
                        .accessibilityLabel("Sharp above")
                        .accessibilityValue("\(Int(sharpnessAcceptableThreshold)) out of 100")
                }
                Text("Below \(Int(sharpnessBlurryThreshold)): Blurry. Above \(Int(sharpnessAcceptableThreshold)): Sharp.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("Reset") {
                    sharpnessBlurryThreshold = AppSettingsDefaults.sharpnessBlurryThreshold
                    sharpnessAcceptableThreshold = AppSettingsDefaults.sharpnessAcceptableThreshold
                }
                .disabled(sharpnessBlurryThreshold == AppSettingsDefaults.sharpnessBlurryThreshold
                          && sharpnessAcceptableThreshold == AppSettingsDefaults.sharpnessAcceptableThreshold)
            }

            Section("Aesthetics") {
                Toggle("Aesthetics", isOn: $aestheticScoringEnabled)
                Text("Runs in the background.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .padding(16)
    }
}

// MARK: - Tab 3: Cache & Performance
@MainActor
final class CacheTabState: ObservableObject {
    @Published var purgeFeedback: String?
}

struct CachePerformanceSettingsTab: View {
    @AppStorage(AppSettingsKeys.thumbnailCacheLimitMB)
    private var thumbnailCacheLimitMB: Int = AppSettingsDefaults.thumbnailCacheLimitMB

    @AppStorage(AppSettingsKeys.preloadWindowBefore)
    private var preloadWindowBefore: Int = AppSettingsDefaults.preloadWindowBefore

    @AppStorage(AppSettingsKeys.preloadWindowAfter)
    private var preloadWindowAfter: Int = AppSettingsDefaults.preloadWindowAfter

    @StateObject private var state = CacheTabState()

    var body: some View {
        Form {
            Section("Decoded Thumbnail Memory Cache") {
                Picker("Cache Memory Ceiling", selection: $thumbnailCacheLimitMB) {
                    Text("256 MB").tag(256)
                    Text("512 MB (Default)").tag(512)
                    Text("1 GB").tag(1024)
                    Text("2 GB").tag(2048)
                    Text("4 GB").tag(4096)
                }
                .onChange(of: thumbnailCacheLimitMB) { _, newLimit in
                    Task {
                        await ThumbnailCache.shared.setMemoryLimitMB(newLimit)
                    }
                }

                HStack {
                    Button("Purge In-Memory Cache") {
                        Task {
                            await ThumbnailCache.shared.removeAll()
                            state.purgeFeedback = "Cache cleared"
                            try? await Task.sleep(nanoseconds: 1_500_000_000)
                            state.purgeFeedback = nil
                        }
                    }

                    if let feedback = state.purgeFeedback {
                        Text(feedback)
                            .font(.caption)
                            .foregroundStyle(.green)
                    }
                }
            }

            Section("Sliding Window Preloading") {
                Stepper("Preload before current: \(preloadWindowBefore) photos", value: $preloadWindowBefore, in: 1...5)
                Stepper("Preload after current: \(preloadWindowAfter) photos", value: $preloadWindowAfter, in: 2...10)
                Text("Pre-decodes textures in memory ahead of keyboard scrub navigation.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .padding(16)
    }
}
