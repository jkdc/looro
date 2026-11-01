package com.jkdc.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jkdc.app.core.ProgressEngine
import com.jkdc.app.engines.local.AudioFrameSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Exercises the mounted ViewModel with deterministic capture/STT boundaries. */
class PronunciationIdentityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class Recorder : AudioFrameSource {
        var stops = 0
        override fun start() = true
        override fun readFrame(): FloatArray? = null
        override fun stop() { stops++ }
        override fun pause() {}
        override fun drainStale() {}
        override fun resume() = true
    }

    private fun database(vm: AppViewModel): com.jkdc.app.data.SqlDatabase {
        val field = AppViewModel::class.java.getDeclaredField("store").apply { isAccessible = true }
        return (field.get(vm) as com.jkdc.app.data.LearningDbStore).db
    }

    private fun durableEvidence(vm: AppViewModel): List<List<List<Any?>>> {
        // The history query queues behind preceding ViewModel writes, not just Compose work.
        kotlinx.coroutines.runBlocking { vm.loadLevelTestAttemptHistory(vm.language.id, "A1") }
        return listOf("completions", "exam_sync_outbox", "usage_outbox").map { table ->
            database(vm).query("SELECT * FROM $table ORDER BY rowid").map { it.values }
        }
    }

    private fun owner(vm: AppViewModel, id: String) {
        compose.runOnIdle { vm.setExamOwner(id) }
        compose.waitUntil(10_000) { compose.runOnUiThread { vm.loadedLearningIdentity == "member:$id" } }
        compose.runOnIdle { vm.selectLanguage("en") }
    }

    private fun lateSuccess(changeOwner: Boolean) {
        val vm = compose.awaitHistoryLoaded()
        owner(vm, "pron-a-${UUID.randomUUID()}")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val recorder = Recorder()
        val lesson = "pron-${UUID.randomUUID()}"
        compose.runOnIdle {
            vm.pronunciationRecorder = { recorder }
            vm.pronunciationCapture = { floatArrayOf(1f) }
            // A non-cooperative dependency can still return after cancellation.
            vm.pronunciationTranscribe = { _, _ -> withContext(NonCancellable) {
                entered.complete(Unit); release.await(); returned.complete(Unit); "hello"
            } }
            vm.checkPronunciation("hello", lesson)
        }
        compose.waitUntil(5_000) { entered.isCompleted }
        if (changeOwner) owner(vm, "pron-b-${UUID.randomUUID()}")
        else compose.runOnIdle { vm.selectLanguage("es") }
        val before = compose.runOnUiThread { vm.archive.completions.orEmpty().toList() }
        val beforeNotice = compose.runOnUiThread { vm.notice }
        val beforeDurable = durableEvidence(vm)
        release.complete(Unit)
        compose.waitUntil(5_000) { returned.isCompleted }
        compose.waitForIdle()
        val afterDurable = durableEvidence(vm)
        compose.runOnIdle {
            assertEquals(beforeDurable, afterDurable)
            assertNull(vm.pronunciationResult)
            assertNull(vm.pronunciationListening)
            assertEquals(before, vm.archive.completions.orEmpty())
            assertEquals(beforeNotice, vm.notice)
            assertEquals(1, recorder.stops)
        }
    }

    @Test fun lateOwnerSuccessCannotPublishOrPersistIntoNewOwner() = lateSuccess(true)
    @Test fun lateLanguageSuccessCannotPublishOrPersistIntoNewLanguage() = lateSuccess(false)

    @Test fun stableSuccessRecordsExactlyOnceInCapturedLanguage() {
        val vm = compose.awaitHistoryLoaded()
        owner(vm, "pron-success-${UUID.randomUUID()}")
        val recorder = Recorder()
        val lesson = "pron-${UUID.randomUUID()}"
        compose.runOnIdle {
            vm.pronunciationRecorder = { recorder }
            vm.pronunciationCapture = { floatArrayOf(1f) }
            vm.pronunciationNowSeconds = { 1_000.0 }
            vm.pronunciationTranscribe = { _, language -> assertEquals("en", language); "hello" }
            vm.checkPronunciation("hello", lesson)
        }
        compose.waitUntil(5_000) { compose.runOnUiThread { vm.pronunciationResult != null } }
        durableEvidence(vm)
        val durableMarks = database(vm).query("SELECT * FROM completions WHERE kind = ? AND owner_key = ?",
            listOf(ProgressEngine.PRONUNCIATION, vm.loadedLearningIdentity))
        assertEquals(1, durableMarks.size)
        assertEquals("en", durableMarks.single()["language_id"])
        assertEquals(1_000.0, durableMarks.single()["at"] as Double, 0.0)
        compose.runOnIdle {
            assertEquals("hello" to 100, vm.pronunciationResult)
            val marks = vm.archive.completions.orEmpty().filter { it.kind == ProgressEngine.PRONUNCIATION }
            assertEquals(1, marks.size)
            assertEquals("en", marks.single().languageID)
            assertEquals(1, recorder.stops)
        }
    }

    @Test fun cancellingCaptureReleasesRecorderWithoutUserError() {
        val vm = compose.awaitHistoryLoaded()
        owner(vm, "pron-cancel-${UUID.randomUUID()}")
        val entered = CompletableDeferred<Unit>()
        val recorder = Recorder()
        compose.runOnIdle {
            vm.pronunciationRecorder = { recorder }
            vm.pronunciationCapture = { entered.complete(Unit); CompletableDeferred<FloatArray>().await() }
            vm.checkPronunciation("hello", "cancelled")
        }
        compose.waitUntil(5_000) { entered.isCompleted }
        val notice = compose.runOnUiThread { vm.notice }
        compose.runOnIdle { vm.selectLanguage("es") }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, recorder.stops)
            assertNull(vm.pronunciationListening)
            assertNull(vm.pronunciationResult)
            assertEquals(notice, vm.notice)
        }
    }

    @Test fun captureFailureReleasesRecorderAndAllowsRetry() {
        val vm = compose.awaitHistoryLoaded()
        owner(vm, "pron-error-${UUID.randomUUID()}")
        val recorder = Recorder()
        compose.runOnIdle {
            vm.pronunciationRecorder = { recorder }
            vm.pronunciationCapture = { error("capture failed") }
            vm.checkPronunciation("hello", "failed")
        }
        compose.waitUntil(5_000) { compose.runOnUiThread { vm.pronunciationListening == null } }
        compose.runOnIdle {
            assertEquals(1, recorder.stops)
            assertNull(vm.pronunciationResult)
            assertEquals(compose.activity.getString(R.string.lesson_pronounce_unavailable), vm.notice)
            assertTrue(vm.archive.completions.orEmpty().isEmpty())
            vm.pronunciationCapture = { floatArrayOf(1f) }
            vm.pronunciationTranscribe = { _, _ -> "hello" }
            vm.checkPronunciation("hello", "retry")
        }
        compose.waitUntil(5_000) { compose.runOnUiThread { vm.pronunciationResult != null } }
    }
}
