//
//  MindPrompt.swift
//  JsonMind
//
//  What a mind is told when a turn starts, and what comes back. The prompt
//  has a plain text form so any chat model can serve it; the reply is a JSON
//  array of commands that `MindReply.parse` reads from the model's text, and
//  `MindReplyAssembler` reads while the text is still streaming.
//

import Foundation
import JsonUICore

/// What a mind perceives when a turn starts.
public struct MindPrompt: Equatable {
    public var actorId: String
    public var actorName: String?
    public var persona: String
    /// The event that started the turn (`tap`, `near`, `spoken`, `timer`).
    public var event: String
    /// Text heard from the user, for `spoken`.
    public var heard: String?
    /// Sense name → value, limited to the mind's `senses`.
    public var senses: [String: JsonValue]
    public var tools: [String]
    /// Earlier turns, oldest first.
    public var history: [MindTurn]
    public var maxTokens: Int

    public init(actorId: String, actorName: String? = nil, persona: String, event: String, heard: String? = nil, senses: [String: JsonValue] = [:], tools: [String] = Mind.allTools, history: [MindTurn] = [], maxTokens: Int = Mind.Budget.defaultPerTurn) {
        self.actorId = actorId; self.actorName = actorName; self.persona = persona; self.event = event; self.heard = heard
        self.senses = senses; self.tools = tools; self.history = history; self.maxTokens = maxTokens
    }

    /// The system prompt: who the actor is and the reply format.
    public var systemText: String {
        var s = "You are \(actorName ?? actorId), a character in an augmented reality scene.\n"
        s += persona.isEmpty ? "" : "\(persona)\n"
        s += "\nReply with ONLY a JSON array of commands, nothing else. Allowed commands:\n"
        for tool in tools {
            switch tool {
            case "say": s += "  {\"say\": \"short text to speak\"}\n"
            case "play": s += "  {\"play\": \"animation name\"}\n"
            case "sound": s += "  {\"sound\": \"sound name\"}\n"
            case "moveTo": s += "  {\"moveTo\": \"user\"} or {\"moveTo\": [x, y, z]}\n"
            case "lookAt": s += "  {\"lookAt\": \"user\"}\n"
            case "behave": s += "  {\"behave\": \"wander\" | \"approach\" | \"flee\" | \"follow\" | \"idle\"}\n"
            case "set": s += "  {\"set\": {\"stateKey\": value}}\n"
            case "stop": s += "  {\"stop\": true}\n"
            case "wait": s += "  {\"wait\": seconds}\n"
            default: s += "  {\"\(tool)\": ...}\n"
            }
        }
        if let a = senses["animations"]?.arrayValue { s += "Animations: \(a.compactMap(\.text).joined(separator: ", "))\n" }
        if let a = senses["sounds"]?.arrayValue { s += "Sounds: \(a.compactMap(\.text).joined(separator: ", "))\n" }
        if let a = senses["behaviors"]?.arrayValue { s += "Behaviors: \(a.compactMap(\.text).joined(separator: ", "))\n" }
        s += "Keep replies short: at most 3 commands and one sentence of speech."
        return s
    }

    /// The user message: the event and the senses, as JSON.
    public var userText: String {
        var o: [String: JsonValue] = ["event": .string(event)]
        if let h = heard { o["heard"] = .string(h) }
        for (k, v) in senses where !MindPrompt.systemSenses.contains(k) { o[k] = v }
        return JsonValue.object(o).jsonString()
    }

    /// Senses that describe the actor's own repertoire and go into the system prompt instead.
    public static let systemSenses: Set<String> = ["animations", "sounds", "behaviors"]

    /// The conversation as alternating messages, oldest first, for chat APIs: (role, text).
    public var messages: [(role: String, text: String)] {
        var out: [(String, String)] = []
        for turn in history {
            out.append(("user", turn.userText))
            out.append(("assistant", turn.replyText))
        }
        out.append(("user", userText))
        return out
    }

    /// A rough token estimate (4 characters per token) used to enforce budgets before a call.
    public var estimatedTokens: Int { MindTurn.estimateTokens(systemText) + MindTurn.estimateTokens(userText) + history.reduce(0) { $0 + $1.estimatedTokens } }
}

/// One completed turn, kept as history.
public struct MindTurn: Equatable {
    public var event: String
    public var heard: String?
    public var replyText: String
    public var tokens: Int

    public init(event: String, heard: String? = nil, replyText: String, tokens: Int) { self.event = event; self.heard = heard; self.replyText = replyText; self.tokens = tokens }

