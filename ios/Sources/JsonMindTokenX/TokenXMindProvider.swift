//
//  TokenXMindProvider.swift
//  JsonMindTokenX
//
//  The MindProvider that gets its tokens from TokenX. Each mind gets a
//  TokenX session named after it, on the `character` profile by default,
//  with the mind's own budget as the session budget. JsonMind keeps its
//  ledger and cooldown; TokenX keeps the keys, the model and the daily cap.
//

import Foundation
import JsonMind
import TokenX

public final class TokenXMindProvider: MindProvider {
    public let client: TokenClient
    public let profile: Profile
    /// Total tokens the TokenX session of each mind may spend; `nil` leaves it to JsonMind's budget and the daily cap.
    public let sessionBudget: Int?
    private var sessions: [String: TokenSession] = [:]
    private let lock = NSLock()

    public init(client: TokenClient, profile: Profile = .character, sessionBudget: Int? = nil) {
        self.client = client; self.profile = profile; self.sessionBudget = sessionBudget
    }

    public convenience init(broker: TokenBroker, profile: Profile = .character) {
        self.init(client: TokenClient(broker: broker), profile: profile)
    }

    /// Whether TokenX can serve requests right now (a provider is configured).
    public var isReady: Bool { client.isReady }

    /// The TokenX session for an actor, created on first use.
    public func session(for actorId: String) -> TokenSession {
        lock.lock(); defer { lock.unlock() }
        if let s = sessions[actorId] { return s }
        let s = client.session(consumer: actorId, profile: profile, budget: sessionBudget)
        sessions[actorId] = s
        return s
    }

    public func respond(to prompt: MindPrompt, onText: @escaping (String) -> Void, completion: @escaping (Result<MindUsage, Error>) -> Void) {
        let request = TokenXMindProvider.request(for: prompt)
        session(for: prompt.actorId).stream(request, onText: onText) { result in
            switch result {
            case .success(let reply):
                completion(.success(MindUsage(promptTokens: reply.usage.promptTokens, replyTokens: reply.usage.replyTokens)))
            case .failure(let error):
                completion(.failure(error))
            }
        }
    }

    /// The prompt's system text and message history as a TokenX request; `maxTokens` follows the mind's per-turn cap.
    public static func request(for prompt: MindPrompt) -> ChatRequest {
        let messages = prompt.messages.map { $0.role == "assistant" ? ChatMessage.assistant($0.text) : ChatMessage.user($0.text) }
        return ChatRequest(system: prompt.systemText, messages: messages, maxTokens: min(prompt.maxTokens, 1024))
    }
}
