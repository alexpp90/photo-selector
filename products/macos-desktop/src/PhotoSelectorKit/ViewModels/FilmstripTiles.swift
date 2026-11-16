import Foundation

/// One tile of the filmstrip: its position and how it is marked. It holds no photo: the view
/// reads the photo of the tile it draws from the model, so nothing the view keeps contains the
/// whole list (the graph compared the list for every tile on every publish, REQ-MAC-PERF.03).
public struct FilmstripTile: Identifiable, Equatable {
    public let index: Int
    public let isSelected: Bool
    public let isInComparison: Bool
    public var id: Int { index }
}

/// The tiles of the filmstrip as a lazy collection of a count and two small values: a tile is
/// made when it is read, so a render costs the visible window, not the folder.
public struct FilmstripTileList: RandomAccessCollection, Equatable {
    let count: Int
    let currentIndex: Int
    let comparisonIndices: [Int]

    public var startIndex: Int { 0 }
    public var endIndex: Int { count }

    public subscript(position: Int) -> FilmstripTile {
        FilmstripTile(
            index: position,
            isSelected: position == currentIndex,
            isInComparison: comparisonIndices.contains(position)
        )
    }
}

/// The tiles the filmstrip shows. Built on every render of the strip.
public enum FilmstripTiles {
    public static func make(count: Int, currentIndex: Int, comparisonIndices: [Int]) -> FilmstripTileList {
        FilmstripTileList(count: max(0, count), currentIndex: currentIndex, comparisonIndices: comparisonIndices)
    }
}
