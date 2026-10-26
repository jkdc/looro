package com.jkdc.app.core

import com.jkdc.app.data.LearnerOwnershipDao
import com.jkdc.app.data.SqlDatabase

/** Resolves the local learner partition without treating a missing member as an ownerless user. */
internal object LocalLearnerIdentity {
    fun resolve(memberAccountID: String?, stableGuestID: String): String {
        require(stableGuestID.startsWith("guest:") && stableGuestID.length > "guest:".length) {
            "A stable per-install guest identity is required"
        }
        return memberAccountID?.takeIf(String::isNotBlank)?.let { "member:$it" } ?: stableGuestID
    }

    /** Activates the account partition only while the callback's exact session generation is live. */
    fun activateDatabaseForAccountCallback(
        db: SqlDatabase,
        requestedSession: AccountSession,
        requestedGeneration: Long,
        currentSession: () -> AccountSession?,
        currentAccountID: () -> String?,
        currentGeneration: () -> Long,
        initialize: (SqlDatabase, String) -> Unit,
    ): Boolean {
        val identity = "member:${requestedSession.accountID}"
        return LearnerOwnershipDao.runForIdentity(
            db,
            identity,
            isCurrent = {
                currentSession() == requestedSession &&
                    currentAccountID() == requestedSession.accountID &&
                    currentGeneration() == requestedGeneration
            },
        ) { initialize(it, identity) }
    }
}


/** Progress upload is a member-only capability; guest credentials remain local-only. */
internal object MemberProgressSyncPolicy {
    fun session(memberSession: AccountSession?, guestSession: AccountSession?): AccountSession? = memberSession
}
