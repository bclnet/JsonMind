//
//  MindSession.swift
//  JsonMind
//
//  Runs one mind: the token ledger (what this mind may still spend), the
//  cooldown, the history sent with each prompt, streaming replies into
//  commands, and the fallback to canned rules when there is no provider,
//  no budget or an error.
//

import Foundation
import JsonUICore

/// Tracks the tokens a mind has spent against its budget. The supply side (keys, models, quotas)
/// is the provider's business; this is the local allowance the document asked for.
public struct TokenLedger: Equatable {
    public var budget: Mind.Budget
    public private(set) var spent: Int = 0
    public private(set) var turns: Int = 0
    public private(set) var lastTurnAt: TimeInterval?

    public init(budget: Mind.Budget) { self.budget = budget }

    public var remaining: Int? { budget.tokens.map { max(0, $0 - spent) } }
    public var isExhausted: Bool { remaining.map { $0 <= 0 } ?? false }

    /// Checks whether a turn estimated at `tokens` may start at `now`.
    public func check(estimated tokens: Int, now: TimeInterval) -> MindError? {
        if isExhausted { return .budgetExhausted }
        if let last = lastTurnAt, now - last < budget.cooldown { return .coolingDown(remaining: budget.cooldown - (now - last)) }
        if tokens > budget.perTurn { return .turnTooLarge(estimated: tokens, limit: budget.perTurn) }
        if let r = remaining, tokens > r { return .budgetExhausted }
        return nil
    }

    public mutating func charge(_ tokens: Int, at now: TimeInterval) {
        spent += max(0, tokens)
        turns += 1
        lastTurnAt = now
    }
}

public final class MindSession {
    public let actorId: String
    public let mind: Mind
    public var provider: MindProvider?
    public private(set) var ledger: TokenLedger
    public private(set) var history: [MindTurn] = []
    /// How many past turns are sent with each prompt.
    public var historyLimit = 6
    /// Called with each command as soon as the streaming reply completes it (before `completion`).
    public var onCommand: ((ActorCommand) -> Void)?
    private let canned: CannedMindProvider

    public init(actorId: String, mind: Mind, provider: MindProvider? = nil) {
        self.actorId = actorId
        self.mind = mind
        self.provider = provider
        self.ledger = TokenLedger(budget: mind.budget)
        self.canned = CannedMindProvider(rules: mind.canned)
    }

    public func wakes(on event: String) -> Bool { mind.triggers.contains(event) }

    public func prompt(event: String, heard: String? = nil, actorName: String? = nil, senses: [String: JsonValue]) -> MindPrompt {
        let allowed = senses.filter { mind.senses.contains($0.key) || MindPrompt.systemSenses.contains($0.key) }
        return MindPrompt(actorId: actorId, actorName: actorName, persona: mind.persona, event: event, heard: heard, senses: allowed, tools: mind.tools, history: Array(history.suffix(historyLimit)), maxTokens: mind.budget.perTurn)
    }

    /// Asks the provider, or the canned rules when there is no provider or no budget. The commands returned
    /// are filtered by the mind's `tools`; streamed commands also reach `onCommand` as they complete.
    public func respond(to prompt: MindPrompt, now: TimeInterval = Date().timeIntervalSince1970, completion: @escaping (Result<[ActorCommand], MindError>) -> Void) {
        guard let provider = provider else {
            completion(.success(filter(canned.reply(to: prompt)?.commands ?? [])))
            return
        }
        if let error = ledger.check(estimated: prompt.estimatedTokens, now: now) {
            if case .coolingDown = error { completion(.failure(error)); return }
            // No budget left: fall back to the canned rules so the actor still reacts.
            if let reply = canned.reply(to: prompt) { completion(.success(filter(reply.commands))) } else { completion(.failure(error)) }
            return
        }
        var assembler = MindReplyAssembler()
        var streamed = 0
        provider.respond(to: prompt, onText: { [weak self] delta in
            guard let self = self else { return }
            for command in assembler.append(delta) where self.mind.allows(command) {
                streamed += 1
                self.onCommand?(command)
            }
        }, completion: { [weak self] result in
            guard let self = self else { return }
            switch result {
            case .success(let usage):
                let reply = assembler.finish(tokensUsed: usage.total)
                let tokens = usage.total ?? (prompt.estimatedTokens + MindTurn.estimateTokens(reply.text))
                self.ledger.charge(tokens, at: now)
                self.history.append(MindTurn(event: prompt.event, heard: prompt.heard, replyText: reply.text, tokens: tokens))
                let commands = self.filter(reply.commands)
                // Commands that only appeared at the end (plain text replies) are delivered here too.
                if streamed == 0 { commands.forEach { self.onCommand?($0) } }
                completion(.success(commands))
            case .failure(let error):
                if let reply = self.canned.reply(to: prompt) { completion(.success(self.filter(reply.commands))) }
                else { completion(.failure(.provider("\(error)"))) }
            }
        })
    }

    func filter(_ commands: [ActorCommand]) -> [ActorCommand] { commands.filter { mind.allows($0) } }
}
