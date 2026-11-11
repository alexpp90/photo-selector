import Foundation

public struct UIScenario: Codable, Equatable, Sendable {
    public let id: String
    public let window: UIScenarioWindow?
    public let steps: [UIScenarioStep]

    public init(id: String, window: UIScenarioWindow? = nil, steps: [UIScenarioStep]) {
        self.id = id
        self.window = window
        self.steps = steps
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case name
        case window
        case steps
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let decodedId = try container.decodeIfPresent(String.self, forKey: .id)
            ?? container.decodeIfPresent(String.self, forKey: .name)
            ?? ""
        self.id = decodedId
        self.window = try container.decodeIfPresent(UIScenarioWindow.self, forKey: .window)
        self.steps = try container.decode([UIScenarioStep].self, forKey: .steps)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encodeIfPresent(window, forKey: .window)
        try container.encode(steps, forKey: .steps)
    }

    public static let knownActions: Set<String> = [
        "waitLoaded", "waitFor", "key", "click", "wait", "sleep", "checkpoint"
    ]

    public static func load(from url: URL) throws -> UIScenario {
        let data = try Data(contentsOf: url)
        let scenario = try JSONDecoder().decode(UIScenario.self, from: data)
        try scenario.validate()
        return scenario
    }

    public func validate() throws {
        if id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw UIScenarioValidationError.emptyId
        }
        var seenCheckpoints = Set<String>()
        for (idx, step) in steps.enumerated() {
            guard Self.knownActions.contains(step.action) else {
                throw UIScenarioValidationError.unknownAction(index: idx, action: step.action)
            }
            switch step.action {
            case "waitFor":
                guard let target = step.target, !target.isEmpty else {
                    throw UIScenarioValidationError.missingParameter(index: idx, action: "waitFor", parameter: "waitFor")
                }
            case "key":
                guard let key = step.key, !key.isEmpty else {
                    throw UIScenarioValidationError.missingParameter(index: idx, action: "key", parameter: "key")
                }
            case "click":
                guard let target = step.target, !target.isEmpty else {
                    throw UIScenarioValidationError.missingParameter(index: idx, action: "click", parameter: "target")
                }
            case "wait":
                guard let ms = step.ms, ms >= 0 else {
                    throw UIScenarioValidationError.missingParameter(index: idx, action: "wait", parameter: "ms")
                }
            case "sleep":
                guard let secs = step.timeoutSeconds, secs >= 0 else {
                    throw UIScenarioValidationError.missingParameter(index: idx, action: "sleep", parameter: "sleep")
                }
            case "checkpoint":
                guard let name = step.name, !name.isEmpty else {
                    throw UIScenarioValidationError.missingParameter(index: idx, action: "checkpoint", parameter: "name")
                }
                if !seenCheckpoints.insert(name).inserted {
                    throw UIScenarioValidationError.duplicateCheckpoint(name: name)
                }
            default:
                break
            }
        }
    }
}

public struct UIScenarioWindow: Codable, Equatable, Sendable {
    public let width: Int
    public let height: Int

    public init(width: Int, height: Int) {
        self.width = width
        self.height = height
    }

    private enum CodingKeys: String, CodingKey {
        case width
        case height
        case w
        case h
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if let width = try container.decodeIfPresent(Int.self, forKey: .width),
           let height = try container.decodeIfPresent(Int.self, forKey: .height) {
            self.width = width
            self.height = height
        } else {
            self.width = try container.decode(Int.self, forKey: .w)
            self.height = try container.decode(Int.self, forKey: .h)
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(width, forKey: .width)
        try container.encode(height, forKey: .height)
    }
}

public struct UIScenarioStep: Codable, Equatable, Sendable {
    public let action: String
    public let key: String?
    public let modifiers: [String]?
    public let target: String?
    public let ms: Int?
    public let name: String?
    public let timeoutSeconds: Double?

    public init(
        action: String,
        key: String? = nil,
        modifiers: [String]? = nil,
        target: String? = nil,
        ms: Int? = nil,
        name: String? = nil,
        timeoutSeconds: Double? = nil
    ) {
        self.action = action
        self.key = key
        self.modifiers = modifiers
        self.target = target
        self.ms = ms
        self.name = name
        self.timeoutSeconds = timeoutSeconds
    }

