import Foundation
import Testing
@testable import PhotoSelectorKit

@Suite("UIScenario Tests")
struct UIScenarioTests {
    @Test("Decodes and validates a valid scenario JSON")
    func testValidScenario() throws {
        let json = """
        {
          "id": "J-MAC-01",
          "window": {"width": 1440, "height": 900},
          "steps": [
            {"action": "waitLoaded"},
            {"action": "checkpoint", "name": "loaded"},
            {"action": "key", "key": "3"},
            {"action": "wait", "ms": 300},
            {"action": "click", "target": "toolbar_next"},
            {"action": "key", "key": "z", "modifiers": ["command"]},
            {"action": "checkpoint", "name": "after_undo"}
          ]
        }
        """
        let scenario = try JSONDecoder().decode(UIScenario.self, from: Data(json.utf8))
        try scenario.validate()
        #expect(scenario.id == "J-MAC-01")
        #expect(scenario.window == UIScenarioWindow(width: 1440, height: 900))
        #expect(scenario.steps.count == 7)
    }

    @Test("Rejects unknown action")
    func testUnknownAction() throws {
        let scenario = UIScenario(
            id: "bad",
            steps: [UIScenarioStep(action: "teleport")]
        )
        #expect(throws: UIScenarioValidationError.unknownAction(index: 0, action: "teleport")) {
            try scenario.validate()
        }
    }

    @Test("Rejects duplicate checkpoint names")
    func testDuplicateCheckpoints() throws {
        let scenario = UIScenario(
            id: "dup",
            steps: [
                UIScenarioStep(action: "checkpoint", name: "first"),
                UIScenarioStep(action: "checkpoint", name: "first")
            ]
        )
        #expect(throws: UIScenarioValidationError.duplicateCheckpoint(name: "first")) {
            try scenario.validate()
        }
    }

    @Test("Rejects missing required parameters")
    func testMissingParameters() throws {
        let missingKey = UIScenario(id: "s1", steps: [UIScenarioStep(action: "key")])
        #expect(throws: UIScenarioValidationError.missingParameter(index: 0, action: "key", parameter: "key")) {
            try missingKey.validate()
        }

        let missingClick = UIScenario(id: "s2", steps: [UIScenarioStep(action: "click")])
        #expect(throws: UIScenarioValidationError.missingParameter(index: 0, action: "click", parameter: "target")) {
            try missingClick.validate()
        }
    }
}

@Suite("Live-control commands (--ui-live)")
struct UILiveCommandTests {
    @Test("A command file carries a scenario step: key with modifiers, click, checkpoint")
    func parsesTheScenarioVocabulary() throws {
        let key = try UILiveCommand.parse(Data(#"{"seq": 3, "step": {"key": "z", "modifiers": ["command"]}}"#.utf8))
        #expect(key.seq == 3)
        #expect(key.step.action == "key")
        #expect(key.step.key == "z")
        #expect(key.step.modifiers == ["command"])
        let click = try UILiveCommand.parse(Data(#"{"seq": 4, "step": {"click": "toolbar_zoom"}}"#.utf8))
        #expect(click.step.action == "click")
        #expect(click.step.target == "toolbar_zoom")
        let cp = try UILiveCommand.parse(Data(#"{"seq": 5, "step": {"checkpoint": "s3"}}"#.utf8))
        #expect(cp.step.action == "checkpoint")
        #expect(cp.step.name == "s3")
        #expect(throws: (any Error).self) { try UILiveCommand.parse(Data("not json".utf8)) }
    }

    @Test("The answer is {ok: true} or {ok: false, error}")
    func answers() throws {
        let ok = try JSONSerialization.jsonObject(with: UILiveCommand.answer(ok: true)) as? [String: Any]
        #expect(ok?["ok"] as? Bool == true)
        let bad = try JSONSerialization.jsonObject(with: UILiveCommand.answer(ok: false, error: "boom")) as? [String: Any]
        #expect(bad?["ok"] as? Bool == false)
        #expect(bad?["error"] as? String == "boom")
    }
}
