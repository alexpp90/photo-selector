import Foundation

/// One-time copy of the user's preferences from the bundle id used before the Lensjet rename
/// (`com.photoselectortoolbox.macos`) into the app's current defaults.
///
/// Rules: only keys the current domain does not have yet are copied (nothing is ever
/// overwritten), the old domain is only read (never changed or deleted), and a done-marker is
/// written so the copy happens once. The app is not sandboxed (`build_app.sh` signs ad hoc with
/// no entitlements), so `UserDefaults(suiteName:)` reads the old
/// `~/Library/Preferences/com.photoselectortoolbox.macos.plist`.
public enum SettingsMigration {
    /// Defaults domain of the pre-rename app bundle.
    public static let legacyDomain = "com.photoselectortoolbox.macos"
    /// Set in the current defaults once the migration has run.
    public static let doneMarkerKey = "lensjetSettingsMigrationDone"

    /// Copies missing keys from `legacy` into `defaults`, once.
    /// - Parameters:
    ///   - defaults: destination (the app's standard defaults).
    ///   - legacy: the old domain; when nil it is opened from `legacyDomain`. An absent old
    ///     domain is fine: nothing is copied and the marker is still written.
    ///   - keys: keys to carry over; defaults to every key the app persists.
    /// - Returns: the number of keys copied (0 when already migrated).
    @discardableResult
    public static func migrateIfNeeded(
        into defaults: UserDefaults = .standard,
        from legacy: UserDefaults? = nil,
        keys: [String] = AppSettingsKeys.all
    ) -> Int {
        if defaults.bool(forKey: doneMarkerKey) { return 0 }
        var copied = 0
        if let source = legacy ?? UserDefaults(suiteName: legacyDomain) {
            for key in keys where defaults.object(forKey: key) == nil {
                if let value = source.object(forKey: key) {
                    defaults.set(value, forKey: key)
                    copied += 1
                }
            }
        }
        defaults.set(true, forKey: doneMarkerKey)
        return copied
    }
}
