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

    private data class Identity(val owner: String, val language: String?, val kind: String, val item: String)
}
