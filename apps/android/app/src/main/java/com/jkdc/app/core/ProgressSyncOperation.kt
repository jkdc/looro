package com.jkdc.app.core

import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

/** Pure account-transition rules for owner-scoped progress synchronization. */
internal object ProgressSyncActivationPolicy {
    fun shouldInvalidate(previousOwner: String?, currentOwner: String?, accountBusy: Boolean): Boolean =
        accountBusy || previousOwner != currentOwner

    fun canStart(activeOwner: String?, loadedOwner: String?, accountBusy: Boolean, storageReady: Boolean): Boolean =
        storageReady && !accountBusy && activeOwner != null && activeOwner == loadedOwner
}

/** Retries a load when ownership changes and only delegates side effects for the still-current identity. */
internal class OwnerLearningLoadCoordinator<T>(
    private val currentIdentity: () -> String?,
    private val load: suspend (String) -> T,
    private val publish: (String, T) -> Boolean,
    private val reportFailure: (String, Exception) -> Boolean,
) {
    suspend fun loadAndPublish(): Boolean {
        while (true) {
            val identity = currentIdentity() ?: return false
            val value = try {
                load(identity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (currentIdentity() != identity) continue
                if (reportFailure(identity, failure)) return false
                if (currentIdentity() != identity) continue
                throw failure
            }
            if (currentIdentity() != identity) continue
            if (publish(identity, value)) return true
            if (currentIdentity() != identity) continue
            return false
        }
    }
}

/** A short-lived identity for one remote progress pass; the bearer itself is never retained here. */
internal data class ProgressSyncIdentity(
    val accountID: String,
    val credentialFingerprint: String,
    val dataGeneration: Long,
) {
    companion object {
        fun create(accountID: String, accessToken: String, dataGeneration: Long): ProgressSyncIdentity {
            val digest = MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.UTF_8))
            return ProgressSyncIdentity(accountID, digest.joinToString("") { "%02x".format(it) }, dataGeneration)
        }
    }
}

/** Serializes progress passes and grants writes only while their captured identity is still live. */
internal class ProgressSyncOperation {
    internal class Token internal constructor(val identity: ProgressSyncIdentity, val serial: Long)
    internal class CommitLease internal constructor(
        val token: Token,
        val identity: ProgressSyncIdentity,
        internal val completion: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private var generation = 0L
    private var serial = 0L
    private var active: Token? = null
    private var activeCommit: CommitLease? = null
    private var invalidationPending = false

    @Synchronized
    fun identity(accountID: String, accessToken: String) =
        ProgressSyncIdentity.create(accountID, accessToken, generation)

    @Synchronized
    fun begin(identity: ProgressSyncIdentity): Token? {
        if (active != null) return null
        return Token(identity, ++serial).also { active = it }
    }

    @Synchronized
    fun isCurrent(token: Token, liveIdentity: ProgressSyncIdentity): Boolean =
        active == token && token.identity == liveIdentity

    /** The guard and the storage mutation share one monitor with invalidation. */
    @Synchronized
    fun <T> writeIfCurrent(token: Token, liveIdentity: ProgressSyncIdentity, write: () -> T): T? =
        if (isCurrent(token, liveIdentity)) write() else null

    @Synchronized
    fun beginCommit(token: Token, liveIdentity: ProgressSyncIdentity): CommitLease? {
        if (activeCommit != null || !isCurrent(token, liveIdentity)) return null
        return CommitLease(token, liveIdentity).also { activeCommit = it }
    }

    /** Storage writes during a reserved commit remain valid until memory publishes and the lease closes. */
    @Synchronized
    fun writeCommit(lease: CommitLease, write: () -> Unit): Boolean {
        if (activeCommit !== lease || active !== lease.token) return false
        write()
        return true
    }

    @Synchronized
    fun publishCommit(lease: CommitLease, publish: () -> Unit): Boolean {
        if (activeCommit !== lease || active !== lease.token) return false
        publish()
        return true
    }

    @Synchronized
    fun finishCommit(lease: CommitLease) {
        if (activeCommit !== lease) return
        activeCommit = null
        if (invalidationPending) {
            generation++
            active = null
            invalidationPending = false
        }
        lease.completion.complete(Unit)
    }

    suspend fun awaitCommit() {
        val completion = synchronized(this) { activeCommit?.completion }
        completion?.await()
    }

    @Synchronized
    fun invalidate() {
        if (activeCommit != null) {
            invalidationPending = true
        } else {
            generation++
            active = null
        }
    }

    @Synchronized
    fun finish(token: Token) {
        if (active == token) active = null
    }
}

/** Admission gate shared by sync startup and whole-archive replacements. */
internal class ProgressSyncAdmission {
    private var replacementCount = 0
    private var generation = 0L
    private var serial = 0L
    internal class Lease internal constructor(internal val generation: Long, internal val serial: Long)

    @Synchronized fun acquire(): Lease? = if (replacementCount > 0) null else Lease(generation, ++serial)
    @Synchronized fun isCurrent(lease: Lease): Boolean = replacementCount == 0 && lease.generation == generation
    @Synchronized fun beginReplacement() { replacementCount++; generation++ }
    @Synchronized fun endReplacement() { check(replacementCount > 0); replacementCount-- }
}

/** Shared sync lifecycle called by AppViewModel and driven through real injectable boundaries in tests. */
internal class ProgressSyncRunner(
    private val operation: ProgressSyncOperation,
    private val admission: ProgressSyncAdmission,
) {
    suspend fun run(
        resolveSession: suspend () -> AccountSession?,
        fetch: suspend (AccountSession, ProgressSyncOperation.Token, ProgressSyncIdentity) -> String?,
        readLiveSession: suspend () -> AccountSession?,
        mergeRemote: suspend (String, ProgressSyncOperation.Token, ProgressSyncIdentity) -> Boolean,
        outboundSnapshot: () -> String,
        send: suspend (AccountSession, String, ProgressSyncOperation.Token, ProgressSyncIdentity) -> Unit,
    ) {
        val admissionLease = admission.acquire() ?: return
        var token: ProgressSyncOperation.Token? = null
        try {
            if (!admission.isCurrent(admissionLease)) return
            val captured = resolveSession() ?: return
            if (!admission.isCurrent(admissionLease)) return
            val identity = operation.identity(captured.accountID, captured.accessToken)
            val acquired = operation.begin(identity) ?: return
            token = acquired

            val remote = fetch(captured, acquired, identity) ?: return
            if (!admission.isCurrent(admissionLease)) return
            val afterFetch = readLiveSession() ?: return
            if (!admission.isCurrent(admissionLease)) return
            val liveIdentity = operation.identity(afterFetch.accountID, afterFetch.accessToken)
            if (!operation.isCurrent(acquired, liveIdentity)) return
            if (!mergeRemote(remote, acquired, liveIdentity)) return
            if (!admission.isCurrent(admissionLease)) return
            if (!operation.isCurrent(acquired, liveIdentity)) return

            val outbound = outboundSnapshot()
            val beforeSend = readLiveSession() ?: return
            if (!admission.isCurrent(admissionLease)) return
            val sendIdentity = operation.identity(beforeSend.accountID, beforeSend.accessToken)
            if (!operation.isCurrent(acquired, sendIdentity)) return
            send(captured, outbound, acquired, sendIdentity)
        } finally {
            token?.let(operation::finish)
        }
    }
}
