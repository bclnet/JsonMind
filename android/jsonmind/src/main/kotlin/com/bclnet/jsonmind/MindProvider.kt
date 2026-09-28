/*
 * MindProvider.kt
 * JsonMind
 *
 * Where the tokens come from. JsonMind never holds API keys or picks
 * models: a provider streams the reply text for a prompt and reports what
 * it used. TokenX (the token streaming SDK) implements this interface; the
 * library ships the canned rule provider and an adapter for simple
 * request/response providers.
 */
package com.bclnet.jsonmind

import com.bclnet.jsonui.toJsonString
import kotlinx.serialization.json.JsonArray

sealed class MindError(message: String) : Exception(message) {
    object BudgetExhausted : MindError("token budget exhausted")
    data class TurnTooLarge(val estimated: Int, val limit: Int) : MindError("turn of $estimated tokens exceeds $limit")
    data class CoolingDown(val remaining: Double) : MindError("cooling down for $remaining s")
    object NoProvider : MindError("no mind provider")
    data class Provider(val reason: String) : MindError(reason)
}

/** A source of replies: streams text for a prompt, then reports the tokens used. */
fun interface MindProvider {
    /**
     * `onText` receives deltas of the reply as they arrive (may be called once with the whole text);
     * `completion` is called exactly once when the reply is complete or failed.
     */
    fun respond(prompt: MindPrompt, onText: (String) -> Unit, completion: (Result<MindUsage>) -> Unit)
}

/** Wraps a request/response function (no streaming) as a provider. */
class CompletionMindProvider(private val complete: (MindPrompt, (Result<MindReply>) -> Unit) -> Unit) : MindProvider {
    override fun respond(prompt: MindPrompt, onText: (String) -> Unit, completion: (Result<MindUsage>) -> Unit) {
        complete(prompt) { result ->
            result.fold(
                onSuccess = { reply ->
                    onText(if (reply.text.isEmpty()) JsonArray(reply.commands.map { it.value }).toJsonString() else reply.text)
                    completion(Result.success(MindUsage(null, reply.tokensUsed)))
                },
                onFailure = { completion(Result.failure(it)) },
            )
        }
    }
}

/** Rule based replies from the `canned` list; costs no tokens. */
class CannedMindProvider(val rules: List<Mind.Rule>) : MindProvider {
    fun reply(prompt: MindPrompt): MindReply? {
        val text = listOf(prompt.event, prompt.heard ?: "").joinToString(" ")
        val rule = rules.firstOrNull { it.matches(text) } ?: return null
        return MindReply(rule.script.commands, 0, rule.script.value.toJsonString())
    }

    override fun respond(prompt: MindPrompt, onText: (String) -> Unit, completion: (Result<MindUsage>) -> Unit) {
        reply(prompt)?.let { onText(it.text) }
        completion(Result.success(MindUsage(0, 0)))
    }
}
