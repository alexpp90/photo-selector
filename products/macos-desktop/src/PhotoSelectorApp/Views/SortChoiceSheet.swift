import SwiftUI
import PhotoSelectorKit

/// `S`: choose the order of the photos. The same four orders as View > Sort By, with a checkmark
/// on the active one; a click or the number key picks it and closes the sheet, Esc cancels.
struct SortChoiceSheet: View {
    @ObservedObject var viewModel: CullingWorkspaceViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(LensjetCommand.sortBy.name())
                .font(.system(size: 15, weight: .bold))
            ForEach(Array(WorkspaceSortOrder.allCases.enumerated()), id: \.element.id) { index, order in
                Button {
                    viewModel.setSortOrder(order)
                    dismiss()
                } label: {
                    HStack {
                        Image(systemName: order == viewModel.sortOrder ? "checkmark" : "")
                            .frame(width: 16)
                        Text(order.title)
                        Spacer()
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .keyboardShortcut(KeyEquivalent(Character(String(index + 1))), modifiers: [])
                .accessibilityLabel(order == viewModel.sortOrder ? "\(order.title), selected" : order.title)
            }
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
            }
        }
        .padding(20)
        .frame(width: 280)
        .preferredColorScheme(.dark)
        .uiTestFrame("sort_choice")
    }
}
