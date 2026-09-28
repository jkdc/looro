package com.jkdc.app.core

/** Progress metrics projected from the completion ledger; a pure projection, never stored. */
data class CourseProgress(val courseID: String, val title: String, val lessonsDone: Int, val lessonsTotal: Int) {
    val percent: Int get() = if (lessonsTotal == 0) 0 else lessonsDone * 100 / lessonsTotal
}

data class ProgressStats(
    val courses: List<CourseProgress>, val lessonsDone: Int, val lessonsTotal: Int,
    val activeStreakDays: Int, val longestStreakDays: Int, val last30DaysActivities: List<Int>,
    val themesPractised: Int, val exercisesCompleted: Int, val listeningCompleted: Int,
    val practiceRounds: Int, val wordsPracticeItems: Int, val assessmentsPassed: Int,
)

/** The completion catalog every surface shares: the local ledger, the server
    `user_progress.kind` CHECK and the sync wire all use exactly these names. */
object ProgressEngine {
    const val LESSON = "lesson"
    const val CONVERSATION_THEME = "conversation_theme"
    const val EXERCISE_SET = "exercise_set"
    const val LISTENING = "listening"
    const val RECALL_ROUND = "recall_round"
    const val WORDS_PRACTICE = "words_practice"
    const val ASSESSMENT = "assessment"
    val kinds = listOf(LESSON, CONVERSATION_THEME, EXERCISE_SET, LISTENING, RECALL_ROUND, WORDS_PRACTICE, ASSESSMENT)

    private val SLUG = Regex("[a-z0-9][a-z0-9_-]{0,63}")

    /** Stable ledger/wire key: readable slugs pass through; anything else (a word
        lemma with accents or spaces) collapses to 16 hex chars of SHA-256. */
    fun completionKey(raw: String): String =
        if (SLUG.matches(raw)) raw
        else java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    fun stats(sessions: List<SessionRecord>, lessons: List<LessonState>, completions: List<CompletionMark>,
              courses: List<Course>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), now: Double = nowSeconds()): ProgressStats {
        fun epochDay(seconds: Double): Long = java.time.Instant.ofEpochSecond((seconds + APPLE_EPOCH_UNIX_SECONDS).toLong()).atZone(zone).toLocalDate().toEpochDay()
        val done = lessons.filter { it.attempts > 0 }.map { it.lessonID }.toSet()
        val courseProgress = courses.map { course ->
            val all = course.units.flatMap { it.lessons }
            CourseProgress(course.id, course.title, all.count { it.id in done }, all.size)
        }
        val today = epochDay(now)
        val activeDays = buildSet {
            completions.forEach { add(epochDay(it.at)) }
            sessions.filter { it.voiceSeconds > 0 }.forEach { add(epochDay(it.startedAt)) }
        }
        var active = 0
        var cursor = if (today in activeDays) today.toLong() else today - 1
        while (cursor in activeDays) { active++; cursor-- }
        var longest = 0; var run = 0; var previous = Long.MIN_VALUE
        for (day in activeDays.sorted()) { run = if (day == previous + 1) run + 1 else 1; if (run > longest) longest = run; previous = day }
        val byKind = completions.groupBy { it.kind }
        fun distinctItems(kind: String) = byKind[kind].orEmpty().map { it.itemID }.toSet().size
        return ProgressStats(
            courses = courseProgress, lessonsDone = courseProgress.sumOf { it.lessonsDone },
            lessonsTotal = courseProgress.sumOf { it.lessonsTotal },
            activeStreakDays = active, longestStreakDays = longest,
            last30DaysActivities = (29 downTo 0).map { offset -> completions.count { epochDay(it.at) == today - offset } },
            themesPractised = distinctItems(CONVERSATION_THEME), exercisesCompleted = distinctItems(EXERCISE_SET),
            listeningCompleted = distinctItems(LISTENING), practiceRounds = byKind[RECALL_ROUND].orEmpty().size,
            wordsPracticeItems = distinctItems(WORDS_PRACTICE),
            assessmentsPassed = byKind[ASSESSMENT].orEmpty().filter { it.bestScore >= 100 }.map { it.itemID }.toSet().size,
        )
    }
}

/** One cumulative wire entry for the server ledger: (kind, item) rollups merge
    monotonically server-side, so cumulative counters are safe to resend. */
data class ProgressSyncEntry(
    val kind: String, val itemID: String, val atMilliseconds: Long, val attempts: Int, val bestScore: Int
)

data class RemoteProgress(
    val kind: String, val itemID: String, val firstAt: Double, val lastAt: Double,
    val attempts: Int, val bestScore: Int
)

object ProgressSync {
    const val MAXIMUM_ENTRIES = 200

    /** Cumulative rollups from marks + lesson counters, newest first, wire-capped.
        Lesson counters outrank the (capped) mark ledger so attempts never regress. */
    fun entries(lessons: List<LessonState>, completions: List<CompletionMark>): List<ProgressSyncEntry> {
        data class Rollup(var attempts: Int, var firstAt: Double, var lastAt: Double, var bestScore: Int)
        val rollups = LinkedHashMap<String, Rollup>()
        fun rollup(kind: String, item: String, at: Double, score: Int): Rollup =
            rollups.getOrPut("$kind|$item") { Rollup(0, at, at, 0) }.apply {
                attempts += 1; firstAt = minOf(firstAt, at); lastAt = maxOf(lastAt, at); bestScore = maxOf(bestScore, score)
            }
        completions.forEach { rollup(it.kind, it.itemID, it.at, it.bestScore) }
        lessons.filter { it.attempts > 0 }.forEach { lesson ->
            val mark = rollup(ProgressEngine.LESSON, lesson.lessonID, lesson.lastSeen, 0)
            mark.attempts = maxOf(mark.attempts, lesson.attempts)
            mark.lastAt = maxOf(mark.lastAt, lesson.lastSeen)
        }
        return rollups.map { (key, value) ->
            ProgressSyncEntry(key.substringBefore('|'), key.substringAfter('|'),
                ((value.lastAt + APPLE_EPOCH_UNIX_SECONDS) * 1000).toLong(), value.attempts, value.bestScore)
        }.sortedByDescending { it.atMilliseconds }.take(MAXIMUM_ENTRIES)
    }

    /** Applies a server pull: lessons merge counter-wise; exercise/listening items the
        device has never seen come back as one restored mark (per-day history does not
        survive a reinstall — accepted v1 limit). */
    fun mergeRemote(archive: Archive, rows: List<RemoteProgress>): Archive {
        val lessons = archive.lessons.toMutableList()
        val marks = archive.completions.orEmpty().toMutableList()
        rows.forEach { row ->
            when (row.kind) {
                ProgressEngine.LESSON -> {
                    val i = lessons.indexOfFirst { it.lessonID == row.itemID }
                    if (i >= 0) lessons[i] = lessons[i].copy(attempts = maxOf(lessons[i].attempts, row.attempts), lastSeen = maxOf(lessons[i].lastSeen, row.lastAt))
                    else if (row.attempts > 0) lessons += LessonState(lessonID = row.itemID, attempts = row.attempts, lastSeen = row.lastAt)
                }
                ProgressEngine.EXERCISE_SET, ProgressEngine.LISTENING ->
                    if (marks.none { it.kind == row.kind && it.itemID == row.itemID })
                        marks += CompletionMark(row.kind, row.itemID, row.lastAt, row.bestScore)
            }
        }
        return archive.copy(lessons = lessons,
            completions = if (marks.isEmpty()) archive.completions else marks.toMutableList())
    }
}