    private enum CodingKeys: String, CodingKey {
        case action
        case waitFor
        case key
        case modifiers
        case click
        case target
        case ms
        case sleep
        case checkpoint
        case name
        case timeout
        case timeoutSeconds
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let explicitAction = try container.decodeIfPresent(String.self, forKey: .action)
        let waitForTarget = try container.decodeIfPresent(String.self, forKey: .waitFor)
        let clickTarget = try container.decodeIfPresent(String.self, forKey: .click)
        let targetVal = try container.decodeIfPresent(String.self, forKey: .target)
        let keyVal = try container.decodeIfPresent(String.self, forKey: .key)
        let modsVal = try container.decodeIfPresent([String].self, forKey: .modifiers)
        let msVal = try container.decodeIfPresent(Int.self, forKey: .ms)
        let sleepVal = try container.decodeIfPresent(Double.self, forKey: .sleep)
        let cpVal = try container.decodeIfPresent(String.self, forKey: .checkpoint)
        let nameVal = try container.decodeIfPresent(String.self, forKey: .name)
        let timeoutVal = try container.decodeIfPresent(Double.self, forKey: .timeoutSeconds)
            ?? container.decodeIfPresent(Double.self, forKey: .timeout)

        if let explicitAction {
            self.action = explicitAction
            self.key = keyVal
            self.modifiers = modsVal
            self.target = targetVal ?? waitForTarget ?? clickTarget
            self.ms = msVal
            self.name = nameVal ?? cpVal
            self.timeoutSeconds = timeoutVal ?? sleepVal
        } else if let waitForTarget {
            self.action = "waitFor"
            self.key = nil
            self.modifiers = nil
            self.target = waitForTarget
            self.ms = nil
            self.name = nil
            self.timeoutSeconds = timeoutVal
        } else if let cpVal {
            self.action = "checkpoint"
            self.key = nil
            self.modifiers = nil
            self.target = nil
            self.ms = nil
            self.name = cpVal
            self.timeoutSeconds = nil
        } else if let clickTarget {
            self.action = "click"
            self.key = nil
            self.modifiers = nil
            self.target = clickTarget
            self.ms = nil
            self.name = nil
            self.timeoutSeconds = timeoutVal
        } else if let sleepVal {
            self.action = "sleep"
            self.key = nil
            self.modifiers = nil
            self.target = nil
            self.ms = Int((sleepVal * 1000.0).rounded())
            self.name = nil
            self.timeoutSeconds = sleepVal
        } else if let keyVal {
            self.action = "key"
            self.key = keyVal
            self.modifiers = modsVal
            self.target = nil
            self.ms = nil
            self.name = nil
            self.timeoutSeconds = nil
        } else {
            self.action = ""
            self.key = nil
            self.modifiers = nil
            self.target = nil
            self.ms = nil
            self.name = nil
            self.timeoutSeconds = nil
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(action, forKey: .action)
        try container.encodeIfPresent(key, forKey: .key)
        try container.encodeIfPresent(modifiers, forKey: .modifiers)
        try container.encodeIfPresent(target, forKey: .target)
        try container.encodeIfPresent(ms, forKey: .ms)
        try container.encodeIfPresent(name, forKey: .name)
        try container.encodeIfPresent(timeoutSeconds, forKey: .timeoutSeconds)
    }
}

public enum UIScenarioValidationError: Error, Equatable, LocalizedError {
    case emptyId
    case unknownAction(index: Int, action: String)
    case missingParameter(index: Int, action: String, parameter: String)
    case duplicateCheckpoint(name: String)

    public var errorDescription: String? {
        switch self {
        case .emptyId:
            return "Scenario id must not be empty"
        case let .unknownAction(index, action):
            return "Step \(index): unknown action '\(action)'"
        case let .missingParameter(index, action, parameter):
            return "Step \(index) (\(action)): missing required parameter '\(parameter)'"
        case let .duplicateCheckpoint(name):
            return "Duplicate checkpoint name '\(name)'"
        }
    }
}


/// One command of the live-control folder (`--ui-live <dir>`): the lab writes `cmd-<seq>.json`
/// holding `{"seq": 3, "step": {"key": "m", "modifiers": ["command"]}}` (or a `click` / `checkpoint`
/// step, the vocabulary of a scenario file) and the app answers with `done-<seq>.json`.
public struct UILiveCommand: Decodable, Equatable, Sendable {
    public let seq: Int
    public let step: UIScenarioStep

    public static func parse(_ data: Data) throws -> UILiveCommand {
        try JSONDecoder().decode(UILiveCommand.self, from: data)
    }

    /// The answer file content: `{"ok": true}` or `{"ok": false, "error": "..."}`.
    public static func answer(ok: Bool, error: String? = nil) -> Data {
        var payload: [String: Any] = ["ok": ok]
        if let error { payload["error"] = error }
        return (try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])) ?? Data("{\"ok\":false}".utf8)
    }
}
