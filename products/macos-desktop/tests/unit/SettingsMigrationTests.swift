import Testing
import Foundation
@testable import PhotoSelectorKit

@Suite("Settings migration from the pre-Lensjet bundle id")
struct SettingsMigrationTests {
    /// A throwaway defaults domain that is wiped before and after use.
    private func makeSuite(_ label: String) -> (UserDefaults, String) {
        let name = "com.photoselector.tests.migration.\(label).\(UUID().uuidString)"
        let suite = UserDefaults(suiteName: name)!
        suite.removePersistentDomain(forName: name)
        return (suite, name)
    }

    @Test("Copies keys the new domain does not have yet")
    func copiesMissingKeys() {
        let (old, oldName) = makeSuite("old")
        let (new, newName) = makeSuite("new")
        defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }

        old.set("ClientPicks", forKey: AppSettingsKeys.cullingDestinationFolderName)
        old.set(false, forKey: AppSettingsKeys.autoAdvance)
        old.set(42.5, forKey: AppSettingsKeys.sharpnessBlurryThreshold)
        old.set(1024, forKey: AppSettingsKeys.thumbnailCacheLimitMB)
        old.set("/Volumes/SSD/Picks", forKey: AppSettingsKeys.externalCullingDestinationURL)

        let copied = SettingsMigration.migrateIfNeeded(into: new, from: old)

        #expect(copied == 5)
        #expect(new.string(forKey: AppSettingsKeys.cullingDestinationFolderName) == "ClientPicks")
        #expect(new.object(forKey: AppSettingsKeys.autoAdvance) as? Bool == false)
        #expect(new.double(forKey: AppSettingsKeys.sharpnessBlurryThreshold) == 42.5)
        #expect(new.integer(forKey: AppSettingsKeys.thumbnailCacheLimitMB) == 1024)
        #expect(new.externalCullingDestinationURL?.path == "/Volumes/SSD/Picks")
        #expect(new.bool(forKey: SettingsMigration.doneMarkerKey))
    }

    @Test("Covers every key the app persists")
    func coversEveryKey() {
        let (old, oldName) = makeSuite("old")
        let (new, newName) = makeSuite("new")
        defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }

        for key in AppSettingsKeys.all { old.set("value-\(key)", forKey: key) }
        let copied = SettingsMigration.migrateIfNeeded(into: new, from: old)

        #expect(copied == AppSettingsKeys.all.count)
        #expect(Set(AppSettingsKeys.all).count == AppSettingsKeys.all.count)
        #expect(AppSettingsKeys.all.count == 10)
        for key in AppSettingsKeys.all {
            #expect(new.string(forKey: key) == "value-\(key)")
        }
    }

    @Test("Never overwrites a value the new domain already has")
    func neverOverwrites() {
        let (old, oldName) = makeSuite("old")
        let (new, newName) = makeSuite("new")
        defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }

        old.set("Old", forKey: AppSettingsKeys.cullingDestinationFolderName)
        old.set(false, forKey: AppSettingsKeys.autoAdvance)
        new.set("New", forKey: AppSettingsKeys.cullingDestinationFolderName)

        let copied = SettingsMigration.migrateIfNeeded(into: new, from: old)

        #expect(copied == 1)
        #expect(new.string(forKey: AppSettingsKeys.cullingDestinationFolderName) == "New")
        #expect(new.object(forKey: AppSettingsKeys.autoAdvance) as? Bool == false)
    }

    @Test("Runs once and leaves the old domain untouched")
    func runsOnce() {
        let (old, oldName) = makeSuite("old")
        let (new, newName) = makeSuite("new")
        defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }

        old.set(true, forKey: AppSettingsKeys.aestheticScoringEnabled)
        #expect(SettingsMigration.migrateIfNeeded(into: new, from: old) == 1)

        // A key added to the old domain later, or removed from the new one, is not picked up.
        old.set(7, forKey: AppSettingsKeys.preloadWindowBefore)
        new.removeObject(forKey: AppSettingsKeys.aestheticScoringEnabled)
        #expect(SettingsMigration.migrateIfNeeded(into: new, from: old) == 0)
        #expect(new.object(forKey: AppSettingsKeys.preloadWindowBefore) == nil)
        #expect(new.object(forKey: AppSettingsKeys.aestheticScoringEnabled) == nil)

        // The old domain still has everything.
        #expect(old.object(forKey: AppSettingsKeys.aestheticScoringEnabled) as? Bool == true)
        #expect(old.integer(forKey: AppSettingsKeys.preloadWindowBefore) == 7)
    }

    @Test("Tolerates an absent old domain and still records the marker")
    func absentOldDomain() {
        let (old, oldName) = makeSuite("absent")
        let (new, newName) = makeSuite("new")
        defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }

        let copied = SettingsMigration.migrateIfNeeded(into: new, from: old)

        #expect(copied == 0)
        #expect(new.bool(forKey: SettingsMigration.doneMarkerKey))
        for key in AppSettingsKeys.all { #expect(new.object(forKey: key) == nil) }
    }

    @Test("The legacy domain is the pre-rename bundle id")
    func legacyDomainConstant() {
        #expect(SettingsMigration.legacyDomain == "com.photoselectortoolbox.macos")
    }
}
