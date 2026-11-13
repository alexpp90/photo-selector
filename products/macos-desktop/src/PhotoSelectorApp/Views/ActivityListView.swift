import SwiftUI
import PhotoSelectorKit

/// The Activity list ([USER 2026-11-12] decision #23): the decisions of this folder, newest first,
/// at most 100. Each row is one spoken item; a click selects the photo while it is in the folder.
struct ActivityListView: View {
    @ObservedObject var viewModel: CullingWorkspaceViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let rows = viewModel.activityRows
        VStack(alignment: .leading, spacing: 0) {
            Text(LensjetCommand.activity.name())
                .font(.system(size: 13, weight: .bold))
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
            Divider()
            if rows.isEmpty {
                Text(ActivityList.emptyText)
                    .font(.system(size: 12))
                    .foregroundStyle(Color.lensjetTextSecondary)
                    .frame(maxWidth: .infinity, minHeight: 80)
                    .accessibilityIdentifier("activity_empty")
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(rows) { row in
                            Button {
                                viewModel.selectActivity(row)
                                dismiss()
                            } label: { rowView(row) }
                            .buttonStyle(.plain)
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel(row.spokenLabel)
                        }
                    }
                }
                .frame(maxHeight: 360)
            }
        }
        .frame(width: 340)
        .preferredColorScheme(.dark)
        .onExitCommand { dismiss() }
        .uiTestFrame("activity_list")
    }

    private func rowView(_ row: ActivityRow) -> some View {
        HStack(spacing: 8) {
            Image(systemName: row.symbol)
                .font(.system(size: 12, weight: .semibold))
                .frame(width: 18)
            Text(row.filename)
                .font(.system(size: 12, weight: .semibold))
                .lineLimit(1)
                .truncationMode(.middle)
            if let destination = row.destination {
                Text(destination)
                    .font(.system(size: 11))
                    .foregroundStyle(Color.lensjetTextSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 6)
            Text(row.relativeTime)
                .font(.system(size: 11))
                .foregroundStyle(Color.lensjetTextSecondary)
                .lineLimit(1)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .frame(minHeight: 28)
        .contentShape(Rectangle())
    }
}

/// The 1.2 s pill of the clean view, bottom-centre over the preview.
struct CleanPillView: View {
    let pill: CleanPill

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: pill.symbol)
            Text(pill.text).monospacedDigit()
        }
        .font(.system(size: 13, weight: .semibold))
        .foregroundStyle(Color.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .background(Capsule().fill(Color.black.opacity(0.78)))
        .padding(.bottom, 16)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(pill.text)
        .allowsHitTesting(false)
        .uiTestFrame("clean_pill")
    }
}
