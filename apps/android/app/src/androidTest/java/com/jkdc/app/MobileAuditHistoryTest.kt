package com.jkdc.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jkdc.app.core.LevelTestEngine
import com.jkdc.app.core.LevelTestResult
import com.jkdc.app.network.LevelTestSnapshotAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.UUID

class MobileAuditHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun lateOwnerHistoryCannotRepopulateNewAccount() = runBlocking {
        val vm = compose.awaitHistoryLoaded()
        val languageID = vm.language.id
        val level = requireNotNull(vm.levelTestPool("A1"))
        val selection = LevelTestSnapshotAdapter.fromBundled(level, LevelTestEngine.compose(level, 202642))
        val ownerAAttemptID = "history-a-${UUID.randomUUID()}"
        val ownerBAttemptID = "history-b-${UUID.randomUUID()}"
        compose.runOnIdle { vm.setExamOwner("history-owner-a") }
        val oldIdentity = compose.runOnUiThread { vm.captureLevelTestAttemptHistoryIdentity() }
        vm.startLevelTestAttempt(selection, ownerAAttemptID)
        vm.loadLevelTestAttemptHistory(languageID, "A1")
        assertEquals(listOf(ownerAAttemptID), vm.levelTestAttemptHistoryState.value.map { it.attemptID })
        vm.saveLevelTestResult(LevelTestResult(languageID, "A1", 202642, 71,
            listOf("recognition" to 71), passed = true, nextBand = null,
            weakSections = emptyList(), weakUnitIDs = emptyList(), at = 1_000L))
        assertEquals(71, vm.levelTestHistory("A1")?.overall)

        val oldQueryEntered = CompletableDeferred<Unit>()
        val releaseOldQuery = CompletableDeferred<Unit>()
        vm.beforeLevelTestAttemptHistoryQuery = { identity ->
            if (identity == oldIdentity) {
                oldQueryEntered.complete(Unit)
                releaseOldQuery.await()
            }
        }
        val oldQuery = async { vm.loadLevelTestAttemptHistory(languageID, "A1") }
        oldQueryEntered.await()
        assertEquals(listOf(ownerAAttemptID), vm.levelTestAttemptHistoryState.value.map { it.attemptID })

        compose.runOnIdle { vm.setExamOwner("history-owner-b") }
        val currentIdentity = compose.runOnUiThread { vm.captureLevelTestAttemptHistoryIdentity() }
        assertTrue(vm.levelTestAttemptHistoryState.value.isEmpty())
        assertEquals(null, vm.levelTestHistory("A1"))
        releaseOldQuery.complete(Unit)
        oldQuery.await()
        assertTrue(vm.levelTestAttemptHistoryState.value.isEmpty())

        vm.beforeLevelTestAttemptHistoryQuery = {}
        vm.startLevelTestAttempt(selection, ownerBAttemptID)
        vm.loadLevelTestAttemptHistory(languageID, "A1")
        assertEquals(listOf(ownerBAttemptID), vm.levelTestAttemptHistoryState.value.map { it.attemptID })

        val secondOldQueryEntered = CompletableDeferred<Unit>()
        val releaseSecondOldQuery = CompletableDeferred<Unit>()
        vm.beforeLevelTestAttemptHistoryQuery = { identity ->
            if (identity == currentIdentity) {
                secondOldQueryEntered.complete(Unit)
                releaseSecondOldQuery.await()
            }
        }
        val secondOldQuery = async { vm.loadLevelTestAttemptHistory(languageID, "A1") }
        secondOldQueryEntered.await()
        assertEquals(listOf(ownerBAttemptID), vm.levelTestAttemptHistoryState.value.map { it.attemptID })
        compose.runOnIdle { vm.setExamOwner(null) }
        assertTrue(vm.levelTestAttemptHistoryState.value.isEmpty())
        releaseSecondOldQuery.complete(Unit)
        secondOldQuery.await()
        assertTrue(vm.levelTestAttemptHistoryState.value.isEmpty())

        assertTrue(currentIdentity.generation > oldIdentity.generation)
    }
}
