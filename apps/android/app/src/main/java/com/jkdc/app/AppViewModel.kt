package com.jkdc.app

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jkdc.app.core.*
import com.jkdc.app.core.engines.ConversationEngineDeps
import com.jkdc.app.core.engines.ConversationEngineFactory
import com.jkdc.app.core.voice.LocalVoiceTransport
import com.jkdc.app.core.voice.VoiceTransport
import com.jkdc.app.network.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Maps a network/credential failure reason to its user-facing string resource, or 0 if unmapped. */
@StringRes
internal fun errorMessageRes(e: Throwable): Int = when (e) {
    is HostedFailure -> hostedErrorMessageRes(e)
    is AccountFailure.Http -> when (e.status) {
        401 -> R.string.hosted_sign_in_again
        429 -> R.string.hosted_rate_limit
        else -> R.string.hosted_request_failed
    }
    AccountFailure.Unavailable -> R.string.account_error_connection
    is APIClient.APIException.MissingKey -> R.string.error_missing_key
    is APIClient.APIException.Refused -> R.string.error_request_refused
    is APIClient.APIException.InvalidResponse, is APIClient.APIException.Incomplete -> R.string.error_incomplete_response
    is APIClient.APIException.Http -> when (e.status) {
        401 -> R.string.error_http_401
        403, 404 -> R.string.error_http_403_404
        429 -> R.string.error_http_429
        else -> R.string.error_http_generic
    }
    is CredentialStore.CredentialException.Invalid -> R.string.error_key_invalid
    is CredentialStore.CredentialException.Save -> R.string.error_key_save
    is CredentialStore.CredentialException.Remove -> R.string.error_key_remove
    else -> 0
}

@StringRes
internal fun hostedErrorMessageRes(failure: HostedFailure): Int = when (failure) {
    HostedFailure.SignInRequired -> R.string.hosted_sign_in_again
    HostedFailure.Unconfirmed -> R.string.hosted_unconfirmed
    HostedFailure.Unavailable -> R.string.hosted_unavailable
    is HostedFailure.Http -> when {
        needsAccountRecovery(failure) -> R.string.hosted_sign_in_again
        failure.code in setOf("insufficient_minutes", "insufficient_credit") -> R.string.hosted_no_minutes
        failure.code in setOf("helper_budget_exhausted", "helper_session_limit") -> R.string.hosted_extra_help_limit
        failure.code == "helper_session_window_closed" -> R.string.hosted_window_closed
        failure.code in setOf("helper_request_already_attempted", "helper_response_uncertain", "live_request_already_created", "provider_session_unconfirmed") -> R.string.hosted_unconfirmed
        failure.code == "live_session_unresolved" -> R.string.hosted_checking_previous
        failure.code == "provider_create_rejected" -> R.string.hosted_start_rejected
        failure.status == 429 -> R.string.hosted_rate_limit
        failure.code in setOf("hosted_voice_not_ready", "hosted_helpers_not_ready") -> R.string.hosted_unavailable
        else -> R.string.hosted_request_failed
    }
    else -> R.string.hosted_request_failed
}

internal fun needsAccountRecovery(error: Throwable): Boolean = error == HostedFailure.SignInRequired ||
    (error is HostedFailure.Http && (error.status == 401 || error.code == "sign_in_to_continue")) ||
    (error is AccountFailure.Http && error.status == 401)

internal fun requestErrorReference(error: Throwable): String? = safeRequestErrorReference(when (error) {
    is HostedFailure.Http -> error.reference
    is AccountFailure.Http -> error.reference
    else -> null
})

/** Post-session review lifecycle for situation practice (mode="scenario"). */
sealed interface ScenarioReviewState {
    val sessionID: String
    val scenarioID: String
    val scenarioTitle: String
    data class Preparing(override val sessionID: String, override val scenarioID: String,
        override val scenarioTitle: String) : ScenarioReviewState
    data class Ready(override val sessionID: String, override val scenarioID: String,
        override val scenarioTitle: String, val review: ScenarioReview) : ScenarioReviewState
    data class Failed(override val sessionID: String, override val scenarioID: String,
        override val scenarioTitle: String) : ScenarioReviewState
}

/** Whether the failure means the learner needs to add or fix their OpenAI key in Settings. */
internal fun errorNeedsKeySetup(e: Throwable): Boolean =
    e is APIClient.APIException.MissingKey || (e is APIClient.APIException.Http && e.status == 401)

/** Imported partial conversations are history, not local sessions awaiting cloud recovery. */
internal fun prepareImportedArchive(data: String, importedAt: Double = nowSeconds()): Archive =
    ArchiveCodec.decode(data).also { imported ->
        imported.sessions.filter { it.endedAt == null }.forEach {
            it.endedAt = importedAt
            it.endReason = "Imported unfinished conversation"
        }
    }

class AppViewModel(application: Application) : AndroidViewModel(application) {
    // Keep intent, not an Activity-capturing callback, while the learner reviews consent.
    var pendingCloudAction: CloudAction? = null
    var archive by mutableStateOf(Archive()); private set
    var loadingHistory by mutableStateOf(true); private set
    var session by mutableStateOf<SessionRecord?>(null); private set
    var state by mutableStateOf("idle"); private set
    var error by mutableStateOf<String?>(null); private set
    var errorNeedsKeySetup by mutableStateOf(false); private set
    var errorNeedsAccountSignIn by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var meaning by mutableStateOf(""); private set
    var pendingLookupOpen by mutableStateOf(false)
    var translating by mutableStateOf(false); private set
    var meaningFailed by mutableStateOf(false); private set
    var working by mutableStateOf(false); private set
    var isMuted by mutableStateOf(false); private set
    var inputLevel by mutableStateOf(0.0); private set
    var outputLevel by mutableStateOf(0.0); private set
    /** Provisional on-device transcription while the user speaks; never stored as a fragment. */
    var partialUserTranscript by mutableStateOf<String?>(null); private set
    var selectedTheme by mutableStateOf<ConversationTheme?>(null); private set
    var selectedLesson by mutableStateOf<Lesson?>(null); private set
    var selectedScenario by mutableStateOf<Scenario?>(null); private set
    var lookupResult by mutableStateOf<String?>(null); private set
    var lookupError by mutableStateOf<String?>(null); private set
    var lookupLoading by mutableStateOf(false); private set
    private var lookupGeneration = 0L
    private var lookupJob: Job? = null
    var topicResult by mutableStateOf<TopicBrief?>(null); private set
    var hasKey by mutableStateOf(false); private set
    val language get() = LanguageRegistry.get(archive.preferences.learningLanguageID)!!
    val learner get() = LearningEngine.project(archive.sessions, language.id, archive.preferences.hiddenWords)

    /** Progress-screen summary recomputed on demand (never stored); scoped to the learning language. */
    fun stats(): LearningStats = LearningEngine.stats(archive.sessions.filter { it.languageID == language.id }, learner)
    val isRunning get() = state in listOf("connecting", "active", "closing")
    val isVoiceSession get() = voiceSession

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val repository = LearningRepository(application)
    private val credentials = CredentialStore(application)

    private val voiceManualTurnStore by lazy {
        com.jkdc.app.core.voice.VoiceManualTurnStore(getApplication())
    }
    private val api = APIClient(credentials)
    private val engineOverrides = com.jkdc.app.core.engines.EngineOverridesStore(application)
    private val engineSelection = com.jkdc.app.core.engines.LocalEngineSelectionStore(application)

    /** User override first, then environment, then the deployed bundled key. */
    private val engineKeyLookup: (String) -> String? = { name ->
        val engineId = com.jkdc.app.core.engines.localEngineIdForKeyEnv(name)
        engineId?.let { engineOverrides.customKey(it) }
            ?: System.getenv(name)
            ?: com.jkdc.app.core.engines.ApiKeysStore.get(application, name)
    }
    private val engineDeps = ConversationEngineDeps(
        application,
        api,
        keyLookup = engineKeyLookup,
        json = json,
    )
    private val conversationEngine: TeachingClient =
        if (BuildConfig.CONVERSATION_ENGINE == "openai") {
            ConversationEngineFactory.create(BuildConfig.CONVERSATION_ENGINE, engineDeps)
        } else {
            // Runtime-selectable among the bundled local engines; the build-time
            // engine remains the default.
            com.jkdc.app.core.engines.LocalEngineSwitcher(engineDeps) {
                engineSelection.selected(BuildConfig.CONVERSATION_ENGINE)
            }
        }

    /** Compose-observable mirror of the transport's turnInFlight: the flag
        flips from the IO loop, so a plain getter would never recompose the
        end-turn button's pending state. */
    var turnInFlightUi by androidx.compose.runtime.mutableStateOf(false)
        private set

    private val pronunciationStt = com.jkdc.app.engines.local.WhisperStt(
        // models/whisper under the app's external files dir — same layout
        // the local voice pipeline has always used (see docs/conversation-engines.md).
        java.io.File(application.getExternalFilesDir(null) ?: application.filesDir, "models/whisper"),
    )

    private val localTransport = LocalVoiceTransport(
            context = application,
            scope = viewModelScope,
            openOnStart = true,
            stt = pronunciationStt,
            engine = conversationEngine,
            tts = com.jkdc.app.engines.local.LocalTtsEngine(application),
        ).apply {
            // Cloud STT providers (OpenAI-compatible); the settings row selects
            // one per turn from live preferences — toggling applies mid-session.
            cloudStts = mapOf(
                "openai" to com.jkdc.app.network.CloudStt(apiKey = { credentials.read() }),
                "groq" to com.jkdc.app.network.CloudStt(
                    endpoint = "https://api.groq.com/openai/v1/audio/transcriptions",
                    model = "whisper-large-v3-turbo",
                    apiKey = { com.jkdc.app.core.engines.ApiKeysStore.get(application, "GROQ_API_KEY") },
                ),
            )
            cloudSttSelection = { archive.preferences.sttEngine?.takeUnless { it == "local" } }
            onTurnInFlight = { turnInFlightUi = it }
        }
    private val liveTransport by lazy { LiveTransport(application, viewModelScope) }

