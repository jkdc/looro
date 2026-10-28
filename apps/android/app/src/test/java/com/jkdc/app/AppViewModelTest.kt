package com.jkdc.app

import com.jkdc.app.core.AccountFailure
import com.jkdc.app.network.HostedFailure
import com.jkdc.app.core.Archive
import com.jkdc.app.core.ArchiveCodec
import com.jkdc.app.core.Fragment
import com.jkdc.app.core.SessionRecord
import com.jkdc.app.core.Speaker
import com.jkdc.app.data.BackupManager
import com.jkdc.app.network.APIClient
import com.jkdc.app.network.CredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppViewModelTest {
    @Test fun progressSyncActivationIgnoresSameOwnerProfileRefreshButFencesTransitions() {
        val policy = com.jkdc.app.core.ProgressSyncActivationPolicy
        assertFalse(policy.shouldInvalidate("member:a", "member:a", accountBusy = false))
        assertTrue(policy.shouldInvalidate("member:a", "member:b", accountBusy = false))
        assertTrue(policy.shouldInvalidate("member:a", "member:a", accountBusy = true))
        assertFalse(policy.canStart("member:a", null, accountBusy = false, storageReady = true))
        assertFalse(policy.canStart("member:a", "member:b", accountBusy = false, storageReady = true))
        assertFalse(policy.canStart("member:a", "member:a", accountBusy = true, storageReady = true))
        assertTrue(policy.canStart("member:a", "member:a", accountBusy = false, storageReady = true))
    }

    @Test fun exactUnavailableUploadFallbackDoesNotAdvanceV2ResumeCursor() = kotlinx.coroutines.runBlocking {
        val entries = (0 until 201).map { index -> com.jkdc.app.core.ProgressSyncEntry(
            kind = "lesson", itemID = "item-%03d".format(index), atMilliseconds = 1_792_929_600_000,
            attempts = 1, bestScore = 0, language = "en", firstAtMilliseconds = 1_792_929_599_000)
        }
        var v2Requests = 0
        var legacyRequests = 0
        val acknowledgements = mutableListOf<Pair<String?, Long>>()

        val succeeded = uploadProgressBatches(
            entries = entries,
            initialCursor = null,
            passGeneration = 4,
            isCurrent = { true },
            sendV2 = { v2Requests++; com.jkdc.app.network.ProgressSendResult.VersionUnavailable },
            sendLegacy = { batch ->
                legacyRequests++
                com.jkdc.app.network.ProgressSendResult.Acknowledged(batch.size)
            },
            persistAcknowledgement = { cursor, generation ->
                acknowledgements += cursor to generation
                true
            },
        )

        assertTrue(succeeded)
        assertEquals(1, v2Requests)
        assertEquals(2, legacyRequests)
        assertTrue(acknowledgements.isEmpty())
    }

    @Test fun importedUnfinishedHistoryCannotLookLikeALocalInterruptedSession() {
        val partial = SessionRecord(languageID = "es", startedAt = 800_000_000.0).apply {
            append(Fragment(speaker = Speaker.user, text = "la radio", startMS = 0, endMS = 1_000))
        }
        val finished = SessionRecord(languageID = "fr", startedAt = 800_000_000.0, endedAt = 800_000_010.0,
            usageFinal = true, endReason = "Ended by you")
        val imported = prepareImportedArchive(ArchiveCodec.encode(Archive(sessions = mutableListOf(partial, finished))),
            importedAt = 800_000_020.0)
        assertEquals(800_000_020.0, imported.sessions[0].endedAt!!, 0.0)
        assertEquals(partial.fragments, imported.sessions[0].fragments)
        assertFalse(imported.sessions[0].usageFinal)
        assertEquals(finished, imported.sessions[1])
        assertEquals(imported, ArchiveCodec.decode(ArchiveCodec.encode(imported)))
    }

    @Test fun everyApiAndCredentialReasonMapsToANonZeroResource() {
        val reasons = listOf(
            APIClient.APIException.MissingKey,
            APIClient.APIException.Refused,
            APIClient.APIException.InvalidResponse,
            APIClient.APIException.Http(401),
            APIClient.APIException.Http(403),
            APIClient.APIException.Http(429),
            APIClient.APIException.Http(500),
            CredentialStore.CredentialException.Invalid,
            CredentialStore.CredentialException.Save,
            CredentialStore.CredentialException.Remove,
        )
        reasons.forEach { assertTrue("expected a resource for $it", errorMessageRes(it) != 0) }
    }

    @Test fun incompleteResponseSharesTheSameResourceAsInvalidResponse() {
        assertEquals(errorMessageRes(APIClient.APIException.InvalidResponse), errorMessageRes(APIClient.APIException.Incomplete))
    }

    @Test fun httpReasonsWithTheSameMeaningShareAResourceButOthersDiffer() {
        assertEquals(errorMessageRes(APIClient.APIException.Http(403)), errorMessageRes(APIClient.APIException.Http(404)))
        val distinctReasons = listOf(
            APIClient.APIException.MissingKey,
            APIClient.APIException.Refused,
            APIClient.APIException.InvalidResponse,
            APIClient.APIException.Http(401),
            APIClient.APIException.Http(403),
            APIClient.APIException.Http(429),
            APIClient.APIException.Http(500),
            CredentialStore.CredentialException.Invalid,
            CredentialStore.CredentialException.Save,
            CredentialStore.CredentialException.Remove,
        )
        val ids = distinctReasons.map { errorMessageRes(it) }
        assertEquals("distinct reasons must map to distinct resources", ids.size, ids.toSet().size)
    }

    @Test fun unmappedThrowableHasNoResource() {
        assertEquals(0, errorMessageRes(IllegalStateException("unexpected")))
    }

    @Test fun everyBackupRejectionReasonMapsToADistinctNonZeroResource() {
        val ids = BackupManager.BackupIssue.entries.map { backupNoticeRes(it) }
        ids.forEach { assertTrue("expected a resource for every backup issue", it != 0) }
        assertEquals("distinct rejection reasons must map to distinct resources",
            ids.size, ids.toSet().size)
    }

    @Test fun onlyMissingKeyAndUnauthorizedNeedKeySetup() {
        assertTrue(errorNeedsKeySetup(APIClient.APIException.MissingKey))
        assertTrue(errorNeedsKeySetup(APIClient.APIException.Http(401)))
        assertFalse(errorNeedsKeySetup(APIClient.APIException.Http(403)))
        assertFalse(errorNeedsKeySetup(APIClient.APIException.Refused))
        assertFalse(errorNeedsKeySetup(CredentialStore.CredentialException.Invalid))
    }
    @Test fun hostedSignInFailuresRecoverThroughAccountAndNeverPersonalKeySetup() {
        for (error in listOf(HostedFailure.SignInRequired, HostedFailure.Http(401, "sign_in_required"),
            HostedFailure.Http(403, "sign_in_to_continue"), AccountFailure.Http(401, "sign_in_required"))) {
            assertEquals(R.string.hosted_sign_in_again, errorMessageRes(error))
            assertTrue(needsAccountRecovery(error))
            assertFalse(errorNeedsKeySetup(error))
        }
        assertFalse(needsAccountRecovery(HostedFailure.Http(503, "internal")))
        assertEquals(R.string.hosted_rate_limit, errorMessageRes(HostedFailure.Http(429, "rate_limit")))
        assertEquals(R.string.hosted_start_rejected, errorMessageRes(HostedFailure.Http(502, "provider_create_rejected")))
    }

    @Test fun onlySafeOpaqueReferencesReachTheErrorMessage() {
        assertEquals("0123abcdef45", requestErrorReference(HostedFailure.Http(500, "internal", reference = "0123abcdef45")))
        assertEquals("0123abcdef45", requestErrorReference(AccountFailure.Http(500, "internal", "0123abcdef45")))
        for (value in listOf("person@example.com", "0123ABCDEF45", "0123abcdef45\n", "", "123")) {
            assertEquals(null, requestErrorReference(HostedFailure.Http(500, "internal", reference = value)))
            assertEquals(null, requestErrorReference(AccountFailure.Http(500, "internal", value)))
        }
    }

}
