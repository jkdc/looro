package com.jkdc.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

class ProgressSyncOperationTest {
    private fun identity(operation: ProgressSyncOperation, account: String = "A", credential: String = "bearer-A") =
        operation.identity(account, credential)

    @Test fun progressSyncStatusIsOwnerScopedAndDropsStaleCompletion() {
        val pending = ProgressSyncStatus("account-A", ProgressSyncStatus.Phase.PENDING)

        assertEquals(pending, ProgressSyncStatus.forOwner(pending, "account-A"))
        assertNull(ProgressSyncStatus.forOwner(pending, "account-B"))
        assertNull(ProgressSyncStatus.finished("account-A", "account-B", succeeded = false))
        assertEquals(ProgressSyncStatus("account-A", ProgressSyncStatus.Phase.FAILED),
            ProgressSyncStatus.finished("account-A", "account-A", succeeded = false))
        assertEquals(ProgressSyncStatus("account-A", ProgressSyncStatus.Phase.SYNCED),
            ProgressSyncStatus.finished("account-A", "account-A", succeeded = true))
    }

    @Test fun ownerLoadCoordinatorRetriesStaleSuccessAndPublishesOnlyCurrentOwner() = runTest {
        var activeOwner = "A"
        val startedA = CompletableDeferred<Unit>()
        val resultA = CompletableDeferred<String>()
        val published = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<String>()
        val coordinator = OwnerLearningLoadCoordinator<String>(
            currentIdentity = { activeOwner },
            load = { owner -> if (owner == "A") { startedA.complete(Unit); resultA.await() } else "state-$owner" },
            publish = { owner, value -> if (activeOwner != owner) false else { published += owner to value; true } },
            reportFailure = { owner, _ -> if (activeOwner != owner) false else { errors += owner; true } },
        )

        val task = async { coordinator.loadAndPublish() }
        startedA.await()
        activeOwner = "B"
        resultA.complete("state-A")

        assertTrue(task.await())
        assertEquals(listOf("B" to "state-B"), published)
        assertTrue(errors.isEmpty())
    }

    @Test fun ownerLoadCoordinatorRetriesStaleFailureWithoutReportingItToNewOwner() = runTest {
        var activeOwner = "A"
        val startedA = CompletableDeferred<Unit>()
        val failA = CompletableDeferred<String>()
        val published = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<String>()
        val coordinator = OwnerLearningLoadCoordinator<String>(
            currentIdentity = { activeOwner },
            load = { owner -> if (owner == "A") { startedA.complete(Unit); failA.await() } else "state-$owner" },
            publish = { owner, value -> if (activeOwner != owner) false else { published += owner to value; true } },
            reportFailure = { owner, _ -> if (activeOwner != owner) false else { errors += owner; true } },
        )

        val task = async { coordinator.loadAndPublish() }
        startedA.await()
        activeOwner = "B"
        failA.completeExceptionally(IllegalStateException("A failed"))

        assertTrue(task.await())
        assertEquals(listOf("B" to "state-B"), published)
        assertTrue(errors.isEmpty())
    }

    @Test fun ownerLoadCoordinatorReportsCurrentOwnerFailureAndStops() = runTest {
        val errors = mutableListOf<String>()
        var loads = 0
        val coordinator = OwnerLearningLoadCoordinator<String>(
            currentIdentity = { "A" },
            load = { loads++; throw IllegalStateException("A failed") },
            publish = { _, _ -> fail("failed load must not publish"); false },
            reportFailure = { owner, failure -> errors += "$owner:${failure.message}"; true },
        )

        assertFalse(coordinator.loadAndPublish())
        assertEquals(1, loads)
        assertEquals(listOf("A:A failed"), errors)
    }

