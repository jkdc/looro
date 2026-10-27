package com.jkdc.app.core

import java.time.ZoneId
import kotlin.math.pow
import kotlin.math.roundToInt

/** Pre-built index: activityID → skillIDs and skillID → activityIDs.
    Built once per content version, reused across mastery computations. */
class SkillCatalogIndex(course: Course, listenings: List<ListeningText>, readings: List<ReadingText>,
    includePronunciation: Boolean = true) {
    val skillsByActivityID: Map<String, Set<String>>
    val activitiesBySkillID: Map<String, Set<String>>
    val skillCategory: Map<String, String>

    init {
        val byActivity = HashMap<String, MutableSet<String>>()
        val bySkill = HashMap<String, MutableSet<String>>()
        val cats = HashMap<String, String>()

        fun index(activityID: String, skills: List<String>) {
            for (skill in skills) {
                if (skill in SkillsData.categories && (includePronunciation || !skill.startsWith("pron:"))) {
                    byActivity.getOrPut(activityID) { mutableSetOf() }.add(skill)
                    bySkill.getOrPut(skill) { mutableSetOf() }.add(activityID)
                    cats[skill] = SkillsData.categories[skill] ?: skill.substringBefore(':')
                }
            }
        }
        course.units.flatMap { it.lessons }.forEach { lesson ->
            index(lesson.id, lesson.skills)
        }
        listenings.forEach { text -> index(text.id, text.skills) }
        readings.forEach { text -> index(text.id, text.skills) }

        skillsByActivityID = byActivity
        activitiesBySkillID = bySkill
        skillCategory = cats
    }

    val taughtSkills: Set<String> get() = skillCategory.keys
}

/** A single skill's mastery — raw is what the evidence shows, effective
    applies temporal decay at read time. */
data class SkillMastery(
    val skillID: String,
    val category: String,
    val rawMastery: Int,
    val effectiveMastery: Int,
    val evidenceCount: Int,
    val lastPracticedAt: Double?,
)

data class CategoryMastery(val category: String, val mastery: Int, val skillCount: Int)

data class MasteryStats(
    val overall: Int,
    val categories: List<CategoryMastery>,
    val skills: List<SkillMastery>,
    val ledgerRevision: Long,
    val masteryVersion: Int,
)

object MasteryEngine {
    const val CHECKPOINT_WEIGHT = 1.0
    const val ORAL_WEIGHT = 0.9
    const val EXERCISE_WEIGHT = 0.6
    const val LISTENING_WEIGHT = 0.5
    const val READING_WEIGHT = 0.5
    // Self-scored WER evidence is worth less than any graded activity — a
    // generous transcript must never outweigh a graded exercise.
    const val PRONUNCIATION_WEIGHT = 0.8
    const val ORAL_BASELINE = 75
    const val HALF_LIFE_DAYS = 60.0

    /** Bump this when the algorithm, weights, or skill mappings change. */
    const val MASTERY_VERSION = 2

    private val CATEGORY_ORDER = listOf("grammar", "vocab", "function", "speaking", "listening", "reading", "writing", "pron")

    /** Pre-built index: created once per content load, reused across calls. */
    fun buildIndex(course: Course, listenings: List<ListeningText>, readings: List<ReadingText>,
        includePronunciation: Boolean = true) =
        SkillCatalogIndex(course, listenings, readings, includePronunciation)

