import XCTest
@testable import JsonMind
import JsonUICore

final class MindTests: XCTestCase {
    func testExamplesParse() throws {
        let bush = try Examples.mind("singing-bush")
        XCTAssertEqual(bush.budget, Mind.Budget(tokens: 20000, perTurn: 300, cooldown: 8))
        XCTAssertEqual(bush.tools, ["say", "play", "sound"])
        XCTAssertEqual(bush.triggers, ["tap", "spoken"])
        XCTAssertEqual(bush.canned.count, 3)
        XCTAssertEqual(Mind(bush.value), bush)
        let snoopy = try Examples.mind("snoopy")
        XCTAssertEqual(snoopy.interval, 45)
        XCTAssertEqual(snoopy.canned.last?.script.commands, [.behave("wander")])
        XCTAssertEqual(Mind(snoopy.value), snoopy)
        XCTAssertEqual(Mind("just a persona").persona, "just a persona")
    }

    func testMindAsFragment() throws {
        let scene: JsonValue = ["type": "Scene", "actors": [["id": "s", "mind": ["$ref": "minds/snoopy.json", "budget": ["tokens": 5000]]]]]
        let resolver = JsonFragmentResolver { url in try JsonValue.parse(try Data(contentsOf: url)) }
        let resolved = try resolver.resolve(scene, base: Examples.directory.appendingPathComponent("scene.json"))
        let mind = Mind(resolved["actors"][0]["mind"])
        XCTAssertEqual(mind.budget.tokens, 5000)
        XCTAssertEqual(mind.budget.perTurn, Mind.Budget.defaultPerTurn, "the override replaced the whole budget object")
        XCTAssertTrue(mind.persona.hasPrefix("You are Snoopy"))
    }

    func testReplyParsing() {
        XCTAssertEqual(MindReply.parse("[{\"say\": \"hi\"}, {\"play\": \"sing\"}]").commands, [.say("hi"), .play(animation: "sing", loop: nil, speed: nil)])
        XCTAssertEqual(MindReply.parse("Sure!\n```json\n[{\"say\": \"hi\"}]\n```").commands, [.say("hi")])
        XCTAssertEqual(MindReply.parse("{\"lookAt\": \"user\"}").commands, [.lookAt(.user)])
        XCTAssertEqual(MindReply.parse("Woof.").commands, [.say("Woof.")])
        XCTAssertEqual(MindReply.parse("  ").commands, [])
        XCTAssertEqual(MindReply.parse("[{\"nuke\": 1}]").commands, [])
    }

    func testStreamingAssembler() {
        var assembler = MindReplyAssembler()
        XCTAssertEqual(assembler.append("[{\"say\": \"Hel"), [])
        XCTAssertEqual(assembler.append("lo }\"}, {\"pl"), [.say("Hel\u{6C}o }")])
        XCTAssertEqual(assembler.append("ay\": \"sing\"}"), [.play(animation: "sing", loop: nil, speed: nil)])
        XCTAssertEqual(assembler.append("]"), [])
        XCTAssertEqual(assembler.finish(tokensUsed: 12).commands.count, 2)
        XCTAssertEqual(assembler.finish().tokensUsed, nil)
        var plain = MindReplyAssembler()
        plain.append("Just wo")
        plain.append("rds.")
        XCTAssertEqual(plain.finish().commands, [.say("Just words.")])
        var nested = MindReplyAssembler()
        nested.append("[{\"moveTo\": {\"target\": \"user\", \"stopAt\": 0.5}}]")
        XCTAssertEqual(nested.commands, [.moveTo(.target(.user), stopAt: 0.5)])
    }

    func testPromptText() {
        let prompt = MindPrompt(actorId: "bush", actorName: "Bush", persona: "A shrub.", event: "tap", senses: ["userDistance": 1.5, "animations": ["idle", "sing"]], tools: ["say", "play"], history: [MindTurn(event: "near", replyText: "[]", tokens: 10)])
        XCTAssertTrue(prompt.systemText.contains("You are Bush"))
        XCTAssertTrue(prompt.systemText.contains("{\"say\""))
        XCTAssertFalse(prompt.systemText.contains("{\"moveTo\""))
        XCTAssertTrue(prompt.systemText.contains("Animations: idle, sing"))
        XCTAssertEqual(prompt.userText, "{\"event\":\"tap\",\"userDistance\":1.5}")
        XCTAssertEqual(prompt.messages.map(\.role), ["user", "assistant", "user"])
        XCTAssertEqual(prompt.messages[0].text, "{\"event\":\"near\"}")
        XCTAssertGreaterThan(prompt.estimatedTokens, 20)
    }

    func testLedger() {
        var ledger = TokenLedger(budget: Mind.Budget(tokens: 1000, perTurn: 300, cooldown: 5))
        XCTAssertNil(ledger.check(estimated: 200, now: 0))
        XCTAssertEqual(ledger.check(estimated: 400, now: 0), .turnTooLarge(estimated: 400, limit: 300))
        ledger.charge(600, at: 0)
        XCTAssertEqual(ledger.remaining, 400)
        XCTAssertEqual(ledger.check(estimated: 100, now: 2), .coolingDown(remaining: 3))
        XCTAssertNil(ledger.check(estimated: 100, now: 6))
        ledger.charge(400, at: 6)
        XCTAssertTrue(ledger.isExhausted)
        XCTAssertEqual(ledger.check(estimated: 1, now: 100), .budgetExhausted)
        XCTAssertNil(TokenLedger(budget: Mind.Budget()).remaining)
        XCTAssertEqual(MindUsage(promptTokens: 10, replyTokens: 5).total, 15)
        XCTAssertNil(MindUsage().total)
    }

