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
        #expect(AppSettingsKeys.all.count == 13)
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

    // MARK: - Sort order default (new install vs. update)

    @Test("A new install opens newest first and nothing is written")
    func newInstallOpensNewestFirst() {
        let (old, oldName) = makeSuite("old")
        let (new, newName) = makeSuite("new")
        defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }

        #expect(SettingsMigration.pinSortOrderForExistingInstall(in: new, legacy: old) == false)
        #expect(new.object(forKey: AppSettingsKeys.sortOrder) == nil)
        #expect(new.workspaceSortOrder == .newestFirst)
    }

    @Test("An install that predates the sort menu keeps the file-name order")
    func existingInstallKeepsFileNameOrder() {
        for trigger in ["marker", "own key", "legacy key"] {
            let (old, oldName) = makeSuite("old")
            let (new, newName) = makeSuite("new")
            defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }
            switch trigger {
            case "marker": new.set(true, forKey: SettingsMigration.doneMarkerKey)
            case "own key": new.set(false, forKey: AppSettingsKeys.autoAdvance)
            default: old.set("Picks", forKey: AppSettingsKeys.cullingDestinationFolderName)
            }
            #expect(SettingsMigration.pinSortOrderForExistingInstall(in: new, legacy: old), "\(trigger)")
            #expect(new.workspaceSortOrder == .fileName, "\(trigger)")
            // Idempotent, and a later choice is never undone.
            new.workspaceSortOrder = .oldestFirst
            #expect(SettingsMigration.pinSortOrderForExistingInstall(in: new, legacy: old) == false)
            #expect(new.workspaceSortOrder == .oldestFirst)
        }
    }

    @Test("The first name of the file-name order (natural) still reads")
    func naturalReadsAsFileName() {
        let (new, newName) = makeSuite("new")
        defer { new.removePersistentDomain(forName: newName) }
        new.set("natural", forKey: AppSettingsKeys.sortOrder)
        #expect(new.workspaceSortOrder == .fileName)
        new.set("garbage", forKey: AppSettingsKeys.sortOrder)
        #expect(new.workspaceSortOrder == .newestFirst)
    }

    @Test("Upgrade from the oldest shipped settings: every key keeps its effect")
    func upgradeKeepsEveryShippedSetting() {
        let (old, oldName) = makeSuite("old")
        let (new, newName) = makeSuite("new")
        defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }

        // The keys of the first Lensjet release, all set to non-default values in the old domain.
        old.set("Picks", forKey: "cullingDestinationFolderName")
        old.set("/Volumes/SSD/Picks", forKey: "externalCullingDestinationURL")
        old.set(false, forKey: "autoAdvance")
        old.set(20.0, forKey: "sharpnessBlurryThreshold")
        old.set(60.0, forKey: "sharpnessAcceptableThreshold")
        old.set(5.0, forKey: "aestheticThreshold")
        old.set(false, forKey: "aestheticScoringEnabled")
        old.set(1024, forKey: "thumbnailCacheLimitMB")
        old.set(4, forKey: "preloadWindowBefore")
        old.set(8, forKey: "preloadWindowAfter")

        // The order the app runs them in at launch.
        SettingsMigration.pinSortOrderForExistingInstall(in: new, legacy: old)
        SettingsMigration.migrateIfNeeded(into: new, from: old)

        #expect(new.cullingDestinationFolderName == "Picks")
        #expect(new.externalCullingDestinationURL?.path == "/Volumes/SSD/Picks")
        // A store with a custom folder but no mode reads as custom: photos keep going where they went.
        #expect(new.cullingDestinationMode == .customFolder)
        #expect(new.autoAdvance == false)
        #expect(new.sharpnessBlurryThreshold == 20.0)
        #expect(new.sharpnessAcceptableThreshold == 60.0)
        #expect(new.aestheticThreshold == 5.0)
        #expect(new.aestheticScoringEnabled == false)
        #expect(new.thumbnailCacheLimitMB == 1024)
        #expect(new.preloadWindowBefore == 4)
        #expect(new.preloadWindowAfter == 8)
        // New settings: the order the user always had, and the stop threshold off.
        #expect(new.workspaceSortOrder == .fileName)
        #expect(new.sharpnessStopThreshold == 0)
    }

    // MARK: - Aesthetics scoring is off for a new install and stays on for an existing one
    // ([USER 2026-11-12] decision #21). Each earlier schema is a store a released version wrote.

    @Test("A new install: aesthetics off, nothing written but the marker")
    func newInstallAestheticsOff() {
        let (old, oldName) = makeSuite("old")
        let (new, newName) = makeSuite("new")
        defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }
        #expect(SettingsMigration.pinAestheticsForExistingInstall(in: new, legacy: old) == false)
        SettingsMigration.migrateIfNeeded(into: new, from: old)
        #expect(new.aestheticScoringEnabled == false)
        #expect(new.object(forKey: AppSettingsKeys.aestheticScoringEnabled) == nil)
    }

    @Test("Earlier schema A: the first Lensjet release (marker only, no stored scoring key) keeps aesthetics on")
    func markerOnlyKeepsOn() {
        let (old, oldName) = makeSuite("old")
        let (new, newName) = makeSuite("new")
        defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }
        new.set(true, forKey: SettingsMigration.doneMarkerKey)
        #expect(SettingsMigration.pinAestheticsForExistingInstall(in: new, legacy: old) == true)
        #expect(new.aestheticScoringEnabled == true)
    }

    @Test("Earlier schema B: another setting stored, scoring key never touched, keeps aesthetics on")
    func otherKeyKeepsOn() {
        let (old, oldName) = makeSuite("old")
        let (new, newName) = makeSuite("new")
        defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }
        new.set(false, forKey: AppSettingsKeys.autoAdvance)
        SettingsMigration.pinAestheticsForExistingInstall(in: new, legacy: old)
        #expect(new.aestheticScoringEnabled == true)
    }

    @Test("Earlier schema C: the pre-rename domain holds settings but no scoring key: on")
    func legacyDomainWithoutKeyKeepsOn() {
        let (old, oldName) = makeSuite("old")
        let (new, newName) = makeSuite("new")
        defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }
        old.set(7, forKey: AppSettingsKeys.preloadWindowAfter)
        SettingsMigration.pinAestheticsForExistingInstall(in: new, legacy: old)
        SettingsMigration.migrateIfNeeded(into: new, from: old)
        #expect(new.aestheticScoringEnabled == true)
    }

    @Test("A stored choice is honoured, here and in the pre-rename domain, in the launch order")
    func storedChoiceHonoured() {
        let (old, oldName) = makeSuite("old")
        let (new, newName) = makeSuite("new")
        let (old2, old2Name) = makeSuite("old2")
        let (new2, new2Name) = makeSuite("new2")
        defer {
            for (d, n) in [(old, oldName), (new, newName), (old2, old2Name), (new2, new2Name)] { d.removePersistentDomain(forName: n) }
        }
        new.set(true, forKey: SettingsMigration.doneMarkerKey)
        new.set(false, forKey: AppSettingsKeys.aestheticScoringEnabled)
        SettingsMigration.pinAestheticsForExistingInstall(in: new, legacy: old)
        #expect(new.aestheticScoringEnabled == false)

        old2.set(false, forKey: AppSettingsKeys.aestheticScoringEnabled)
        SettingsMigration.pinSortOrderForExistingInstall(in: new2, legacy: old2)
        SettingsMigration.pinAestheticsForExistingInstall(in: new2, legacy: old2)
        SettingsMigration.migrateIfNeeded(into: new2, from: old2)
        #expect(new2.aestheticScoringEnabled == false, "the pre-rename stored off is not overwritten by the pin")
    }

    @Test("The pin runs once: a later switch-off is not undone")
    func pinRunsOnce() {
        let (old, oldName) = makeSuite("old")
        let (new, newName) = makeSuite("new")
        defer { old.removePersistentDomain(forName: oldName); new.removePersistentDomain(forName: newName) }
        new.set(true, forKey: SettingsMigration.doneMarkerKey)
        SettingsMigration.pinAestheticsForExistingInstall(in: new, legacy: old)
        new.removeObject(forKey: AppSettingsKeys.aestheticScoringEnabled)
        #expect(SettingsMigration.pinAestheticsForExistingInstall(in: new, legacy: old) == false)
        #expect(new.aestheticScoringEnabled == false)
    }
}
