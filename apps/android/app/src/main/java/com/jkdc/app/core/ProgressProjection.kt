package com.jkdc.app.core

/** One owner/language-scoped cumulative progress identity. Times are Unix milliseconds. */
data class ProgressAggregate(
    val ownerAccountID: String,
    val languageID: String?,
    val kind: String,
    val itemID: String,
    val firstAt: Long,
    val lastAt: Long,
    val attempts: Int,
    val bestScore: Int,
)

/** Pure projection of local and remote rollups; it never writes activity history. */
object ProgressProjection {
    fun merge(ownerAccountID: String, languageID: String,
              local: List<ProgressAggregate>, remote: List<ProgressAggregate>): List<ProgressAggregate> {
        val merged = linkedMapOf<Identity, ProgressAggregate>()
        (local + remote).filter { it.ownerAccountID == ownerAccountID && it.languageID == languageID }.forEach { entry ->
            val identity = Identity(entry.ownerAccountID, entry.languageID, entry.kind, entry.itemID)
            val previous = merged[identity]
            merged[identity] = if (previous == null) entry else previous.copy(
                firstAt = minOf(previous.firstAt, entry.firstAt),
                lastAt = maxOf(previous.lastAt, entry.lastAt),
                attempts = maxOf(previous.attempts, entry.attempts),
                bestScore = maxOf(previous.bestScore, entry.bestScore),
            )
        }
        return merged.values.sortedWith(compareBy<ProgressAggregate>(
            { it.ownerAccountID }, { it.languageID ?: "" }, { it.kind }, { it.itemID },
        ))
    }

    /** Converts scoped aggregate evidence to transient completion records. These
     * records are read-model inputs only; callers must keep activity readers on
     * their original local completion list. */
    fun effectiveCompletions(ownerAccountID: String, languageID: String,
        local: List<CompletionMark>, remote: List<ProgressAggregate>): List<CompletionMark> {
        val localAggregates = local.filter { it.languageID == languageID }.map {
            ProgressAggregate(ownerAccountID, languageID, it.kind, it.itemID,
                unixMillis(it.at), unixMillis(it.at), 1, it.bestScore)
        }
        return merge(ownerAccountID, languageID, localAggregates, remote).flatMap { aggregate ->
            val instants = if (aggregate.kind == ProgressEngine.LESSON && aggregate.firstAt != aggregate.lastAt)
                listOf(aggregate.firstAt, aggregate.lastAt) else listOf(aggregate.lastAt)
            instants.map { at -> CompletionMark(aggregate.kind, aggregate.itemID, appleSeconds(at), aggregate.bestScore, aggregate.languageID) }
        }
    }

    fun effectiveLessons(ownerAccountID: String, languageID: String, local: List<LessonState>,
        remote: List<ProgressAggregate>, allowedLessonIDs: Set<String> = local.map { it.lessonID }.toSet()): List<LessonState> {
        val scopedLocal = local.filter { it.lessonID in allowedLessonIDs }
        val localAggregates = scopedLocal.map { lesson ->
            ProgressAggregate(ownerAccountID, languageID, ProgressEngine.LESSON, lesson.lessonID,
                unixMillis(lesson.lastSeen), unixMillis(lesson.lastSeen), lesson.attempts, 0)
        }
        val effective = merge(ownerAccountID, languageID, localAggregates, remote)
            .filter { it.kind == ProgressEngine.LESSON }
        val localByID = scopedLocal.associateBy { it.lessonID }
        return effective.map { row ->
            val localLesson = localByID[row.itemID]
            LessonState(lessonID = row.itemID, attempts = row.attempts,
                lastSeen = maxOf(localLesson?.lastSeen ?: 0.0, appleSeconds(row.lastAt)))
        }
    }

    private fun unixMillis(appleSeconds: Double) = ((appleSeconds + APPLE_EPOCH_UNIX_SECONDS) * 1000).toLong()
    private fun appleSeconds(unixMillis: Long) = unixMillis / 1000.0 - APPLE_EPOCH_UNIX_SECONDS

    private data class Identity(val owner: String, val language: String?, val kind: String, val item: String)
}
