import SwiftUI
import PhotoSelectorKit

/// "Go to Photo": a number is the position the counter shows, anything else a file name. Shows the
/// file name the entry would land on while the user types (REQ-MAC-NAV). Enter goes, Esc cancels.
struct GoToPhotoSheet: View {
    @ObservedObject var viewModel: CullingWorkspaceViewModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var entry = GoToEntryState()
    @FocusState private var fieldFocused: Bool

    var body: some View {
        let result = GoToPhotoResolver.resolve(query: entry.query, photos: viewModel.photos)
        VStack(alignment: .leading, spacing: 12) {
            Text("Go to Photo")
                .font(.system(size: 15, weight: .bold))
            TextField("Number or name", text: $entry.query)
                .textFieldStyle(.roundedBorder)
                .focused($fieldFocused)
                .accessibilityLabel("Photo number or file name")
                .onSubmit { go(result) }
                .uiTestFrame("goto_field")
            Group {
                switch result {
                case .index(let index):
                    Label(viewModel.photos[index].filename, systemImage: "arrow.right.circle.fill")
                        .foregroundStyle(Color.white)
                        .accessibilityLabel("Goes to photo \(index + 1), \(viewModel.photos[index].filename)")
                case .noMatch:
                    Label("No match", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(Color.lensjetTextSecondary)
                case .empty:
                    Text("1 to \(viewModel.photos.count), or part of a file name")
                        .foregroundStyle(Color.lensjetTextSecondary)
                }
            }
            .font(.system(size: 12))
            .lineLimit(1)
            .truncationMode(.middle)
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("Go") { go(result) }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
                    .disabled({ if case .index = result { return false } else { return true } }())
            }
        }
        .padding(20)
        .frame(width: 360)
        .preferredColorScheme(.dark)
        .onAppear { fieldFocused = true }
    }

    private func go(_ result: GoToPhotoResolver.Result) {
        guard case .index(let index) = result else { return }
        viewModel.selectPhoto(at: index)
        dismiss()
    }
}

@MainActor
final class GoToEntryState: ObservableObject {
    @Published var query: String = ""
}
