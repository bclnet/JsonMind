/*
 * ActorCommand.kt
 * JsonMind
 *
 * The command vocabulary shared by event handlers, behaviors and minds:
 * `{ "play": "sing" }`, `{ "moveTo": "user" }`, `{ "say": "Hello" }`.
 * Anything that is not a command is a JsonUI action (`set`, scripts, host
 * actions), so an `on` handler mixes both freely.
 */
package com.bclnet.jsonmind

import com.bclnet.jsonui.JsonAction
import com.bclnet.jsonui.flag
import com.bclnet.jsonui.jsonNumber
import com.bclnet.jsonui.numberValue
import com.bclnet.jsonui.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A target of a command or behavior: the viewer or another actor. */
sealed class Target {
    object User : Target() { override fun toString() = "User" }
    data class ActorId(val id: String) : Target()

    val value: JsonElement get() = JsonPrimitive(when (this) { is User -> "user"; is ActorId -> id })

    companion object {
        fun of(value: JsonElement): Target? {
            val s = value.text?.takeIf { it.isNotEmpty() } ?: return null
            return if (s == "user") User else ActorId(s)
        }
    }
}

/** A point in scene space, metres, +Y up. Defined here so commands can carry positions; the scene has the geometry. */
data class Point3(val x: Double, val y: Double, val z: Double) {
    val value: JsonElement get() = JsonArray(listOf(jsonNumber(x), jsonNumber(y), jsonNumber(z)))

    companion object {
        /** Parses `[x, y, z]`, `[x, z]` (y = 0) or `{ "x": .., "y": .., "z": .. }`. */
        fun of(value: JsonElement): Point3? = when (value) {
            is JsonArray -> {
                val n = value.mapNotNull { it.numberValue }
                if (n.size != value.size || n.isEmpty()) null
                else when (n.size) {
                    1 -> Point3(n[0], 0.0, 0.0)
                    2 -> Point3(n[0], 0.0, n[1])
                    else -> Point3(n[0], n[1], n[2])
                }
            }
            is JsonObject -> Point3(value["x"]?.numberValue ?: 0.0, value["y"]?.numberValue ?: 0.0, value["z"]?.numberValue ?: 0.0)
            else -> null
        }
    }
}

sealed class ActorCommand {
    data class Play(val animation: String, val loop: Boolean? = null, val speed: Double? = null) : ActorCommand()
    data class Sound(val name: String, val loop: Boolean? = null) : ActorCommand()
    /** `null` stops every sound. */
    data class StopSound(val name: String?) : ActorCommand()
    data class Say(val text: String) : ActorCommand()
    data class MoveTo(val target: MoveTarget, val stopAt: Double? = null) : ActorCommand()
    data class LookAt(val target: Target) : ActorCommand()
    object Stop : ActorCommand() { override fun toString() = "Stop" }
    data class Wait(val seconds: Double) : ActorCommand()
    data class Emit(val event: String) : ActorCommand()
    /** Selects a behavior by name or description; the scene interprets the value, so a mind can steer without knowing the geometry. */
    data class Behave(val behavior: JsonElement) : ActorCommand()

    sealed class MoveTarget {
        data class Point(val point: Point3) : MoveTarget()
        data class Of(val target: Target) : MoveTarget()
    }

    val verb: String
        get() = when (this) {
            is Play -> "play"; is Sound -> "sound"; is StopSound -> "stopSound"; is Say -> "say"; is MoveTo -> "moveTo"
            is LookAt -> "lookAt"; is Stop -> "stop"; is Wait -> "wait"; is Emit -> "emit"; is Behave -> "behave"
        }

    val value: JsonElement
        get() = when (this) {
            is Play -> JsonObject(linkedMapOf<String, JsonElement>("play" to JsonPrimitive(animation)).also { o ->
                loop?.let { o["loop"] = JsonPrimitive(it) }; speed?.let { o["speed"] = jsonNumber(it) }
            })
            is Sound -> JsonObject(linkedMapOf<String, JsonElement>("sound" to JsonPrimitive(name)).also { o -> loop?.let { o["loop"] = JsonPrimitive(it) } })
            is StopSound -> JsonObject(mapOf("stopSound" to (name?.let { JsonPrimitive(it) } ?: JsonPrimitive(true))))
            is Say -> JsonObject(mapOf("say" to JsonPrimitive(text)))
            is MoveTo -> JsonObject(linkedMapOf<String, JsonElement>().also { o ->
                o["moveTo"] = when (target) { is MoveTarget.Point -> target.point.value; is MoveTarget.Of -> target.target.value }
                stopAt?.let { o["stopAt"] = jsonNumber(it) }
            })
            is LookAt -> JsonObject(mapOf("lookAt" to target.value))
            is Stop -> JsonObject(mapOf("stop" to JsonPrimitive(true)))
            is Wait -> JsonObject(mapOf("wait" to jsonNumber(seconds)))
            is Emit -> JsonObject(mapOf("emit" to JsonPrimitive(event)))
            is Behave -> JsonObject(mapOf("behave" to behavior))
        }

