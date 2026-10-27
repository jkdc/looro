package com.jkdc.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Decode the validated legacy bootstrap rows without creating local activity. */
internal fun decodeLegacyProgressRows(rows: JsonArray, owner: String): List<RemoteProgressRollup>? {
    return try {
        rows.map { element ->
            val row = element.jsonObject
            val kind = (row["kind"] as? JsonPrimitive)?.contentOrNull ?: return null
            val item = (row["itemID"] as? JsonPrimitive)?.contentOrNull ?: return null
            val hasRange = row.containsKey("firstAt") || row.containsKey("lastAt")
            val firstAt = (row[if (hasRange) "firstAt" else "at"] as? JsonPrimitive)?.longOrNull ?: return null
            val lastAt = (row[if (hasRange) "lastAt" else "at"] as? JsonPrimitive)?.longOrNull ?: return null
            val now = System.currentTimeMillis().toDouble()
            if (lastAt < firstAt || com.jkdc.app.core.ProgressTime.fromUnixMilliseconds(firstAt.toDouble(), now) == null ||
                com.jkdc.app.core.ProgressTime.fromUnixMilliseconds(lastAt.toDouble(), now) == null) return null
            val language = (row["language"] as? JsonPrimitive)?.contentOrNull
            if (row["language"] != null && row["language"] != JsonNull && language == null) return null
            val attempts = (row["attempts"] as? JsonPrimitive)?.intOrNull ?: return null
            val score = (row["bestScore"] as? JsonPrimitive)?.intOrNull ?: return null
            RemoteProgressRollup(owner, language, kind, item, firstAt, lastAt, attempts, score, 1)
        }
    } catch (_: Exception) {
        null
    }
}
