/*
 * MindSession.kt
 * JsonMind
 *
 * Runs one mind: the token ledger (what this mind may still spend), the
 * cooldown, the history sent with each prompt, streaming replies into
 * commands, and the fallback to canned rules when there is no provider,
 * no budget or an error.
 */
package com.bclnet.jsonmind

import kotlinx.serialization.json.JsonElement

/**
 * Tracks the tokens a mind has spent against its budget. The supply side (keys, models, quotas)
 * is the provider's business; this is the local allowance the document asked for.
 */
class TokenLedger(val budget: Mind.Budget) {
    var spent: Int = 0
        private set
    var turns: Int = 0
        private set
    var lastTurnAt: Double? = null
        private set

    val remaining: Int? get() = budget.tokens?.let { maxOf(0, it - spent) }
    val isExhausted: Boolean get() = remaining?.let { it <= 0 } ?: false

    /** Checks whether a turn estimated at `tokens` may start at `now` (seconds). */
    fun check(estimated: Int, now: Double): MindError? {
        if (isExhausted) return MindError.BudgetExhausted
        lastTurnAt?.let { last -> if (now - last < budget.cooldown) return MindError.CoolingDown(budget.cooldown - (now - last)) }
        if (estimated > budget.perTurn) return MindError.TurnTooLarge(estimated, budget.perTurn)
        remaining?.let { if (estimated > it) return MindError.BudgetExhausted }
        return null
    }

    fun charge(tokens: Int, now: Double) {
        spent += maxOf(0, tokens)
        turns += 1
        lastTurnAt = now
    }
}

class MindSession(val actorId: String, val mind: Mind, var provider: MindProvider? = null) {
    val ledger = TokenLedger(mind.budget)
    private val _history = mutableListOf<MindTurn>()
    val history: List<MindTurn> get() = _history.toList()
    /** How many past turns are sent with each prompt. */
    var historyLimit = 6
    /** Called with each command as soon as the streaming reply completes it (before `completion`). */
    var onCommand: ((ActorCommand) -> Unit)? = null
    private val canned = CannedMindProvider(mind.canned)

    fun wakes(event: String): Boolean = mind.triggers.contains(event)

    fun prompt(event: String, heard: String? = null, actorName: String? = null, senses: Map<String, JsonElement>): MindPrompt {
        val allowed = senses.filterKeys { mind.senses.contains(it) || it in MindPrompt.SYSTEM_SENSES }
        return MindPrompt(actorId, actorName, mind.persona, event, heard, allowed, mind.tools, _history.takeLast(historyLimit), mind.budget.perTurn)
    }

    /**
     * Asks the provider, or the canned rules when there is no provider or no budget. The commands returned
     * are filtered by the mind's `tools`; streamed commands also reach `onCommand` as they complete.
     */
    fun respond(prompt: MindPrompt, now: Double = System.currentTimeMillis() / 1000.0, completion: (Result<List<ActorCommand>>) -> Unit) {
        val provider = provider
        if (provider == null) {
            completion(Result.success(filter(canned.reply(prompt)?.commands ?: emptyList())))
            return
        }
        ledger.check(prompt.estimatedTokens, now)?.let { error ->
            if (error is MindError.CoolingDown) { completion(Result.failure(error)); return }
            // No budget left: fall back to the canned rules so the actor still reacts.
            val reply = canned.reply(prompt)
            if (reply != null) completion(Result.success(filter(reply.commands))) else completion(Result.failure(error))
            return
        }
        val assembler = MindReplyAssembler()
        var streamed = 0
        provider.respond(prompt, onText = { delta ->
            for (command in assembler.append(delta)) if (mind.allows(command)) { streamed++; onCommand?.invoke(command) }
        }, completion = { result ->
            result.fold(
                onSuccess = { usage ->
                    val reply = assembler.finish(usage.total)
                    val tokens = usage.total ?: (prompt.estimatedTokens + MindTurn.estimateTokens(reply.text))
                    ledger.charge(tokens, now)
                    _history += MindTurn(prompt.event, prompt.heard, reply.text, tokens)
                    val commands = filter(reply.commands)
                    // Commands that only appeared at the end (plain text replies) are delivered here too.
                    if (streamed == 0) commands.forEach { onCommand?.invoke(it) }
                    completion(Result.success(commands))
                },
                onFailure = { error ->
                    val reply = canned.reply(prompt)
                    if (reply != null) completion(Result.success(filter(reply.commands)))
                    else completion(Result.failure(MindError.Provider(error.message ?: error.toString())))
                },
            )
        })
    }

    internal fun filter(commands: List<ActorCommand>): List<ActorCommand> = commands.filter { mind.allows(it) }
}
