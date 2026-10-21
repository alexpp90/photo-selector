import Foundation

/// Type-safe UserDefaults keys for user-configurable preferences.
public enum AppSettingsKeys {
    // MARK: - General
    public static let cullingDestinationFolderName = "cullingDestinationFolderName"
    public static let externalCullingDestinationURL = "externalCullingDestinationURL"
    public static let autoAdvance = "autoAdvance"

    // MARK: - Scoring & Aesthetics
    public static let sharpnessBlurryThreshold = "sharpnessBlurryThreshold"
    public static let sharpnessAcceptableThreshold = "sharpnessAcceptableThreshold"
    public static let aestheticThreshold = "aestheticThreshold"
    public static let aestheticScoringEnabled = "aestheticScoringEnabled"

    // MARK: - Cache & Performance
    public static let thumbnailCacheLimitMB = "thumbnailCacheLimitMB"
    public static let preloadWindowBefore = "preloadWindowBefore"
    public static let preloadWindowAfter = "preloadWindowAfter"

    /// Every key the app persists in `UserDefaults`. `SettingsMigration` copies exactly these;
    /// add a new key here when you add one above (a test pins the list).
    public static let all: [String] = [
        cullingDestinationFolderName,
        externalCullingDestinationURL,
        autoAdvance,
        sharpnessBlurryThreshold,
        sharpnessAcceptableThreshold,
        aestheticThreshold,
        aestheticScoringEnabled,
        thumbnailCacheLimitMB,
        preloadWindowBefore,
        preloadWindowAfter
    ]
}

/// Fallback default values for all preferences.
public struct AppSettingsDefaults {
    // General Defaults
    public static let cullingDestinationFolderName: String = "Selection"
    public static let autoAdvance: Bool = true

    // Scoring Defaults
    public static let sharpnessBlurryThreshold: Double = 35.0
    public static let sharpnessAcceptableThreshold: Double = 70.0
    public static let aestheticThreshold: Double = 0.0
    /// Apple Vision aesthetic (AI) scoring runs lazily after the photo is on screen; users can
    /// switch it off entirely, in which case the Vision request is never issued.
    public static let aestheticScoringEnabled: Bool = true

    // Cache & Performance Defaults
    public static let thumbnailCacheLimitMB: Int = 512
    public static let preloadWindowBefore: Int = 2
    public static let preloadWindowAfter: Int = 4
}

/// Typed UserDefaults extension providing direct property access and reset capability.
public extension UserDefaults {
    var cullingDestinationFolderName: String {
        get {
            guard let val = string(forKey: AppSettingsKeys.cullingDestinationFolderName) else {
                return AppSettingsDefaults.cullingDestinationFolderName
            }
            return AppSettings.sanitizeDestinationFolderName(val)
        }
        set {
            let sanitized = AppSettings.sanitizeDestinationFolderName(newValue)
            set(sanitized, forKey: AppSettingsKeys.cullingDestinationFolderName)
        }
    }

    /// Optional external base directory for Move/Copy destination (e.g., an internal SSD folder
    /// when culling directly off an SD card). When nil, Move/Copy targets `<rootDirectoryURL>/<cullingDestinationFolderName>`.
    var externalCullingDestinationURL: URL? {
        get {
            guard let rawPath = string(forKey: AppSettingsKeys.externalCullingDestinationURL)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
                  !rawPath.isEmpty else {
                return nil
            }
            return URL(fileURLWithPath: rawPath, isDirectory: true)
        }
        set {
            if let url = newValue {
                set(url.standardizedFileURL.path, forKey: AppSettingsKeys.externalCullingDestinationURL)
            } else {
                removeObject(forKey: AppSettingsKeys.externalCullingDestinationURL)
            }
        }
    }

    var autoAdvance: Bool {
        get {
            object(forKey: AppSettingsKeys.autoAdvance) as? Bool ?? AppSettingsDefaults.autoAdvance
        }
        set {
            set(newValue, forKey: AppSettingsKeys.autoAdvance)
        }
    }

    var sharpnessBlurryThreshold: Double {
        get {
            let val = object(forKey: AppSettingsKeys.sharpnessBlurryThreshold) as? Double
            return val ?? AppSettingsDefaults.sharpnessBlurryThreshold
        }
        set {
            set(newValue, forKey: AppSettingsKeys.sharpnessBlurryThreshold)
        }
    }

