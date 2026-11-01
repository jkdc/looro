package com.jkdc.app.core

/** Free CEFR placement test (placement-test): banks live in `PlacementData.kt`
    (generated from the shared placement JSON). The core is an adaptive band
    ladder over authored items; fixed listening (TTS) and short-form writing
    blocks run at the resulting band and can each lower only their own
    breakdown. Deterministic and offline, like `CheckpointEngine`: the
    measurement path never touches AI or the network. A result is a
    suggestion for where to start, never a certificate and never a gate. */
data class PlacementItem(val id: String, val level: String, val mode: String,
                         val exercise: Exercise, val skill: String, val speech: String? = null) {
    val isCore: Boolean get() = mode == MODE_CORE
    companion object {
        const val MODE_CORE = "core"
        const val MODE_LISTENING = "listening"
        const val MODE_WRITING = "writing"
    }
}

data class PlacementBank(val languageID: String, val version: Int, val items: List<PlacementItem>)

object PlacementRegistry {
    val all: List<PlacementBank> get() = PlacementData.all
    fun forLanguage(languageID: String): PlacementBank? = all.firstOrNull { it.languageID == languageID }
}

/** The result: a suggestion (source `test`) or a self-assessed guess
    (source `cando`, produced when the test is abandoned). Band names are the
    catalog's CEFR levels; `categories` mirrors `CheckpointEngine`'s
    per-category percents. */
data class PlacementResult(val languageID: String, val version: Int, val band: String,
                           val categories: List<Pair<String, Int>>, val listeningBand: String?,
                           val writingBand: String?, val confidence: String, val corePercent: Int,
                           val source: String, val at: Long = 0) {
    companion object {
        const val SOURCE_TEST = "test"
        const val SOURCE_CANDO = "cando"
        const val CONFIDENCE_HIGH = "high"
        const val CONFIDENCE_MEDIUM = "medium"
        const val CONFIDENCE_LOW = "low"
    }
}

object PlacementEngine {
    const val FLOOR = "A1"
    const val CAP = "C1"
    const val CORE_CAP = 18
    const val BLOCK_SIZE = 3
    val BANDS = listOf("A1", "A2", "B1", "B2", "C1")

    /** The reconstructible core state: the asked item ids and their verdicts
        fully determine the ladder (selection is deterministic), so a session
        survives process restore from two plain lists. */
    data class Core(val bank: PlacementBank, val band: String, val asked: List<PlacementItem>,
                    val verdicts: List<Boolean>, val window: List<Boolean>, val finished: Boolean) {
        val answered: Int get() = asked.size
        fun verdict(item: PlacementItem) = verdicts.getOrNull(asked.indexOfFirst { it.id == item.id }) ?: false
    }

    private fun bandUp(band: String): String = BANDS.elementAtOrNull(BANDS.indexOf(band) + 1) ?: band
    private fun bandDown(band: String): String = BANDS.elementAtOrNull(BANDS.indexOf(band) - 1) ?: band

    fun start(bank: PlacementBank, startBand: String): Core =
        Core(bank, coerce(startBand), emptyList(), emptyList(), emptyList(), false)

    private fun coerce(band: String): String = if (band in BANDS) band else FLOOR

    /** The next core item of the current band: the unused item whose skill
        category has the fewest answers so far, bank order as tiebreak. Null
        means the band's items are exhausted — the band verdict stands. */
    fun nextCoreItem(state: Core): PlacementItem? {
        if (state.finished) return null
        val used = state.asked.mapTo(mutableSetOf()) { it.id }
        val counts = state.asked.groupingBy { CheckpointEngine.categoryOf(it.skill) }.eachCount()
        return state.bank.items.asSequence()
            .filter { it.isCore && it.level == state.band && it.id !in used }
            .minWithOrNull(compareBy({ counts[CheckpointEngine.categoryOf(it.skill)] ?: 0 }, { state.bank.items.indexOf(it) }))
    }

    /** Records one core answer and moves the ladder. Over the last 3 answers
        of the current band window: 2+ correct → up, 2+ wrong → down. An up at
        C1 or a down at A1 is the ceiling/floor verdict and finishes the core;
        so does reaching CORE_CAP answers. */
    fun applyCore(state: Core, item: PlacementItem, attempt: String): Core {
        if (state.finished || !item.isCore || item.level != state.band) return state
        val correct = ExerciseEngine.isCorrect(item.exercise, attempt)
        val asked = state.asked + item
        val verdicts = state.verdicts + correct
        var window = (state.window + correct).takeLast(3)
        var band = state.band
        var finished = asked.size >= CORE_CAP
        if (window.size == 3) {
            val correctCount = window.count { it }
            if (correctCount >= 2) {
                if (band == CAP) finished = true
                else { band = bandUp(band); window = emptyList() }
            } else {
                if (band == FLOOR) finished = true
                else { band = bandDown(band); window = emptyList() }
            }
        }
        return Core(state.bank, band, asked, verdicts, window, finished)
    }

    fun blockItems(bank: PlacementBank, band: String, mode: String): List<PlacementItem> =
        bank.items.filter { it.mode == mode && it.level == band }.take(BLOCK_SIZE)

    /** A final block keeps the global band with 2+ correct of 3; below that,
        its own breakdown drops one band (floor A1). */
    fun blockBand(globalBand: String, verdicts: List<Boolean>): String =
        if (verdicts.size >= 2 && verdicts.count { it } >= 2) globalBand else bandDown(globalBand)

    fun confidence(coreAnswered: Int): String = when {
        coreAnswered < 15 -> PlacementResult.CONFIDENCE_HIGH
        coreAnswered < CORE_CAP -> PlacementResult.CONFIDENCE_MEDIUM
        else -> PlacementResult.CONFIDENCE_LOW
    }

    /** Category percents over the core answers (same shape as
        `CheckpointEngine.grade`'s categories). */
    fun categories(asked: List<PlacementItem>, verdicts: List<Boolean>): List<Pair<String, Int>> =
        asked.mapIndexed { index, item -> CheckpointEngine.categoryOf(item.skill) to (verdicts.getOrNull(index) ?: false) }
            .groupBy({ it.first }, { it.second })
            .map { (category, verdictList) -> category to percent(verdictList) }
            .sortedBy { it.first }

    private fun percent(verdicts: List<Boolean>): Int =
        if (verdicts.isEmpty()) 0 else verdicts.count { it } * 100 / verdicts.size

    fun corePercent(verdicts: List<Boolean>): Int = percent(verdicts)

    /** Self-assessed suggestion when the test is abandoned: the marked band,
        no category data. */
    fun canDoResult(bank: PlacementBank, band: String): PlacementResult =
        PlacementResult(bank.languageID, bank.version, coerce(band), emptyList(), null, null,
            PlacementResult.CONFIDENCE_LOW, 0, PlacementResult.SOURCE_CANDO)
}