    @Test fun ownerLoadCoordinatorCanRetryAfterCurrentFailureWhenUserRequestsAgain() = runTest {
        var loads = 0
        var published: String? = null
        val coordinator = OwnerLearningLoadCoordinator<String>(
            currentIdentity = { "A" },
            load = { if (++loads == 1) throw IllegalStateException("temporary failure") else "state-A" },
            publish = { _, value -> published = value; true },
            reportFailure = { _, _ -> true },
        )

        assertFalse(coordinator.loadAndPublish())
        assertNull(published)
        assertTrue(coordinator.loadAndPublish())
        assertEquals(2, loads)
        assertEquals("state-A", published)
    }

    @Test fun ownerLoadCoordinatorPropagatesCancellation() = runTest {
        val coordinator = OwnerLearningLoadCoordinator<String>(
            currentIdentity = { "A" },
            load = { throw kotlinx.coroutines.CancellationException("cancelled") },
            publish = { _, _ -> fail("cancelled load must not publish"); false },
            reportFailure = { _, _ -> fail("cancellation must not be reported as a load failure"); false },
        )

        var propagated = false
        try {
            coordinator.loadAndPublish()
        } catch (_: kotlinx.coroutines.CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test fun staleFetchAfterImportDoesNotWrite() {
        val operation = ProgressSyncOperation()
        val oldIdentity = identity(operation)
        val token = operation.begin(oldIdentity)!!
        val memory = mutableListOf("replacement-memory")
        val persisted = mutableListOf("replacement-row")
        operation.invalidate()
        val live = identity(operation)
        val fetched = listOf("stale-remote-row")

        val applied = operation.writeIfCurrent(token, live) {
            memory += fetched
            persisted += fetched
        }

        assertNull(applied)
        assertEquals(listOf("replacement-memory"), memory)
        assertEquals(listOf("replacement-row"), persisted)
    }

    @Test fun staleAccountResponseCannotPostReplacementSnapshot() {
        val operation = ProgressSyncOperation()
        val token = operation.begin(identity(operation))!!
        val replacement = identity(operation, "B", "bearer-B")
        val outbound = mutableListOf<String>()
        val rotatedCredential = identity(operation, "A", "rotated-bearer")

        if (operation.isCurrent(token, replacement)) outbound += "snapshot-B"
        if (operation.isCurrent(token, rotatedCredential)) outbound += "snapshot-with-rotated-credential"

        assertTrue(outbound.isEmpty())
    }

    @Test fun lateAckCannotAdvanceReplacementGeneration() {
        val operation = ProgressSyncOperation()
        val token = operation.begin(identity(operation))!!
        var cursor = "replacement-cursor"
        operation.invalidate()
        val replacementIdentity = identity(operation)
        operation.begin(replacementIdentity)!!

        val ackWrite = operation.writeIfCurrent(token, replacementIdentity) { cursor = "stale-ack" }

        assertNull(ackWrite)
        assertEquals("replacement-cursor", cursor)
    }

    @Test fun onlyOnePassOwnsWriter() {
        val operation = ProgressSyncOperation()
        val owner = operation.begin(identity(operation))!!
        val second = operation.begin(identity(operation))
        val writes = mutableListOf<String>()

        operation.writeIfCurrent(owner, owner.identity) { writes += "one" }
        second?.let { operation.writeIfCurrent(it, it.identity) { writes += "two" } }

        assertNull(second)
        assertEquals(listOf("one"), writes)
    }

    @Test fun queuedStorageMutationIsRejectedAfterInvalidation() {
        val operation = ProgressSyncOperation()
        val identity = identity(operation)
        val token = operation.begin(identity)!!
        val lease = operation.beginCommit(token, identity)!!
        val persisted = mutableListOf("replacement-row")
        val queuedWrite = { operation.writeCommit(lease) { persisted += "stale-row" } }

        operation.invalidate()

        assertTrue(queuedWrite())
        assertEquals(listOf("replacement-row", "stale-row"), persisted)
        operation.finishCommit(lease)
        assertFalse(operation.isCurrent(token, identity(operation)))
    }

    @Test fun replacementWindowRejectsSyncUntilBothImportPathsFinish() {
        val admission = ProgressSyncAdmission()

        admission.beginReplacement()
        assertNull(admission.acquire())
        admission.endReplacement()
        assertNotNull(admission.acquire())

        // A second import entry uses the same gate independently.
        admission.beginReplacement()
        assertNull(admission.acquire())
        admission.endReplacement()
        assertNotNull(admission.acquire())
    }

    @Test fun invalidationCannotSplitQueuedFenceFromStorageMutation() {
        val operation = ProgressSyncOperation()
        val identity = identity(operation)
        val token = operation.begin(identity)!!
        val lease = operation.beginCommit(token, identity)!!
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val invalidationFinished = CountDownLatch(1)
        val persisted = mutableListOf("before")

        val writer = Thread {
            operation.writeCommit(lease) {
                writeStarted.countDown()
                check(releaseWrite.await(2, TimeUnit.SECONDS))
                persisted += "committed"
            }
        }
        writer.start()
        assertTrue(writeStarted.await(2, TimeUnit.SECONDS))
        val invalidator = Thread {
            operation.invalidate()
            invalidationFinished.countDown()
        }
        invalidator.start()

        assertFalse("invalidation must wait for the fenced storage action", invalidationFinished.await(50, TimeUnit.MILLISECONDS))
        releaseWrite.countDown()
        writer.join(2_000)
        invalidator.join(2_000)

        assertTrue(invalidationFinished.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("before", "committed"), persisted)
        operation.publishCommit(lease) { }
        operation.finishCommit(lease)
    }

    @Test fun invalidationDuringDurableMergeStillPublishesMemoryBeforeReleasingCommit() {
        val operation = ProgressSyncOperation()
        val identity = identity(operation)
        val token = operation.begin(identity)!!
        val lease = operation.beginCommit(token, identity)!!
        var durable = "old"
        var memory = "old"

        assertTrue(operation.writeCommit(lease) { durable = "merged" })
        operation.invalidate()
        assertTrue(operation.publishCommit(lease) { memory = "merged" })
        operation.finishCommit(lease)

        assertEquals(durable, memory)
        assertFalse(operation.isCurrent(token, operation.identity("A", "bearer-A")))
    }

    @Test fun admissionLeaseDoesNotSurviveAnImportThatStartedDuringSessionResolution() {
        val admission = ProgressSyncAdmission()
        val lease = admission.acquire()!!

        admission.beginReplacement()
        admission.endReplacement()

        assertFalse(admission.isCurrent(lease))
        assertNotNull(admission.acquire())
    }

    @Test fun appViewModelRunnerRejectsSessionResolvedAfterImportWindow() = runTest {
        val operation = ProgressSyncOperation()
        val admission = ProgressSyncAdmission()
        val runner = ProgressSyncRunner(operation, admission)
        val resolveStarted = CompletableDeferred<Unit>()
        val resolveSession = CompletableDeferred<com.jkdc.app.core.AccountSession?>()
        var fetches = 0
        var sends = 0
        val task = async {
            runner.run(
                resolveSession = { resolveStarted.complete(Unit); resolveSession.await() },
                fetch = { _, _, _ -> fetches++; null },
                readLiveSession = { null },
                mergeRemote = { _, _, _ -> false },
                outboundSnapshot = { "snapshot" },
                send = { _, _, _, _ -> sends++ },
            )
        }

        resolveStarted.await()
        admission.beginReplacement()
        operation.invalidate()
        admission.endReplacement()
        resolveSession.complete(com.jkdc.app.core.AccountSession("00000000-0000-0000-0000-00000000000a", "a".repeat(43), 100L))
        task.await()

        assertEquals(0, fetches)
        assertEquals(0, sends)
    }

    @Test fun appViewModelRunnerKeepsStorageAndMemoryTogetherWhenImportStartsDuringCommit() = runTest {
        val operation = ProgressSyncOperation()
        val admission = ProgressSyncAdmission()
        val runner = ProgressSyncRunner(operation, admission)
        val commitStarted = CompletableDeferred<Unit>()
        val allowStorage = CompletableDeferred<Unit>()
        var durable = "old"
        var memory = "old"
        var sends = 0
        val session = com.jkdc.app.core.AccountSession("00000000-0000-0000-0000-00000000000a", "a".repeat(43), 100L)
        val task = async {
            runner.run(
                resolveSession = { session },
                fetch = { _, _, _ -> "remote" },
                readLiveSession = { session },
                mergeRemote = { _, token, identity ->
                    val commit = operation.beginCommit(token, identity)
                    if (commit == null) false else {
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                commitStarted.complete(Unit)
                                allowStorage.await()
                                val wrote = operation.writeCommit(commit) { durable = "merged" }
                                if (wrote) operation.publishCommit(commit) { memory = "merged" }
                            }
                        } finally { operation.finishCommit(commit) }
                        true
                    }
                },
                outboundSnapshot = { "snapshot" },
                send = { _, _, _, _ -> sends++ },
            )
        }

        commitStarted.await()
        admission.beginReplacement()
        operation.invalidate()
        allowStorage.complete(Unit)
        task.await()
        admission.endReplacement()

        assertEquals("merged", durable)
        assertEquals(durable, memory)
        assertEquals(0, sends)
    }

