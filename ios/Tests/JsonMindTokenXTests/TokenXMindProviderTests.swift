import XCTest
@testable import JsonMindTokenX
import JsonMind
import JsonUICore
import TokenX

/// A broker that answers with a fixed streamed reply and remembers what it was asked.
final class FakeBroker: TokenBroker {
    var isReady = true
    var chunks = ["[{\"say\": \"Ask, and the bush ", "shall sing.\"}, {\"play\": \"sing\"}]"]
    var usage = Usage(promptTokens: 40, replyTokens: 20)
    var failure: TokenXError?
    var requests: [(ChatRequest, Profile, String)] = []

    func stream(_ request: ChatRequest, profile: Profile, consumer: String, onEvent: @escaping (ChatEvent) -> Void, completion: @escaping (Result<ChatReply, TokenXError>) -> Void) -> Cancellable {
        requests.append((request, profile, consumer))
        if let failure = failure { completion(.failure(failure)); return NoopCancellableForTests() }
        chunks.forEach { onEvent(.text($0)) }
        onEvent(.done(usage: usage, stop: .end))
        completion(.success(ChatReply(text: chunks.joined(), usage: usage, stop: .end)))
        return NoopCancellableForTests()
    }
}

struct NoopCancellableForTests: Cancellable { func cancel() {} }

final class TokenXMindProviderTests: XCTestCase {
    func testMindGetsStreamedCommandsAndUsageFromTokenX() {
        let broker = FakeBroker()
        let provider = TokenXMindProvider(broker: broker)
        let mind = Mind(["persona": "You are the singing bush.", "tools": ["say", "play"], "budget": ["tokens": 5000, "cooldown": 0]])
        let session = MindSession(actorId: "bush", mind: mind, provider: provider)
        var streamed: [ActorCommand] = []
        session.onCommand = { streamed.append($0) }
        var got: [ActorCommand] = []
        session.respond(to: session.prompt(event: "tap", senses: ["userDistance": 1.2]), now: 0) { got = (try? $0.get()) ?? [] }
        XCTAssertEqual(got, [.say("Ask, and the bush shall sing."), .play(animation: "sing", loop: nil, speed: nil)])
        XCTAssertEqual(streamed, got)
        XCTAssertEqual(session.ledger.spent, 60, "TokenX's reported usage is charged to the mind")
        let (request, profile, consumer) = broker.requests[0]
        XCTAssertEqual(consumer, "bush")
        XCTAssertEqual(profile, .character)
        XCTAssertTrue(request.system?.contains("You are the singing bush.") ?? false)
        XCTAssertEqual(request.messages.count, 1)
        XCTAssertEqual(request.messages[0].role, .user)
        XCTAssertTrue(request.messages[0].text.contains("\"event\":\"tap\""))
        XCTAssertEqual(request.maxTokens, 400)
        XCTAssertEqual(provider.session(for: "bush").spent, 60)
        // History rides along on the next turn.
        session.respond(to: session.prompt(event: "near", senses: [:]), now: 1) { _ in }
        XCTAssertEqual(broker.requests[1].0.messages.map(\.role), [.user, .assistant, .user])
    }

    func testTokenXErrorsFallBackToCannedRules() {
        let broker = FakeBroker()
        broker.failure = .dailyCapReached
        let mind = Mind(["persona": "p", "canned": [["match": "tap", "do": ["say": "canned"]]]])
        let session = MindSession(actorId: "bush", mind: mind, provider: TokenXMindProvider(broker: broker))
        var got: [ActorCommand] = []
        session.respond(to: session.prompt(event: "tap", senses: [:]), now: 0) { got = (try? $0.get()) ?? [] }
        XCTAssertEqual(got, [.say("canned")])
        XCTAssertEqual(session.ledger.spent, 0)
    }

    func testRequestShape() {
        let prompt = MindPrompt(actorId: "a", persona: "p", event: "tap", history: [MindTurn(event: "near", replyText: "[]", tokens: 1)], maxTokens: 5000)
        let request = TokenXMindProvider.request(for: prompt)
        XCTAssertEqual(request.messages.map(\.role), [.user, .assistant, .user])
        XCTAssertEqual(request.maxTokens, 1024, "capped so a runaway per-turn budget cannot produce essays")
    }
}
