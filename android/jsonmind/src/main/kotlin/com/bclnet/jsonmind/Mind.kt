/*
 * Mind.kt
 * JsonMind
 *
 * A mind is the personality of something in a JsonUI document: an actor in
 * a scene, or a form assistant. It is prompt material (`persona`, `senses`,
 * `tools`), the token `budget` it may spend, the events that wake it and
 * the `canned` rules used when no model is attached. Minds are often shared
 * as fragments: `"mind": { "$ref": "minds/snoopy.json" }`.
 */
package com.bclnet.jsonmind

import com.bclnet.jsonui.integerValue
import com.bclnet.jsonui.jsonNumber
import com.bclnet.jsonui.numberValue
import com.bclnet.jsonui.parseJson
import com.bclnet.jsonui.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class Mind(
    val persona: String,
    val senses: List<String> = ALL_SENSES,
    val tools: List<String> = ALL_TOOLS,
    val budget: Budget = Budget(),
    val triggers: List<String> = DEFAULT_TRIGGERS,
    /** Seconds between `timer` turns when `timer` is a trigger. */
    val interval: Double = 30.0,
    val canned: List<Rule> = emptyList(),
) {
    data class Budget(
        /** Total tokens for the mind's lifetime; `null` is unlimited (the token supply still decides). */
        val tokens: Int? = null,
        /** Maximum tokens one turn may spend (prompt + reply). */
        val perTurn: Int = DEFAULT_PER_TURN,
        /** Minimum seconds between turns. */
        val cooldown: Double = DEFAULT_COOLDOWN,
    ) {
        val value: JsonElement
            get() {
                val o = linkedMapOf<String, JsonElement>()
                tokens?.let { o["tokens"] = jsonNumber(it.toDouble()) }
                if (perTurn != DEFAULT_PER_TURN) o["perTurn"] = jsonNumber(perTurn.toDouble())
                if (cooldown != DEFAULT_COOLDOWN) o["cooldown"] = jsonNumber(cooldown)
                return JsonObject(o)
            }

        companion object {
            const val DEFAULT_PER_TURN = 400
            const val DEFAULT_COOLDOWN = 5.0

            fun of(value: JsonElement): Budget {
                val o = value as? JsonObject ?: JsonObject(emptyMap())
                return Budget(o["tokens"]?.integerValue ?: value.integerValue, o["perTurn"]?.integerValue ?: DEFAULT_PER_TURN, o["cooldown"]?.numberValue ?: DEFAULT_COOLDOWN)
            }
        }
    }

    /** A canned reaction: a regular expression over the event name and heard text, and what to do. */
    data class Rule(val match: String, val script: ActorScript) {
        val value: JsonElement get() = JsonObject(mapOf("match" to JsonPrimitive(match), "do" to script.value))

        fun matches(text: String): Boolean = runCatching { Regex(match, RegexOption.IGNORE_CASE).containsMatchIn(text) }
            .getOrElse { text.contains(match, ignoreCase = true) }

        companion object {
            fun of(value: JsonElement): Rule? {
                val o = value as? JsonObject ?: return null
                val m = o["match"]?.text ?: return null
                val s = o["do"]?.let { ActorScript.of(it) } ?: return null
                return Rule(m, s)
            }
        }
    }

    fun allows(command: ActorCommand): Boolean = tools.contains(command.verb)

    val value: JsonElement
        get() {
            val o = linkedMapOf<String, JsonElement>("persona" to JsonPrimitive(persona))
            if (senses != ALL_SENSES) o["senses"] = JsonArray(senses.map { JsonPrimitive(it) })
            if (tools != ALL_TOOLS) o["tools"] = JsonArray(tools.map { JsonPrimitive(it) })
            if (budget != Budget()) o["budget"] = budget.value
            if (triggers != DEFAULT_TRIGGERS) o["triggers"] = JsonArray(triggers.map { JsonPrimitive(it) })
            if (interval != 30.0) o["interval"] = jsonNumber(interval)
            if (canned.isNotEmpty()) o["canned"] = JsonArray(canned.map { it.value })
            return JsonObject(o)
        }

    companion object {
        val ALL_SENSES = listOf("userDistance", "userLooking", "timeOfDay", "state", "actors", "lastEvent")
        val ALL_TOOLS = listOf("say", "play", "sound", "moveTo", "lookAt", "behave", "set")
        val DEFAULT_TRIGGERS = listOf("tap", "near")

        fun of(value: JsonElement): Mind {
            val o = value as? JsonObject ?: JsonObject(emptyMap())
            fun strings(key: String, fallback: List<String>): List<String> = (o[key] as? JsonArray)?.mapNotNull { it.text } ?: fallback
            return Mind(
                persona = o["persona"]?.text ?: value.text ?: "",
                senses = strings("senses", ALL_SENSES),
                tools = strings("tools", ALL_TOOLS),
                budget = Budget.of(o["budget"] ?: JsonNull),
                triggers = strings("triggers", DEFAULT_TRIGGERS),
                interval = o["interval"]?.numberValue ?: 30.0,
                canned = (o["canned"] as? JsonArray)?.mapNotNull { Rule.of(it) } ?: emptyList(),
            )
        }

        fun parse(json: String): Mind = of(parseJson(json))
    }
}
