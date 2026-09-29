package com.jkdc.app.core

/** Skill mastery projected from the completion ledger — a pure projection, never
    stored and never a CEFR claim (percentages per category live in the UI layer).
    Mastery per skill is the best weighted evidence, decayed with a 60-day
    half-life: dominating something two months ago is not dominating it today, and
    fresh practice (recall, exercises, listening) brings the number back. */
data class SkillMastery(val skillID: String, val category: String, val mastery: Int, val lastPracticed: Double?)

data class CategoryMastery(val category: String, val mastery: Int, val skillCount: Int)

data class MasteryStats(val overall: Int, val categories: List<CategoryMastery>, val skills: List<SkillMastery>)

object MasteryEngine {
    const val CHECKPOINT_WEIGHT = 1.0
    const val ORAL_WEIGHT = 0.9
    const val EXERCISE_WEIGHT = 0.6
    const val LISTENING_WEIGHT = 0.5

    /** Guided oral practice records attempts, not marks; showing up and speaking
        is strong evidence even without a score. */
    const val ORAL_BASELINE = 75
    const val HALF_LIFE_DAYS = 60.0

    private val CATEGORY_ORDER = listOf("grammar", "vocab", "function", "speaking", "listening", "reading", "writing", "pron")

    /** Taught skills of a language, from its content — exposureSkills are
        deliberately excluded: nobody is penalised for a structure they have
        only heard. */
    fun taggedSkills(course: Course, listenings: List<ListeningText>): Map<String, String> {
        val tagged = LinkedHashMap<String, String>()
        course.units.flatMap { it.lessons }.forEach { lesson ->
            lesson.skills.forEach { skill -> if (skill !in tagged) SkillsData.categories[skill]?.let { tagged[skill] = it } }
        }
        listenings.forEach { text ->
            text.skills.forEach { skill -> if (skill !in tagged) SkillsData.categories[skill]?.let { tagged[skill] = it } }
        }
        return tagged
    }

    fun stats(course: Course, listenings: List<ListeningText>, lessons: List<LessonState>,
              completions: List<CompletionMark>, nowSeconds: Double): MasteryStats {
        val tagged = taggedSkills(course, listenings)
        val lessonsBySkill = HashMap<String, MutableList<String>>()
        course.units.flatMap { it.lessons }.forEach { lesson ->
            lesson.skills.forEach { skill -> lessonsBySkill.getOrPut(skill) { mutableListOf() }.add(lesson.id) }
        }
        val listeningsBySkill = HashMap<String, MutableList<String>>()
        listenings.forEach { text ->
            text.skills.forEach { skill -> listeningsBySkill.getOrPut(skill) { mutableListOf() }.add(text.id) }
        }
        val mastery = tagged.map { (skillID, category) ->
            var best = 0.0
            var bestAt = 0.0
            fun offer(weight: Double, score: Int, at: Double) {
                val value = weight * (score / 100.0) * decay(at, nowSeconds)
                if (value > best) { best = value; bestAt = at }
            }
            lessonsBySkill[skillID]?.forEach { lessonID ->
                completions.filter { it.kind == ProgressEngine.EXERCISE_SET && it.itemID == lessonID }
                    .maxByOrNull { it.bestScore }?.let { offer(EXERCISE_WEIGHT, it.bestScore, it.at) }
                val state = lessons.firstOrNull { it.lessonID == lessonID }
                if (state != null && state.attempts > 0 && state.lastSeen > 0.0) offer(ORAL_WEIGHT, ORAL_BASELINE, state.lastSeen)
            }
            listeningsBySkill[skillID]?.forEach { textID ->
                completions.filter { it.kind == ProgressEngine.LISTENING && it.itemID == textID }
                    .maxByOrNull { it.bestScore }?.let { offer(LISTENING_WEIGHT, it.bestScore, it.at) }
            }
            SkillMastery(skillID, category, Math.round(best * 100).toInt().coerceIn(0, 100), bestAt.takeIf { it > 0.0 })
        }
        val categories = mastery.groupBy { it.category }.map { (category, skills) ->
            CategoryMastery(category, Math.round(skills.map { it.mastery }.average()).toInt(), skills.size)
        }.sortedBy { CATEGORY_ORDER.indexOf(it.category) }
        val overall = if (categories.isEmpty()) 0 else Math.round(categories.map { it.mastery }.average()).toInt()
        return MasteryStats(overall, categories, mastery.sortedWith(compareBy({ CATEGORY_ORDER.indexOf(it.category) }, { it.skillID })))
    }

    /** Exponential forgetting: half of the evidence value is gone after
        HALF_LIFE_DAYS without practice. */
    fun decay(atSeconds: Double, nowSeconds: Double): Double {
        val days = ((nowSeconds - atSeconds) / 86_400.0).coerceAtLeast(0.0)
        return Math.pow(0.5, days / HALF_LIFE_DAYS)
    }
}
