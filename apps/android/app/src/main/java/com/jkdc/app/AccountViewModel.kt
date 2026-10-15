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
import com.jkdc.app.network.ExamAttemptOutboxSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    val configuration = ManagedAccountConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN, BuildConfig.GOOGLE_SERVER_CLIENT_ID)
    private val controller = configuration?.let {
        AccountController(ManagedAccountClient(it), AccountSessionStore(application, it.origin.toString()))
    }
    private val sessionStore = configuration?.let { AccountSessionStore(application, it.origin.toString()) }
    private val accountClient = configuration?.let { ManagedAccountClient(it) }
    private val examOutboxSync = ExamAttemptOutboxSync(
        sendCreation = { session, snapshot, attempt -> accountClient!!.syncExamAttempt(session, snapshot, attempt) },
        sendEvent = { session, event -> accountClient!!.syncExamEvent(session, event) },
        acknowledge = { owner, attempt, event, revision, acceptedRevision, at ->
            val db = LearningDb.open(application)
            try { ExamHistoryDao.acknowledgeOutbox(db, owner, attempt, event, revision, at, acceptedRevision) }
            finally { db.close() }
        },
        conflict = { owner, attempt, server, at ->
            val db = LearningDb.open(application)
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
            state.collect { current -> if (current.signedIn) syncOwnedExamAttempts() }
        }
    }

    private suspend fun syncOwnedExamAttempts() {
        val sessions = sessionStore ?: return
        accountClient ?: return
        val session = sessions.read() ?: return
        if (!session.isValid(System.currentTimeMillis())) return
        try {
            withContext(Dispatchers.IO) {
                val db = LearningDb.open(getApplication())
                try {
                    // The captured session is bound to this owner's pending rows; switching accounts
                    // can never cause an older owner's answers to be sent under a newer bearer.
                    examOutboxSync.drain(db, session)
                } finally { db.close() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the outbox pending; a later session restoration retries without losing edits.
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
