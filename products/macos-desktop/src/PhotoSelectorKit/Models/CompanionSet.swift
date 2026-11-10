import Foundation

/// The files that travel with a photo: what the chips on the photo show (REQ-MAC-FILES.05).
public enum CompanionKind: String, Sendable, Equatable, CaseIterable {
    case raw = "RAW"
    case jpeg = "JPG"
    case sidecar = "XMP"
    case edit = "EDIT"
    case other = "FILE"

    /// A glyph per kind, so the chip is not told apart by its word alone.
    public var symbol: String {
        switch self {
        case .raw: return "camera.aperture"
        case .jpeg: return "photo"
        case .sidecar: return "doc.text"
        case .edit: return "wand.and.stars"
        case .other: return "doc"
        }
    }

    public var spokenName: String {
        switch self {
        case .raw: return "RAW file"
        case .jpeg: return "JPEG file"
        case .sidecar: return "XMP sidecar"
        case .edit: return "edited version"
        case .other: return "other file"
        }
    }
}

public enum CompanionSet {

    /// The distinct kinds of `companions` (the primary itself excluded), in a stable order:
    /// RAW, JPG, EDIT, XMP, other. Empty when the photo travels alone.
    public static func kinds(primary: URL, companions: [URL]) -> [CompanionKind] {
        let primaryName = primary.lastPathComponent
        let primaryStem = primary.deletingPathExtension().lastPathComponent.lowercased()
        var found = Set<CompanionKind>()
        for url in companions where url.lastPathComponent != primaryName {
            let ext = url.pathExtension.lowercased()
            let stem = url.deletingPathExtension().lastPathComponent.lowercased()
            if ext == "xmp" {
                found.insert(.sidecar)
            } else if stem != primaryStem, stem.hasPrefix(primaryStem) {
                found.insert(.edit)
            } else if SupportedFormats.rawExtensions.contains(ext) {
                found.insert(.raw)
            } else if ext == "jpg" || ext == "jpeg" {
                found.insert(.jpeg)
            } else {
                found.insert(.other)
            }
        }
        return [CompanionKind.raw, .jpeg, .edit, .sidecar, .other].filter(found.contains)
    }

    /// "RAW, JPG, XMP" for a tooltip; nil when there are none.
    public static func summary(_ kinds: [CompanionKind]) -> String? {
        kinds.isEmpty ? nil : kinds.map(\.rawValue).joined(separator: ", ")
    }

    /// VoiceOver: "Travels with a JPEG file and an XMP sidecar".
    public static func spoken(_ kinds: [CompanionKind]) -> String? {
        guard !kinds.isEmpty else { return nil }
        return "Travels with " + kinds.map(\.spokenName).joined(separator: ", ")
    }
}