    var sharpnessAcceptableThreshold: Double {
        get {
            let val = object(forKey: AppSettingsKeys.sharpnessAcceptableThreshold) as? Double
            return val ?? AppSettingsDefaults.sharpnessAcceptableThreshold
        }
        set {
            set(newValue, forKey: AppSettingsKeys.sharpnessAcceptableThreshold)
        }
    }

    var aestheticThreshold: Double {
        get {
            let val = object(forKey: AppSettingsKeys.aestheticThreshold) as? Double
            return val ?? AppSettingsDefaults.aestheticThreshold
        }
        set {
            set(newValue, forKey: AppSettingsKeys.aestheticThreshold)
        }
    }

    var aestheticScoringEnabled: Bool {
        get {
            object(forKey: AppSettingsKeys.aestheticScoringEnabled) as? Bool ?? AppSettingsDefaults.aestheticScoringEnabled
        }
        set {
            set(newValue, forKey: AppSettingsKeys.aestheticScoringEnabled)
        }
    }

    var thumbnailCacheLimitMB: Int {
        get {
            let val = integer(forKey: AppSettingsKeys.thumbnailCacheLimitMB)
            return val > 0 ? val : AppSettingsDefaults.thumbnailCacheLimitMB
        }
        set {
            set(newValue, forKey: AppSettingsKeys.thumbnailCacheLimitMB)
        }
    }

    var preloadWindowBefore: Int {
        get {
            let val = integer(forKey: AppSettingsKeys.preloadWindowBefore)
            return val > 0 ? val : AppSettingsDefaults.preloadWindowBefore
        }
        set {
            set(newValue, forKey: AppSettingsKeys.preloadWindowBefore)
        }
    }

    var preloadWindowAfter: Int {
        get {
            let val = integer(forKey: AppSettingsKeys.preloadWindowAfter)
            return val > 0 ? val : AppSettingsDefaults.preloadWindowAfter
        }
        set {
            set(newValue, forKey: AppSettingsKeys.preloadWindowAfter)
        }
    }

    /// Resets all application settings to their documented defaults.
    func resetAppSettingsToDefaults() {
        removeObject(forKey: AppSettingsKeys.cullingDestinationFolderName)
        removeObject(forKey: AppSettingsKeys.externalCullingDestinationURL)
        removeObject(forKey: AppSettingsKeys.autoAdvance)
        removeObject(forKey: AppSettingsKeys.sharpnessBlurryThreshold)
        removeObject(forKey: AppSettingsKeys.sharpnessAcceptableThreshold)
        removeObject(forKey: AppSettingsKeys.aestheticThreshold)
        removeObject(forKey: AppSettingsKeys.aestheticScoringEnabled)
        removeObject(forKey: AppSettingsKeys.thumbnailCacheLimitMB)
        removeObject(forKey: AppSettingsKeys.preloadWindowBefore)
        removeObject(forKey: AppSettingsKeys.preloadWindowAfter)
    }
}

/// Centralized manager for application settings.
public struct AppSettings: Sendable {
    /// Sanitizes user input for destination folder names.
    /// Strips directory separators ('/', '\', ':'), traversal sequences ('..'),
    /// and trims whitespace and dots. Falls back to AppSettingsDefaults on empty result.
    public static func sanitizeDestinationFolderName(_ name: String) -> String {
        var sanitized = name.trimmingCharacters(in: .whitespacesAndNewlines)
        sanitized = sanitized.replacingOccurrences(of: "/", with: "")
        sanitized = sanitized.replacingOccurrences(of: "\\", with: "")
        sanitized = sanitized.replacingOccurrences(of: ":", with: "")
        sanitized = sanitized.replacingOccurrences(of: "..", with: "")
        sanitized = sanitized.trimmingCharacters(in: CharacterSet(charactersIn: ".").union(.whitespacesAndNewlines))
        return sanitized.isEmpty ? AppSettingsDefaults.cullingDestinationFolderName : sanitized
    }

    public static func reset(userDefaults: UserDefaults = .standard) {
        userDefaults.resetAppSettingsToDefaults()
    }
}
