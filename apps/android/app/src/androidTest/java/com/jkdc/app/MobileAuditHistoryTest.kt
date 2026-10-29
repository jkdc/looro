package com.jkdc.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jkdc.app.core.LevelTestEngine
import com.jkdc.app.core.LevelTestResult
import com.jkdc.app.network.LevelTestSnapshotAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.UUID

class MobileAuditHistoryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun resolvedMemberProjectsRemoteLessonOnlyAfterItsLoadCompletes() {
        val vm = compose.awaitHistoryLoaded()
        val account = "projection-${UUID.randomUUID()}"
        val other = "other-${UUID.randomUUID()}"
        compose.runOnIdle { vm.setExamOwner(account) }
        compose.waitUntil(10_000) { compose.runOnUiThread { vm.loadedLearningIdentity == "member:$account" } }
        compose.runOnIdle {
            vm.selectLanguage("en")
            val field = AppViewModel::class.java.getDeclaredField("store").apply { isAccessible = true }
            val store = field.get(vm) as com.jkdc.app.data.LearningDbStore
            val lesson = "remote-${UUID.randomUUID()}"
            com.jkdc.app.data.RemoteProgressRollupsDao.merge(store.db,
                com.jkdc.app.data.RemoteProgressRollup(account, "en", "lesson", lesson,
                    1_700_000_000_000, 1_700_000_000_000, 1, 0, 1))
            assertTrue(vm.completedLessonIDs().contains(lesson))
            vm.setExamOwner(other)
            assertTrue(vm.completedLessonIDs().isEmpty())
        }
        compose.waitUntil(10_000) { compose.runOnUiThread { vm.loadedLearningIdentity == "member:$other" } }
        compose.runOnIdle {
            val field = AppViewModel::class.java.getDeclaredField("store").apply { isAccessible = true }
            val store = field.get(vm) as com.jkdc.app.data.LearningDbStore
            // Even a late old-owner row must not enter the new owner's read model.
            com.jkdc.app.data.RemoteProgressRollupsDao.merge(store.db,
                com.jkdc.app.data.RemoteProgressRollup(account, "en", "lesson", "late-old-owner",
                    1_700_000_000_000, 1_700_000_000_000, 1, 0, 1))
            assertTrue(vm.completedLessonIDs().isEmpty())
            vm.setExamOwner(null)
            assertTrue(vm.completedLessonIDs().isEmpty())
        }
        compose.waitUntil(10_000) { compose.runOnUiThread { vm.loadedLearningIdentity?.startsWith("guest:") == true } }
        compose.runOnIdle { assertFalse(vm.completedLessonIDs().contains("late-old-owner")) }
    }

    @Test fun mountedExamCardFollowsLanguageWithoutChangingLevelOrOwner() {
        val vm = compose.awaitHistoryLoaded()
        compose.runOnIdle { vm.setExamOwner("card-${UUID.randomUUID()}") }
        compose.waitUntil(10_000) { compose.runOnUiThread { vm.loadedLearningIdentity != null } }
        compose.runOnIdle {
            vm.selectLanguage("en")
            vm.saveLevelTestResult(LevelTestResult("en", "A1", 202642, 71,
                listOf("recognition" to 71), true, null, emptyList(), emptyList(), 1_000L))
            compose.activity.setContent {
                com.jkdc.app.ui.LearnScreen(vm, onPractice = {}, onThemeChosen = {})
            }
        }
        val card = compose.onNodeWithTag("leveltest-card")
        card.performScrollTo().assertTextContains("71", substring = true)
        compose.runOnIdle { vm.selectLanguage("es") }
        card.performScrollTo().assertTextContains(compose.activity.getString(R.string.leveltest_entry_cta), substring = true)
        compose.runOnIdle { vm.selectLanguage("en") }
        card.performScrollTo().assertTextContains("71", substring = true)
    }

    @Test fun mountedExamCardRestoresEachLanguagesOwnResult() {
        val vm = compose.awaitHistoryLoaded()
        compose.runOnIdle { vm.setExamOwner("cards-${UUID.randomUUID()}") }
        compose.waitUntil(10_000) { compose.runOnUiThread { vm.loadedLearningIdentity != null } }
        val attempts = mutableMapOf<String, String>()
        for ((language, score) in listOf("en" to 71, "es" to 83)) {
            compose.runOnIdle { vm.selectLanguage(language) }
            val pool = requireNotNull(vm.levelTestPool("A1"))
            val selection = LevelTestSnapshotAdapter.fromBundled(pool, LevelTestEngine.compose(pool, 202642))
            val attemptID = "card-$language-${UUID.randomUUID()}"
            attempts[language] = attemptID
            runBlocking {
                vm.startLevelTestAttempt(selection, attemptID)
                vm.completeLevelTestAttempt(attemptID, selection, emptyMap(), 0,
                    LevelTestResult(language, "A1", 202642, score,
                        listOf("recognition" to score), true, null, emptyList(), emptyList(), 1_000L))
            }
        }
        compose.runOnIdle {
            compose.activity.setContent { com.jkdc.app.ui.LearnScreen(vm, onPractice = {}, onThemeChosen = {}) }
        }
        val card = compose.onNodeWithTag("leveltest-card")
        for ((language, score) in listOf("es" to 83, "en" to 71, "es" to 83)) {
            compose.runOnIdle { vm.selectLanguage(language) }
            compose.waitUntil(5_000) {
                vm.levelTestAttemptHistoryState.value.singleOrNull()?.attemptID == attempts[language]
            }
            card.performScrollTo().assertTextContains(score.toString(), substring = true)
            compose.onNodeWithTag("leveltest-history-${attempts[language]}").performScrollTo().assertExists()
            compose.onNodeWithText(compose.activity.getString(R.string.leveltest_review_summary_only)).assertDoesNotExist()
        }
    }

    @Test fun lateOwnerHistoryCannotRepopulateNewAccount() = runBlocking {
        val vm = compose.awaitHistoryLoaded()
        val ownerA = "history-owner-a-${UUID.randomUUID()}"
        val ownerB = "history-owner-b-${UUID.randomUUID()}"
        compose.runOnIdle { vm.setExamOwner(ownerA) }
        compose.waitUntil(10_000) { compose.runOnUiThread { vm.loadedLearningIdentity == "member:$ownerA" } }
        compose.runOnIdle { vm.selectLanguage("en") }
        val languageID = vm.language.id
        val level = requireNotNull(vm.levelTestPool("A1"))
        val selection = LevelTestSnapshotAdapter.fromBundled(level, LevelTestEngine.compose(level, 202642))
        val ownerAAttemptID = "history-a-${UUID.randomUUID()}"
        val ownerBAttemptID = "history-b-${UUID.randomUUID()}"
        compose.runOnIdle { vm.setExamOwner(ownerA) }
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

        compose.runOnIdle { vm.setExamOwner(ownerB) }
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
