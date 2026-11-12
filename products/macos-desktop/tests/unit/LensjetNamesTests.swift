import Testing
import Foundation
@testable import PhotoSelectorKit

/// One name per thing: the Kit's `LensjetCommand` table is the only place a command is named, and
/// every surface reads it. These tests read the real source files, so a surface that drifts from
/// the table (a hand-typed "Focus 3-Up", a jargon line) fails here.
@Suite("Names: one table, read by every surface")
struct LensjetNamesTests {

    private static var productRoot: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    }

    private static func source(_ relative: String) throws -> String {
        try String(contentsOf: productRoot.appendingPathComponent("src/\(relative)"), encoding: .utf8)
    }

    // MARK: The table

    @Test("Every command has a name, names are unique, keys are unique")
    func tableIsConsistent() {
        let names = LensjetCommand.allCases.map { $0.name() }
        #expect(names.allSatisfy { !$0.isEmpty })
        #expect(Set(names).count == names.count)
        let keys = LensjetCommand.allCases.compactMap(\.key)
        #expect(Set(keys).count == keys.count)
    }

    @Test("The names the product settled on")
    func settledNames() {
        #expect(LensjetCommand.threeUp.name() == "3-Up")
        #expect(LensjetCommand.twoUp.name() == "2-Up")
        #expect(LensjetCommand.singleUp.name() == "1-Up")
        #expect(LensjetCommand.findDuplicates.name() == "Find Duplicates")
        #expect(LensjetCommand.statistics.name() == "Statistics")
        #expect(LensjetCommand.keyboardShortcuts.name() == "Keyboard Shortcuts")
        #expect(LensjetCommand.autoAdvance.name() == "Auto-Advance")
        #expect(LensjetCommand.info.name() == "Info")
        #expect(LensjetCommand.infoMenuTitle(isShown: false) == "Show Info")
        #expect(LensjetCommand.infoMenuTitle(isShown: true) == "Hide Info")
        #expect(LensjetWords.tally == ["Selected", "Copied", "Trashed", "Remaining"])
    }

    @Test("Move and Copy carry the configured destination folder name; the default is Selection")
    func destinationName() {
        #expect(LensjetCommand.move.name() == "Move to Selection")
        #expect(LensjetCommand.move.name(destination: "Keepers") == "Move to Keepers")
        #expect(LensjetCommand.copy.tooltip(destination: "Keepers") == "Copy to Keepers (C)")
        #expect(ChromeDensity.full.dockTitle(.move, destination: "Keepers") == "Move to Keepers")
        #expect(ChromeDensity.compact.dockTitle(.move, destination: "Keepers") == "Move")
    }

    @Test("The toolbar and the dock read the table")
    func chromeReadsTable() {
        for shortcut in 1...3 {
            let command: LensjetCommand = [.singleUp, .twoUp, .threeUp][shortcut - 1]
            #expect(ChromeDensity.full.modeLabel(shortcut: shortcut) == command.name())
            #expect(ChromeDensity.compact.modeLabel(shortcut: shortcut) == command.name())
        }
        for action in ChromeDensity.DockAction.allCases {
            #expect(action.fullTitle == action.command.name())
            #expect(action.shortTitle == action.command.shortName)
        }
    }

    @Test("Undo names the action and the file")
    func undoTitle() {
        #expect(LensjetCommand.undoMenuTitle(action: .move, filename: "IMG_0001.CR3") == "Undo Move of IMG_0001.CR3")
        #expect(LensjetCommand.undoMenuTitle(action: .copy, filename: "a.jpg") == "Undo Copy of a.jpg")
        #expect(LensjetCommand.undoMenuTitle(action: .trash, filename: "a.jpg") == "Undo Trash of a.jpg")
        #expect(LensjetCommand.undoMenuTitle(action: nil, filename: nil) == "Undo")
    }

    // MARK: The menu bar

    @Test("Every command is in exactly one menu; menu titles are unique; Undo is in Edit")
    func menuStructure() {
        let titles = LensjetMenu.allCases.map(\.rawValue)
        #expect(Set(titles).count == titles.count)
        let placed = LensjetMenu.allCases.flatMap(\.commands)
        #expect(Set(placed.map { "\($0)" }).count == placed.count)
        #expect(Set(placed.map { "\($0)" }) == Set(LensjetCommand.allCases.map { "\($0)" }))
        #expect(LensjetMenu.edit.commands == [.undo])
        #expect(LensjetMenu.file.commands.contains(.showInFinder) && LensjetMenu.file.commands.contains(.quickLook))
        #expect(LensjetMenu.go.commands.contains(.previousPhoto) && LensjetMenu.go.commands.contains(.nextPhoto))
        #expect(LensjetMenu.view.commands.contains(.switchPhoto))
        #expect(LensjetCommand.showInFinder.key == "\u{21E7}\u{2318}R")
        #expect(LensjetCommand.quickLook.key == "\u{2318}Y")
    }

    @Test("AppCommands puts each command under the menu the table names, and adds to the system View menu")
    func commandsFileFollowsTable() throws {
        let text = try Self.source("PhotoSelectorApp/AppCommands.swift")
        // Sections start at "// MARK: <Menu>".
        let parts = text.components(separatedBy: "// MARK: ").dropFirst()
        var sections: [String: String] = [:]
        for part in parts {
            let title = String(part.prefix { $0.isLetter })
            sections[title] = part
        }
        for menu in LensjetMenu.allCases {
            let body = try #require(sections[menu.rawValue], "no section for the \(menu.rawValue) menu")
            for command in menu.commands {
                #expect(body.contains("LensjetCommand.\(command)"), "\(command) is not in the \(menu.rawValue) section")
            }
        }
        // One View menu: items join the system's (which keeps Enter Full Screen), no second CommandMenu("View").
        #expect(!text.contains("CommandMenu(\"View\")"))
        #expect(!text.contains("CommandMenu(\"Edit\")"))
        #expect(text.contains("CommandGroup(after: .undoRedo)"))
        #expect(text.contains("CommandGroup(before: .toolbar)"))
    }

    // MARK: Banned words

    /// Files that name test hooks and layout identifiers, not words a user reads.
    private static let hookFiles = ["UISelfTestRunner.swift", "UIScenarioRunner.swift", "UITestDriver.swift",
                                    "UITestFrameRegistry.swift", "HermeticLabSession.swift", "UIScenario.swift",
                                    "LensjetNames.swift"]

    @Test("No user-visible string uses jargon or a retired name")
    func bannedWords() throws {
        let src = Self.productRoot.appendingPathComponent("src")
        let literal = try NSRegularExpression(pattern: #""((?:[^"\\\n]|\\.)*)""#)
        var hits: [String] = []
        let files = try #require(FileManager.default.enumerator(at: src, includingPropertiesForKeys: nil))
        for case let url as URL in files where url.pathExtension == "swift" && !Self.hookFiles.contains(url.lastPathComponent) {
            let text = try String(contentsOf: url, encoding: .utf8)
            for (number, rawLine) in text.components(separatedBy: "\n").enumerated() {
                if rawLine.trimmingCharacters(in: .whitespaces).hasPrefix("//") { continue }
                let range = NSRange(rawLine.startIndex..., in: rawLine)
                for match in literal.matches(in: rawLine, range: range) {
                    guard let r = Range(match.range(at: 1), in: rawLine) else { continue }
                    let value = String(rawLine[r])
                    for word in LensjetWords.banned where value.range(of: word, options: .caseInsensitive) != nil {
                        hits.append("\(url.lastPathComponent):\(number + 1) \"\(word)\" in \"\(value)\"")
                    }
                }
            }
        }
        #expect(hits.isEmpty, "\(hits.joined(separator: "\n"))")
    }

    @Test("The shortcut sheet and the empty state read the table, not their own words")
    func surfacesReadTheTable() throws {
        let sheet = try Self.source("PhotoSelectorApp/Views/ShortcutsHelpSheet.swift")
        #expect(sheet.contains("LensjetCommand.infoMenuTitle"))
        #expect(!sheet.contains("3-Up Focus"))
        let empty = try Self.source("PhotoSelectorApp/EmptyDropTargetView.swift")
        #expect(empty.contains("LensjetWords.dropFolder") && empty.contains("LensjetWords.dropToOpen"))
        #expect(LensjetWords.dropFolder == "Drop a folder")
        #expect(LensjetWords.dropToOpen == "Drop to open")
    }

    @Test("The shortcut sheet's Statistics row says what the view shows")
    func statisticsRowIsTrue() throws {
        // LibraryStatisticsView shows counts, progress, file formats and the two scores.
        let view = try Self.source("PhotoSelectorApp/Views/Tools/LibraryStatisticsView.swift")
        for shown in ["Culling Progress", "File Formats", "Quality Distribution"] { #expect(view.contains(shown)) }
        #expect(!LensjetWords.statisticsCaption.lowercased().contains("histogram"))
        #expect(!view.lowercased().contains("histogram"))
        let sheet = try Self.source("PhotoSelectorApp/Views/ShortcutsHelpSheet.swift")
        #expect(!sheet.contains("histograms"))
    }

    @Test("Settings names its tabs and bands with the table's words")
    func settingsWords() throws {
        let settings = try Self.source("PhotoSelectorApp/Views/Settings/SettingsView.swift")
        #expect(settings.contains("LensjetWords.scoresSettingsTab"))
        #expect(settings.contains("Sharpness bands"))
        #expect(settings.contains("Below \\(Int(sharpnessBlurryThreshold)): Blurry. Above \\(Int(sharpnessAcceptableThreshold)): Sharp."))
    }
}
