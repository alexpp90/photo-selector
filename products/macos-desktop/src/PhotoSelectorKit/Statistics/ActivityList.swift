import Foundation

/// One line of the Activity list ([USER 2026-11-12] decision #23): a decision that stands.
public struct ActivityRow: Identifiable, Equatable, Sendable {
    public let id: UUID
    /// SF Symbol of the verb: `arrow.right.circle` move, `doc.on.doc` copy, `trash` trash.
    public let symbol: String
    public let verb: String
    public let filename: String
    /// Dimmed text after the file name; nil for an entry without one.
    public let destination: String?
    public let relativeTime: String
    /// The one spoken item: "Moved IMG_0001.CR3 to Selection, 2 minutes ago".
    public let spokenLabel: String
    /// The photo to select when the row is clicked, when it is still in the folder.
    public let photoID: UUID?
}

/// The decision log as an Activity list: newest first, at most `limit` rows, one spoken item per row.
public enum ActivityList {
    public static let limit = 100
    public static let emptyText = "No decisions yet"

    public static func rows(from log: [CullingDecisionLogEntry], now: Date = Date()) -> [ActivityRow] {
        log.suffix(limit).reversed().map { entry in
            let (symbol, verb): (String, String) = {
                switch entry.action {
                case .move: return ("arrow.right.circle", "Moved")
                case .copy: return ("doc.on.doc", "Copied")
                case .trash: return ("trash", entry.destination == RejectsDestination.folderName ? "Rejected" : "Trashed")
                }
            }()
            let time = relativeTime(from: entry.timestamp, to: now)
            let to = entry.destination.map { " to \($0)" } ?? ""
            return ActivityRow(
                id: entry.id, symbol: symbol, verb: verb, filename: entry.filename,
                destination: entry.destination, relativeTime: time,
                spokenLabel: "\(verb) \(entry.filename)\(to), \(time)", photoID: entry.photoID)
        }
    }

    static func relativeTime(from date: Date, to now: Date) -> String {
        if now.timeIntervalSince(date) < 5 { return "just now" }
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = Locale(identifier: "en_US") // English only, by decision
        formatter.unitsStyle = .full
        // The formatter separates number and unit with a no-break space; spoken and compared text use a plain one.
        return formatter.localizedString(for: date, relativeTo: now)
            .replacingOccurrences(of: "\u{00A0}", with: " ").replacingOccurrences(of: "\u{202F}", with: " ")
    }
}
