//
//  MindProvider.swift
//  JsonMind
//
//  Where the tokens come from. JsonMind never holds API keys or picks
//  models: a provider streams the reply text for a prompt and reports what
//  it used. TokenX (the token streaming SDK) implements this protocol; the
//  library ships the canned rule provider and an adapter for simple
//  request/response providers.
//

import Foundation
import JsonUICore

public enum MindError: Error, Equatable {
    case budgetExhausted
    case turnTooLarge(estimated: Int, limit: Int)
    case coolingDown(remaining: Double)
    case noProvider
    case provider(String)
}

/// A source of replies: streams text for a prompt, then reports the tokens used.
public protocol MindProvider {
    /// `onText` receives deltas of the reply as they arrive (may be called once with the whole text);
    /// `completion` is called exactly once when the reply is complete or failed.
    func respond(to prompt: MindPrompt, onText: @escaping (String) -> Void, completion: @escaping (Result<MindUsage, Error>) -> Void)
}

/// Wraps a request/response function (no streaming) as a provider.
public struct CompletionMindProvider: MindProvider {
    public typealias Completion = (_ prompt: MindPrompt, _ completion: @escaping (Result<MindReply, Error>) -> Void) -> Void
    private let complete: Completion

    public init(_ complete: @escaping Completion) { self.complete = complete }

    public func respond(to prompt: MindPrompt, onText: @escaping (String) -> Void, completion: @escaping (Result<MindUsage, Error>) -> Void) {
        complete(prompt) { result in
            switch result {
            case .success(let reply):
                onText(reply.text.isEmpty ? JsonValue.array(reply.commands.map(\.value)).jsonString() : reply.text)
                completion(.success(MindUsage(promptTokens: nil, replyTokens: reply.tokensUsed)))
            case .failure(let error):
                completion(.failure(error))
            }
        }
    }
}

/// Rule based replies from the `canned` list; costs no tokens.
public struct CannedMindProvider: MindProvider {
    public var rules: [Mind.Rule]
    public init(rules: [Mind.Rule]) { self.rules = rules }

    public func reply(to prompt: MindPrompt) -> MindReply? {
        let text = [prompt.event, prompt.heard ?? ""].joined(separator: " ")
        guard let rule = rules.first(where: { $0.matches(text) }) else { return nil }
        return MindReply(commands: rule.script.commands, tokensUsed: 0, text: rule.script.value.jsonString())
    }

    public func respond(to prompt: MindPrompt, onText: @escaping (String) -> Void, completion: @escaping (Result<MindUsage, Error>) -> Void) {
        if let reply = reply(to: prompt) { onText(reply.text) }
        completion(.success(MindUsage(promptTokens: 0, replyTokens: 0)))
    }
}
