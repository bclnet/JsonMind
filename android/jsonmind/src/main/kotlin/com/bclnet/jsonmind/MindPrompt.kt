/*
 * MindPrompt.kt
 * JsonMind
 *
 * What a mind is told when a turn starts, and what comes back. The prompt
 * has a plain text form so any chat model can serve it; the reply is a JSON
 * array of commands that `MindReply.parse` reads from the model's text, and
 * `MindReplyAssembler` reads while the text is still streaming.
 */
package com.bclnet.jsonmind

import com.bclnet.jsonui.parseJson
import com.bclnet.jsonui.text
import com.bclnet.jsonui.toJsonString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a mind perceives when a turn starts. */
data class MindPrompt(
    val actorId: String,
    val actorName: String? = null,
    val persona: String,
    /** The event that started the turn (`tap`, `near`, `spoken`, `timer`). */
    val event: String,
    /** Text heard from the user, for `spoken`. */
    val heard: String? = null,
    /** Sense name → value, limited to the mind's `senses`. */
    val senses: Map<String, JsonElement> = emptyMap(),
    val tools: List<String> = Mind.ALL_TOOLS,
    /** Earlier turns, oldest first. */
    val history: List<MindTurn> = emptyList(),
    val maxTokens: Int = Mind.Budget.DEFAULT_PER_TURN,
) {
    /** The system prompt: who the actor is and the reply format. */
    val systemText: String
        get() = buildString {
            append("You are ${actorName ?: actorId}, a character in an augmented reality scene.\n")
            if (persona.isNotEmpty()) append("$persona\n")
            append("\nReply with ONLY a JSON array of commands, nothing else. Allowed commands:\n")
            for (tool in tools) {
                when (tool) {
                    "say" -> append("  {\"say\": \"short text to speak\"}\n")
                    "play" -> append("  {\"play\": \"animation name\"}\n")
                    "sound" -> append("  {\"sound\": \"sound name\"}\n")
                    "moveTo" -> append("  {\"moveTo\": \"user\"} or {\"moveTo\": [x, y, z]}\n")
                    "lookAt" -> append("  {\"lookAt\": \"user\"}\n")
                    "behave" -> append("  {\"behave\": \"wander\" | \"approach\" | \"flee\" | \"follow\" | \"idle\"}\n")
                    "set" -> append("  {\"set\": {\"stateKey\": value}}\n")
                    "stop" -> append("  {\"stop\": true}\n")
                    "wait" -> append("  {\"wait\": seconds}\n")
                    else -> append("  {\"$tool\": ...}\n")
                }
            }
            (senses["animations"] as? JsonArray)?.let { append("Animations: ${it.mapNotNull { a -> a.text }.joinToString(", ")}\n") }
            (senses["sounds"] as? JsonArray)?.let { append("Sounds: ${it.mapNotNull { a -> a.text }.joinToString(", ")}\n") }
            (senses["behaviors"] as? JsonArray)?.let { append("Behaviors: ${it.mapNotNull { a -> a.text }.joinToString(", ")}\n") }
            append("Keep replies short: at most 3 commands and one sentence of speech.")
        }

    /** The user message: the event and the senses, as JSON. */
    val userText: String
        get() {
            val o = linkedMapOf<String, JsonElement>("event" to JsonPrimitive(event))
            heard?.let { o["heard"] = JsonPrimitive(it) }
            for ((k, v) in senses) if (k !in SYSTEM_SENSES) o[k] = v
            return JsonObject(o).toJsonString()
        }

    /** The conversation as alternating messages, oldest first, for chat APIs. */
    val messages: List<Pair<String, String>>
        get() = history.flatMap { listOf("user" to it.userText, "assistant" to it.replyText) } + ("user" to userText)

    /** A rough token estimate (4 characters per token) used to enforce budgets before a call. */
    val estimatedTokens: Int get() = MindTurn.estimateTokens(systemText) + MindTurn.estimateTokens(userText) + history.sumOf { it.estimatedTokens }

    companion object {
        /** Senses that describe the actor's own repertoire and go into the system prompt instead. */
        val SYSTEM_SENSES = setOf("animations", "sounds", "behaviors")
    }
}