    fun stats(course: Course, listenings: List<ListeningText>, readings: List<ReadingText>,
              lessons: List<LessonState>, completions: List<CompletionMark>,
              nowSeconds: Double, index: SkillCatalogIndex,
              ledgerRevision: Long, ownerAccountID: String? = null,
              remote: List<ProgressAggregate> = emptyList()): MasteryStats {
        val tagged = index.taughtSkills
        val lessonIDs = course.units.flatMap { it.lessons }.map { it.id }.toSet()
        val scopedIDs = lessonIDs + listenings.map { it.id } + readings.map { it.id } + course.id
        val readLessons = if (ownerAccountID != null) ProgressProjection.effectiveLessons(ownerAccountID, course.languageID, lessons, remote, lessonIDs) else lessons
        val readCompletions = if (ownerAccountID != null)
            completions.filter { it.languageID == course.languageID || (it.languageID == null && it.itemID in scopedIDs) } + ProgressProjection.effectiveCompletions(ownerAccountID, course.languageID, emptyList(), remote)
            else completions
        val mastery = tagged.map { skillID ->
            val category = index.skillCategory[skillID] ?: skillID.substringBefore(':')
            val activityIDs = index.activitiesBySkillID[skillID] ?: emptySet()
            var best = 0.0
            var bestAt = 0.0
            var evidenceCount = 0

            fun offer(weight: Double, score: Int, at: Double) {
                evidenceCount++
                val value = weight * (score / 100.0) * decay(at, nowSeconds)
                if (value > best) { best = value; bestAt = at }
            }

            // Self-scored pronunciation evidence lands only on pron skills —
            // the mark's itemID is the lesson, so without this guard every
            // co-tagged grammar skill would inherit it.
            val pronSkill = skillID.startsWith("pron:")
            for (activityID in activityIDs) {
                if (pronSkill) {
                    readCompletions.filter { it.kind == ProgressEngine.PRONUNCIATION && it.itemID == activityID }
                        .maxByOrNull { it.bestScore }?.let { offer(PRONUNCIATION_WEIGHT, it.bestScore, it.at) }
                }
                // Exercise set evidence
                readCompletions.filter { it.kind == ProgressEngine.EXERCISE_SET && it.itemID == activityID }
                    .maxByOrNull { it.bestScore }?.let { offer(EXERCISE_WEIGHT, it.bestScore, it.at) }
                // Guided oral evidence
                val state = readLessons.firstOrNull { it.lessonID == activityID }
                if (state != null && state.attempts > 0 && state.lastSeen > 0.0)
                    offer(ORAL_WEIGHT, ORAL_BASELINE, state.lastSeen)
                // Listening evidence
                readCompletions.filter { it.kind == ProgressEngine.LISTENING && it.itemID == activityID }
                    .maxByOrNull { it.bestScore }?.let { offer(LISTENING_WEIGHT, it.bestScore, it.at) }
                // Reading evidence
                readCompletions.filter { it.kind == ProgressEngine.READING && it.itemID == activityID }
                    .maxByOrNull { it.bestScore }?.let { offer(READING_WEIGHT, it.bestScore, it.at) }
            }

            SkillMastery(
                skillID = skillID,
                category = category,
                rawMastery = Math.round(best * 100).toInt().coerceIn(0, 100),
                effectiveMastery = Math.round(best * 100).toInt().coerceIn(0, 100),
                evidenceCount = evidenceCount,
                lastPracticedAt = bestAt.takeIf { it > 0.0 },
            )
        }

        val categories = mastery.groupBy { it.category }.map { (cat, skills) ->
            CategoryMastery(cat, Math.round(skills.map { it.effectiveMastery }.average()).toInt(), skills.size)
        }.sortedBy { CATEGORY_ORDER.indexOf(it.category) }
        val overall = if (categories.isEmpty()) 0 else Math.round(categories.map { it.mastery }.average()).toInt()
        val sorted = mastery.sortedWith(compareBy({ CATEGORY_ORDER.indexOf(it.category) }, { it.skillID }))

        return MasteryStats(overall, categories, sorted, ledgerRevision, MASTERY_VERSION)
    }

    /** Exponential forgetting: half the evidence value gone after HALF_LIFE_DAYS. */
    fun decay(atSeconds: Double, nowSeconds: Double): Double {
        val days = ((nowSeconds - atSeconds) / 86_400.0).coerceAtLeast(0.0)
        return 0.5.pow(days / HALF_LIFE_DAYS)
    }
}
