package com.jkdc.app.core.engines

import com.jkdc.app.network.HelperPurpose

/** Display-only policy snapshot from GET /v1/conversation-policy: the model
    label per purpose and the account's daily token quota. It never
    authorizes anything — the backend proxy decides per call. */
data class ConversationPolicyState(
    val models: Map<String, String>,
    val limitTokens: Long,
    val usedTokensToday: Long,
) {
    val quotaRemaining: Long get() = (limitTokens - usedTokensToday).coerceAtLeast(0)
}

/** Holds the latest conversation policy so the `api` engine can resolve the
    server-chosen model per call purpose. The proxy is the real decision
    point: a stale or empty map must fail the call clearly, never send a
    client-guessed model. */
class ConversationPolicyStore {
    @Volatile private var policy: ConversationPolicyState? = null

    fun update(state: ConversationPolicyState) { policy = state }

    fun current(): ConversationPolicyState? = policy

    /** The server-chosen model for a purpose; null when the policy is
        unavailable (callers must refuse to guess). */
    fun modelFor(purpose: HelperPurpose?): String? =
        policy?.models?.get(purposeKey(purpose))?.takeIf { it.isNotBlank() }

    companion object {
        /** The proxy's legacy route key; the app's default purpose. */
        fun purposeKey(purpose: HelperPurpose?): String = purpose?.wireValue ?: "typed_reply"
    }
}