/** One completed turn, kept as history. */
data class MindTurn(val event: String, val heard: String? = null, val replyText: String, val tokens: Int) {
    val userText: String
        get() {
            val o = linkedMapOf<String, JsonElement>("event" to JsonPrimitive(event))
            heard?.let { o["heard"] = JsonPrimitive(it) }
            return JsonObject(o).toJsonString()
        }

    val estimatedTokens: Int get() = estimateTokens(userText) + estimateTokens(replyText)

    companion object {
        fun estimateTokens(text: String): Int = (text.toByteArray(Charsets.UTF_8).size + 3) / 4
    }
}

/** Token usage reported by a provider for one turn. */
data class MindUsage(val promptTokens: Int? = null, val replyTokens: Int? = null) {
    /** Total reported tokens, or `null` when the provider reported nothing. */
    val total: Int? get() = if (promptTokens == null && replyTokens == null) null else (promptTokens ?: 0) + (replyTokens ?: 0)
}

data class MindReply(
    val commands: List<ActorCommand>,
    /** Tokens the provider reports for the turn; `null` means estimate. */
    val tokensUsed: Int? = null,
    /** The raw model text, kept for history. */
    val text: String = "",
) {
    companion object {
        /**
         * Parses a model's text reply: a JSON array of commands, tolerating code fences and prose around it.
         * Plain text with no JSON becomes a single `say`.
         */
        fun parse(text: String, tokensUsed: Int? = null): MindReply {
            var body = text.trim()
            if (body.startsWith("```")) {
                body = body.split("\n").drop(1).joinToString("\n")
                val fence = body.lastIndexOf("```")
                if (fence >= 0) body = body.substring(0, fence)
            }
            val start = body.indexOf('['); val end = body.lastIndexOf(']')
            if (start in 0 until end) {
                runCatching { parseJson(body.substring(start, end + 1)) }.getOrNull()?.let { value ->
                    return MindReply((value as? JsonArray)?.mapNotNull { ActorCommand.of(it) } ?: emptyList(), tokensUsed, text)
                }
            }
            val os = body.indexOf('{'); val oe = body.lastIndexOf('}')
            if (os in 0 until oe) {
                runCatching { parseJson(body.substring(os, oe + 1)) }.getOrNull()?.let { ActorCommand.of(it) }?.let { return MindReply(listOf(it), tokensUsed, text) }
            }
            val spoken = body.trim()
            return MindReply(if (spoken.isEmpty()) emptyList() else listOf(ActorCommand.Say(spoken)), tokensUsed, text)
        }
    }
}

/**
 * Reads commands out of a reply while it streams, so `say` can start before the model finishes.
 * Feed text deltas with `append`; each completed top level JSON object becomes a command.
 */
class MindReplyAssembler {
    private val buffer = StringBuilder()
    private val _commands = mutableListOf<ActorCommand>()
    private var objectStart = -1
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var scanned = 0

    val text: String get() = buffer.toString()
    val commands: List<ActorCommand> get() = _commands.toList()

    /** Appends a delta and returns the commands completed by it. */
    fun append(delta: String): List<ActorCommand> {
        buffer.append(delta)
        val produced = mutableListOf<ActorCommand>()
        while (scanned < buffer.length) {
            val c = buffer[scanned]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                '{' -> { if (depth == 0) objectStart = scanned; depth++ }
                '}' -> {
                    depth = maxOf(0, depth - 1)
                    if (depth == 0 && objectStart >= 0) {
                        val obj = buffer.substring(objectStart, scanned + 1)
                        objectStart = -1
                        runCatching { parseJson(obj) }.getOrNull()?.let { ActorCommand.of(it) }?.let { _commands += it; produced += it }
                    }
                }
                else -> {}
            }
            scanned++
        }
        return produced
    }

    /** The reply once the stream ends: the streamed commands, or a `say` of plain text when there were none. */
    fun finish(tokensUsed: Int? = null): MindReply =
        if (_commands.isEmpty()) MindReply.parse(text, tokensUsed) else MindReply(_commands.toList(), tokensUsed, text)
}
