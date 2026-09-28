/*
 * TokenXMindProvider.kt
 * JsonMind (TokenX adapter)
 *
 * The MindProvider that gets its tokens from TokenX. Each mind gets a
 * TokenX session named after it, on the CHARACTER profile by default, with
 * the mind's own budget as the session budget. JsonMind keeps its ledger and
 * cooldown; TokenX keeps the keys, the model and the daily cap.
 */
package com.bclnet.jsonmind.tokenx

import com.bclnet.jsonmind.MindPrompt
import com.bclnet.jsonmind.MindProvider
import com.bclnet.jsonmind.MindUsage
import com.bclnet.tokenx.ChatMessage
import com.bclnet.tokenx.ChatRequest
import com.bclnet.tokenx.Profile
import com.bclnet.tokenx.TokenBroker
import com.bclnet.tokenx.TokenClient
import com.bclnet.tokenx.TokenSession

class TokenXMindProvider(
    val client: TokenClient,
    val profile: Profile = Profile.CHARACTER,
    /** Total tokens the TokenX session of each mind may spend; `null` leaves it to JsonMind's budget and the daily cap. */
    val sessionBudget: Int? = null,
) : MindProvider {
    constructor(broker: TokenBroker, profile: Profile = Profile.CHARACTER) : this(TokenClient(broker), profile)

    private val sessions = HashMap<String, TokenSession>()

    /** Whether TokenX can serve requests right now (a provider is configured). */
    val isReady: Boolean get() = client.isReady

    /** The TokenX session for an actor, created on first use. */
    @Synchronized fun session(actorId: String): TokenSession = sessions.getOrPut(actorId) { client.session(actorId, profile, sessionBudget) }

    override fun respond(prompt: MindPrompt, onText: (String) -> Unit, completion: (Result<MindUsage>) -> Unit) {
        session(prompt.actorId).stream(request(prompt), onText) { result ->
            completion(result.map { MindUsage(it.usage.promptTokens, it.usage.replyTokens) })
        }
    }

    companion object {
        /** The prompt's system text and message history as a TokenX request; `maxTokens` follows the mind's per-turn cap. */
        fun request(prompt: MindPrompt): ChatRequest = ChatRequest(
            system = prompt.systemText,
            messages = prompt.messages.map { (role, text) -> if (role == "assistant") ChatMessage.assistant(text) else ChatMessage.user(text) },
            maxTokens = minOf(prompt.maxTokens, 1024),
        )
    }
}
