package com.jkdc.app.core

import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.IsoFields

/** Weekly level-verification tests (level-tests): per-level pools in
    `LevelTestsData.kt` (harvested from authored lesson exercises, checkpoint
    questions and listening texts). The engine composes THIS week's test
    deterministically from a seed over the pool — same test for every device
    in a week, different across weeks, fully offline. Grading follows the
    checkpoint semantics: an overall threshold plus per-section floors; the
    result is a directional suggestion (ready for the next band / what to
    reinforce), never a gate. Reading and listening always draw on disjoint
    passages within one composition. */

enum class LevelTestSection { RECOGNITION, READING, LISTENING, WRITING }

data class LevelTestPassage(val id: String, val title: String, val text: String)

data class LevelTestItem(val id: String, val section: LevelTestSection, val exercise: Exercise,
                         val unitID: String? = null, val passageId: String? = null, val speech: String? = null)

data class LevelTestLevel(val languageID: String, val level: String, val version: Int,
                          val thresholds: Map<String, Int>, val passages: List<LevelTestPassage>,
                          val pool: List<LevelTestItem>)

data class LevelTestBank(val languageID: String, val levels: List<LevelTestLevel>)

object LevelTestsRegistry {
    val all: List<LevelTestBank> get() = LevelTestsData.all
    fun forLanguage(languageID: String): LevelTestBank? = all.firstOrNull { it.languageID == languageID }
    fun level(languageID: String, level: String): LevelTestLevel? =
        forLanguage(languageID)?.levels?.firstOrNull { it.level == level }
}

data class LevelTestResult(val languageID: String, val level: String, val weekIndex: Long,
                           val overall: Int, val sections: List<Pair<String, Int>>, val passed: Boolean,
                           val nextBand: String?, val weakSections: List<String>, val weakUnitIDs: List<String>,
                           val at: Long = 0)

object LevelTestEngine {
    /** Fixed weekly counts: 12 recognition + 2 reading passages × 3 + 2
        listening passages × 2 + 3 writing = 25 items ≈ 10–15 minutes. */
    const val RECOGNITION_COUNT = 12
    const val READING_PASSAGES = 2
    const val READING_PER_PASSAGE = 3
    const val LISTENING_PASSAGES = 2
    const val LISTENING_PER_PASSAGE = 2
    const val WRITING_COUNT = 3

    val BANDS = listOf("A1", "A2", "B1", "B2", "C1")

    /** ISO week-based year × 100 + week: a stable, human-meaningful week id. */
    fun weekIndex(date: LocalDate = LocalDate.now()): Long =
        date.get(IsoFields.WEEK_BASED_YEAR) * 100L + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    /** One flattened exam: sections in fixed order (warm-up first), items in
        seeded-shuffled order. Reading/listening carry their passage text or
        speech for the UI. */
    data class Exam(val weekIndex: Long, val level: String,
                    val items: List<LevelTestItem>,
                    val passageById: Map<String, LevelTestPassage>,
                    val readingPassageIds: List<String>,
                    val listeningPassageIds: List<String>)

    fun compose(bank: LevelTestLevel, weekIndex: Long): Exam {
        // Language-neutral weekly rotation: every item gets a stable
        // pseudo-random order from sha256(version|SECTION|id) — no RNG, so a
        // server composes the identical test — and the ISO week picks a block
        // of that stable order. Consecutive weeks take consecutive blocks:
        // zero overlap while the pool holds ≥ 2× the exam. Reading picks its
        // passages first; the listening pool then excludes them, so one exam
        // never re-uses a text across the two blocks.
        val recognition = stableBlock(bank.pool.filter { it.section == LevelTestSection.RECOGNITION },
            bank.version, RECOGNITION_COUNT, weekIndex)
        val writing = stableBlock(bank.pool.filter { it.section == LevelTestSection.WRITING },
            bank.version, WRITING_COUNT, weekIndex)

        val readingReady = stablePassages(bank.pool, bank.passages, bank.version,
            LevelTestSection.READING, READING_PER_PASSAGE)
        val readingBlocks = readingReady.chunked(READING_PASSAGES)
        val readingBlock = readingBlocks.getOrElse((weekIndex % readingBlocks.size).toInt()) { readingReady }
        val readingIds = (if (readingBlock.size == READING_PASSAGES) readingBlock else readingReady.take(READING_PASSAGES))
            .map { it.id }
        val readingItems = bank.pool
            .filter { it.section == LevelTestSection.READING && it.passageId in readingIds }
            .sortedWith(compareBy({ it.passageId }, { stableHash(bank.version, it.id) }))

        val listeningReady = stablePassages(bank.pool, bank.passages, bank.version,
            LevelTestSection.LISTENING, LISTENING_PER_PASSAGE).filter { it.id !in readingIds }
        val listeningBlocks = listeningReady.chunked(LISTENING_PASSAGES)
        val listeningBlock = listeningBlocks.getOrElse((weekIndex % listeningBlocks.size).toInt()) { listeningReady }
        val listeningIds = (if (listeningBlock.size == LISTENING_PASSAGES) listeningBlock else listeningReady.take(LISTENING_PASSAGES))
            .map { it.id }
        val listeningItems = bank.pool
            .filter { it.section == LevelTestSection.LISTENING && it.passageId in listeningIds }
            .sortedWith(compareBy({ it.passageId }, { stableHash(bank.version, it.id) }))

        val items = recognition + readingItems + listeningItems + writing
        return Exam(weekIndex, bank.level, items,
            bank.passages.associateBy { it.id }, readingIds, listeningIds)
    }

