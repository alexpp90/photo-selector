import SwiftUI
import PhotoSelectorKit

/// Finds the companions of a photo off the main actor (REQ-MAC-FILES.05). A photo whose primary
/// file has already moved away keeps the chips it had.
@MainActor
final class CompanionLoader: ObservableObject {
    @Published private(set) var kinds: [CompanionKind] = []
    private var loadedID: UUID?

    func load(for photo: PhotoItem) async {
        if loadedID != photo.id {
            kinds = []
            loadedID = photo.id
        }
        let url = photo.url
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        let found = await Task.detached(priority: .utility) {
            CompanionSet.kinds(primary: url, companions: FileCullingManager().findCompanionFiles(for: url))
        }.value
        if loadedID == photo.id { kinds = found }
    }
}

/// "JPG", "XMP", "RAW", "EDIT" chips beside the photo's own extension badge: the set a photo is, so
/// the toast that counts files has something to point at. A glyph and a word per chip; hidden when
/// the photo travels alone.
struct CompanionChipsView: View {
    let photo: PhotoItem
    @StateObject private var loader = CompanionLoader()

    private struct LoadKey: Hashable {
        let id: UUID
        let status: PhotoStatus
    }

    var body: some View {
        Group {
            if !loader.kinds.isEmpty {
                HStack(spacing: 4) {
                    ForEach(loader.kinds, id: \.self) { kind in
                        HStack(spacing: 3) {
                            Image(systemName: kind.symbol)
                                .font(.system(size: 8, weight: .bold))
                            Text(kind.rawValue)
                                .font(.system(size: 9, weight: .bold))
                        }
                        .foregroundStyle(Color.white)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(Color.white.opacity(0.16))
                        .clipShape(Capsule())
                    }
                }
                .fixedSize()
                .help("Travels with: \(CompanionSet.summary(loader.kinds) ?? "")")
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(CompanionSet.spoken(loader.kinds) ?? "")
                .accessibilityIdentifier("companion_chips")
                .uiTestFrame("companion_chips")
            }
        }
        .task(id: LoadKey(id: photo.id, status: photo.status)) {
            await loader.load(for: photo)
        }
    }
}
