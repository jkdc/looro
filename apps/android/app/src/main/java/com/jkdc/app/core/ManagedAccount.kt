package com.jkdc.app.core

import kotlinx.serialization.Serializable

private val accountIDPattern = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")

@Serializable
data class AccountProviders(val googleAndroid: Boolean = false)

@Serializable
data class AccountChallenge(val challengeID: String, val nonce: String, val expiresInSeconds: Int) {
    init {
        require(accountIDPattern.matches(challengeID) && Regex("[a-f0-9]{64}").matches(nonce))
        require(expiresInSeconds in 1..300)
    }
    override fun toString() = "AccountChallenge(redacted)"
}

@Serializable
data class AccountExchange(val accountID: String, val accessToken: String, val expiresInSeconds: Int) {
    init {
        require(accountIDPattern.matches(accountID) && Regex("[A-Za-z0-9_-]{43}").matches(accessToken))
        require(expiresInSeconds in 1..86_400)
    }
    override fun toString() = "AccountExchange(redacted)"
}

@Serializable
data class AccountSession(val accountID: String, val accessToken: String, val expiresAtMilliseconds: Long) {
    init {
        require(accountIDPattern.matches(accountID) && Regex("[A-Za-z0-9_-]{43}").matches(accessToken))
        require(expiresAtMilliseconds > 0)
    }
    fun isValid(now: Long) = expiresAtMilliseconds > now && expiresAtMilliseconds - now <= 86_400_000L
    override fun toString() = "AccountSession(redacted)"
}

@Serializable
data class AccountProfile(val accountID: String, val email: String?, val providers: List<String>, val createdAt: String) {
    init {
        require(accountIDPattern.matches(accountID))
        require(email == null || (email.length <= 254 && email.none { it.isISOControl() }))
        require(providers.isNotEmpty() && providers.size <= 2 && providers.all { it == "google" || it == "apple" })
        require(createdAt.length <= 40)
    }
    override fun toString() = "AccountProfile(redacted)"
}

/** Server-driven subscription state attached to the minutes balance. The tier
 *  and every commercial number come from the backend; the client only paints. */
@Serializable
data class SubscriptionInfo(
    val tier: String = "free",
    val status: String = "none",
    val trialing: Boolean = false,
    val periodEnd: String? = null,
    val features: Map<String, Boolean> = emptyMap(),
    val drip: Drip? = null,
) {
    @Serializable
    data class Drip(val enabled: Boolean = false, val minutesPerMonth: Int = 0)
}

@Serializable
data class MinuteBalance(
    val unit: String,
    val billingBasis: String,
    val balanceMilliseconds: Long,
    val reservedMilliseconds: Long,
    val availableMilliseconds: Long,
    val paid: PaidConversationBalance? = null,
    val subscription: SubscriptionInfo? = null,
) {
    init {
        require(unit == "milliseconds" && billingBasis == "connected-conversation-time")
        require(balanceMilliseconds in 0..9_007_199_254_740_991L)
        require(reservedMilliseconds in 0..balanceMilliseconds)
        require(availableMilliseconds == balanceMilliseconds - reservedMilliseconds)
    }
    val canStartConversation get() = availableMilliseconds > 0 || paid?.available == true
    val readinessMilliseconds get() = if (availableMilliseconds > 0) availableMilliseconds
        else paid?.takeIf { it.available }?.estimatedMilliseconds ?: 0L

    /** Advisory, server-independent warning ladder: 0 ok, 1 low (≤5 min),
     *  2 critical (≤2 min), 3 exhausted. Enforcement stays server-side. */
    val warningLevel: Int
        get() = when {
            availableMilliseconds <= 0L -> 3
            availableMilliseconds <= 2L * 60_000L -> 2
            availableMilliseconds <= 5L * 60_000L -> 1
            else -> 0
        }
}

interface AccountService {
    suspend fun providers(): AccountProviders
    suspend fun challenge(): AccountChallenge
    suspend fun exchange(challenge: AccountChallenge, idToken: String, expectedAccountID: String? = null): AccountExchange
    suspend fun profile(session: AccountSession): AccountProfile
    suspend fun minutes(session: AccountSession): MinuteBalance
    suspend fun signOut(session: AccountSession)
    suspend fun delete(session: AccountSession)
}

interface AccountSessionStorage {
    suspend fun read(): AccountSession?
    suspend fun save(session: AccountSession)
    suspend fun clear()
}

sealed class AccountFailure : Exception() {
    data object Unavailable : AccountFailure()
    data object InvalidResponse : AccountFailure()
    data object SecureStorage : AccountFailure()
    data object Google : AccountFailure()
    class Http(val status: Int, val code: String?, val reference: String? = null) : AccountFailure()
}