    var userText: String {
        var o: [String: JsonValue] = ["event": .string(event)]
        if let h = heard { o["heard"] = .string(h) }
        return JsonValue.object(o).jsonString()
    }

    public var estimatedTokens: Int { MindTurn.estimateTokens(userText) + MindTurn.estimateTokens(replyText) }

    public static func estimateTokens(_ text: String) -> Int { (text.utf8.count + 3) / 4 }
}

/// Token usage reported by a provider for one turn.
public struct MindUsage: Equatable {
    public var promptTokens: Int?
    public var replyTokens: Int?

    public init(promptTokens: Int? = nil, replyTokens: Int? = nil) { self.promptTokens = promptTokens; self.replyTokens = replyTokens }

    /// Total reported tokens, or `nil` when the provider reported nothing.
    public var total: Int? {
        if promptTokens == nil && replyTokens == nil { return nil }
        return (promptTokens ?? 0) + (replyTokens ?? 0)
    }
}

public struct MindReply: Equatable {
    public var commands: [ActorCommand]
    /// Tokens the provider reports for the turn; `nil` means estimate.
    public var tokensUsed: Int?
    /// The raw model text, kept for history.
    public var text: String

    public init(commands: [ActorCommand], tokensUsed: Int? = nil, text: String = "") {
        self.commands = commands; self.tokensUsed = tokensUsed; self.text = text
    }

    /// Parses a model's text reply: a JSON array of commands, tolerating code fences and prose around it.
    /// Plain text with no JSON becomes a single `say`.
    public static func parse(_ text: String, tokensUsed: Int? = nil) -> MindReply {
        var body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if body.hasPrefix("```") {
            body = body.split(separator: "\n", omittingEmptySubsequences: false).dropFirst().joined(separator: "\n")
            if let fence = body.range(of: "```", options: .backwards) { body = String(body[..<fence.lowerBound]) }
        }
        if let start = body.firstIndex(of: "["), let end = body.lastIndex(of: "]"), start < end,
           let value = try? JsonValue.parse(String(body[start...end])) {
            let commands = (value.arrayValue ?? []).compactMap(ActorCommand.init)
            return MindReply(commands: commands, tokensUsed: tokensUsed, text: text)
        }
        if let start = body.firstIndex(of: "{"), let end = body.lastIndex(of: "}"), start < end,
           let value = try? JsonValue.parse(String(body[start...end])), let command = ActorCommand(value) {
            return MindReply(commands: [command], tokensUsed: tokensUsed, text: text)
        }
        let spoken = body.trimmingCharacters(in: .whitespacesAndNewlines)
        return MindReply(commands: spoken.isEmpty ? [] : [.say(spoken)], tokensUsed: tokensUsed, text: text)
    }
}

/// Reads commands out of a reply while it streams, so `say` can start before the model finishes.
/// Feed text deltas with `append`; each completed top level JSON object becomes a command.
public struct MindReplyAssembler {
    public private(set) var text = ""
    public private(set) var commands: [ActorCommand] = []
    private var objectStart: String.Index?
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var scanned: String.Index

    public init() { scanned = text.startIndex }

    /// Appends a delta and returns the commands completed by it.
    @discardableResult
    public mutating func append(_ delta: String) -> [ActorCommand] {
        let offset = text.distance(from: text.startIndex, to: scanned)
        text += delta
        var index = text.index(text.startIndex, offsetBy: offset)
        var produced: [ActorCommand] = []
        while index < text.endIndex {
            let c = text[index]
            if inString {
                if escaped { escaped = false }
                else if c == "\\" { escaped = true }
                else if c == "\"" { inString = false }
            } else {
                switch c {
                case "\"": inString = true
                case "{":
                    if depth == 0 { objectStart = index }
                    depth += 1
                case "}":
                    depth = max(0, depth - 1)
                    if depth == 0, let start = objectStart {
                        let object = String(text[start...index])
                        objectStart = nil
                        if let value = try? JsonValue.parse(object), let command = ActorCommand(value) {
                            commands.append(command)
                            produced.append(command)
                        }
                    }
                default: break
                }
            }
            index = text.index(after: index)
        }
        scanned = text.endIndex
        return produced
    }

    /// The reply once the stream ends: the streamed commands, or a `say` of plain text when there were none.
    public func finish(tokensUsed: Int? = nil) -> MindReply {
        if commands.isEmpty { return MindReply.parse(text, tokensUsed: tokensUsed) }
        return MindReply(commands: commands, tokensUsed: tokensUsed, text: text)
    }
}