    private val transport: VoiceTransport
        get() = if (BuildConfig.CONVERSATION_ENGINE == "openai") liveTransport else localTransport
    private val providerStore = ConversationProviderStore(application)
    private val hostedConfiguration = HostedConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN)
    private val accountConfiguration = ManagedAccountConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN, BuildConfig.GOOGLE_SERVER_CLIENT_ID)
    private val memberSessions = accountConfiguration?.let { AccountSessionStore(application, it.origin.toString()) }
    private val guests = hostedConfiguration?.let { config ->
        GuestMinuteController(GuestInstallationStore(application, config.origin.toString()), GuestMinuteClient(config), {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(java.security.SecureRandom()::nextBytes))
        })
    }
    var guestState by mutableStateOf(GuestMinuteState()); private set
    var showMinuteAccess by mutableStateOf(false); private set
    private var selectedAccount = AccountState(busy = true)
    private var accessJob: Job? = null
    private val hostedBindings = HostedConversationBindings(viewModelScope)
    private var hostedSessionIDs = emptySet<String>()
    private var pendingHostedOwnerID: String? = null
    /** Reauthentication may renew this account only; another account cannot replace an unresolved owner. */
    suspend fun pendingMemberForSignIn(): String? {
        clearAcknowledgedGuestMarker()
        guests?.expectedMemberID()?.let { return it }
        val pending = pendingHostedOwnerID ?: return null
        if (guests?.owns(pending) != true) return pending
        // Renewal retains the same guest identity, allowing an interrupted conversation to settle
        // before Google login. A guest UUID is never sent as Google's expected member identity.
        if (guests.session(pending) == null) guests.acquire()
        return null
    }
    private var reconciliationJob: Job? = null
    var conversationProvider by mutableStateOf(ConversationProvider.PERSONAL_KEY); private set
    var hostedReadiness by mutableStateOf(HostedReadiness()); private set
    private val readiness = HostedReadinessController(viewModelScope,
        readSession = { availableHostedOwner() },
        fetch = { owner ->
            val enabled = hostedClient(owner.accountID).available()
            val balance = if (enabled) hostedBalance(owner) else null
            HostedReadiness(owner.accountID, balance?.readinessMilliseconds ?: 0, enabled)
        }, changed = { hostedReadiness = it })
    var accountChangeBlocked by mutableStateOf(true); private set

    private val reports = ReportController(viewModelScope,
        ReportConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN)?.let(::ReportClient))
    val reportState = reports.state

    fun reportUtterance(sessionID: String, passageID: String) {
        val selectedSession = session?.takeIf { it.id == sessionID }
            ?: archive.sessions.firstOrNull { it.id == sessionID } ?: return
        ReportSelection.from(selectedSession, passageID)?.let(reports::open)
    }
    fun submitReport(report: AIReportSubmission) {
        usageReporter.record(UsageEvents.REPORT_REQUESTED)
        reports.submit(report)
    }
    fun dismissReport() = reports.dismiss()

    // Content-free usage telemetry (core/UsageMetrics.kt). Members' bearer first,
    // then the guest grant's; without either, events simply stay queued.
    private val usageClient = accountConfiguration?.let(::UsageClient)
    private val progressClient = accountConfiguration?.let(::ProgressClient)
    private val usageReporter = UsageReporter(platform = "android", appVersion = appVersionName(),
        send = { batch, _ -> sendUsage(batch) })

    private fun appVersionName(): String = runCatching {
        val context = getApplication<Application>()
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private suspend fun sendUsage(batch: String): Boolean {
        val client = usageClient ?: return false
        val session = memberSessions?.read() ?: guests?.availableSession() ?: return false
        return try { client.send(session, batch) } catch (_: Exception) { false }
    }

    private fun lessonCourseID(lessonID: String?): String? {
        if (lessonID == null) return null
        return CourseRegistry.forLanguage(language.id).firstOrNull { course ->
            course.units.any { unit -> unit.lessons.any { it.id == lessonID } }
        }?.id
    }

    fun practiceRoundCompleted(correct: Int, total: Int) {
        if (total <= 0) return
        recordCompletion(ProgressEngine.RECALL_ROUND, "recall")
        usageReporter.record(UsageEvents.PRACTICE_COMPLETED) {
            put("mode", "recall"); put("scoreBucket", UsageBuckets.score(correct * 100 / total))
        }
    }

    /** Words writing practice records each correct recall; the round reports telemetry. */
    fun wordPracticed(word: WordState) { recordCompletion(ProgressEngine.WORDS_PRACTICE, word.id) }
    fun wordsPracticeRoundCompleted(correct: Int, total: Int) {
        if (total <= 0) return
        usageReporter.record(UsageEvents.PRACTICE_COMPLETED) {
            put("mode", "word"); put("scoreBucket", UsageBuckets.score(correct * 100 / total))
        }
    }

    /** A finished authored exercise set: one ledger mark with the score, one telemetry event. */
    fun exerciseSetCompleted(lessonID: String, correct: Int, total: Int) {
        if (total <= 0) return
        val percent = correct * 100 / total
        recordCompletion(ProgressEngine.EXERCISE_SET, lessonID, percent)
        val courseID = lessonCourseID(lessonID)
        if (courseID != null) usageReporter.record(UsageEvents.EXERCISE_COMPLETED) {
            put("courseID", courseID); put("lessonID", lessonID); put("scoreBucket", UsageBuckets.score(percent))
        }
    }

    /** Appends one completion to the local ledger — the source of every progress metric.
        Marks carry the learning language; the ledger keeps only the newest marks. */
    val pronunciationEnabled: Boolean
        get() = archive.preferences.pronunciationEnabled == true

    fun recordCompletion(kind: String, rawItemID: String, bestScore: Int = 0) {
        val mark = CompletionMark(kind, ProgressEngine.completionKey(rawItemID), nowSeconds(), bestScore, language.id)
        val marks = ((archive.completions ?: mutableListOf()) + mark)
            .sortedByDescending { it.at }.take(ArchiveCodec.MAXIMUM_COMPLETIONS)
        archive = archive.copy(completions = marks.toMutableList())
        bumpLedgerRevision()
        persist()
    }

    /** Pronunciation drill state: the target currently being listened to and
        the last scored target. Compose-observable so the drill recomposes. */
    var pronunciationListening by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var pronunciationResult by androidx.compose.runtime.mutableStateOf<Pair<String, Int>?>(null)
        private set

    /** Records ~4 s of microphone audio, transcribes it with the same on-device
        STT Talk uses, and scores it against the target. A pass records
        `pronunciation` ledger evidence for the lesson's pron skills; any
        failure degrades to the plain listen-and-repeat with a notice. */
    fun checkPronunciation(target: String, lessonID: String) {
        if (pronunciationListening != null || isRunning) return
        pronunciationListening = target
        viewModelScope.launch {
            val outcome = runCatching {
                val recorder = com.jkdc.app.engines.local.MicRecorder()
                check(recorder.start()) { "micro unavailable" }
                val frames = ArrayList<FloatArray>()
                val deadline = System.currentTimeMillis() + 4_000
                while (System.currentTimeMillis() < deadline) {
                    recorder.readFrame()?.let { frames.add(it) }
                    kotlinx.coroutines.delay(20)
                }
                recorder.stop()
                val samples = frames.reduce { acc, frame -> acc + frame }
                val clean = com.jkdc.app.engines.local.MicRecorder.trimSilence(samples)
                check(clean.size >= com.jkdc.app.engines.local.MicRecorder.SAMPLE_RATE / 2) { "too short" }
                PronunciationEngine.score(target, pronunciationStt.transcribe(clean, language.id).text)
            }
            pronunciationListening = null
            outcome.fold(
                onSuccess = { score ->
                    pronunciationResult = target to score
                    if (PronunciationEngine.passed(score)) recordCompletion(ProgressEngine.PRONUNCIATION, lessonID, score)
                },
                onFailure = {
                    notice = getApplication<Application>().getString(R.string.lesson_pronounce_unavailable)
                },
            )
        }
    }

    fun progressStats(): ProgressStats = ProgressEngine.stats(archive.sessions, archive.lessons,
        archive.completions.orEmpty(), CourseRegistry.forLanguage(language.id),
        ListeningRegistry.forLanguage(language.id).map { it.id }.toSet(), language.id,
        ReadingRegistry.forLanguage(language.id).map { it.id }.toSet())

    fun completedLessonIDs(): Set<String> = archive.lessons.filter { it.attempts > 0 }.map { it.lessonID }.toSet()

    /** Derived engagement level for a course — a pure projection of the ledger
        that mixes lesson mastery with the listening points. */
    fun levelStats(course: Course): LevelStats = LevelEngine.stats(course, archive.lessons,
        archive.completions.orEmpty(), ListeningRegistry.forLanguage(course.languageID))
    fun currentLevelStats(): LevelStats? = foundationsCourse()?.let { levelStats(it) }
    fun listeningMedals(): Int = ListeningRegistry.forLanguage(language.id)
        .count { LevelEngine.listeningMedal(listeningScore(it.id)) >= 1 }

    /** The foundations course for the current language — explicitly resolved by
        id so that track courses (alphabetically earlier) never shadow it. */
    fun foundationsCourse(): Course? = CourseRegistry.forLanguage(language.id)
        .firstOrNull { it.id.endsWith("-foundations") }

    /** Monotonic counter — every ledger mutation increments it (§7). */
    private var ledgerRevision: Long = 0
    private fun bumpLedgerRevision() { ledgerRevision++ }

    /** Skill catalog index, keyed by language so a language switch rebuilds it (§5). */
    private var cachedIndex: Pair<Pair<String, Boolean>, SkillCatalogIndex>? = null
    private fun skillIndex(course: Course): SkillCatalogIndex {
        val key = language.id to pronunciationEnabled
        cachedIndex?.takeIf { it.first == key }?.let { return it.second }
        val idx = MasteryEngine.buildIndex(course,
            ListeningRegistry.forLanguage(language.id),
            ReadingRegistry.forLanguage(language.id),
            includePronunciation = pronunciationEnabled)
        cachedIndex = key to idx
        return idx
    }

    /** Memoized mastery (§9): valid only when language, ledgerRevision, and
        masteryVersion all match (§20). */
    private var cachedMastery: Triple<String, Pair<Long, Int>, MasteryStats>? = null

    fun masteryStats(): MasteryStats? = foundationsCourse()?.let { course ->
        val lang = language.id
        val rev = ledgerRevision
        val ver = MasteryEngine.MASTERY_VERSION
        cachedMastery?.takeIf { it.first == lang && it.second.first == rev && it.second.second == ver }?.third
            ?: MasteryEngine.stats(course,
                ListeningRegistry.forLanguage(language.id),
                ReadingRegistry.forLanguage(language.id),
                archive.lessons, archive.completions.orEmpty(),
                nowSeconds(), skillIndex(course), rev
            ).also { cachedMastery = Triple(lang, rev to ver, it) }
    }

    /** A finished reading: one ledger mark with the score, same bargain as listening. */
    fun readingCompleted(itemID: String, correct: Int, total: Int) {
        if (total <= 0) return
        recordCompletion(ProgressEngine.READING, itemID, correct * 100 / total)
    }

    fun readingScore(itemID: String): Int? {
        val key = ProgressEngine.completionKey(itemID)
        return archive.completions.orEmpty()
            .filter { it.kind == ProgressEngine.READING && it.itemID == key }
            .maxOfOrNull { it.bestScore }
    }

    fun checkpoint(id: String): Checkpoint? = CheckpointRegistry.checkpoint(id)

    fun checkpointBest(id: String): Int? = archive.completions.orEmpty()
        .filter { it.kind == ProgressEngine.ASSESSMENT && it.itemID == id }
        .maxOfOrNull { it.bestScore }

    /** Whether a checkpoint has been passed, using the same logic as
        CheckpointEngine.grade (overall threshold AND every category floor). */
    private fun checkpointPassed(checkpoint: Checkpoint): Boolean {
        val best = checkpointBest(checkpoint.id) ?: return false
        return best >= (checkpoint.thresholds["overall"] ?: 100)
    }

    /** The first checkpoint whose level is fully attempted and not yet passed —
        readiness as preparation, never a proficiency claim. */
    fun checkpointReady(): Checkpoint? {
        val course = foundationsCourse() ?: return null
        val done = archive.lessons.filter { it.attempts > 0 }.map { it.lessonID }.toSet()
        return CheckpointRegistry.forLanguage(language.id).firstOrNull { checkpoint ->
            val levelLessons = course.units.filter { it.level == checkpoint.level }.flatMap { it.lessons }
            levelLessons.isNotEmpty() && levelLessons.all { it.id in done } && !checkpointPassed(checkpoint)
        }
    }

    /** A finished checkpoint rides the existing `assessment` kind. */
    fun checkpointCompleted(id: String, percent: Int) {
        recordCompletion(ProgressEngine.ASSESSMENT, id, percent)
    }

    /** Error memory: a wrong exercise answer attributes one hit to every skill
        its lesson teaches (never to exposure-only structures). Local evidence
        for the recommender and the tutor's skill focus — not synced in v1. */
    fun recordExerciseMistake(lessonID: String, prompt: String) {
        val lesson = CourseRegistry.lesson(lessonID)
        if (lesson == null) {
            android.util.Log.w("LooroContent", "recordExerciseMistake: unknown lesson $lessonID")
            return
        }
        val skills = lesson.skills.filter { it in SkillsData.categories.keys }
        if (skills.isEmpty()) {
            android.util.Log.w("LooroContent", "recordExerciseMistake: no tagged skills for $lessonID")
            return
        }
        val errors = (archive.errors ?: mutableListOf()).associateBy { it.skillID }.toMutableMap()
        val now = nowSeconds()
        skills.forEach { skill ->
            val current = errors[skill]
            errors[skill] = SkillError(skill, (current?.count ?: 0) + 1, now, prompt.take(120))
        }
        archive = archive.copy(errors = errors.values.sortedByDescending { it.count }
            .take(ArchiveCodec.MAXIMUM_ERRORS).toMutableList())
        persist()
    }

    /** Repeated-error patterns for the current language, strongest first. */
    fun errorPatterns(): List<SkillError> = archive.errors.orEmpty()
        .filter { it.skillID in (SkillsData.categories.keys) }
        .sortedByDescending { it.count }

    /** Short prompt fragment naming the learner's most repeated error patterns. */
    fun errorFocus(): String = errorPatterns().take(3)
        .joinToString("; ") { "${it.skillID.substringAfter(':')} (${it.count})" }

    /** The next lesson to open: the first unattempted one whose prerequisite
        skills are already understood (mastery ≥ 60); fall back to the first
        unattempted lesson in course order. */
    fun recommendedLesson(): Lesson? {
        val course = foundationsCourse() ?: return null
        val mastery = masteryStats()?.skills?.associate { it.skillID to it.effectiveMastery } ?: emptyMap()
        val attempted = archive.lessons.filter { it.attempts > 0 }.map { it.lessonID }.toSet()
        val unattempted = course.units.flatMap { it.lessons }.filter { it.id !in attempted }
        return unattempted.firstOrNull { lesson ->
            lesson.prerequisites.all { prereq -> (mastery[prereq] ?: 0) >= 60 || prereq !in mastery }
        } ?: unattempted.firstOrNull()
    }

    /** Today's plan for a time budget, from due words, the recommended lesson
        and unplayed comprehension content. */
    fun dailyPlan(budget: Int): List<PlanStep> {
        val next = recommendedLesson()
        val unplayedListening = ListeningRegistry.forLanguage(language.id).any { listeningScore(it.id) == null }
        val unplayedReading = ReadingRegistry.forLanguage(language.id).any { readingScore(it.id) == null }
        return DailyPlanEngine.plan(budget, dueRecallCount(), next != null, unplayedListening, unplayedReading)
    }

    /** Push/pull the progress ledger against the account (guests included). Failures
        are silent for the user but logged for debugging — sync must never nag,
        the local ledger stays the source of truth. */
    fun syncProgress() {
        val client = progressClient ?: return
        viewModelScope.launch {
            try {
                val session = memberSessions?.read() ?: guests?.availableSession() ?: return@launch
                val remote = client.fetch(session)
                if (remote != null && mergeRemoteProgress(remote)) { bumpLedgerRevision(); persist() }
                client.send(session, progressBatchJson())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("LooroSync", "syncProgress failed", e)
            }
        }
    }

    private fun progressBatchJson(): String = buildJsonObject {
        putJsonArray("entries") {
            ProgressSync.entries(archive.lessons, archive.completions.orEmpty(),
                CourseRegistry.forLanguage(language.id), ListeningRegistry.forLanguage(language.id).map { it.id }.toSet(),
                language.id, ReadingRegistry.forLanguage(language.id).map { it.id }.toSet()).forEach { entry ->
                addJsonObject {
                    put("kind", entry.kind); put("itemID", entry.itemID); put("at", entry.atMilliseconds)
                    put("attempts", entry.attempts); put("bestScore", entry.bestScore); put("language", entry.language)
                }
            }
        }
    }.toString()

    /** Returns true when the pull changed the archive. */
    private fun mergeRemoteProgress(body: String): Boolean {
        val rows = try { json.parseToJsonElement(body).jsonObject.getValue("progress").jsonArray
            .map { it.jsonObject } } catch (_: Exception) { return false }
        val merged = rows.mapNotNull { row ->
            val kind = (row["kind"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val itemID = (row["itemID"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val firstAt = (row["firstAt"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lastAt = (row["lastAt"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val attempts = (row["attempts"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val bestScore = (row["bestScore"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val language = (row["language"] as? JsonPrimitive)?.contentOrNull
            RemoteProgress(kind, itemID, firstAt, lastAt, attempts, bestScore, language)
        }
        if (merged.isEmpty()) return false
        archive = ProgressSync.mergeRemote(archive, merged, language.id)
        return true
    }

    private data class SaveRequest(val snapshot: LearningSnapshot, val completion: CompletableDeferred<Unit>? = null)
    private val writes = Channel<SaveRequest>(Channel.UNLIMITED)
    private var storageReady = false
    private var finalAssessmentTickets: List<FinalAssessmentTicket> = emptyList()
    private val recoveredAssessmentIDs = mutableSetOf<String>()
    private val hostedFinalAssessmentJobs = mutableMapOf<String, Job>()
    private var connectionJob: Job? = null
    private var durationJob: Job? = null
    private var closeJob: Job? = null
    private var resetJob: Job? = null
    private var assessmentJob: Job? = null
    private var actionJob: Job? = null
    var scenarioReviewState by mutableStateOf<ScenarioReviewState?>(null); private set
    private var scenarioReviewJob: Job? = null
    private val meanings = MeaningController(viewModelScope, canRetryFailure = HostedHelperRetry::canRetryAtBoundary,
        retryDelay = HostedHelperRetry::automaticDelay) { request ->
        if (archive.preferences.aiConsentVersion != 1) throw IllegalStateException("AI processing consent is required.")
        val module = LanguageRegistry.get(request.learningLanguageID) ?: throw IllegalStateException("Unsupported language.")
        val result = teaching(request.sessionID, HelperPurpose.MEANING, request.cacheKey,
            TeachingPolicy.translation(module, request.meaningLanguage), request.text.takeLast(2200))
        MeaningResult(result.text, result.usage.input, result.usage.output)
    }
    private val finalAssessments = FinalAssessmentQueue(viewModelScope) { snapshot, passage -> requestAssessment(snapshot, passage) }
    private val languageDetector = LanguageDetector(application)
    private var languageCheckJob: Job? = null
    private var lastLanguageRedirect: String? = null
    private val delegations = mutableMapOf<String, Job>()
    private var voiceSession = false
    private var lastActivity = nowSeconds()
    private var generation = 0

    /**
     * Transport callbacks arrive from arbitrary threads (the Main connect flow,
     * the IO listen loop, the Default greeting/commentary coroutines) and mutate
     * session state. Funneling every signal through this single-consumer channel
     * makes handling effectively serial and confines those mutations to
     * viewModelScope (Main), so plain vars like [lastActivity] and [generation]
     * are never raced.
     */
    private sealed interface TransportSignal {
        class Event(val json: JsonObject) : TransportSignal
        class Failure(val message: String) : TransportSignal
        class Levels(val input: Double, val output: Double) : TransportSignal
    }
    private val transportSignals = Channel<TransportSignal>(Channel.UNLIMITED)

    init {
        guests?.let { controller -> viewModelScope.launch { controller.state.collect { guestState = it } } }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { repository.load() to credentials.hasKey }
                archive = loaded.first.archive
                val providers = providerStore.read(if (loaded.second) ConversationProvider.PERSONAL_KEY else ConversationProvider.HOSTED_MINUTES)
                hostedSessionIDs = providers.hostedIDs
                pendingHostedOwnerID = providers.pendingOwnerID
                accountChangeBlocked = providers.pendingOwnerID != null
                guests?.recoverAcknowledgedOwnerAtStartup(pendingHostedOwnerID,
                    clear = { owner -> clearAcknowledgedGuestMarker(owner) },
                    onFailure = { presentError(getApplication<Application>().getString(R.string.error_guest_secure_storage_unavailable)) })
                conversationProvider = providers.selection
                finalAssessmentTickets = ConversationProviderPolicy.recoveryTickets(loaded.first.finalAssessments, hostedSessionIDs)
                hasKey = loaded.second
                storageReady = true
                usageReporter.enabled = archive.preferences.usageMetricsEnabled != false && usageClient != null
                usageReporter.record(UsageEvents.APP_OPEN)
                viewModelScope.launch { usageReporter.flush() }
                syncProgress()
                recoverFinalAssessments()
                refreshHostedReadiness()
                if (accountChangeBlocked) reconcileHostedSessions()
            } catch (_: CancellationException) { }
            catch (_: Exception) {
                presentError(getApplication<Application>().getString(R.string.error_local_history_unavailable))
            } finally { loadingHistory = false }
        }
        viewModelScope.launch {
            for (first in writes) {
                var latest = first
                val completions = mutableListOf<CompletableDeferred<Unit>>()
                first.completion?.let(completions::add)
                // Coalesce transcript deltas while preserving every requested durability barrier.
                while (true) {
                    val next = writes.tryReceive().getOrNull() ?: break
                    latest = next
                    next.completion?.let(completions::add)
                }
                try {
                    withContext(Dispatchers.IO) { repository.save(latest.snapshot) }
                    completions.forEach { it.complete(Unit) }
                } catch (error: Exception) {
                    completions.forEach { it.completeExceptionally(error) }
                    if (error is CancellationException) throw error
                    presentError(getApplication<Application>().getString(R.string.error_save_progress_failed))
                }
            }
        }
        viewModelScope.launch {
            for (signal in transportSignals) {
                when (signal) {
                    is TransportSignal.Event ->
                        try { handle(signal.json) }
                        catch (_: IllegalArgumentException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
                        catch (_: IllegalStateException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
                    is TransportSignal.Failure -> fail(signal.message)
                    is TransportSignal.Levels -> {
                        inputLevel = signal.input; outputLevel = signal.output
                        if (signal.input > 0.03 || signal.output > 0.03) lastActivity = nowSeconds()
                    }
                }
            }
        }
        transport.onEvent = { event -> transportSignals.trySend(TransportSignal.Event(event)) }
        transport.onFailure = { message -> transportSignals.trySend(TransportSignal.Failure(message)) }
        transport.onLevels = { input, output -> transportSignals.trySend(TransportSignal.Levels(input, output)) }
        meanings.onChange = { meaning = meanings.text; translating = meanings.isLoading; meaningFailed = meanings.error != null }
        meanings.onResult = { request, result ->
            if (session?.id == request.sessionID) updateSession {
                it.translations[request.cacheKey] = result.text
                addUsage(it, APIUsage(result.inputTokens, result.outputTokens))
            }
        }
        finalAssessments.beforeAssessment = { snapshot, passage ->
            val current = archive.sessions.firstOrNull { it.id == snapshot.id }
            val ticket = finalAssessmentTickets.firstOrNull { it.matches(snapshot, passage) }
            if (snapshot.id in hostedSessionIDs) false
            else if (!storageReady || !hasKey || archive.preferences.aiConsentVersion != 1 || current == null ||
                ticket == null || !FinalAssessmentRecovery.canAttempt(ticket, current, nowSeconds())) false
            else {
                finalAssessmentTickets = finalAssessmentTickets.map {
                    if (it == ticket) it.copy(attempts = it.attempts + 1, lastAttemptAt = nowSeconds()) else it
                }
                persistAndWait()
                true
            }
        }
        finalAssessments.onResult = { result ->
            result.applying(archive.sessions.firstOrNull { it.id == result.sessionID })?.let { updated ->
                usageReporter.record(UsageEvents.ASSESSMENT_COMPLETED) {
                    put("passed", true); lessonCourseID(updated.lessonID)?.let { put("courseID", it) }
                }
                recordCompletion(ProgressEngine.ASSESSMENT, lessonCourseID(updated.lessonID) ?: "free", 100)
                save(updated)
                if (session?.id == updated.id) session = clone(updated)
                persistAndWait()
            }
        }
    }

    private fun recoverFinalAssessments() {
        if (!storageReady || !hasKey || archive.preferences.aiConsentVersion != 1) return
        val remaining = FinalAssessmentRecovery.MAX_RECOVERED_PER_LAUNCH - recoveredAssessmentIDs.size
        if (remaining <= 0) return
        FinalAssessmentRecovery.recover(archive.sessions, finalAssessmentTickets, nowSeconds())
            .filterNot { it.id in recoveredAssessmentIDs || it.id in hostedSessionIDs }.take(remaining)
            .forEach { if (finalAssessments.submit(clone(it))) recoveredAssessmentIDs += it.id }
    }

    private fun clone(s: SessionRecord): SessionRecord = s.copy(
        fragments = s.fragments.map { it.copy(previousTexts = it.previousTexts.toList()) }.toMutableList(),
        assessments = s.assessments.map { it.copy(words = it.words.toList()) }.toMutableList(),
        translations = s.translations.toMutableMap(),
        topics = s.topics.map { it.copy(sources = it.sources.toList()) }.toMutableList(),
    )
    private fun persistenceSnapshot() = LearningSnapshot(
        archive.copy(sessions = archive.sessions.toMutableList(), preferences = archive.preferences.copy()),
        finalAssessmentTickets.toList(),
    )
    private fun persist() {
        if (storageReady) writes.trySend(SaveRequest(persistenceSnapshot()))
    }
    private suspend fun persistAndWait() {
        check(storageReady)
        val completion = CompletableDeferred<Unit>()
        writes.send(SaveRequest(persistenceSnapshot(), completion))
        completion.await()
    }
    private fun save(s: SessionRecord) {
        finalAssessmentTickets = ConversationProviderPolicy.enqueueRecovery(s, finalAssessmentTickets, hostedSessionIDs)
        archive = archive.copy(sessions = (archive.sessions.filterNot { it.id == s.id } + clone(s)).toMutableList())
        persist()
    }
    private fun updateSession(change: (SessionRecord) -> Unit) {
        val current = session ?: return
        val next = clone(current); change(next); session = next; save(next)
    }
    private fun resolveMessage(e: Throwable, @StringRes fallback: Int): String {
        val app = getApplication<Application>()
        val res = errorMessageRes(e)
        val message = when {
            res == R.string.error_http_generic && e is APIClient.APIException.Http -> app.getString(res, e.status)
            res != 0 -> app.getString(res)
            e is LiveTransport.TransportException -> e.message ?: app.getString(fallback)
            else -> app.getString(fallback)
        }
        return requestErrorReference(e)?.let { message + "\n\n" + app.getString(R.string.hosted_error_reference, it) } ?: message
    }
    private fun presentError(message: String, needsKeySetup: Boolean = false) {
        error = message; errorNeedsKeySetup = needsKeySetup; errorNeedsAccountSignIn = false
    }
    private fun presentError(e: Throwable, @StringRes fallback: Int) {
        android.util.Log.e("AppLocalEngine", "voice/teaching failure: ${e.javaClass.simpleName}: ${e.message}", e)
        presentError(resolveMessage(e, fallback), errorNeedsKeySetup(e))
        errorNeedsAccountSignIn = needsAccountRecovery(e)
    }
    /**
     * Remote-compatible engines (deepseek/glm) never use the OpenAI credential
     * or hosted minutes: they are ready when their own provider key is
     * available (key-free engines are always ready). Surfaces the actionable
     * error and returns false otherwise.
     */
    data class LocalEngineStatus(val id: String, val label: String, val selected: Boolean,
                                 val hasCustomKey: Boolean, val hasKey: Boolean)

    private var enginesVersion by mutableStateOf(0)

    /** Local engines for the Settings selector; recomputed after each mutation. */
    val localEngines: List<LocalEngineStatus>
        get() {
            @Suppress("UNUSED_EXPRESSION") enginesVersion
            val selected = engineSelection.selected(BuildConfig.CONVERSATION_ENGINE)
            return ConversationEngineFactory.available.filter { it.id != "openai" }.map { info ->
                val hasCustom = engineOverrides.hasCustomKey(info.id)
                val env = com.jkdc.app.core.engines.localEngineKeyEnv(info.id)
                LocalEngineStatus(info.id, info.label, selected = selected == info.id,
                    hasCustomKey = hasCustom, hasKey = hasCustom || (env != null && engineKeyLookup(env) != null))
            }
        }

    /** Applies to new conversations; rejected while a conversation runs. */
    fun selectLocalEngine(id: String) {
        if (isRunning || BuildConfig.CONVERSATION_ENGINE == "openai") return
        runCatching { engineSelection.select(id) }.onSuccess { enginesVersion++ }
    }

    fun saveEngineKey(id: String, key: String): Boolean {
        if (isRunning) return false
        return engineOverrides.saveCustomKey(id, key).also { enginesVersion++ }
    }

    fun removeEngineKey(id: String) {
        if (isRunning) return
        engineOverrides.removeCustomKey(id); enginesVersion++
    }

    private fun engineKeyReady(): Boolean {
        val envVar = conversationEngine.keyEnvVar
        if (envVar != null && engineKeyLookup(envVar) == null) {
            presentError(getApplication<Application>().getString(R.string.error_missing_engine_key, envVar))
            return false
        }
        return true
    }
    private fun cloudReady(): Boolean {
        if (!storageReady) { presentError(getApplication<Application>().getString(R.string.error_resolve_local_history_first)); return false }
        if (archive.preferences.aiConsentVersion != 1) {
            presentError(getApplication<Application>().getString(R.string.error_accept_ai_consent)); return false
        }
        if (BuildConfig.CONVERSATION_ENGINE != "openai") return engineKeyReady()
        val currentHosted = session?.id in hostedSessionIDs
        if (!currentHosted && conversationProvider == ConversationProvider.PERSONAL_KEY && !hasKey) {
            presentError(getApplication<Application>().getString(R.string.error_missing_key), needsKeySetup = true); return false
        }
        return true
    }
    /** Called by the minutes/account UI after an explicit provider choice. No failure changes it. */
    fun selectConversationProvider(provider: ConversationProvider) {
        if (isRunning || !storageReady) return
        conversationProvider = provider
        viewModelScope.launch {
            try { providerStore.select(provider) }
            catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.provider_preference_save_failed)) }
        }
        if (provider == ConversationProvider.HOSTED_MINUTES) refreshHostedReadiness()
    }

    fun onAccountChanged(account: AccountState) {
        selectedAccount = account
        // Invalidate immediately. Slow guest or member reads may not restore access during a transition.
        readiness.selectAccount(null, true)
        accessJob?.cancel()
        if (!account.busy) refreshHostedReadiness()
    }
    fun refreshHostedReadiness() {
        if (selectedAccount.busy || !storageReady) return
        accessJob?.cancel()
        accessJob = viewModelScope.launch {
            try {
                val member = memberSessions?.read()?.takeIf { it.isValid(System.currentTimeMillis()) }
                if (member != null) {
                    clearAcknowledgedGuestMarker()
                    if (guests?.needsLink() == true) {
                        readiness.selectAccount(null, true)
                        if (!completeGuestSignIn() && guests.memberMaySpend(member.accountID) != true) return@launch
                    }
                    if (selectedAccount.busy) return@launch
                    readiness.selectAccount(member.accountID, false)
                    readiness.refresh()
                } else {
                    if (archive.preferences.aiConsentVersion != 1 || conversationProvider != ConversationProvider.HOSTED_MINUTES) {
                        readiness.selectAccount(null, false); return@launch
                    }
                    guests?.acquire()
                    if (selectedAccount.busy) return@launch
                    val guest = guests?.availableSession()
                    readiness.selectAccount(guest?.accountID, false)
                    readiness.refresh()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { readiness.selectAccount(null, false) }
        }
    }
    fun dismissMinuteAccess() { showMinuteAccess = false }
    fun needsMinuteAccess(): Boolean {
        // On-device/remote-compatible engines never use hosted minutes.
        if (BuildConfig.CONVERSATION_ENGINE != "openai") return false
        if (conversationProvider != ConversationProvider.HOSTED_MINUTES || isRunning) return false
        if (hostedReadiness.ready && !selectedAccount.busy) return false
        showMinuteAccess = true; refreshHostedReadiness(); return true
    }
    suspend fun prepareGuestCustodyForDeletion(memberID: String) {
        try { guests?.retireAcknowledgedLinkForDeletion(memberID) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw AccountFailure.SecureStorage }
    }

    /** Two encrypted stores are not crash-atomic. The durable guest receipt repairs only its marker. */
    private suspend fun clearAcknowledgedGuestMarker() {
        guests?.recoverAcknowledgedOwner(pendingHostedOwnerID) { owner -> clearAcknowledgedGuestMarker(owner) }
    }
    private suspend fun clearAcknowledgedGuestMarker(owner: String) {
        providerStore.clearPending()
        pendingHostedOwnerID = null; accountChangeBlocked = false
        hostedBindings.delegateOwnerRecovery(owner)
    }

    /** Member spending requires a durable server acknowledgment; guest transfer may finish later. */
    suspend fun completeGuestSignIn(): Boolean {
        val member = memberSessions?.read()?.takeIf { it.isValid(System.currentTimeMillis()) } ?: return false
        val guestOwner = guests?.retainedOwnerID()
        if (guestOwner != null && pendingHostedOwnerID == guestOwner) {
            if (isRunning) return false
            // Finish pending local provenance writes before handing remote recovery to the server.
            connectionJob?.join()
            reconciliationJob?.cancelAndJoin()
        }
        val accepted = guests?.linkTo(member, allowDeferred = true) ?: true
        val safe = accepted || guests?.memberMaySpend(member.accountID) == true
        if (safe && guestOwner != null && pendingHostedOwnerID == guestOwner) {
            // The retained GuestInstallation still owns its bearer and acknowledged member binding.
            // Server recovery owns the old lease; never close it with the new member bearer.
            withContext(NonCancellable) {
                providerStore.clearPending()
                pendingHostedOwnerID = null; accountChangeBlocked = false
                hostedBindings.delegateOwnerRecovery(guestOwner)
            }
        }
        if (!safe) showMinuteAccess = true
        return safe
    }
    private suspend fun availableHostedOwner(): AccountSession? {
        val member = memberSessions?.read()?.takeIf { it.isValid(System.currentTimeMillis()) }
        if (member != null) return member.takeIf { guests?.needsLink() != true || guests?.memberMaySpend(member.accountID) == true }
        return guests?.availableSession()
    }
    private suspend fun requireHostedOwner(ownerID: String? = null): AccountSession {
        // The pending guest lease must still be closable after Google has stored a member bearer.
        val guest = if (ownerID != null) guests?.sessionForSettlement(ownerID) else null
        return guest ?: availableHostedOwner()?.takeIf { ownerID == null || it.accountID == ownerID }
            ?: throw HostedFailure.SignInRequired
    }
    private suspend fun hostedBalance(owner: AccountSession): MinuteBalance =
        GuestMinuteClient(hostedConfiguration ?: throw HostedFailure.Unavailable).balance(owner)

    private fun hostedClient(ownerID: String): HostedAPIClient {
        val config = hostedConfiguration ?: throw HostedFailure.Unavailable
        return HostedAPIClient(config.origin, { requireHostedOwner(ownerID) }, okhttp3.OkHttpClient())
    }
    private fun binding(lease: HostedAPIClient.HostedLease) = HostedConversationBindings.Lease(
        lease.sessionID, lease.teaching, lease::requestClose, lease::status, lease.deadlineMilliseconds)

    /** The creating session, rather than the currently selected settings option, chooses every helper. */
    private suspend fun teaching(localID: String?, purpose: HelperPurpose, logicalID: String,
        instructions: String, input: String, schema: JsonObject? = null, search: Boolean = false): APIResult {
        if (archive.preferences.aiConsentVersion != 1) throw HostedFailure.Unavailable
        if (BuildConfig.CONVERSATION_ENGINE != "openai") {
            return conversationEngine.respond(instructions, input, schema, search, purpose)
        }
        if (localID != null && localID in hostedSessionIDs) {
            return hostedBindings.respond(localID, purpose, logicalID, instructions, input, schema, search)
        }
        if (localID == null && conversationProvider == ConversationProvider.HOSTED_MINUTES) throw HostedFailure.Unavailable
        return conversationEngine.respond(instructions, input, schema, search, purpose)
    }
    private fun helperContext(snapshot: SessionRecord, passage: Passage? = null): String =
        if (snapshot.id in hostedSessionIDs) ConversationHistory.helperContext(snapshot, passage)
        else TeachingPolicy.context(snapshot, passage)

    /** Renewing a member token must not turn a retained guest transfer back into an OAuth gate. */
    suspend fun settleRenewedMember(): Boolean {
        val pending = pendingHostedOwnerID ?: return true
        if (guests?.owns(pending) == true) return true
        return prepareForAccountChange()
    }

    /** Google identity can be added while guest accounting finishes; guest credentials stay separate. */
    suspend fun prepareForSignIn(): Boolean = AccountSignInPreparation(
        finishLocalWork = {
            if (!storageReady) {
                presentError(getApplication<Application>().getString(R.string.error_resolve_local_history_first))
                false
            } else {
                hostedBindings.disableHelpers(); meanings.reset()
                if (isRunning && (session?.id in hostedSessionIDs || conversationProvider == ConversationProvider.HOSTED_MINUTES)) {
                    updateSession { it.endReason = "Signing in" }; finish(false)
                }
                hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear()
                // Wait only for local startup/provenance persistence, never remote guest settlement.
                awaitLocalSignInStartup(connectionJob) {
                    presentError(getApplication<Application>().getString(R.string.error_sign_in_finishing_startup))
                }
            }
        },
        pendingOwner = { pendingHostedOwnerID },
        guestOwns = { guests?.owns(it) == true },
        prepareAccountChange = { prepareForAccountChange() },
    ).prepare()

    /** Root UI wiring must use this before sign-out, deletion, account switching, or session revocation. */
    suspend fun prepareForAccountChange(): Boolean {
        if (!storageReady) {
            presentError(getApplication<Application>().getString(R.string.error_resolve_local_history_first)); return false
        }
        hostedBindings.disableHelpers(); meanings.reset()
        hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear()
        if (!accountChangeBlocked) {
            val member = memberSessions?.read()?.takeIf { it.isValid(System.currentTimeMillis()) }
            if (member != null && guests?.needsLink() == true && !completeGuestSignIn()) return false
            return true
        }
        if (isRunning && (session?.id in hostedSessionIDs || conversationProvider == ConversationProvider.HOSTED_MINUTES)) {
            updateSession { it.endReason = "Account change" }; finish(false)
        }
        val finished = withTimeoutOrNull(10_000) { connectionJob?.join(); reconciliationJob?.join(); true } ?: false
        val ready = finished && settleHostedSessions()
        if (!ready) presentError(getApplication<Application>().getString(R.string.hosted_checking_previous))
        return ready
    }

    private fun reconcileHostedSessions() {
        if (reconciliationJob?.isActive == true) return
        reconciliationJob = viewModelScope.launch {
            // Wait for cancelled creation/provenance writes before clearing the durable pending marker.
            connectionJob?.join()
            if (settleHostedSessions()) refreshHostedReadiness()
        }
    }

    private suspend fun settleHostedSessions(): Boolean {
        val ownerID = pendingHostedOwnerID
        if (ownerID == null) { accountChangeBlocked = false; return true }
        accountChangeBlocked = true
        return try {
            val owner = requireHostedOwner(ownerID)
            if (owner.accountID != ownerID) return false
            for (id in hostedBindings.openSessionIDs.filter { hostedBindings.owner(it) == ownerID })
                if (!hostedBindings.closeAndConfirm(id)) return false
            // An interrupted create may have committed remotely without returning its lease locally.
            val client = hostedClient(ownerID)
            val current = client.currentSession()
            if (current != null) {
                try { current.requestClose() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                val closed = withTimeoutOrNull(8_000) {
                    while (current.status().state != "closed") delay(500)
                    true
                } ?: false
                if (!closed) return false
            }
            val balance = hostedBalance(owner)
            if (balance.reservedMilliseconds != 0L) return false
            guests?.recordSettledBalance(owner, balance)
            providerStore.clearPending()
            pendingHostedOwnerID = null; accountChangeBlocked = false
            readiness.refresh()
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    fun dismissError() { error = null; errorNeedsKeySetup = false; errorNeedsAccountSignIn = false }
    fun clearLookup() {
        lookupGeneration++
        lookupJob?.cancel()
        lookupJob = null; lookupResult = null; lookupError = null; lookupLoading = false
    }
    fun saveKey(key: String) {
        if (isRunning) return
        try { credentials.save(key); hasKey = credentials.hasKey; selectConversationProvider(ConversationProvider.PERSONAL_KEY); recoverFinalAssessments(); notice = getApplication<Application>().getString(R.string.notice_key_saved) }
        catch (e: Exception) { presentError(e, R.string.error_key_save_failed) }
    }
    fun deleteKey() {
        if (isRunning) return
        try { credentials.delete(); hasKey = false }
        catch (e: Exception) { presentError(e, R.string.error_key_delete_failed) }
        finally { hasKey = credentials.hasKey }
    }
    fun updatePreferences(preferences: Preferences) {
        val localEngine = BuildConfig.CONVERSATION_ENGINE != "openai"
        if (!storageReady) return
        if (isRunning) {
            // OpenAI voice sessions keep server-side state and stay gated (original behavior).
            // Local/remote-compatible sessions are cost-free: end them and apply right after.
            if (!localEngine) return
            end("Preferences changed")
            viewModelScope.launch {
                var waited = 0
                while (isRunning && waited < 6_000) { delay(100); waited += 100 }
                updatePreferences(preferences)
            }
            return
        }
        if (LanguageRegistry.get(preferences.learningLanguageID) == null || preferences.meaningLanguage !in MeaningLanguages.all || preferences.sessionMinutes !in 1..60) return
        val languageChanged = preferences.learningLanguageID != language.id
        actionJob?.cancel(); clearLookup(); working = false
        if (preferences.aiConsentVersion != 1) {
            finalAssessments.cancelAll(); hostedBindings.disableHelpers()
            hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear()
        }
        generation++; meanings.reset()
        if (languageChanged) resetConversation()
        val previousPreferences = archive.preferences
        archive = archive.copy(preferences = preferences.copy(interests = preferences.interests.take(500)))
        persist(); scheduleTranslation(); recoverFinalAssessments(); refreshHostedReadiness()
        if (previousPreferences.speechRate != preferences.speechRate) usageReporter.record(UsageEvents.TTS_SPEED_CHANGED) {
            put("rate", SpeechRates.idOf(preferences.speechRate))
        }
        usageReporter.enabled = preferences.usageMetricsEnabled != false && usageClient != null
    }
    fun selectLanguage(id: String) {
        if (!isRunning && LanguageRegistry.get(id) != null) updatePreferences(archive.preferences.copy(learningLanguageID = id))
    }
    fun chooseTheme(theme: ConversationTheme?) {
        if (!isRunning && session != null) resetConversation()
        selectedTheme = theme
        selectedLesson = null
        if (theme != null) usageReporter.record(UsageEvents.THEME_SELECTED) { put("themeID", theme.id) }
        if (state == "active") {
            updateSession { it.themeID = theme?.id; it.title = theme?.title ?: language.defaultTitle }
            command("instructions", TeachingPolicy.theme(theme, language))
        }
    }
    /** Marks an authored lesson as the next conversation; consent and microphone are handled by the caller. */
    fun selectLesson(lessonID: String) {
        if (isRunning) return
        val lesson = CourseRegistry.forLanguage(language.id).flatMap { it.units }.flatMap { it.lessons }
            .firstOrNull { it.id == lessonID } ?: return
        if (!isRunning && session != null) resetConversation()
        selectedTheme = null; topicResult = null
        selectedLesson = lesson
    }
    fun lessonAttempts(lessonID: String): Int = archive.lessons.firstOrNull { it.lessonID == lessonID }?.attempts ?: 0

    /** Marks a situation (curated or the learner's own) as the next practice; the
        caller handles consent and the microphone. No hot-switch while running. */
    fun chooseScenario(scenario: Scenario) {
        if (isRunning) return
        if (!isRunning && session != null) resetConversation()
        selectedTheme = null; selectedLesson = null; topicResult = null
        selectedScenario = scenario
    }

    /** Most recent reviewed attempt at this situation feeds the next prompt, so
        the coach deliberately drills the errors that were actually made. */
    private fun previousScenarioErrors(scenarioID: String): String = archive.sessions
        .lastOrNull { it.scenarioID == scenarioID && it.scenarioReview?.errors?.isNotEmpty() == true }
        ?.scenarioReview?.let(ScenarioReviewing::previousErrors) ?: ""

    fun dismissScenarioReview() { scenarioReviewJob?.cancel(); scenarioReviewState = null }

    /** Restarts the reviewed situation immediately; the review's verified errors
        flow back into the new session's prompt via previousScenarioErrors. */
    fun practiseAgain() {
        val scenarioID = (scenarioReviewState as? ScenarioReviewState.Ready)?.scenarioID
            ?: (scenarioReviewState as? ScenarioReviewState.Failed)?.scenarioID ?: return
        val scenario = selectedScenario?.takeIf { it.id == scenarioID } ?: Scenarios.byID(scenarioID) ?: return
        if (isRunning) return
        scenarioReviewJob?.cancel(); scenarioReviewState = null
        resetConversation()
        chooseScenario(scenario)
        start()
    }

    /** Replay the tutor's last spoken passage. In a voice session it goes through
        the transport (which pauses the mic, like a normal reply); otherwise the
        card reader speaks it — no microphone is live in typed sessions. */
    var replaying by mutableStateOf(false); private set
    private var replayJob: Job? = null
    fun replayReply(text: String) {
        if (replaying || working || lessonReading || text.isBlank()) return
        usageReporter.record(UsageEvents.REPLAY_USED)
        replaying = true
        replayJob = viewModelScope.launch {
            try {
                if (state == "active" && isVoiceSession) {
                    (transport as? com.jkdc.app.core.voice.LocalVoiceTransport)?.replay(text)
                } else if (lessonReader.initialize() && lessonReader.setLanguage(language.locale)) {
                    lessonReader.setSpeechRate(archive.preferences.effectiveSpeechRate().toFloat())
                    lessonReader.speak(text)
                }
            } catch (_: CancellationException) {
            } finally { replaying = false }
        }
    }
    /** Study-card read-aloud: local synthesis in the course language, independent of the conversation engine. */
    var lessonAudioOn by mutableStateOf(true); private set
    var lessonReading by mutableStateOf(false); private set
    private var lessonReadJob: Job? = null
    private val lessonReader by lazy { com.jkdc.app.engines.local.LocalTtsEngine(getApplication()) }
    fun setLessonAudio(on: Boolean) {
        lessonAudioOn = on
        if (!on) stopLessonReading()
    }
    fun readLessonCard(vararg parts: String) {
        if (!lessonAudioOn || lessonReading) return
        val text = parts.filter { it.isNotBlank() }.joinToString("   ")
        lessonReadJob = viewModelScope.launch {
            // A missing course-language voice degrades to silent reading (same rule as session TTS).
            if (!lessonReader.initialize() || !lessonReader.setLanguage(language.locale)) {
                lessonReading = false; return@launch
            }
            lessonReader.setSpeechRate(archive.preferences.effectiveSpeechRate().toFloat())
            lessonReading = true
            try { lessonReader.speak(text) } finally { lessonReading = false }
        }
    }
    fun stopLessonReading() {
        lessonReadJob?.cancel()
        lessonReader.stop()
        lessonReading = false
    }

    /** Listening section: speaks an authored text with the course voice at the
        chosen rate, sharing the study-card synthesizer (QUEUE_FLUSH semantics). */
    var listeningPlaying by mutableStateOf(false); private set
    fun playListeningText(text: String) {
        if (listeningPlaying || lessonReading || text.isBlank()) return
        lessonReadJob = viewModelScope.launch {
            // A missing course-language voice degrades to silence (same rule as session TTS).
            if (!lessonReader.initialize() || !lessonReader.setLanguage(language.locale)) {
                listeningPlaying = false; return@launch
            }
            lessonReader.setSpeechRate(archive.preferences.effectiveSpeechRate().toFloat())
            listeningPlaying = true
            try { lessonReader.speak(text) } finally { listeningPlaying = false }
        }
    }
    fun stopListeningText() {
        lessonReadJob?.cancel()
        lessonReader.stop()
        listeningPlaying = false
    }

    /** A finished listening exercise: one ledger mark with the score, one telemetry event. */
    fun listeningCompleted(itemID: String, correct: Int, total: Int) {
        if (total <= 0) return
        val percent = correct * 100 / total
        recordCompletion(ProgressEngine.LISTENING, itemID, percent)
        usageReporter.record(UsageEvents.LISTENING_COMPLETED) {
            put("itemID", ProgressEngine.completionKey(itemID)); put("scoreBucket", UsageBuckets.score(percent))
        }
    }

    fun listeningScore(itemID: String): Int? = archive.completions.orEmpty()
        .filter { it.kind == ProgressEngine.LISTENING && it.itemID == ProgressEngine.completionKey(itemID) }
        .maxOfOrNull { it.bestScore }
    fun toggleMeaning() {
        archive = archive.copy(preferences = archive.preferences.copy(meaningVisible = !archive.preferences.meaningVisible)); persist()
        meanings.reset()
        if (archive.preferences.meaningVisible) scheduleTranslation()
    }
    fun toggleMute() { if (state == "active" && voiceSession) { isMuted = !isMuted; transport.mute(isMuted) } }

    /** Reopen a past conversation as the active session. Continuation is typed
        (voice always starts a fresh conversation); new turns append to the same
        saved session, so its history keeps growing. */
    fun resumeSession(id: String) {
        if (isRunning || state == "connecting") return
        val saved = archive.sessions.firstOrNull { it.id == id } ?: return
        resetConversation()
        selectedTheme = null; selectedLesson = null; selectedScenario = null; topicResult = null
        session = saved.copy(
            fragments = saved.fragments.toMutableList(),
            assessments = saved.assessments.toMutableList(),
            translations = saved.translations.toMutableMap(),
            topics = saved.topics.toMutableList())
        voiceSession = false
        state = "active"
        lastActivity = nowSeconds()
        usageReporter.record(UsageEvents.SESSION_RESUMED)
        scheduleTranslation()
    }

    /** Conversation pause: stop the tutor's audio and stop listening, so the
        learner can hold a side conversation without the app processing it. */
    fun toggleVoicePause() {
        if (state != "active" || !isVoiceSession) return
        val pausing = !isMuted
        if (pausing) (transport as? com.jkdc.app.core.voice.LocalVoiceTransport)?.stopPlayback()
        toggleMute()
    }

    /** Beginner voice mode: the turn ends only when the user presses the button. */
    var voiceManualTurn by androidx.compose.runtime.mutableStateOf(
        BuildConfig.CONVERSATION_ENGINE != "openai" && voiceManualTurnStore.get())
        private set

    fun updateVoiceManualTurn(enabled: Boolean) {
        voiceManualTurn = enabled
        voiceManualTurnStore.set(enabled)
        (transport as? com.jkdc.app.core.voice.LocalVoiceTransport)?.manualTurn = enabled
    }

    /** Ventana de silencio que cierra el turno (solo pipeline de voz on-device). */
    var silenceEndMs by androidx.compose.runtime.mutableStateOf(voiceManualTurnStore.getSilenceEndMs())
        private set

    fun updateSilenceEndMs(ms: Long) {
        val clamped = ms.coerceIn(600, 3_000)
        voiceManualTurnStore.setSilenceEndMs(clamped)
        silenceEndMs = clamped
        (transport as? com.jkdc.app.core.voice.LocalVoiceTransport)?.silenceEndMs = clamped
    }

    fun endVoiceTurn() {
        (transport as? com.jkdc.app.core.voice.LocalVoiceTransport)?.endTurn()
    }

    fun help() {
        if (state != "active") return
        usageReporter.record(UsageEvents.HELP_REQUESTED)
        if (voiceSession) { command("instructions", TeachingPolicy.help(language)); notice = getApplication<Application>().getString(R.string.notice_help_simpler) }
        else {
            if (working || !cloudReady()) return
            val snapshot = session ?: return
            val token = generation; working = true
            actionJob = viewModelScope.launch {
                try {
                    val result = teaching(snapshot.id, HelperPurpose.HELP, UUID.randomUUID().toString(),
                        TeachingPolicy.help(language) + " Return only your brief explanation.", helperContext(snapshot))
                    if (token != generation || session?.id != snapshot.id || state != "active") return@launch
                    val offset = ((nowSeconds() - snapshot.startedAt) * 1000).toInt().coerceAtLeast(0)
                    updateSession {
                        it.append(Fragment(speaker = Speaker.assistant, text = result.text, startMS = offset, endMS = offset + 1))
                        addUsage(it, result.usage)
                    }
                    lastActivity = nowSeconds(); scheduleTranslation()
                } catch (_: CancellationException) { }
                catch (e: Exception) { if (token == generation) presentError(e, R.string.error_help_failed) }
                finally { if (token == generation) working = false }
            }
        }
    }

    private fun newSession(voice: Boolean, id: String = UUID.randomUUID().toString()) {
        generation++; resetJob?.cancel(); assessmentJob?.cancel(); actionJob?.cancel(); clearLookup(); working = false; meanings.reset()
        scenarioReviewJob?.cancel(); scenarioReviewState = null
        dismissError(); notice = null; isMuted = false
        voiceSession = voice; lastActivity = nowSeconds()
        val record = SessionRecord(id = id, languageID = language.id, themeID = selectedTheme?.id,
            title = selectedLesson?.title ?: selectedScenario?.title ?: selectedTheme?.title ?: language.defaultTitle,
            mode = when {
                selectedLesson != null -> "lesson"
                selectedScenario != null -> "scenario"
                voice && archive.preferences.listeningPractice == true -> "listening"
                else -> null
            },
            lessonID = selectedLesson?.id, scenarioID = selectedScenario?.id)
        topicResult?.takeIf { it.languageID == language.id && selectedTheme?.id == "current" }?.let { record.topics += it }
        session = record; save(record)
    }
    fun start() {
        if (isRunning || !storageReady) return
        if (archive.preferences.aiConsentVersion != 1) {
            presentError(getApplication<Application>().getString(R.string.error_accept_ai_consent)); return
        }
        val localVoice = BuildConfig.CONVERSATION_ENGINE != "openai"
        if (!localVoice) {
            if (!cloudReady()) return
        } else {
            // On-device voice needs no OpenAI gates, but a missing engine key
            // must fail before the session is created: the greeting would die
            // silently and the first spoken turn would surface the key error.
            if (!engineKeyReady()) return
        }
        val choice = conversationProvider
        if (!localVoice && choice == ConversationProvider.HOSTED_MINUTES && accountChangeBlocked) {
            presentError(getApplication<Application>().getString(R.string.hosted_checking_previous))
            reconcileHostedSessions(); return
        }
        if (!localVoice && !ConversationProviderPolicy.canStart(choice, hasKey, hostedReadiness)) {
            if (choice == ConversationProvider.HOSTED_MINUTES) {
                usageReporter.record(UsageEvents.PAYWALL_VIEWED) { put("surface", "talk") }
                showMinuteAccess = true; refreshHostedReadiness()
            } else presentError(getApplication<Application>().getString(R.string.error_missing_key), true)
            return
        }
        (transport as? com.jkdc.app.core.voice.LocalVoiceTransport)?.apply {
            manualTurn = voiceManualTurnStore.get()
            silenceEndMs = voiceManualTurnStore.getSilenceEndMs()
            speechRate = archive.preferences.effectiveSpeechRate().toFloat()
        }
        newSession(true); state = "connecting"
        usageReporter.record(UsageEvents.SESSION_STARTED) {
            put("input", "voice")
            put("provider", if (localVoice || choice == ConversationProvider.PERSONAL_KEY) "byok" else "hosted")
            if (localVoice) put("engine", BuildConfig.CONVERSATION_ENGINE)
        }
        session?.takeIf { it.isLesson }?.lessonID?.let { lessonID ->
            usageReporter.record(UsageEvents.LESSON_STARTED) {
                put("lessonID", lessonID); lessonCourseID(lessonID)?.let { put("courseID", it) }
            }
        }
        session?.takeIf { it.isScenario }?.scenarioID?.let { scenarioID ->
            usageReporter.record(UsageEvents.SCENARIO_STARTED) { put("scenarioID", scenarioID) }
        }
        val id = session!!.id
        val module = language
        val lesson = selectedLesson
        val scenario = selectedScenario
        val instructions = when {
            lesson != null -> TeachingPolicy.lessonPractice(lesson, module)
            scenario != null -> TeachingPolicy.scenarioPractice(scenario, module, learner, archive.preferences.interests,
                archive.preferences.meaningLanguage, slowSpeech = archive.preferences.effectiveSpeechRate() < 1.0,
                skillFocus = errorFocus(), previousErrors = previousScenarioErrors(scenario.id))
            else -> TeachingPolicy.voice(module, learner, selectedTheme, archive.preferences.interests,
                archive.preferences.meaningLanguage, slowSpeech = archive.preferences.effectiveSpeechRate() < 1.0,
                skillFocus = errorFocus())
        }
        val history = ConversationHistory.messages(session)
        if (!localVoice && choice == ConversationProvider.HOSTED_MINUTES) accountChangeBlocked = true
        connectionJob = viewModelScope.launch {
            try {
                val provider: LiveSessionProvider = if (localVoice || choice == ConversationProvider.PERSONAL_KEY) api else {
                    val owner = requireHostedOwner()
                    if (selectedAccount.busy || owner.accountID != hostedReadiness.accountID) throw HostedFailure.SignInRequired
                    val hosted = hostedClient(owner.accountID)
                    val balance = hostedBalance(owner)
                    if (!balance.canStartConversation || !hosted.available()) throw HostedFailure.Unavailable
                    // Commit provider provenance and the unresolved-owner marker before making a paid create.
                    hostedSessionIDs = hostedSessionIDs + id
                    pendingHostedOwnerID = owner.accountID
                    withContext(NonCancellable) { providerStore.markHosted(id, owner.accountID) }
                    object : LiveSessionProvider {
                        override suspend fun createLiveSession(request: LiveSessionRequest): LiveSessionConnection {
                            val result = hosted.createLiveSession(request.copy(requestedMilliseconds = archive.preferences.sessionMinutes * 60_000L))
                            val lease = result.lease as? HostedAPIClient.HostedLease ?: throw HostedFailure.InvalidResponse
                            withContext(NonCancellable + Dispatchers.Main.immediate) {
                                hostedBindings.bind(id, owner.accountID, binding(lease))
                                if (session?.id != id || state != "connecting") {
                                    hostedBindings.ended(id); reconcileHostedSessions()
                                }
                            }
                            return result
                        }
                    }
                }
                transport.connect(provider, instructions, history, module.locale)
            } catch (cancelled: CancellationException) {
                if (choice == ConversationProvider.HOSTED_MINUTES) reconcileHostedSessions()
                throw cancelled
            } catch (e: Exception) {
                if (session?.id == id && isRunning) fail(e, R.string.error_voice_connect_failed)
                if (choice == ConversationProvider.HOSTED_MINUTES) reconcileHostedSessions()
            }
        }
    }
    fun end(reason: String = "Ended by you") {
        if (state !in listOf("active", "connecting")) return
        val connecting = state == "connecting"
        state = "closing"; isMuted = true
        connectionJob?.cancel(); durationJob?.cancel(); assessmentJob?.cancel(); actionJob?.cancel(); clearLookup()
        delegations.values.toList().forEach { it.cancel() }; delegations.clear(); working = false
        updateSession { it.endReason = reason }
        if (!voiceSession || connecting) { finish(false); return }
        transport.close()
        closeJob = viewModelScope.launch { delay(5000); if (state == "closing") finish(false) }
    }
    fun background() {
        // Leaving the foreground ends the conversation and releases the microphone; its final assessment still completes.
        generation++; actionJob?.cancel(); clearLookup(); meanings.reset(); working = false
        if (isRunning) { updateSession { it.endReason = "App moved to background" }; finish(false) }
    }
    private fun finish(final: Boolean) {
        if (!isRunning) return
        connectionJob?.cancel(); durationJob?.cancel(); closeJob?.cancel(); assessmentJob?.cancel()
        actionJob?.cancel(); replayJob?.cancel(); clearLookup(); languageCheckJob?.cancel()
        delegations.values.toList().forEach { it.cancel() }; delegations.clear()
        transport.disconnect(); inputLevel = 0.0; outputLevel = 0.0; working = false; isMuted = false
        partialUserTranscript = null
        updateSession { it.endedAt = nowSeconds(); it.usageFinal = final }
        session?.takeIf { s -> s.isLesson }?.lessonID?.let(::recordLessonAttempt)
        session?.takeIf { !it.isLesson }?.themeID?.takeIf { it.isNotBlank() }?.let { recordCompletion(ProgressEngine.CONVERSATION_THEME, it) }
        session?.let { ended ->
            val seconds = ((ended.endedAt ?: nowSeconds()) - ended.startedAt).toInt().coerceAtLeast(0)
            val userTurns = ended.passages.count { it.speaker == Speaker.user }
            usageReporter.record(UsageEvents.SESSION_ENDED) {
                put("durationBucket", UsageBuckets.duration(seconds))
                put("turnsBucket", UsageBuckets.turns(userTurns))
                ended.themeID?.let { put("themeID", it) }
            }
            ended.takeIf { it.isScenario }?.scenarioID?.let { scenarioID ->
                recordCompletion(ProgressEngine.SCENARIO_PRACTICE, scenarioID)
                usageReporter.record(UsageEvents.SCENARIO_COMPLETED) {
                    put("scenarioID", scenarioID)
                    put("durationBucket", UsageBuckets.duration(seconds))
                    put("turnsBucket", UsageBuckets.turns(userTurns))
                }
            }
            ended.lessonID?.let { lessonID ->
                usageReporter.record(UsageEvents.LESSON_COMPLETED) {
                    put("lessonID", lessonID); lessonCourseID(lessonID)?.let { put("courseID", it) }
                }
            }
        }
        state = "ended"
        viewModelScope.launch { usageReporter.flush() }
        syncProgress()
        session?.let {
            if (it.id in hostedSessionIDs) {
                hostedBindings.ended(it.id); reconcileHostedSessions(); finishHostedAssessment(clone(it))
            } else finalAssessments.submit(clone(it))
        }
        session?.takeIf { it.isScenario }?.let { maybeGenerateScenarioReview(clone(it)) }
        scheduleTranslation(utteranceComplete = true)
        resetJob = viewModelScope.launch { delay(15000); if (state == "ended") resetConversation() }
    }
    /** One attempt per finished practice session; mastery is never claimed here. */
    private fun recordLessonAttempt(lessonID: String) {
        val lessons = archive.lessons.toMutableList()
        val i = lessons.indexOfFirst { it.lessonID == lessonID }
        if (i >= 0) lessons[i] = lessons[i].copy(attempts = lessons[i].attempts + 1, lastSeen = nowSeconds())
        else lessons += LessonState(lessonID = lessonID, attempts = 1, lastSeen = nowSeconds())
        archive = archive.copy(lessons = lessons, completions = ((archive.completions ?: mutableListOf()) +
            CompletionMark(ProgressEngine.LESSON, lessonID, nowSeconds(), 0, language.id))
            .sortedByDescending { it.at }.take(ArchiveCodec.MAXIMUM_COMPLETIONS).toMutableList())
        bumpLedgerRevision()
        CourseRegistry.lesson(lessonID)?.let { RecallEngine.seedFromLesson(archive, it, nowSeconds()) }
        persist()
    }

    // Spaced recall (spaced-recall-notebook): expressions seeded from lessons and
    // saved lookups, scheduled by RecallEngine on the word-bar ladder.
    fun dueRecallCount(): Int = RecallEngine.dueCount(archive, nowSeconds())
    fun recallQueue(): List<ExpressionState> = RecallEngine.due(archive, nowSeconds())
    fun notebookExpressions(): List<ExpressionState> = archive.expressions.sortedByDescending { it.lastSeen }
    fun saveLookupExpression(text: String, meaning: String): Boolean {
        val saved = RecallEngine.saveLookup(archive, text, meaning, nowSeconds())
        if (saved) persist()
        return saved
    }
    fun removeExpression(text: String) {
        // Copy first, mutate the copy: RecallEngine.remove mutates the list in
        // place, and the state setter skips element-wise-equal writes.
        val next = archive.copy(expressions = archive.expressions.toMutableList())
        RecallEngine.remove(next, text)
        archive = next
        persist()
    }
    fun gradeRecall(text: String, correct: Boolean) { RecallEngine.grade(archive, text, correct, nowSeconds()); persist() }
    private fun fail(message: String, needsKeySetup: Boolean = false) { finish(false); resetJob?.cancel(); state = "failed"; presentError(message, needsKeySetup) }
    private fun fail(e: Throwable, @StringRes fallback: Int) {
        fail(resolveMessage(e, fallback), errorNeedsKeySetup(e))
        errorNeedsAccountSignIn = needsAccountRecovery(e)
    }
    fun resetConversation() {
        if (isRunning) return
        generation++; resetJob?.cancel(); actionJob?.cancel(); clearLookup(); assessmentJob?.cancel(); languageCheckJob?.cancel(); meanings.reset()
        session = null; selectedTheme = null; selectedLesson = null; selectedScenario = null; topicResult = null
        notice = null; working = false; state = "idle"; voiceSession = false
        partialUserTranscript = null
    }
    private fun command(kind: String, content: String, delegationID: String? = null): Boolean {
        if (state != "active" || !voiceSession) return false
        return transport.send(buildJsonObject {
            put("type", "session.$kind.append"); put("event_id", UUID.randomUUID().toString())
            put("delegation_id", delegationID?.let(::JsonPrimitive) ?: JsonNull); put("content", content.take(1000))
        }).also { if (!it) notice = getApplication<Application>().getString(R.string.notice_update_send_failed) }
    }
    private fun handle(event: JsonObject) {
        if (session == null || !isRunning) return
        when (event["type"]?.jsonPrimitive?.content) {
            "app.session.created" -> updateSession { it.providerID = (event["session"] as? JsonObject)?.get("id")?.jsonPrimitive?.content; it.voiceSeconds = 15.0 }
            "session.started" -> if (state == "connecting") {
                state = "active"; lastActivity = nowSeconds()
                updateSession { it.providerID = (event["session"] as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: it.providerID }
                val lesson = session?.lessonID?.let { CourseRegistry.lesson(it) }
                command("instructions", when {
                    lesson != null -> TeachingPolicy.lessonOpening(lesson, language)
                    session?.isScenario == true -> TeachingPolicy.scenarioOpening(language)
                    session?.isListening == true -> TeachingPolicy.listeningStory(language)
                    else -> TeachingPolicy.greeting(language, selectedTheme)
                }); startDurationChecks()
            }
            "session.input_transcript.partial" -> {
                val text = event["delta"]?.jsonPrimitive?.contentOrNull ?: return
                if (text.length > 50000) return
                // An empty delta is the transport retracting a discarded turn.
                if (text.isEmpty()) { partialUserTranscript = null; return }
                partialUserTranscript = text
                lastActivity = nowSeconds()
            }
            "session.input_transcript.delta", "session.output_transcript.delta" -> {
                val text = event["delta"]?.jsonPrimitive?.contentOrNull ?: return
                val start = event["start_ms"]?.jsonPrimitive?.intOrNull ?: return
                val end = event["end_ms"]?.jsonPrimitive?.intOrNull ?: return
                if (start < 0 || end < start || text.length > 50000) return
                val speaker = if (event["type"]?.jsonPrimitive?.content == "session.input_transcript.delta") Speaker.user else Speaker.assistant
                updateSession { it.append(Fragment(id = event["event_id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(), speaker = speaker, text = text, startMS = start, endMS = end, meaningVisible = archive.preferences.meaningVisible)) }
                lastActivity = nowSeconds()
                if (speaker == Speaker.user) partialUserTranscript = null
                if (speaker == Speaker.assistant) { scheduleTranslation(); if (state == "active") checkLanguage() } else scheduleAssessment()
            }
            "session.delegation.created" -> {
                val d = event["delegation"] as? JsonObject ?: return
                if (d["target"]?.jsonPrimitive?.content == "client") d["id"]?.jsonPrimitive?.content?.let(::delegate)
            }
            "session.usage.updated", "session.closed" -> {
                val seconds = (event["usage"] as? JsonObject)?.get("seconds")?.jsonPrimitive?.doubleOrNull
                if (seconds != null && seconds.isFinite() && seconds in 0.0..31536000.0) updateSession { it.voiceSeconds = seconds }
                if (event["type"]?.jsonPrimitive?.content == "session.closed") finish(true)
            }
            "error" -> { notice = getApplication<Application>().getString(R.string.notice_voice_update_rejected) }
        }
    }
    private fun startDurationChecks() {
        durationJob?.cancel()
        val localVoiceSession = BuildConfig.CONVERSATION_ENGINE != "openai"
        durationJob = viewModelScope.launch {
            while (state == "active") {
                delay(5000)
                val current = session ?: break
                if (current.id in hostedSessionIDs && hostedBindings.reachedDeadline(current.id)) {
                    updateSession { it.endReason = "Reserved conversation time ended" }; finish(false); break
                }
                // Cost-free engines: sessions only end when the user ends them —
                // a slow reply must never cost them the conversation.
                if (!localVoiceSession && nowSeconds() - current.startedAt > archive.preferences.sessionMinutes * 60) {
                    notice = getApplication<Application>().getString(R.string.notice_time_limit_reached); end("Time limit"); break
                }
                // Local voice is cost-free and turns can outlast the inactivity window
                // (slow devices); only WebRTC sessions auto-close on idle.
                if (!localVoiceSession && SessionLimits.endsForInactivity(voiceSession, nowSeconds() - lastActivity)) {
                    notice = getApplication<Application>().getString(R.string.notice_ended_inactivity); end("Inactivity"); break
                }
            }
        }
    }

    private fun addUsage(s: SessionRecord, usage: APIUsage) {
        s.inputTokens = (s.inputTokens.toLong() + usage.input).coerceAtMost(1_000_000_000).toInt()
        s.outputTokens = (s.outputTokens.toLong() + usage.output).coerceAtMost(1_000_000_000).toInt()
        s.searchCalls = (s.searchCalls.toLong() + usage.searches).coerceAtMost(1_000_000_000).toInt()
    }
    private fun scheduleTranslation(utteranceComplete: Boolean = state == "ended") {
        // Meanings are a plain chat completion on every engine (the conversation
        // never queues behind them on remote engines), so the learning loop works
        // identically with openai, deepseek and glm. Listening-practice sessions
        // hide subtitles for the whole exercise; word lookup stays available.
        if (!archive.preferences.meaningVisible || archive.preferences.aiConsentVersion != 1) return
        val current = session ?: return
        if (current.isListening) return
        // A meaning in the learning language just repeats the passage on screen.
        if (archive.preferences.meaningLanguage == language.name) { meaning = ""; translating = false; return }
        val passage = current.passages.lastOrNull { it.speaker == Speaker.assistant } ?: return
        val request = MeaningRequest(current.id, passage, current.languageID, archive.preferences.meaningLanguage)
        meanings.update(request, current.translations[request.cacheKey], utteranceComplete, conversationEnded = state == "ended")
    }
    fun retryMeaning() { scheduleTranslation(); meanings.retry() }
    private fun checkLanguage() {
        val passage = session?.passages?.lastOrNull { it.speaker == Speaker.assistant }
        if (!LanguageDetector.shouldCheck(passage, lastLanguageRedirect) || languageCheckJob?.isActive == true) return
        val module = language; val passageID = passage!!.id; val text = passage.text
        languageCheckJob = viewModelScope.launch {
            val detected = languageDetector.detect(text) ?: return@launch
            if (state == "active" && lastLanguageRedirect != passageID &&
                TeachingPolicy.shouldRedirectSpeech(module, detected.languageID, detected.confidence)) {
                lastLanguageRedirect = passageID
                command("instructions", TeachingPolicy.redirect(module))
            }
        }
    }
    @Serializable private data class AssessmentResponse(val outcome: Outcome, val suggestedLevel: Int, val nextGoal: String, val capability: String, val words: List<WordProposal>)
    private fun assessmentSchema(id: String): JsonObject {
        fun string() = buildJsonObject { put("type", "string") }
        fun obj(fields: Map<String, JsonElement>) = buildJsonObject {
            put("type", "object"); put("properties", JsonObject(fields)); put("required", JsonArray(fields.keys.map(::JsonPrimitive))); put("additionalProperties", false)
        }
        fun enumeration(values: List<String>) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
        return obj(mapOf(
            "outcome" to enumeration(Outcome.entries.map { it.name }),
            "suggestedLevel" to buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 5) },
            "nextGoal" to string(), "capability" to string(),
            "words" to buildJsonObject {
                put("type", "array"); put("maxItems", 12)
                put("items", obj(mapOf("lemma" to string(), "meaning" to string(), "form" to string(), "quote" to string(),
                    "language" to enumeration(listOf(id, "en", "mixed", "uncertain").distinct()),
                    "kind" to enumeration(EvidenceKind.entries.map { it.name }),
                    "confidence" to buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 1) },
                    "sourceIDs" to buildJsonObject { put("type", "array"); put("items", string()) }
                )))
            }
        ))
    }
    private suspend fun requestAssessment(snapshot: SessionRecord, passage: Passage): FinalAssessmentResult {
        if (archive.preferences.aiConsentVersion != 1) throw IllegalStateException("AI processing consent is required.")
        val module = LanguageRegistry.get(snapshot.languageID) ?: throw IllegalStateException("Unsupported language.")
        val result = teaching(snapshot.id, HelperPurpose.ASSESSMENT, passage.revisionKey,
            TeachingPolicy.assessment(module), helperContext(snapshot, passage), assessmentSchema(module.id))
        val decoded = json.decodeFromString<AssessmentResponse>(result.text)
        val proposal = Assessment(passage.id, passage.revisionKey, decoded.outcome, decoded.suggestedLevel, decoded.nextGoal, decoded.capability, decoded.words, context = snapshot.themeID ?: "free")
        return FinalAssessmentResult(snapshot.id, snapshot.languageID, proposal, result.usage.input, result.usage.output, result.usage.searches)
    }
    /** Hosted finalization awaits the original helper for its remaining lease window, without entering BYOK recovery. */
    private fun finishHostedAssessment(snapshot: SessionRecord) {
        val passage = FinalAssessmentRecovery.passage(snapshot) ?: return
        if (hostedFinalAssessmentJobs.containsKey(snapshot.id) || !hostedBindings.canAssess(snapshot.id)) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(HostedConversationBindings.POST_END_MILLIS) {
                    val result = requestAssessment(snapshot, passage)
                    if (archive.preferences.aiConsentVersion != 1) return@withTimeout
                    result.applying(archive.sessions.firstOrNull { it.id == result.sessionID })?.let { updated ->
                        save(updated)
                        if (session?.id == updated.id) session = clone(updated)
                        persistAndWait()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep uncertain evidence unassessed; never submit a replacement request. */ }
            finally { hostedFinalAssessmentJobs.remove(snapshot.id) }
        }
        hostedFinalAssessmentJobs[snapshot.id] = job
        job.start()
    }

    @Serializable private data class ScenarioReviewResponse(val strengths: List<String> = emptyList(),
        val errors: List<ReviewedError> = emptyList(), val keyPhrases: List<ReviewedPhrase> = emptyList(),
        val retryFocus: String = "")
    private fun scenarioReviewSchema(): JsonObject {
        fun string(maxLength: Int? = null) = buildJsonObject {
            put("type", "string"); maxLength?.let { put("maxLength", it) }
        }
        fun obj(fields: Map<String, JsonElement>) = buildJsonObject {
            put("type", "object"); put("properties", JsonObject(fields)); put("required", JsonArray(fields.keys.map(::JsonPrimitive))); put("additionalProperties", false)
        }
        return obj(mapOf(
            "strengths" to buildJsonObject { put("type", "array"); put("maxItems", ScenarioReviewing.MAXIMUM_STRENGTHS); put("items", string(200)) },
            "errors" to buildJsonObject {
                put("type", "array"); put("maxItems", ScenarioReviewing.MAXIMUM_ERRORS)
                put("items", obj(mapOf(
                    "quote" to string(200), "correction" to string(200),
                    "kind" to buildJsonObject { put("type", "string"); put("enum", JsonArray(ScenarioReviewing.KINDS.map(::JsonPrimitive))) },
                    "note" to string(200),
                    "sourceIDs" to buildJsonObject { put("type", "array"); put("maxItems", ScenarioReviewing.MAXIMUM_SOURCE_IDS); put("items", string()) },
                )))
            },
            "keyPhrases" to buildJsonObject {
                put("type", "array"); put("maxItems", ScenarioReviewing.MAXIMUM_KEY_PHRASES)
                put("items", obj(mapOf("phrase" to string(120), "meaning" to string(160))))
            },
            "retryFocus" to string(200),
        ))
    }

    /** Runs once after a situation session ends: the review helper grades the
        whole learner transcript against a strict schema, the client verifies
        every quote against the real fragments, and the result is stored on the
        session and surfaced in the review sheet. Hosted sessions ride the same
        120 s post-end window as the final assessment. */
    private fun maybeGenerateScenarioReview(snapshot: SessionRecord) {
        val scenarioID = snapshot.scenarioID ?: return
        if (snapshot.passages.count { it.speaker == Speaker.user } < 2) return
        val title = selectedScenario?.takeIf { it.id == scenarioID }?.title
            ?: Scenarios.byID(scenarioID)?.title ?: snapshot.title
        scenarioReviewJob?.cancel()
        scenarioReviewState = ScenarioReviewState.Preparing(snapshot.id, scenarioID, title)
        scenarioReviewJob = viewModelScope.launch {
            try {
                val module = LanguageRegistry.get(snapshot.languageID) ?: throw IllegalStateException("Unsupported language.")
                val result = withTimeout(HostedConversationBindings.POST_END_MILLIS) {
                    teaching(snapshot.id, HelperPurpose.SCENARIO_REVIEW, UUID.randomUUID().toString(),
                        TeachingPolicy.scenarioReview(module, archive.preferences.meaningLanguage),
                        TeachingPolicy.reviewContext(snapshot), scenarioReviewSchema())
                }
                val parsed = ScenarioReviewing.parse(result.text) ?: throw IllegalStateException("scenario review was not valid JSON")
                val review = ScenarioReviewing.validate(parsed, snapshot)
                archive.sessions.firstOrNull { it.id == snapshot.id }?.let { stored ->
                    stored.scenarioReview = review
                    save(stored)
                    if (session?.id == stored.id) session = clone(stored)
                }
                if (storageReady) persistAndWait()
                if ((scenarioReviewState as? ScenarioReviewState.Preparing)?.sessionID == snapshot.id)
                    scenarioReviewState = ScenarioReviewState.Ready(snapshot.id, scenarioID, title, review)
            } catch (timeout: TimeoutCancellationException) {
                if ((scenarioReviewState as? ScenarioReviewState.Preparing)?.sessionID == snapshot.id)
                    scenarioReviewState = ScenarioReviewState.Failed(snapshot.id, scenarioID, title)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if ((scenarioReviewState as? ScenarioReviewState.Preparing)?.sessionID == snapshot.id)
                    scenarioReviewState = ScenarioReviewState.Failed(snapshot.id, scenarioID, title)
            }
        }
    }

    private fun scheduleAssessment() {
        // Local LLM: per-turn assessments are full generations; running them
        // after every user turn starves the conversation. Final assessments
        // (on session end) still run so learning stats keep working.
        if (BuildConfig.CONVERSATION_ENGINE != "openai" && state == "active") return
        assessmentJob?.cancel()
        assessmentJob = viewModelScope.launch {
            delay(3000)
            val snapshot = session?.let(::clone) ?: return@launch
            val passage = snapshot.passages.lastOrNull { it.speaker == Speaker.user && it.text.length >= 3 } ?: return@launch
            if (snapshot.assessments.any { it.passageID == passage.id && it.revisionKey == passage.revisionKey }) return@launch
            try {
                val result = requestAssessment(snapshot, passage)
                val current = archive.sessions.firstOrNull { it.id == snapshot.id && it.languageID == snapshot.languageID } ?: return@launch
                val valid = LearningEngine.validate(result.assessment, current) ?: return@launch
                val updated = clone(current)
                updated.assessments.removeAll { it.passageID == valid.passageID }; updated.assessments += valid
                addUsage(updated, APIUsage(result.inputTokens, result.outputTokens, result.searchCalls)); save(updated)
                if (session?.id == updated.id) session = clone(updated)
                if (state == "active" && session?.id == snapshot.id) {
                    val progress = learner
                    val targetLanguage = LanguageRegistry.get(snapshot.languageID)?.name ?: language.name
                    val revisit = progress.words.filter { it.dueAt < nowSeconds() }.take(3).joinToString(", ") { it.lemma }
                    command("thinking", "Teaching context, not spoken text: challenge ${progress.challenge}/5 in $targetLanguage. Next goal: ${progress.nextGoal}. Revisit naturally: $revisit.")
                }
            } catch (_: CancellationException) { } catch (_: Exception) { /* No unverified progress. */ }
        }
    }
    private fun delegate(id: String) {
        if (state != "active" || delegations.containsKey(id)) return
        val sessionID = session?.id ?: return
        delegations[id] = viewModelScope.launch {
            try {
                delay(500)
                val snapshot = session ?: return@launch
                val result = teaching(snapshot.id, HelperPurpose.DELEGATION, id,
                    TeachingPolicy.delegation(language), helperContext(snapshot), search = snapshot.searchCalls < 3)
                if (state != "active" || session?.id != sessionID) return@launch
                updateSession { addUsage(it, result.usage); if (result.sources.isNotEmpty()) it.topics += TopicBrief(languageID = it.languageID, query = getApplication<Application>().getString(R.string.topics_from_conversation), text = result.text, sources = result.sources) }
                command("commentary", result.text, id)
            } catch (_: CancellationException) { }
            catch (_: Exception) { if (session?.id == sessionID) command("commentary", language.lookupUnavailableReply, id) }
            finally { delegations.remove(id) }
        }
    }
    fun sendTyped(text: String) {
        val localOnly = BuildConfig.CONVERSATION_ENGINE != "openai"
        val clean = text.trim().take(2000)
        if (clean.isEmpty() || working || state in listOf("connecting", "closing")) return
        if (!localOnly && !cloudReady()) return
        if (state != "active") {
            if (!localOnly && conversationProvider == ConversationProvider.HOSTED_MINUTES) {
                presentError(getApplication<Application>().getString(R.string.hosted_typed_start)); return
            }
            newSession(false); state = "active"; startDurationChecks()
            usageReporter.record(UsageEvents.SESSION_STARTED) {
                put("input", "typed")
                put("provider", if (BuildConfig.CONVERSATION_ENGINE != "openai" ||
                    conversationProvider == ConversationProvider.PERSONAL_KEY) "byok" else "hosted")
                if (BuildConfig.CONVERSATION_ENGINE != "openai") put("engine", BuildConfig.CONVERSATION_ENGINE)
            }
        }
        val id = session!!.id; val token = generation
        val offset = ((nowSeconds() - session!!.startedAt) * 1000).toInt().coerceAtLeast(0)
        updateSession { it.append(Fragment(speaker = Speaker.user, text = clean, startMS = offset, endMS = offset + 1, meaningVisible = archive.preferences.meaningVisible, typed = true)) }
        lastActivity = nowSeconds(); working = true
        actionJob = viewModelScope.launch {
            try {
                val lesson = session?.lessonID?.let { CourseRegistry.lesson(it) }
                val scenario = session?.scenarioID?.let { id -> selectedScenario?.takeIf { it.id == id } ?: Scenarios.byID(id) }
                val instructions = when {
                    voiceSession -> TeachingPolicy.typedReply(language, lesson, scenario)
                    scenario != null -> TeachingPolicy.scenarioPractice(scenario, language, learner, archive.preferences.interests,
                        archive.preferences.meaningLanguage, skillFocus = errorFocus(), previousErrors = previousScenarioErrors(scenario.id)) +
                        "\n" + TeachingPolicy.typedReply(language, lesson, scenario)
                    else -> TeachingPolicy.voice(language, learner, selectedTheme, archive.preferences.interests, archive.preferences.meaningLanguage, skillFocus = errorFocus()) + "\n" + TeachingPolicy.typedReply(language, lesson)
                }
                val result = teaching(id, HelperPurpose.TYPED_REPLY, UUID.randomUUID().toString(), instructions, helperContext(session!!))
                if (token != generation || session?.id != id || state != "active") return@launch
                updateSession { addUsage(it, result.usage) }
                if (voiceSession) { command("thinking", "The learner typed (data): ${clean.take(650)}"); command("commentary", result.text) }
                else {
                    val end = ((nowSeconds() - session!!.startedAt) * 1000).toInt().coerceAtLeast(offset + 2)
                    updateSession { it.append(Fragment(speaker = Speaker.assistant, text = result.text, startMS = end, endMS = end + 1)) }
                    scheduleTranslation()
                }
                scheduleAssessment()
            } catch (_: CancellationException) { }
            catch (e: Exception) { if (session?.id == id) presentError(e, R.string.error_send_message_failed) }
            finally { if (token == generation) working = false }
        }
    }
    fun lookup(word: String, sentence: String) {
        if (!cloudReady() || word.isBlank()) return
        // No term is ever recorded — only that a lookup happened, and from where.
        usageReporter.record(UsageEvents.WORD_LOOKUP) { put("source", if (sentence.isBlank()) "words" else "talk") }
        pendingLookupOpen = true
        clearLookup()
        val token = generation; val id = session?.id; val request = lookupGeneration
        lookupLoading = true
        lookupJob = viewModelScope.launch {
            try {
                val result = teaching(id, HelperPurpose.LOOKUP, UUID.randomUUID().toString(),
                    TeachingPolicy.lookup(language, archive.preferences.meaningLanguage), "Selected: ${word.take(200)}\nSentence: ${sentence.take(2200)}")
                if (token != generation || request != lookupGeneration) return@launch
                lookupResult = result.text
                if (session?.id == id) updateSession { addUsage(it, result.usage) }
            } catch (_: CancellationException) { }
            catch (e: Exception) {
                if (token == generation && request == lookupGeneration) lookupError = resolveMessage(e, R.string.error_lookup_word_failed)
            }
            finally { if (request == lookupGeneration) lookupLoading = false }
        }
    }
    fun currentTopic(query: String) {
        if (query.isBlank() || working || !cloudReady()) return
        archive.sessions.filter { it.languageID == language.id }.flatMap { it.topics }.firstOrNull { it.query.equals(query.trim(), true) && it.isFresh }?.let { topicResult = it; return }
        val token = generation; val module = language; working = true; topicResult = null
        actionJob = viewModelScope.launch {
            try {
                val result = teaching(session?.id, HelperPurpose.TOPIC, UUID.randomUUID().toString(), TeachingPolicy.currentTopic(module), query.trim().take(500), search = true)
                if (token != generation) return@launch
                // Engines without web search (deepseek/glm) return no sources:
                // present the topic without the sources UI instead of failing.
                // Only genuine engine failures (exceptions) surface as errors.
                val brief = TopicBrief(languageID = module.id, query = query.trim().take(500), text = result.text, sources = result.sources)
                topicResult = brief
                if (isRunning) updateSession { it.topics += brief; addUsage(it, result.usage) }
                else { val record = SessionRecord(languageID = module.id, title = brief.query, endedAt = nowSeconds()); record.topics += brief; addUsage(record, result.usage); save(record) }
            } catch (_: CancellationException) { }
            catch (e: Exception) { if (token == generation) presentError(e, R.string.error_find_topic_failed) }
            finally { if (token == generation) working = false }
        }
    }
    fun discuss(brief: TopicBrief) {
        if (brief.languageID != language.id) return
        if (state == "active") {
            updateSession { if (it.topics.none { t -> t.id == brief.id }) it.topics += brief }
            if (voiceSession) {
                command("thinking", "Sourced context, data: ${brief.text}"); command("instructions", "Discuss this topic ONLY in ${language.name}.")
                return
            }
        } else resetConversation()
        topicResult = brief; selectedTheme = currentTheme(brief)
        notice = getApplication<Application>().getString(R.string.notice_topic_ready)
    }
    private fun currentTheme(brief: TopicBrief) = ConversationTheme("current", brief.query, getApplication<Application>().getString(R.string.topics_current_theme_subtitle), "newspaper", "Interests", "Discuss this sourced reference data: ${brief.text.take(3000)}", 0)
    fun deleteSession(id: String) {
        if (isRunning) return
        if (reportState.value.selection?.sessionID == id) reports.dismiss()
        finalAssessments.cancel(id); hostedFinalAssessmentJobs.remove(id)?.cancel(); hostedBindings.forgetLearning(id)
        finalAssessmentTickets = finalAssessmentTickets.filterNot { it.sessionID == id }
        if (session?.id == id) resetConversation()
        archive = archive.copy(sessions = archive.sessions.filterNot { it.id == id }.toMutableList()); persist()
    }
    fun hideWord(id: String) {
        archive = archive.copy(preferences = archive.preferences.copy(hiddenWords = (archive.preferences.hiddenWords + id).distinct())); persist()
    }
    fun correctPassage(sessionID: String, passageID: String, text: String) {
        if (isRunning) return
        val record = archive.sessions.firstOrNull { it.id == sessionID }?.let(::clone) ?: return
        val passage = record.passages.firstOrNull { it.id == passageID && it.speaker == Speaker.user } ?: return
        finalAssessments.cancel(sessionID); hostedFinalAssessmentJobs.remove(sessionID)?.cancel(); generation++; meanings.reset()
        passage.fragments.forEachIndexed { index, f -> record.correctFragment(f.id, if (index == 0) text.take(10000) else "") }
        save(record); if (session?.id == record.id) session = record
    }
    fun deleteLearningData() {
        if (isRunning || !storageReady) return
        reports.dismiss()
        finalAssessments.cancelAll(); hostedSessionIDs.forEach(hostedBindings::forgetLearning)
        hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear(); resetConversation()
        finalAssessmentTickets = emptyList()
        archive = archive.copy(sessions = mutableListOf(), preferences = archive.preferences.copy(hiddenWords = emptyList())); persist()
    }
    fun exportData(): String = ArchiveCodec.encode(archive)
    fun importData(data: String): Boolean {
        if (isRunning || !storageReady) return false
        return try { archive = ArchiveCodec.merge(archive, prepareImportedArchive(data)); bumpLedgerRevision(); persist(); notice = getApplication<Application>().getString(R.string.notice_backup_imported); true }
        catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.error_import_failed)); false }
    }
    override fun onCleared() { hostedBindings.disableHelpers(); transport.disconnect(); super.onCleared() }
}