    @Test fun appViewModelRunnerRejectsCredentialReplacementAfterFetch() = runTest {
        val operation = ProgressSyncOperation()
        val admission = ProgressSyncAdmission()
        val runner = ProgressSyncRunner(operation, admission)
        val captured = com.jkdc.app.core.AccountSession("00000000-0000-0000-0000-00000000000a", "a".repeat(43), 100L)
        val rotated = captured.copy(accessToken = "b".repeat(43))
        var merges = 0
        var sends = 0
        runner.run(
            resolveSession = { captured },
            fetch = { _, _, _ -> "remote" },
            readLiveSession = { rotated },
            mergeRemote = { _, _, _ -> merges++; true },
            outboundSnapshot = { "snapshot" },
            send = { _, _, _, _ -> sends++ },
        )

        assertEquals(0, merges)
        assertEquals(0, sends)
    }

    @Test fun appViewModelRunnerCancellationWhileFetchIsSuspendedDoesNotReachStorageOrSend() = runTest {
        val operation = ProgressSyncOperation()
        val admission = ProgressSyncAdmission()
        val runner = ProgressSyncRunner(operation, admission)
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchResult = CompletableDeferred<String?>()
        val session = com.jkdc.app.core.AccountSession("00000000-0000-0000-0000-00000000000a", "a".repeat(43), 100L)
        var merges = 0
        var sends = 0
        val task = async {
            runner.run(
                resolveSession = { session },
                fetch = { _, _, _ -> fetchStarted.complete(Unit); fetchResult.await() },
                readLiveSession = { session },
                mergeRemote = { _, _, _ -> merges++; true },
                outboundSnapshot = { "snapshot" },
                send = { _, _, _, _ -> sends++ },
            )
        }

        fetchStarted.await()
        task.cancelAndJoin()
        fetchResult.complete("remote")

        assertEquals(0, merges)
        assertEquals(0, sends)
    }

    @Test fun runnerPassesTheBoundTokenAndIdentityToUploadContinuation() = runTest {
        val operation = ProgressSyncOperation()
        val runner = ProgressSyncRunner(operation, ProgressSyncAdmission())
        val session = com.jkdc.app.core.AccountSession("00000000-0000-0000-0000-00000000000a", "a".repeat(43), 100L)
        var sendToken: ProgressSyncOperation.Token? = null
        var sendIdentity: ProgressSyncIdentity? = null
        runner.run(
            resolveSession = { session }, fetch = { _, _, _ -> "remote" }, readLiveSession = { session },
            mergeRemote = { _, _, _ -> true }, outboundSnapshot = { "snapshot" },
            send = { _, _, token, identity -> sendToken = token; sendIdentity = identity },
        )
        assertNotNull(sendToken)
        assertEquals(sendToken!!.identity, sendIdentity)
    }

}
