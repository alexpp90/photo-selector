import SwiftUI
import PhotoSelectorKit

/// The end of a cull, drawn over the preview and never over the dock: the "Done" card when every
/// photo has a decision, or a notice on the last photo while some are still undecided.
/// Esc, a navigation key or Undo dismisses it (the view model owns that state).
struct CullEndOverlay: View {
    @ObservedObject var viewModel: CullingWorkspaceViewModel

    var body: some View {
        switch viewModel.endState {
        case .none:
            EmptyView()
        case .remaining(let count):
            VStack {
                HStack(spacing: 10) {
                    Image(systemName: "photo.stack")
                    Text("\(count) Remaining")
                        .font(.system(size: 13, weight: .semibold))
                    Button("Show first") { viewModel.showFirstUndecided() }
                        .buttonStyle(.bordered)
                        .focusable(false)
                        .uiTestFrame("end_remaining_show_first")
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(.ultraThinMaterial, in: Capsule())
                .overlay(Capsule().stroke(Color.lensjetSeparator.opacity(0.85), lineWidth: 1))
                .accessibilityElement(children: .contain)
                .uiTestFrame("end_remaining")
                Spacer()
            }
            .padding(.top, 12)
        case .done(let card):
            CullEndCardView(card: card, viewModel: viewModel)
        }
    }
}

/// Moves the "is it safe to format?" file walk off the main actor; the card shows a spinner until it answers.
@MainActor
final class FormatVerdictLoader: ObservableObject {
    @Published var verdict: FormatVerdict?
    @Published var isChecking = true

    func load(root: URL?, card: CullEndCard) async {
        isChecking = true
        guard let root else {
            verdict = nil
            isChecking = false
            return
        }
        let copied = card.copiedURLs
        let inside = card.selectionIsInsideSource
        let result = await Task.detached(priority: .utility) {
            SafeToFormat.verdict(root: root, copiedURLs: copied, selectionIsInsideSource: inside)
        }.value
        verdict = result
        isChecking = false
    }
}

private struct CullEndCardView: View {
    let card: CullEndCard
    @ObservedObject var viewModel: CullingWorkspaceViewModel
    @StateObject private var format = FormatVerdictLoader()

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(Color.lensjetMove)
                Text("Done")
                    .font(.system(size: 20, weight: .bold, design: .rounded))
            }

            HStack(spacing: 18) {
                cell("checkmark.circle.fill", card.selected, LensjetWords.tally[0], Color.lensjetMove)
                if card.copied > 0 {
                    cell("doc.on.doc.fill", card.copied, LensjetWords.tally[1], Color.lensjetCopy)
                }
                cell("trash.fill", card.trashed, LensjetWords.tally[2], Color.lensjetTrash)
            }

            if let path = card.destinationPath {
                Text(path)
                    .font(.system(.callout, design: .monospaced))
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .textSelection(.enabled)
                    .help(path)
                    .frame(maxWidth: 360)
            }

            formatLine

            HStack(spacing: 10) {
                Button("Show in Finder") {
                    if let url = viewModel.resolvedDestination.selectionFolderURL { FinderReveal.revealFolder(url) }
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.accentColor)
                .foregroundStyle(Color.lensjetOnSelection)
                .focusable(false)
                .uiTestFrame("end_show_in_finder")

                Button("Open Selection") { viewModel.openSelectionFolder() }
                    .buttonStyle(.bordered)
                    .focusable(false)
                    .disabled(viewModel.existingSelectionFolderURL == nil)
                    .uiTestFrame("end_open_selection")
            }
        }
        .padding(22)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.lensjetSeparator.opacity(0.85), lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Done. \(card.selected) selected, \(card.copied) copied, \(card.trashed) trashed.")
        .uiTestFrame("end_card")
        .task(id: card) { await format.load(root: viewModel.rootDirectoryURL, card: card) }
    }

    @ViewBuilder
    private var formatLine: some View {
        if format.isChecking {
            if viewModel.rootDirectoryURL.map(SafeToFormat.isRemovableVolume) == true {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text("Checking")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
            }
        } else if let verdict = format.verdict {
            Label(verdict.text, systemImage: verdict == .safe ? "checkmark.seal.fill" : "info.circle")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(verdict == .safe ? Color.lensjetMove : Color.secondary)
        }
    }

    private func cell(_ icon: String, _ count: Int, _ word: String, _ color: Color) -> some View {
        VStack(spacing: 3) {
            Image(systemName: icon).foregroundStyle(color)
            Text("\(count)").font(.system(size: 18, weight: .bold).monospacedDigit())
            Text(word).font(.system(size: 11)).foregroundStyle(.secondary)
        }
    }
}
