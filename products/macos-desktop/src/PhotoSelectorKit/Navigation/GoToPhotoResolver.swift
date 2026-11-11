import Foundation

/// Resolves what the user typed into "Go to Photo" (REQ-MAC-NAV): digits are the position in
/// the list as the counter shows it ("Photo 12 of 240"), anything else matches a file name.
/// Pure, so the rules are unit-tested without a window.
public enum GoToPhotoResolver {

    public enum Result: Equatable, Sendable {
        /// Nothing typed yet.
        case empty
        /// Zero-based index into the photo list.
        case index(Int)
        /// A number outside 1...count, or a name nothing matches.
        case noMatch
    }

    /// - Parameters:
    ///   - query: the text of the field (surrounding whitespace is ignored).
    ///   - photos: the list in the order it is shown.
    public static func resolve(query: String, photos: [PhotoItem]) -> Result {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return .empty }
        if text.allSatisfy({ $0.isASCII && $0.isNumber }) {
            guard let position = Int(text), (1...max(1, photos.count)).contains(position), !photos.isEmpty else {
                return .noMatch
            }
            return .index(position - 1)
        }
        let needle = text.lowercased()
        func stem(_ name: String) -> String { (name as NSString).deletingPathExtension.lowercased() }
        // Exact name or stem beats a prefix, which beats a substring; the first photo in list order wins.
        if let i = photos.firstIndex(where: { $0.filename.lowercased() == needle || stem($0.filename) == needle }) {
            return .index(i)
        }
        if let i = photos.firstIndex(where: { $0.filename.lowercased().hasPrefix(needle) }) {
            return .index(i)
        }
        if let i = photos.firstIndex(where: { $0.filename.lowercased().contains(needle) }) {
            return .index(i)
        }
        return .noMatch
    }
}
