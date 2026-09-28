package com.bclnet.jsonmind

import com.bclnet.jsonui.JsonFragments
import com.bclnet.jsonui.get
import com.bclnet.jsonui.jsonArrayOf
import com.bclnet.jsonui.jsonObjectOf
import com.bclnet.jsonui.jsonOf
import com.bclnet.jsonui.parseJson
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MindTest {
    @Test fun examplesParse() {
        val bush = Examples.mind("singing-bush")
        assertEquals(Mind.Budget(20000, 300, 8.0), bush.budget)
        assertEquals(listOf("say", "play", "sound"), bush.tools)
        assertEquals(listOf("tap", "spoken"), bush.triggers)
        assertEquals(3, bush.canned.size)
        assertEquals(bush, Mind.of(bush.value))
        val snoopy = Examples.mind("snoopy")
        assertEquals(45.0, snoopy.interval, 0.0)
        assertEquals(listOf(ActorCommand.Behave(JsonPrimitive("wander"))), snoopy.canned.last().script.commands)
        assertEquals(snoopy, Mind.of(snoopy.value))
        assertEquals("just a persona", Mind.of(JsonPrimitive("just a persona")).persona)
    }

    @Test fun mindAsFragment() {
        val scene = jsonObjectOf("type" to "Scene", "actors" to jsonArrayOf(jsonObjectOf("id" to "s", "mind" to jsonObjectOf("\$ref" to "minds/snoopy.json", "budget" to jsonObjectOf("tokens" to 5000)))))
        val resolver = JsonFragments { url -> parseJson(File(url).readText()) }
        val resolved = resolver.resolve(scene, File(Examples.directory, "scene.json").toURI())
        val mind = Mind.of(resolved["actors"][0]["mind"])
        assertEquals(5000, mind.budget.tokens)
        assertEquals(Mind.Budget.DEFAULT_PER_TURN, mind.budget.perTurn)
        assertTrue(mind.persona.startsWith("You are Snoopy"))
    }

    @Test fun replyParsing() {
        assertEquals(listOf(ActorCommand.Say("hi"), ActorCommand.Play("sing")), MindReply.parse("[{\"say\": \"hi\"}, {\"play\": \"sing\"}]").commands)
        assertEquals(listOf(ActorCommand.Say("hi")), MindReply.parse("Sure!\n```json\n[{\"say\": \"hi\"}]\n```").commands)
        assertEquals(listOf(ActorCommand.LookAt(Target.User)), MindReply.parse("{\"lookAt\": \"user\"}").commands)
        assertEquals(listOf(ActorCommand.Say("Woof.")), MindReply.parse("Woof.").commands)
        assertEquals(emptyList<ActorCommand>(), MindReply.parse("  ").commands)
        assertEquals(emptyList<ActorCommand>(), MindReply.parse("[{\"nuke\": 1}]").commands)
    }

    @Test fun streamingAssembler() {
        val assembler = MindReplyAssembler()
        assertEquals(emptyList<ActorCommand>(), assembler.append("[{\"say\": \"Hel"))
        assertEquals(listOf(ActorCommand.Say("Hello }")), assembler.append("lo }\"}, {\"pl"))
        assertEquals(listOf(ActorCommand.Play("sing")), assembler.append("ay\": \"sing\"}"))
        assertEquals(emptyList<ActorCommand>(), assembler.append("]"))
        assertEquals(2, assembler.finish(12).commands.size)
        assertNull(assembler.finish().tokensUsed)
        val plain = MindReplyAssembler()
        plain.append("Just wo"); plain.append("rds.")
        assertEquals(listOf(ActorCommand.Say("Just words.")), plain.finish().commands)
        val nested = MindReplyAssembler()
        nested.append("[{\"moveTo\": {\"target\": \"user\", \"stopAt\": 0.5}}]")
        assertEquals(listOf(ActorCommand.MoveTo(ActorCommand.MoveTarget.Of(Target.User), 0.5)), nested.commands)
    }

    @Test fun promptText() {
        val prompt = MindPrompt("bush", "Bush", "A shrub.", "tap", senses = mapOf("userDistance" to jsonOf(1.5), "animations" to jsonArrayOf("idle", "sing")), tools = listOf("say", "play"), history = listOf(MindTurn("near", replyText = "[]", tokens = 10)))
        assertTrue(prompt.systemText.contains("You are Bush"))
        assertTrue(prompt.systemText.contains("{\"say\""))
        assertFalse(prompt.systemText.contains("{\"moveTo\""))
        assertTrue(prompt.systemText.contains("Animations: idle, sing"))
        assertEquals("{\"event\":\"tap\",\"userDistance\":1.5}", prompt.userText)
        assertEquals(listOf("user", "assistant", "user"), prompt.messages.map { it.first })
        assertEquals("{\"event\":\"near\"}", prompt.messages[0].second)
        assertTrue(prompt.estimatedTokens > 20)
    }

    @Test fun ledger() {
        val ledger = TokenLedger(Mind.Budget(1000, 300, 5.0))
        assertNull(ledger.check(200, 0.0))
        assertEquals(MindError.TurnTooLarge(400, 300), ledger.check(400, 0.0))
        ledger.charge(600, 0.0)
        assertEquals(400, ledger.remaining)
        assertEquals(MindError.CoolingDown(3.0), ledger.check(100, 2.0))
        assertNull(ledger.check(100, 6.0))
        ledger.charge(400, 6.0)
        assertTrue(ledger.isExhausted)
        assertEquals(MindError.BudgetExhausted, ledger.check(1, 100.0))
        assertNull(TokenLedger(Mind.Budget()).remaining)
        assertEquals(15, MindUsage(10, 5).total)
        assertNull(MindUsage().total)
    }

    @Test fun cannedProvider() {
        val canned = CannedMindProvider(Examples.mind("singing-bush").canned)
        assertEquals(ActorCommand.Say("Ask, and the bush shall sing."), canned.reply(MindPrompt("a", persona = "", event = "tap"))?.commands?.first())
        assertEquals(ActorCommand.Play("sing"), canned.reply(MindPrompt("a", persona = "", event = "spoken", heard = "Will you SING?"))?.commands?.first())
        assertNull(canned.reply(MindPrompt("a", persona = "", event = "near")))
    }

    private fun streaming(chunks: List<String>, usage: MindUsage, fail: Boolean = false) = MindProvider { _, onText, completion ->
        if (fail) completion(Result.failure(MindError.Provider("boom"))) else { chunks.forEach(onText); completion(Result.success(usage)) }
    }

    @Test fun sessionStreamsChargesAndFilters() {
        val mind = Mind.of(jsonObjectOf("persona" to "p", "tools" to jsonArrayOf("say", "play"), "budget" to jsonObjectOf("tokens" to 500, "perTurn" to 400, "cooldown" to 0),
            "canned" to jsonArrayOf(jsonObjectOf("match" to ".*", "do" to jsonObjectOf("say" to "canned")))))
        val session = MindSession("a", mind, streaming(listOf("[{\"say\": \"hi", "\"}, {\"moveTo\": \"user\"}, {\"pl", "ay\": \"sing\"}]"), MindUsage(400, 90)))
        val streamed = mutableListOf<ActorCommand>()
        session.onCommand = { streamed += it }
        var got: List<ActorCommand> = emptyList()
        session.respond(session.prompt("tap", senses = emptyMap()), 0.0) { got = it.getOrDefault(emptyList()) }
        assertEquals(listOf(ActorCommand.Say("hi"), ActorCommand.Play("sing")), streamed)
        assertEquals(streamed, got)
        assertEquals(490, session.ledger.spent)
        assertEquals(1, session.history.size)
        session.respond(session.prompt("tap", senses = emptyMap()), 1.0) { got = it.getOrDefault(emptyList()) }
        assertEquals(listOf(ActorCommand.Say("canned")), got)
        assertEquals(490, session.ledger.spent)
    }

    @Test fun sessionEstimatesWhenUsageIsMissing() {
        val session = MindSession("a", Mind.of(jsonObjectOf("persona" to "p")), streaming(listOf("Hello there"), MindUsage()))
        val streamed = mutableListOf<ActorCommand>()
        session.onCommand = { streamed += it }
        var got: List<ActorCommand> = emptyList()
        session.respond(session.prompt("tap", senses = emptyMap()), 0.0) { got = it.getOrDefault(emptyList()) }
        assertEquals(listOf(ActorCommand.Say("Hello there")), got)
        assertEquals(got, streamed)
        assertTrue(session.ledger.spent > 0)
    }

    @Test fun sessionWithoutProviderUsesCannedAndErrorsFallBack() {
        val mind = Mind.of(jsonObjectOf("persona" to "p", "canned" to jsonArrayOf(jsonObjectOf("match" to "near", "do" to jsonObjectOf("lookAt" to "user")))))
        val session = MindSession("a", mind)
        var got: List<ActorCommand>? = null
        session.respond(session.prompt("near", senses = emptyMap())) { got = it.getOrNull() }
        assertEquals(listOf(ActorCommand.LookAt(Target.User)), got)
        val failing = MindSession("a", mind, streaming(emptyList(), MindUsage(), fail = true))
        failing.respond(failing.prompt("near", senses = emptyMap())) { got = it.getOrNull() }
        assertEquals(listOf(ActorCommand.LookAt(Target.User)), got)
        var error: Throwable? = null
        failing.respond(failing.prompt("tap", senses = emptyMap()), 100.0) { error = it.exceptionOrNull() }
        assertTrue(error is MindError.Provider)
    }

    @Test fun completionAdapter() {
        val provider = CompletionMindProvider { _, completion -> completion(Result.success(MindReply(listOf(ActorCommand.Say("done")), 7))) }
        val session = MindSession("a", Mind.of(jsonObjectOf("persona" to "p")), provider)
        var got: List<ActorCommand> = emptyList()
        session.respond(session.prompt("tap", senses = emptyMap()), 0.0) { got = it.getOrDefault(emptyList()) }
        assertEquals(listOf(ActorCommand.Say("done")), got)
        assertEquals(7, session.ledger.spent)
    }

    @Test fun sensesAreFilteredByMind() {
        val session = MindSession("a", Mind.of(jsonObjectOf("persona" to "p", "senses" to jsonArrayOf("userDistance"))))
        val prompt = session.prompt("tap", senses = mapOf("userDistance" to jsonOf(1), "state" to jsonObjectOf("x" to 1), "animations" to jsonArrayOf("idle"), "behaviors" to jsonArrayOf("wander")))
        assertEquals(listOf("animations", "behaviors", "userDistance"), prompt.senses.keys.sorted())
    }
}
