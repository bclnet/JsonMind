//
//  Mind.swift
//  JsonMind
//
//  A mind is the personality of something in a JsonUI document: an actor in
//  a scene, or a form assistant. It is prompt material (`persona`, `senses`,
//  `tools`), the token `budget` it may spend, the events that wake it and
//  the `canned` rules used when no model is attached. Minds are often shared
//  as fragments: `"mind": { "$ref": "minds/snoopy.json" }`.
//

import Foundation
import JsonUICore

public struct Mind: Equatable {
    public struct Budget: Equatable {
        /// Total tokens for the mind's lifetime; `nil` is unlimited (the token supply still decides).
        public var tokens: Int?
        /// Maximum tokens one turn may spend (prompt + reply).
        public var perTurn: Int
        /// Minimum seconds between turns.
        public var cooldown: Double

        public static let defaultPerTurn = 400
        public static let defaultCooldown = 5.0

        public init(tokens: Int? = nil, perTurn: Int = Budget.defaultPerTurn, cooldown: Double = Budget.defaultCooldown) {
            self.tokens = tokens; self.perTurn = perTurn; self.cooldown = cooldown
        }

        public init(_ value: JsonValue) {
            let o = value.objectValue ?? [:]
            self.init(tokens: o["tokens"]?.integerValue ?? value.integerValue, perTurn: o["perTurn"]?.integerValue ?? Budget.defaultPerTurn, cooldown: o["cooldown"]?.numberValue ?? Budget.defaultCooldown)
        }

        public var value: JsonValue {
            var o: [String: JsonValue] = [:]
            if let t = tokens { o["tokens"] = .number(Double(t)) }
            if perTurn != Budget.defaultPerTurn { o["perTurn"] = .number(Double(perTurn)) }
            if cooldown != Budget.defaultCooldown { o["cooldown"] = .number(cooldown) }
            return .object(o)
        }
    }

    /// A canned reaction: a regular expression over the event name and heard text, and what to do.
    public struct Rule: Equatable {
        public var match: String
        public var script: ActorScript

        public init(match: String, script: ActorScript) { self.match = match; self.script = script }

        public init?(_ value: JsonValue) {
            guard let o = value.objectValue, let m = o["match"]?.text, let s = o["do"].flatMap(ActorScript.init) else { return nil }
            self.init(match: m, script: s)
        }

        public var value: JsonValue { ["match": .string(match), "do": script.value] }

        public func matches(_ text: String) -> Bool {
            guard let re = try? NSRegularExpression(pattern: match, options: [.caseInsensitive]) else { return text.range(of: match, options: .caseInsensitive) != nil }
            return re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
        }
    }

    public static let allSenses = ["userDistance", "userLooking", "timeOfDay", "state", "actors", "lastEvent"]
    public static let allTools = ["say", "play", "sound", "moveTo", "lookAt", "behave", "set"]
    public static let defaultTriggers = ["tap", "near"]

    public var persona: String
    public var senses: [String]
    public var tools: [String]
    public var budget: Budget
    public var triggers: [String]
    /// Seconds between `timer` turns when `timer` is a trigger.
    public var interval: Double
    public var canned: [Rule]

    public init(persona: String, senses: [String] = Mind.allSenses, tools: [String] = Mind.allTools, budget: Budget = Budget(), triggers: [String] = Mind.defaultTriggers, interval: Double = 30, canned: [Rule] = []) {
        self.persona = persona; self.senses = senses; self.tools = tools; self.budget = budget; self.triggers = triggers; self.interval = interval; self.canned = canned
    }

    public init(_ value: JsonValue) {
        let o = value.objectValue ?? [:]
        let strings = { (key: String, fallback: [String]) -> [String] in o[key]?.arrayValue.map { $0.compactMap(\.text) } ?? fallback }
        self.init(persona: o["persona"]?.text ?? value.text ?? "",
                  senses: strings("senses", Mind.allSenses),
                  tools: strings("tools", Mind.allTools),
                  budget: Budget(o["budget"] ?? .null),
                  triggers: strings("triggers", Mind.defaultTriggers),
                  interval: o["interval"]?.numberValue ?? 30,
                  canned: (o["canned"]?.arrayValue ?? []).compactMap(Rule.init))
    }

    public init(json: String) throws { self.init(try JsonValue.parse(json)) }

    public var value: JsonValue {
        var o: [String: JsonValue] = ["persona": .string(persona)]
        if senses != Mind.allSenses { o["senses"] = .array(senses.map(JsonValue.string)) }
        if tools != Mind.allTools { o["tools"] = .array(tools.map(JsonValue.string)) }
        if budget != Budget() { o["budget"] = budget.value }
        if triggers != Mind.defaultTriggers { o["triggers"] = .array(triggers.map(JsonValue.string)) }
        if interval != 30 { o["interval"] = .number(interval) }
        if !canned.isEmpty { o["canned"] = .array(canned.map(\.value)) }
        return .object(o)
    }

    public func allows(_ command: ActorCommand) -> Bool { tools.contains(command.verb) }
}
