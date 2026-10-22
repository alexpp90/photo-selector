import Foundation
import Testing

@Suite("check_no_test_hooks.sh")
struct NoTestHooksScriptTests {
    private static var script: String {
        URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("scripts/check_no_test_hooks.sh").path
    }

    private func runScript(on contents: String) throws -> Int32 {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("hooks-fixture-\(UUID().uuidString)")
        try Data(contents.utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/bash")
        process.arguments = [Self.script, file.path]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        return process.terminationStatus
    }

    @Test("A release-like binary passes, even with the refusal literals a release keeps")
    func releasePasses() throws {
        let release = "PhotoSelectorApp\n--ui-scenario\n--ui-self-test\nPST_SHOW_GUI\nThis build has no UI test hooks\n"
        #expect(try runScript(on: release) == 0)
    }

    @Test("A hooks build fails, by type name and by hook-only literal")
    func hooksBuildFails() throws {
        #expect(try runScript(on: "UIScenarioRunner\n") != 0)
        #expect(try runScript(on: "scenario-artifacts\n") != 0)
        #expect(try runScript(on: "layout.boundary_slot_retains_frame\n") != 0)
        #expect(try runScript(on: "slot_next at last photo=\n") != 0)
    }
}
