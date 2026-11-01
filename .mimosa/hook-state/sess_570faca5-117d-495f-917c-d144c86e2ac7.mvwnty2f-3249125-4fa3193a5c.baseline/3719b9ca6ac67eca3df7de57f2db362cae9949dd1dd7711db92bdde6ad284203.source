package com.jkdc.app.core

/** Legacy wire cursor parsed and compared using the same field order as uploads. */
internal data class ProgressUploadCursor(
    val kind: String,
    val itemID: String,
    val language: String,
) : Comparable<ProgressUploadCursor> {
    override fun compareTo(other: ProgressUploadCursor): Int =
        compareValuesBy(this, other, ProgressUploadCursor::kind, ProgressUploadCursor::itemID,
            ProgressUploadCursor::language)

    fun precedes(entry: ProgressSyncEntry): Boolean =
        compareTo(ProgressUploadCursor(entry.kind, entry.itemID, entry.language)) < 0

    fun encode(): String = "$kind|$itemID|$language"

    companion object {
        fun parse(value: String?): ProgressUploadCursor? {
            if (value == null) return null
            val fields = value.split('|')
            if (fields.size != 3 || fields.any(String::isBlank) || fields[0] !in ProgressEngine.kinds ||
                LanguageRegistry.all.none { it.id == fields[2] }) return null
            return ProgressUploadCursor(fields[0], fields[1], fields[2])
        }

        /** Invalid historical cursors restart idempotently instead of skipping records. */
        fun pending(entries: List<ProgressSyncEntry>, encodedCursor: String?): List<ProgressSyncEntry> {
            val cursor = parse(encodedCursor) ?: return entries
            return entries.filter { cursor.precedes(it) }
        }

        fun encode(entry: ProgressSyncEntry): String =
            ProgressUploadCursor(entry.kind, entry.itemID, entry.language).encode()
    }
}