    /** Stable pseudo-random order: ascending sha256 of version|SECTION|id. */
    private fun stableOrder(items: List<LevelTestItem>, version: Int): List<LevelTestItem> =
        items.sortedBy { stableHash(version, "${it.section.name}|${it.id}") }

    private fun stablePassages(pool: List<LevelTestItem>, passages: List<LevelTestPassage>,
                               version: Int, section: LevelTestSection, perPassage: Int): List<LevelTestPassage> =
        passages.filter { passage ->
            pool.count { it.passageId == passage.id && it.section == section } >= perPassage
        }.sortedBy { stableHash(version, "${section.name}|${it.id}") }

    private fun stableBlock(items: List<LevelTestItem>, version: Int, count: Int, weekIndex: Long): List<LevelTestItem> {
        val ordered = stableOrder(items, version)
        val blocks = ordered.chunked(count)
        val block = blocks.getOrElse((weekIndex % blocks.size).toInt()) { ordered }
        // A trailing remainder chunk is smaller than the exam needs: fall back
        // to the head of the stable order, which always has a full block.
        return if (block.size == count) block else ordered.take(count)
    }

    fun stableHash(version: Int, value: String): String =
        MessageDigest.getInstance("SHA-256").digest("$version|$value".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun nextBand(level: String): String? = BANDS.getOrNull(BANDS.indexOf(level) + 1)

    /** Overall + per-section percents; passed = overall threshold met AND no
        section below its floor (thresholds map carries both; floors default
        to 60, the level's own checkpoint numbers when harvested). */
    fun grade(level: LevelTestLevel, state: Exam, attempts: Map<String, String>): LevelTestResult {
        val thresholds = level.thresholds
        val verdicts = state.items.map { ExerciseEngine.isCorrect(it.exercise, attempts[it.id] ?: "") }
        val overall = percent(verdicts)
        val sections = LevelTestSection.entries.map { section ->
            val indexes = state.items.withIndex().filter { it.value.section == section }.map { it.index }
            section.name.lowercase() to percent(indexes.map { verdicts[it] })
        }
        val passed = overall >= (thresholds["overall"] ?: 70) &&
            sections.all { (name, score) -> score >= (thresholds[name] ?: 60) }
        val nextBand = if (passed) nextBand(state.level) else null
        val weakSections = sections.filter { (name, score) -> score < (thresholds[name] ?: 60) }
            .sortedBy { it.second }.map { it.first }
        val weakUnits = state.items.withIndex().filter { !verdicts[it.index] && it.value.unitID != null }
            .map { it.value.unitID!! }.distinct().take(4)
        return LevelTestResult(level.languageID, state.level, state.weekIndex, overall, sections, passed,
            nextBand, weakSections, weakUnits)
    }

    private fun percent(verdicts: List<Boolean>): Int =
        if (verdicts.isEmpty()) 0 else verdicts.count { it } * 100 / verdicts.size

    /** Grading straight from the session's parallel attempts list (the shape
        the screen keeps in rememberSaveable). */
    fun gradeByAttempts(level: LevelTestLevel, state: Exam, attempts: List<String>): LevelTestResult =
        grade(level, state, state.items.mapIndexed { index, item -> item.id to attempts.getOrElse(index) { "" } }.toMap())
}
