package com.jkdc.app.core

/** Engagement levels projected from the completion ledger — a pure projection,
    never stored and never a claim about language proficiency (titles live in
    the UI layer). Inputs only ever grow (attempts, max-merged scores, ledger
    marks), so tiers and levels never regress. */
data class LessonMastery(val lessonID: String, val tier: Int)

data class LevelStats(
    val courseID: String, val points: Int, val level: Int, val pointsToNext: Int?,
    val progressInLevel: Double, val mastery: List<LessonMastery>,
    val lessonPoints: Int = 0, val listeningPoints: Int = 0,
)

object LevelEngine {
    const val EXERCISE_GOOD = 60
    const val EXERCISE_MASTERED = 90
    const val LISTENING_MEDAL = 80

    /** Points (Σ lesson mastery tiers + Σ listening points) required for each
        level 1..10. The top (78) is full en-Foundations: 54 lesson points plus
        12 listenings at two points each. */
    val LADDER = listOf(1, 6, 12, 19, 27, 37, 49, 61, 69, 78)

    fun lessonTier(lessonID: String, lessons: List<LessonState>, completions: List<CompletionMark>,
                   zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
                   ownerAccountID: String? = null, languageID: String? = null,
                   remote: List<ProgressAggregate> = emptyList()): Int {
        val readLessons = if (ownerAccountID != null && languageID != null)
            ProgressProjection.effectiveLessons(ownerAccountID, languageID, lessons, remote, setOf(lessonID)) else lessons
        val attempts = readLessons.firstOrNull { it.lessonID == lessonID }?.attempts ?: return 0
        val readCompletions = if (ownerAccountID != null && languageID != null)
            completions.filter { it.languageID == null || it.languageID == languageID } + ProgressProjection.effectiveCompletions(ownerAccountID, languageID, emptyList(), remote)
            else completions
        if (attempts < 1) return 0
        val best = readCompletions.filter { it.kind == ProgressEngine.EXERCISE_SET && it.itemID == lessonID }
            .maxOfOrNull { it.bestScore }
        if (best == null || best < EXERCISE_GOOD) return 1
        val spaced = readCompletions.filter { it.kind == ProgressEngine.LESSON && it.itemID == lessonID }
            .map { epochDay(it.at, zone) }.toSet().size >= 2
        return if (best >= EXERCISE_MASTERED || spaced) 3 else 2
    }

    fun stats(course: Course, lessons: List<LessonState>, completions: List<CompletionMark>,
              listeningTexts: List<ListeningText> = emptyList(),
              zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
              ownerAccountID: String? = null, remote: List<ProgressAggregate> = emptyList()): LevelStats {
        val lessonIDs = course.units.flatMap { it.lessons }.map { it.id }.toSet()
        val mastery = course.units.flatMap { it.lessons }.map { LessonMastery(it.id, lessonTier(it.id, lessons, completions, zone, ownerAccountID, course.languageID, remote)) }
        val lessonPoints = mastery.sumOf { it.tier }
        val listeningIDs = listeningTexts.map { it.id }.toSet()
        val scopedIDs = lessonIDs + course.id + listeningIDs
        val readCompletions = if (ownerAccountID != null)
            completions.filter { it.languageID == course.languageID || (it.languageID == null && it.itemID in scopedIDs) } + ProgressProjection.effectiveCompletions(ownerAccountID, course.languageID, emptyList(), remote)
            else completions
        val listeningPoints = listeningTexts.sumOf { listeningTier(bestListeningScore(readCompletions, it.id)) }
        val points = lessonPoints + listeningPoints
        var level = 0
        for (threshold in LADDER) if (points >= threshold) level++
        val pointsToNext = LADDER.firstOrNull { it > points }?.minus(points)
        val floor = if (level == 0) 0 else LADDER[level - 1]
        val ceiling = LADDER.firstOrNull { it > points }
        val progress = if (ceiling == null) 1.0 else (points - floor).toDouble() / (ceiling - floor)
        return LevelStats(course.id, points, level, pointsToNext, progress, mastery, lessonPoints, listeningPoints)
    }

    /** 0 none · 1 silver (understood) · 2 gold (perfect). The medal grade doubles
        as the listening level tier: silver 1 point, gold 2. */
    fun listeningMedal(bestScore: Int?): Int = when {
        bestScore == null -> 0
        bestScore >= 100 -> 2
        bestScore >= LISTENING_MEDAL -> 1
        else -> 0
    }

    fun listeningTier(bestScore: Int?): Int = listeningMedal(bestScore)

    private fun bestListeningScore(completions: List<CompletionMark>, itemID: String) =
        completions.filter { it.kind == ProgressEngine.LISTENING && it.itemID == itemID }.maxOfOrNull { it.bestScore }

    private fun epochDay(seconds: Double, zone: java.time.ZoneId): Long =
        java.time.Instant.ofEpochSecond((seconds + APPLE_EPOCH_UNIX_SECONDS).toLong()).atZone(zone).toLocalDate().toEpochDay()
}
