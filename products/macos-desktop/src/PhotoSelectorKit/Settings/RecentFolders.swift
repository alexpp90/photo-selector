import Foundation

/// The folders opened before (File > Open Recent, newest first, at most ten) and, per folder, the
/// photo that was on screen, remembered by the photo's file path (its identity), never by its
/// index: a re-sort or photos moved away must not change which photo reopens.
///
/// This is session state, not a preference: it is kept out of `AppSettingsKeys.all`, so
/// `SettingsMigration` does not copy it and "Reset" of a setting never touches it.
public struct RecentFolders {
    public static let limit = 10
    static let foldersKey = "lensjet.recentFolders"
    static let positionsKey = "lensjet.lastPhotoByFolder"

    private let defaults: UserDefaults
    private let fileManager: FileManager

    public init(defaults: UserDefaults = .standard, fileManager: FileManager = .default) {
        self.defaults = defaults
        self.fileManager = fileManager
    }

    private static func path(of url: URL) -> String { url.standardizedFileURL.path }

    /// Stored folder paths, newest first (including folders that no longer exist).
    private var storedPaths: [String] { defaults.stringArray(forKey: Self.foldersKey) ?? [] }

    /// The folders that still exist, newest first.
    public var folders: [URL] {
        storedPaths.compactMap { path in
            var isDirectory: ObjCBool = false
            guard fileManager.fileExists(atPath: path, isDirectory: &isDirectory), isDirectory.boolValue else { return nil }
            return URL(fileURLWithPath: path, isDirectory: true)
        }
    }

    /// The folder to reopen at launch: the newest one that still exists; nil starts on the start view.
    public var lastFolder: URL? { folders.first }

    /// Puts `url` first; a repeat moves it up; the list never grows past `limit`.
    public func record(_ url: URL) {
        let path = Self.path(of: url)
        var paths = storedPaths.filter { $0 != path }
        paths.insert(path, at: 0)
        defaults.set(Array(paths.prefix(Self.limit)), forKey: Self.foldersKey)
    }

    /// "Clear Menu".
    public func clear() {
        defaults.removeObject(forKey: Self.foldersKey)
        defaults.removeObject(forKey: Self.positionsKey)
    }

    /// Remembers the photo on screen in `folder`. Only folders of the recent list keep a position,
    /// so the store stays as small as the list.
    public func remember(photo: URL, in folder: URL) {
        var positions = defaults.dictionary(forKey: Self.positionsKey) as? [String: String] ?? [:]
        positions[Self.path(of: folder)] = Self.path(of: photo)
        let keep = Set(storedPaths)
        positions = positions.filter { keep.contains($0.key) }
        defaults.set(positions, forKey: Self.positionsKey)
    }

    /// The remembered photo of `folder` if it still exists on disk.
    public func rememberedPhoto(in folder: URL) -> URL? {
        let positions = defaults.dictionary(forKey: Self.positionsKey) as? [String: String] ?? [:]
        guard let path = positions[Self.path(of: folder)], fileManager.fileExists(atPath: path) else { return nil }
        return URL(fileURLWithPath: path)
    }
}
