package com.jkdc.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jkdc.app.core.AccountController
import com.jkdc.app.core.AccountState
import com.jkdc.app.core.AccountMutationCoordinator
import com.jkdc.app.network.AccountSessionStore
import com.jkdc.app.network.ManagedAccountClient
import com.jkdc.app.network.ManagedAccountConfiguration
import com.jkdc.app.data.ExamHistoryDao
import com.jkdc.app.data.LearningDb
import com.jkdc.app.data.LearnerOwnershipDao
import com.jkdc.app.data.ProgressRecoveryDao
import com.jkdc.app.network.ExamAttemptOutboxSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    val configuration = ManagedAccountConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN, BuildConfig.GOOGLE_SERVER_CLIENT_ID)
    private val controller = configuration?.let {
        AccountController(ManagedAccountClient(it), AccountSessionStore(application, it.origin.toString()))
    }
    private val sessionStore = configuration?.let { AccountSessionStore(application, it.origin.toString()) }
    private val accountClient = configuration?.let { ManagedAccountClient(it) }
    private val syncMutex = Mutex()
    private val identityGenerationLock = Any()
    private var observedSession: com.jkdc.app.core.AccountSession? = null
    private var identityGeneration = 0L
    @Volatile private var activeSyncSession: com.jkdc.app.core.AccountSession? = null
    @Volatile private var activeSyncGeneration: Long? = null
    private val examOutboxSync = ExamAttemptOutboxSync(
        sendCreation = { session, snapshot, attempt -> accountClient!!.syncExamAttempt(session, snapshot, attempt) },
        sendEvent = { session, event -> accountClient!!.syncExamEvent(session, event) },
        acknowledge = { owner, attempt, event, revision, acceptedRevision, at ->
            val session = checkNotNull(activeSyncSession) { "No active account-sync session" }
            val generation = checkNotNull(activeSyncGeneration) { "No active account-sync generation" }
            val db = openLearningDb(owner, session, generation)
            try { ExamHistoryDao.acknowledgeOutbox(db, owner, attempt, event, revision, at, acceptedRevision) }
            finally { db.close() }
        },
        conflict = { owner, attempt, server, at ->
            val session = checkNotNull(activeSyncSession) { "No active account-sync session" }
            val generation = checkNotNull(activeSyncGeneration) { "No active account-sync generation" }
            val db = openLearningDb(owner, session, generation)
            try { ExamHistoryDao.persistConflict(db, owner, attempt, server, at) }
            finally { db.close() }
        },
    )
    val state = controller?.state ?: MutableStateFlow(AccountState())
    private val mutations = AccountMutationCoordinator(viewModelScope)
    val transitionBusy = mutations.busy
    init {
        viewModelScope.launch {
            controller?.restore()
            syncOwnedExamAttempts()
        }
        viewModelScope.launch {
            state.collect { current ->
                observeSession(sessionStore?.read())
                if (current.signedIn) syncOwnedExamAttempts()
            }
        }
    }

    private suspend fun syncOwnedExamAttempts() {
        val sessions = sessionStore ?: return
        accountClient ?: return
        syncMutex.withLock {
            val session = sessions.read() ?: return
            if (!session.isValid(System.currentTimeMillis()) || state.value.accountID != session.accountID) return
            val generation = observeSession(session)
            activeSyncSession = session
            activeSyncGeneration = generation
            try {
                withContext(Dispatchers.IO) {
                    val db = openLearningDb(session.accountID, session, generation)
                    try {
                        examOutboxSync.drain(db, session)
                        restoreOwnedExamAttempts(db, session)
                    } finally { db.close() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (httpError: com.jkdc.app.core.AccountFailure.Http) {
                android.util.Log.e("ExamRecovery", "drain failed: HTTP ${httpError.status} ${httpError.code}")
            } catch (drainError: Exception) {
                android.util.Log.e("ExamRecovery", "drain failed", drainError)
            } finally {
                activeSyncSession = null
                activeSyncGeneration = null
            }
        }
    }

    private fun observeSession(session: com.jkdc.app.core.AccountSession?): Long = synchronized(identityGenerationLock) {
        if (session != observedSession) {
            observedSession = session
            identityGeneration++
        }
        identityGeneration
    }

    private fun currentObservedSession(): com.jkdc.app.core.AccountSession? = synchronized(identityGenerationLock) {
        observedSession
    }

    private fun openLearningDb(
        ownerAccountID: String,
        expectedSession: com.jkdc.app.core.AccountSession,
        expectedGeneration: Long,
    ): com.jkdc.app.data.SqlDatabase {
        check(expectedSession.accountID == ownerAccountID) { "Account callback does not match its captured session" }
        val currentBeforeOpen = currentObservedSession()
        check(currentBeforeOpen == expectedSession && state.value.accountID == ownerAccountID &&
            observeSession(currentBeforeOpen) == expectedGeneration) {
            "Stale account callback cannot open learner storage"
        }
        return LearningDb.open(getApplication()).also { db ->
            try {
                val activated = com.jkdc.app.core.LocalLearnerIdentity.activateDatabaseForAccountCallback(
                    db = db,
                    requestedSession = expectedSession,
                    requestedGeneration = expectedGeneration,
                    currentSession = { currentObservedSession() },
                    currentAccountID = { state.value.accountID },
                    currentGeneration = { observeSession(currentObservedSession()) },
                ) { target, identity ->
                    LearnerOwnershipDao.activate(target, identity)
                    ExamHistoryDao.assignOwnerlessToIdentityOnce(target, identity)
                    ProgressRecoveryDao.recover(target, System.currentTimeMillis())
                }
                check(activated) { "Stale account callback cannot activate learner storage" }
            }
            catch (failure: Exception) { db.close(); throw failure }
        }
    }

    /** Cross-device/reinstall restore: insert-only import of the account's
        server history, bounded at 10 pages of 10 attempts. Existing local or
        anonymous identities are never overwritten; imported attempts carry no
        upload events because the server already confirmed them. */
    private suspend fun restoreOwnedExamAttempts(db: com.jkdc.app.data.SqlDatabase, session: com.jkdc.app.core.AccountSession) {
        val client = accountClient ?: return
        var cursor: String? = null
        var pages = 0
        do {
            val page = client.examAttemptsPage(session, cursor)
            val listed = page.getValue("attempts").jsonArray
            for (entry in listed) {
                val attemptID = entry.jsonObject.getValue("attemptId").jsonPrimitive.content
                val detail = client.examAttemptDetail(session, attemptID)
                val attempt = detail.getValue("attempt").jsonObject.toString()
                val snapshot = detail.getValue("snapshot").jsonObject.toString()
                com.jkdc.app.data.ExamHistoryDao.restorePage(db, session.accountID, snapshot, attempt)
            }
            cursor = (page["nextCursor"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            pages++
        } while (cursor != null && pages < 10)
    }

    /** Test harness only (DEBUG builds); see [AccountController.debugSignIn]. */
    fun debugSignIn(accountID: String, accessToken: String) {
        viewModelScope.launch {
            // The startup restore() holds the one-shot mutation lock (tryLock
            // semantics): wait for it instead of being silently dropped.
            kotlinx.coroutines.delay(1_500)
            var waited = 1_500
            while (controller?.state?.value?.busy == true && waited < 15_000) {
                kotlinx.coroutines.delay(250); waited += 250
            }
            controller?.debugSignIn(accountID, accessToken)
            syncOwnedExamAttempts()
        }
    }
    fun refresh() {
        if (transitionBusy.value) return
        viewModelScope.launch {
            // A recreated Activity must not take the controller lock while settlement precedes sign-out.
            if (!transitionBusy.value) controller?.refresh()
        }
    }
    suspend fun refreshAndWait() { controller?.refresh() }
    suspend fun signIn(expectedAccountID: String? = null, getToken: suspend (String) -> String) {
        controller?.signIn(expectedAccountID, getToken)
    }
    fun beginSignInTransition(): Long? = if (state.value.busy) null else mutations.begin()
    fun endSignInTransition(ticket: Long) = mutations.end(ticket)
    /** Pass retained conversation-owner callbacks, never an Activity or its lifecycle scope. */
    fun changeAccount(delete: Boolean, prepare: suspend () -> Boolean, finished: () -> Unit, beforeDelete: suspend (String) -> Unit = {}): Boolean {
        if (state.value.busy) return false
        return mutations.submit(prepare, {
            if (delete) controller?.delete(beforeDelete) else controller?.signOut()
            clearGoogleIfSignedOut()
        }, finished)
    }
    fun dismissNotice() { controller?.dismissNotice() }
    private suspend fun clearGoogleIfSignedOut() {
        if (state.value.signedIn) return
        try { CredentialManager.create(getApplication<Application>()).clearCredentialState(ClearCredentialStateRequest()) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* The Palique session has already been removed. */ }
    }
}