    func testCannedProvider() throws {
        let canned = CannedMindProvider(rules: try Examples.mind("singing-bush").canned)
        XCTAssertEqual(canned.reply(to: MindPrompt(actorId: "a", persona: "", event: "tap"))?.commands.first, .say("Ask, and the bush shall sing."))
        XCTAssertEqual(canned.reply(to: MindPrompt(actorId: "a", persona: "", event: "spoken", heard: "Will you SING?"))?.commands.first, .play(animation: "sing", loop: nil, speed: nil))
        XCTAssertNil(canned.reply(to: MindPrompt(actorId: "a", persona: "", event: "near")))
    }

    /// A provider that streams a fixed reply in chunks, the way a token stream would.
    struct StreamingProvider: MindProvider {
        var chunks: [String]
        var usage: MindUsage
        var fail = false
        func respond(to prompt: MindPrompt, onText: @escaping (String) -> Void, completion: @escaping (Result<MindUsage, Error>) -> Void) {
            if fail { completion(.failure(MindError.provider("boom"))); return }
            chunks.forEach(onText)
            completion(.success(usage))
        }
    }

    func testSessionStreamsChargesAndFilters() {
        let mind = Mind(["persona": "p", "tools": ["say", "play"], "budget": ["tokens": 500, "perTurn": 400, "cooldown": 0], "canned": [["match": ".*", "do": ["say": "canned"]]]])
        let provider = StreamingProvider(chunks: ["[{\"say\": \"hi", "\"}, {\"moveTo\": \"user\"}, {\"pl", "ay\": \"sing\"}]"], usage: MindUsage(promptTokens: 400, replyTokens: 90))
        let session = MindSession(actorId: "a", mind: mind, provider: provider)
        var streamed: [ActorCommand] = []
        session.onCommand = { streamed.append($0) }
        var got: [ActorCommand] = []
        session.respond(to: session.prompt(event: "tap", senses: [:]), now: 0) { got = (try? $0.get()) ?? [] }
        XCTAssertEqual(streamed, [.say("hi"), .play(animation: "sing", loop: nil, speed: nil)], "moveTo is not an allowed tool")
        XCTAssertEqual(got, streamed)
        XCTAssertEqual(session.ledger.spent, 490)
        XCTAssertEqual(session.history.count, 1)
        // The next turn would exceed the budget: the canned rules answer and nothing is charged.
        session.respond(to: session.prompt(event: "tap", senses: [:]), now: 1) { got = (try? $0.get()) ?? [] }
        XCTAssertEqual(got, [.say("canned")])
        XCTAssertEqual(session.ledger.spent, 490)
    }

    func testSessionEstimatesWhenUsageIsMissing() {
        let session = MindSession(actorId: "a", mind: Mind(["persona": "p"]), provider: StreamingProvider(chunks: ["Hello there"], usage: MindUsage()))
        var got: [ActorCommand] = []
        var streamed: [ActorCommand] = []
        session.onCommand = { streamed.append($0) }
        session.respond(to: session.prompt(event: "tap", senses: [:]), now: 0) { got = (try? $0.get()) ?? [] }
        XCTAssertEqual(got, [.say("Hello there")])
        XCTAssertEqual(streamed, got, "plain text replies reach onCommand once the stream ends")
        XCTAssertGreaterThan(session.ledger.spent, 0)
    }

    func testSessionWithoutProviderUsesCannedAndErrorsFallBack() {
        let mind = Mind(["persona": "p", "canned": [["match": "near", "do": ["lookAt": "user"]]]])
        let session = MindSession(actorId: "a", mind: mind)
        var got: [ActorCommand]?
        session.respond(to: session.prompt(event: "near", senses: [:])) { got = try? $0.get() }
        XCTAssertEqual(got, [.lookAt(.user)])
        let failing = MindSession(actorId: "a", mind: mind, provider: StreamingProvider(chunks: [], usage: MindUsage(), fail: true))
        failing.respond(to: failing.prompt(event: "near", senses: [:])) { got = try? $0.get() }
        XCTAssertEqual(got, [.lookAt(.user)])
        var error: MindError?
        failing.respond(to: failing.prompt(event: "tap", senses: [:]), now: 100) { if case .failure(let e) = $0 { error = e } }
        if case .provider = error {} else { XCTFail("expected a provider error, got \(String(describing: error))") }
    }

    func testCompletionAdapter() {
        let provider = CompletionMindProvider { _, completion in completion(.success(MindReply(commands: [.say("done")], tokensUsed: 7))) }
        let session = MindSession(actorId: "a", mind: Mind(["persona": "p"]), provider: provider)
        var got: [ActorCommand] = []
        session.respond(to: session.prompt(event: "tap", senses: [:]), now: 0) { got = (try? $0.get()) ?? [] }
        XCTAssertEqual(got, [.say("done")])
        XCTAssertEqual(session.ledger.spent, 7)
    }

    func testSensesAreFilteredByMind() {
        let session = MindSession(actorId: "a", mind: Mind(["persona": "p", "senses": ["userDistance"]]))
        let prompt = session.prompt(event: "tap", senses: ["userDistance": 1, "state": ["x": 1], "animations": ["idle"], "behaviors": ["wander"]])
        XCTAssertEqual(prompt.senses.keys.sorted(), ["animations", "behaviors", "userDistance"])
    }
}
