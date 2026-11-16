import Foundation

/// One tile of the filmstrip: the photo, its position and how it is marked.
public struct FilmstripTile: Identifiable {
    public let index: Int
    public let photo: PhotoItem
    public let isSelected: Bool
    public let isInComparison: Bool
    public var id: UUID { photo.id }
}

/// The tiles the filmstrip shows for a list of photos (REQ-MAC-PERF.03). Built on every render of
/// the strip, so its cost must not grow with the folder.
public enum FilmstripTiles {
    public static func make(photos: [PhotoItem], currentIndex: Int, comparisonIndices: [Int]) -> [FilmstripTile] {
        photos.enumerated().map { index, photo in
            FilmstripTile(
                index: index,
                photo: photo,
                isSelected: index == currentIndex,
                isInComparison: comparisonIndices.contains(index)
            )
        }
    }
}
