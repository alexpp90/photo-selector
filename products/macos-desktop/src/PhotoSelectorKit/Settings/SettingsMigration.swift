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

    /// Set once the sort order of an existing install has been pinned (or found not to need it).
    public static let sortPinnedMarkerKey = "lensjetSortOrderPinned"

    /// Set once the aesthetics setting of an existing install has been pinned (or found not to need it).
    public static let aestheticsPinnedMarkerKey = "lensjetAestheticsPinned"

    /// This code has run before (the migration marker is set), or the defaults or the pre-rename
    /// domain already hold a Lensjet setting.
    static func isExistingInstall(_ defaults: UserDefaults, legacy: UserDefaults? = nil) -> Bool {
        let legacySource = legacy ?? UserDefaults(suiteName: legacyDomain)
        return defaults.bool(forKey: doneMarkerKey)
            || AppSettingsKeys.all.contains { defaults.object(forKey: $0) != nil }
            || (legacySource.map { source in AppSettingsKeys.all.contains { source.object(forKey: $0) != nil } } ?? false)
    }

    /// Aesthetics scoring is off for a new install ([USER 2026-11-12] decision #21); an install that
    /// predates that default ran with it on, so it keeps it on unless the user stored a choice, here
    /// or in the pre-rename domain (`migrateIfNeeded` copies that one). Runs once, before
    /// `migrateIfNeeded`, writes nothing for a new install and never overrides a stored value.
    /// - Returns: true when the existing install's "on" was pinned.
    @discardableResult
    public static func pinAestheticsForExistingInstall(
        in defaults: UserDefaults = .standard,
        legacy: UserDefaults? = nil
    ) -> Bool {
        if defaults.bool(forKey: aestheticsPinnedMarkerKey) { return false }
        defer { defaults.set(true, forKey: aestheticsPinnedMarkerKey) }
        guard defaults.object(forKey: AppSettingsKeys.aestheticScoringEnabled) == nil else { return false }
        let legacySource = legacy ?? UserDefaults(suiteName: legacyDomain)
        guard legacySource?.object(forKey: AppSettingsKeys.aestheticScoringEnabled) == nil else { return false }
        guard isExistingInstall(defaults, legacy: legacy) else { return false }
        defaults.aestheticScoringEnabled = true
        return true
    }

    /// A new install opens newest first; an install that predates the sort menu keeps the file-name
    /// order it always had ("Updates must not destroy the user's experience"). An install is an
    /// existing one when this code has run before (the migration marker is set), or the defaults or
    /// the pre-rename domain already hold a Lensjet setting. Runs once, writes nothing for a
    /// new install, and never overrides a stored order.
    /// - Returns: true when the file-name order was pinned.
    @discardableResult
    public static func pinSortOrderForExistingInstall(
        in defaults: UserDefaults = .standard,
        legacy: UserDefaults? = nil
    ) -> Bool {
        if defaults.bool(forKey: sortPinnedMarkerKey) { return false }
        defer { defaults.set(true, forKey: sortPinnedMarkerKey) }
        guard defaults.object(forKey: AppSettingsKeys.sortOrder) == nil else { return false }
        guard isExistingInstall(defaults, legacy: legacy) else { return false }
        defaults.workspaceSortOrder = .fileName
        return true
    }

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
