package com.jkdc.app.core

/** Engagement levels projected from the completion ledger — a pure projection,
    never stored and never a claim about language proficiency (titles live in
    the UI layer). Inputs only ever grow (attempts, max-merged scores, ledger
    marks), so tiers and levels never regress. */
data class LessonMastery(val lessonID: String, val tier: Int)

data class LevelStats(
    val courseID: String, val points: Int, val level: Int, val pointsToNext: Int?,
    val progressInLevel: Double, val mastery: List<LessonMastery>,
)

object LevelEngine {
    const val EXERCISE_GOOD = 60
    const val EXERCISE_MASTERED = 90
    const val LISTENING_MEDAL = 80

    /** Points (Σ mastery tiers) required for each level 1..10. */
    val LADDER = listOf(1, 4, 8, 13, 19, 26, 34, 42, 48, 54)

    fun lessonTier(lessonID: String, lessons: List<LessonState>, completions: List<CompletionMark>,
                   zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Int {
        val attempts = lessons.firstOrNull { it.lessonID == lessonID }?.attempts ?: return 0
        if (attempts < 1) return 0
        val best = completions.filter { it.kind == ProgressEngine.EXERCISE_SET && it.itemID == lessonID }
            .maxOfOrNull { it.bestScore }
        if (best == null || best < EXERCISE_GOOD) return 1
        val spaced = completions.filter { it.kind == ProgressEngine.LESSON && it.itemID == lessonID }
            .map { epochDay(it.at, zone) }.toSet().size >= 2
        return if (best >= EXERCISE_MASTERED || spaced) 3 else 2
    }

    fun stats(course: Course, lessons: List<LessonState>, completions: List<CompletionMark>,
              zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): LevelStats {
        val mastery = course.units.flatMap { it.lessons }.map { LessonMastery(it.id, lessonTier(it.id, lessons, completions, zone)) }
        val points = mastery.sumOf { it.tier }
        var level = 0
        for (threshold in LADDER) if (points >= threshold) level++
        val pointsToNext = LADDER.firstOrNull { it > points }?.minus(points)
        val floor = if (level == 0) 0 else LADDER[level - 1]
        val ceiling = LADDER.firstOrNull { it > points }
        val progress = if (ceiling == null) 1.0 else (points - floor).toDouble() / (ceiling - floor)
        return LevelStats(course.id, points, level, pointsToNext, progress, mastery)
    }

    /** 0 none · 1 silver (understood) · 2 gold (perfect). */
    fun listeningMedal(bestScore: Int?): Int = when {
        bestScore == null -> 0
        bestScore >= 100 -> 2
        bestScore >= LISTENING_MEDAL -> 1
        else -> 0
    }

    private fun epochDay(seconds: Double, zone: java.time.ZoneId): Long =
        java.time.Instant.ofEpochSecond((seconds + APPLE_EPOCH_UNIX_SECONDS).toLong()).atZone(zone).toLocalDate().toEpochDay()
}
