package com.jkdc.app.core

/** Level checkpoints: authored item banks that mix skills and gate a CEFR band.
    Grading is deterministic (ExerciseEngine); the pass decision needs the
    overall score AND every category floor. The ledger keeps results under the
    existing `assessment` kind with the checkpoint id as itemID — no server
    catalog change. Readiness is a preparation signal, never a proficiency
    claim. */
data class CheckpointQuestion(val exercise: Exercise, val skill: String)

data class Checkpoint(val id: String, val languageID: String, val level: String, val title: String,
                      val thresholds: Map<String, Int>, val questions: List<CheckpointQuestion>)

data class CheckpointResult(val overall: Int, val categories: List<Pair<String, Int>>, val passed: Boolean)

object CheckpointRegistry {
    val all get() = CheckpointsData.all
    fun forLanguage(id: String) = all.filter { it.languageID == id }
    fun checkpoint(id: String) = all.firstOrNull { it.id == id }
}

object CheckpointEngine {
    fun categoryOf(skill: String) = SkillsData.categories[skill] ?: skill.substringBefore(':')

    /** Per-question verdicts aligned with the checkpoint's questions. */
    fun verdicts(checkpoint: Checkpoint, answers: List<String>): List<Boolean> =
        checkpoint.questions.mapIndexed { index, question ->
            ExerciseEngine.isCorrect(question.exercise, answers.getOrElse(index) { "" })
        }

    fun grade(checkpoint: Checkpoint, answers: List<String>): CheckpointResult {
        val verdicts = verdicts(checkpoint, answers)
        val overall = percent(verdicts)
        val categories = checkpoint.questions.mapIndexed { index, question -> categoryOf(question.skill) to verdicts[index] }
            .groupBy({ it.first }, { it.second }).map { (category, verdicts) -> category to percent(verdicts) }
            .sortedBy { it.first }
        val passed = overall >= (checkpoint.thresholds["overall"] ?: 100) &&
            categories.all { (category, score) -> score >= (checkpoint.thresholds[category] ?: 0) }
        return CheckpointResult(overall, categories, passed)
    }

    private fun percent(verdicts: List<Boolean>): Int =
        if (verdicts.isEmpty()) 0 else verdicts.count { it } * 100 / verdicts.size
}