    companion object {
        val VERBS = listOf("play", "sound", "stopSound", "say", "moveTo", "lookAt", "stop", "wait", "emit", "behave")

        fun of(value: JsonElement): ActorCommand? {
            val o = value as? JsonObject ?: return null
            if (o.isEmpty()) return null
            // The verb is the one key that is a known command; extra keys are its options.
            val verb = VERBS.firstOrNull { o.containsKey(it) } ?: return null
            val arg = o[verb] ?: return null
            return when (verb) {
                "play" -> arg.text?.let { Play(it, o["loop"]?.flag, o["speed"]?.numberValue) }
                    ?: (arg as? JsonObject)?.let { p -> (p["animation"]?.text ?: p["name"]?.text)?.let { Play(it, p["loop"]?.flag, p["speed"]?.numberValue) } }
                "sound" -> arg.text?.let { Sound(it, o["loop"]?.flag) }
                    ?: (arg as? JsonObject)?.let { p -> (p["name"]?.text ?: p["sound"]?.text)?.let { Sound(it, p["loop"]?.flag) } }
                "stopSound" -> StopSound(arg.text)
                "say" -> arg.text?.let { Say(it) }
                "moveTo" -> when {
                    arg is JsonArray -> Point3.of(arg)?.let { MoveTo(MoveTarget.Point(it), o["stopAt"]?.numberValue) }
                    arg.text != null -> Target.of(arg)?.let { MoveTo(MoveTarget.Of(it), o["stopAt"]?.numberValue) }
                    arg is JsonObject -> {
                        val t = arg["target"]?.let { Target.of(it) }
                        if (t != null) MoveTo(MoveTarget.Of(t), arg["stopAt"]?.numberValue)
                        else (arg["point"]?.let { Point3.of(it) } ?: Point3.of(arg))?.let { MoveTo(MoveTarget.Point(it), arg["stopAt"]?.numberValue) }
                    }
                    else -> null
                }
                "lookAt" -> Target.of(arg)?.let { LookAt(it) }
                "stop" -> Stop
                "wait" -> arg.numberValue?.let { Wait(it) }
                "emit" -> arg.text?.takeIf { it.isNotEmpty() }?.let { Emit(it) }
                "behave" -> if (arg.text != null || arg is JsonObject) Behave(arg) else null
                else -> null
            }
        }
    }
}

/** One step of a handler: a command for the actor or a JsonUI action. */
sealed class ActorStep {
    data class Command(val command: ActorCommand) : ActorStep()
    data class Action(val action: JsonAction) : ActorStep()

    val value: JsonElement get() = when (this) { is Command -> command.value; is Action -> action.value }

    companion object {
        fun of(value: JsonElement): ActorStep? = ActorCommand.of(value)?.let { Command(it) } ?: JsonAction.of(value)?.let { Action(it) }
    }
}

/** A sequence of steps: a single step or an array of them. */
data class ActorScript(val steps: List<ActorStep>) {
    val isEmpty: Boolean get() = steps.isEmpty()
    val commands: List<ActorCommand> get() = steps.mapNotNull { (it as? ActorStep.Command)?.command }

    val value: JsonElement get() = if (steps.size == 1) steps[0].value else JsonArray(steps.map { it.value })

    companion object {
        fun ofCommands(commands: List<ActorCommand>) = ActorScript(commands.map { ActorStep.Command(it) })

        fun of(value: JsonElement): ActorScript? = when (value) {
            is JsonNull -> null
            is JsonArray -> ActorScript(value.mapNotNull { ActorStep.of(it) })
            else -> ActorStep.of(value)?.let { ActorScript(listOf(it)) }
        }

        /** Parses an `on` object: event name → script. */
        fun handlers(value: JsonElement): Map<String, ActorScript> =
            (value as? JsonObject)?.mapNotNull { (k, v) -> of(v)?.let { k to it } }?.toMap() ?: emptyMap()

        fun value(handlers: Map<String, ActorScript>): JsonElement = JsonObject(handlers.mapValues { it.value.value })
    }
}
