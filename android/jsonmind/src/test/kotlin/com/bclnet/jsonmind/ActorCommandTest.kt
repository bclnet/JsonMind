package com.bclnet.jsonmind

import com.bclnet.jsonui.JsonAction
import com.bclnet.jsonui.jsonArrayOf
import com.bclnet.jsonui.jsonObjectOf
import com.bclnet.jsonui.jsonOf
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActorCommandTest {
    @Test fun parsing() {
        assertEquals(ActorCommand.Play("sing"), ActorCommand.of(jsonObjectOf("play" to "sing")))
        assertEquals(ActorCommand.Play("sing", true, 2.0), ActorCommand.of(jsonObjectOf("play" to jsonObjectOf("animation" to "sing", "loop" to true, "speed" to 2))))
        assertEquals(ActorCommand.Sound("song", true), ActorCommand.of(jsonObjectOf("sound" to "song", "loop" to true)))
        assertEquals(ActorCommand.StopSound(null), ActorCommand.of(jsonObjectOf("stopSound" to true)))
        assertEquals(ActorCommand.StopSound("song"), ActorCommand.of(jsonObjectOf("stopSound" to "song")))
        assertEquals(ActorCommand.Say("hi"), ActorCommand.of(jsonObjectOf("say" to "hi")))
        assertEquals(ActorCommand.MoveTo(ActorCommand.MoveTarget.Of(Target.User)), ActorCommand.of(jsonObjectOf("moveTo" to "user")))
        assertEquals(ActorCommand.MoveTo(ActorCommand.MoveTarget.Point(Point3(1.0, 2.0, 3.0)), 0.2), ActorCommand.of(jsonObjectOf("moveTo" to jsonArrayOf(1, 2, 3), "stopAt" to 0.2)))
        assertEquals(ActorCommand.MoveTo(ActorCommand.MoveTarget.Of(Target.ActorId("bush")), 0.4), ActorCommand.of(jsonObjectOf("moveTo" to jsonObjectOf("target" to "bush", "stopAt" to 0.4))))
        assertEquals(ActorCommand.MoveTo(ActorCommand.MoveTarget.Point(Point3(1.0, 0.0, 2.0))), ActorCommand.of(jsonObjectOf("moveTo" to jsonObjectOf("x" to 1, "z" to 2))))
        assertEquals(ActorCommand.LookAt(Target.User), ActorCommand.of(jsonObjectOf("lookAt" to "user")))
        assertEquals(ActorCommand.Stop, ActorCommand.of(jsonObjectOf("stop" to true)))
        assertEquals(ActorCommand.Wait(1.5), ActorCommand.of(jsonObjectOf("wait" to 1.5)))
        assertEquals(ActorCommand.Emit("sang"), ActorCommand.of(jsonObjectOf("emit" to "sang")))
        assertEquals(ActorCommand.Behave(JsonPrimitive("wander")), ActorCommand.of(jsonObjectOf("behave" to "wander")))
        assertEquals(ActorCommand.Behave(jsonObjectOf("type" to "approach", "target" to "user")), ActorCommand.of(jsonObjectOf("behave" to jsonObjectOf("type" to "approach", "target" to "user"))))
        assertNull(ActorCommand.of(jsonObjectOf("behave" to 3)))
        assertNull(ActorCommand.of(jsonObjectOf("set" to jsonObjectOf("a" to 1))))
        assertNull(ActorCommand.of(JsonPrimitive("play")))
        assertNull(ActorCommand.of(jsonObjectOf("say" to 3)))
        assertNull(ActorCommand.of(jsonObjectOf("emit" to "")))
    }

    @Test fun roundTrip() {
        val commands = listOf(
            ActorCommand.Play("a", true, 0.5), ActorCommand.Sound("s"), ActorCommand.StopSound(null), ActorCommand.Say("x"),
            ActorCommand.MoveTo(ActorCommand.MoveTarget.Point(Point3(1.0, 0.0, 1.0)), 0.1), ActorCommand.MoveTo(ActorCommand.MoveTarget.Of(Target.ActorId("b"))),
            ActorCommand.LookAt(Target.User), ActorCommand.Stop, ActorCommand.Wait(2.0), ActorCommand.Emit("e"), ActorCommand.Behave(JsonPrimitive("wander")),
        )
        for (c in commands) assertEquals(c, ActorCommand.of(c.value))
    }

    @Test fun scriptsMixCommandsAndActions() {
        val script = ActorScript.of(jsonArrayOf(jsonObjectOf("play" to "sing"), jsonObjectOf("set" to jsonObjectOf("mood" to "singing")), "js: state.n = 1", "toast"))!!
        assertEquals(4, script.steps.size)
        assertEquals(listOf(ActorCommand.Play("sing")), script.commands)
        assertTrue((script.steps[1] as ActorStep.Action).action is JsonAction.Set)
        assertTrue((script.steps[2] as ActorStep.Action).action is JsonAction.Script)
        assertEquals("toast", ((script.steps[3] as ActorStep.Action).action as JsonAction.Host).name)
        assertEquals(1, ActorScript.of(jsonObjectOf("say" to "one"))?.steps?.size)
        assertNull(ActorScript.of(jsonOf(null)))
        assertEquals(script, ActorScript.of(script.value))
        assertEquals(listOf("tap"), ActorScript.handlers(jsonObjectOf("tap" to jsonObjectOf("play" to "x"), "bad" to 5)).keys.toList())
    }
}
