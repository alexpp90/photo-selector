import Foundation

/// One tile of the filmstrip: the photo, its position and how it is marked.
public struct FilmstripTile: Identifiable {
    public let index: Int
    public let photo: PhotoItem
    public let isSelected: Bool
    public let isInComparison: Bool
    public var id: UUID { photo.id }
}

/// The tiles of the filmstrip as a lazy collection: a tile is made when it is read, so a render
/// that realises only the visible window costs the window, not the folder (REQ-MAC-PERF.03).
/// Holds the list by value (copy on write, no copy of the photos).
public struct FilmstripTileList: RandomAccessCollection {
    let photos: [PhotoItem]
    let currentIndex: Int
    let comparisonIndices: [Int]

    public var startIndex: Int { 0 }
    public var endIndex: Int { photos.count }

    public subscript(position: Int) -> FilmstripTile {
        FilmstripTile(
            index: position,
            photo: photos[position],
            isSelected: position == currentIndex,
            isInComparison: comparisonIndices.contains(position)
        )
    }
}

/// The tiles the filmstrip shows for a list of photos. Built on every render of the strip.
public enum FilmstripTiles {
    public static func make(photos: [PhotoItem], currentIndex: Int, comparisonIndices: [Int]) -> FilmstripTileList {
        FilmstripTileList(photos: photos, currentIndex: currentIndex, comparisonIndices: comparisonIndices)
    }
}
