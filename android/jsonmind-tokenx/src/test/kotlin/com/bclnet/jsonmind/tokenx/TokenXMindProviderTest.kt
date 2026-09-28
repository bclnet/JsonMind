package com.bclnet.jsonmind.tokenx

import com.bclnet.jsonmind.ActorCommand
import com.bclnet.jsonmind.Mind
import com.bclnet.jsonmind.MindPrompt
import com.bclnet.jsonmind.MindSession
import com.bclnet.jsonmind.MindTurn
import com.bclnet.jsonui.jsonArrayOf
import com.bclnet.jsonui.jsonObjectOf
import com.bclnet.jsonui.jsonOf
import com.bclnet.tokenx.Cancellable
import com.bclnet.tokenx.ChatEvent
import com.bclnet.tokenx.ChatMessage
import com.bclnet.tokenx.ChatReply
import com.bclnet.tokenx.ChatRequest
import com.bclnet.tokenx.NoopCancellable
import com.bclnet.tokenx.Profile
import com.bclnet.tokenx.StopReason
import com.bclnet.tokenx.TokenBroker
import com.bclnet.tokenx.TokenXException
import com.bclnet.tokenx.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A broker that answers with a fixed streamed reply and remembers what it was asked. */
class FakeBroker : TokenBroker {
    override var isReady = true
    var chunks = listOf("[{\"say\": \"Ask, and the bush ", "shall sing.\"}, {\"play\": \"sing\"}]")
    var usage = Usage(40, 20)
    var failure: TokenXException? = null
    val requests = ArrayList<Triple<ChatRequest, Profile, String>>()

    override fun stream(request: ChatRequest, profile: Profile, consumer: String, onEvent: (ChatEvent) -> Unit, completion: (Result<ChatReply>) -> Unit): Cancellable {
        requests += Triple(request, profile, consumer)
        failure?.let { completion(Result.failure(it)); return NoopCancellable }
        chunks.forEach { onEvent(ChatEvent.Text(it)) }
        onEvent(ChatEvent.Done(usage, StopReason.END))
        completion(Result.success(ChatReply(chunks.joinToString(""), usage, StopReason.END)))
        return NoopCancellable
    }
}

class TokenXMindProviderTest {
    @Test fun mindGetsStreamedCommandsAndUsageFromTokenX() {
        val broker = FakeBroker()
        val provider = TokenXMindProvider(broker)
        val mind = Mind.of(jsonObjectOf("persona" to "You are the singing bush.", "tools" to jsonArrayOf("say", "play"), "budget" to jsonObjectOf("tokens" to 5000, "cooldown" to 0)))
        val session = MindSession("bush", mind, provider)
        val streamed = ArrayList<ActorCommand>()
        session.onCommand = { streamed += it }
        var got: List<ActorCommand> = emptyList()
        session.respond(session.prompt("tap", senses = mapOf("userDistance" to jsonOf(1.2))), 0.0) { got = it.getOrDefault(emptyList()) }
        assertEquals(listOf(ActorCommand.Say("Ask, and the bush shall sing."), ActorCommand.Play("sing")), got)
        assertEquals(got, streamed)
        assertEquals(60, session.ledger.spent)
        val (request, profile, consumer) = broker.requests[0]
        assertEquals("bush", consumer)
        assertEquals(Profile.CHARACTER, profile)
        assertTrue(request.system!!.contains("You are the singing bush."))
        assertEquals(1, request.messages.size)
        assertEquals(ChatMessage.Role.USER, request.messages[0].role)
        assertTrue(request.messages[0].text.contains("\"event\":\"tap\""))
        assertEquals(400, request.maxTokens)
        assertEquals(60, provider.session("bush").spent)
        session.respond(session.prompt("near", senses = emptyMap()), 1.0) {}
        assertEquals(listOf(ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT, ChatMessage.Role.USER), broker.requests[1].first.messages.map { it.role })
    }

    @Test fun tokenXErrorsFallBackToCannedRules() {
        val broker = FakeBroker().apply { failure = TokenXException.DailyCapReached }
        val mind = Mind.of(jsonObjectOf("persona" to "p", "canned" to jsonArrayOf(jsonObjectOf("match" to "tap", "do" to jsonObjectOf("say" to "canned")))))
        val session = MindSession("bush", mind, TokenXMindProvider(broker))
        var got: List<ActorCommand> = emptyList()
        session.respond(session.prompt("tap", senses = emptyMap()), 0.0) { got = it.getOrDefault(emptyList()) }
        assertEquals(listOf(ActorCommand.Say("canned")), got)
        assertEquals(0, session.ledger.spent)
    }

    @Test fun requestShape() {
        val prompt = MindPrompt("a", persona = "p", event = "tap", history = listOf(MindTurn("near", replyText = "[]", tokens = 1)), maxTokens = 5000)
        val request = TokenXMindProvider.request(prompt)
        assertEquals(listOf(ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT, ChatMessage.Role.USER), request.messages.map { it.role })
        assertEquals(1024, request.maxTokens)
    }
}
