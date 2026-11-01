package com.jkdc.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local cache of the last placement result per language (placement-test).
    The synced, auditable record is the `assessment` ledger completion; this
    store only carries the suggestion the Learn tab shows (band, breakdown,
    confidence, date). Re-derivable by re-taking the test, so plain
    preferences are enough — no learning-database schema change. */
class PlacementResultStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(result: PlacementResult) {
        val json = JSONObject().apply {
            put("band", result.band)
            put("listeningBand", result.listeningBand ?: JSONObject.NULL)
            put("writingBand", result.writingBand ?: JSONObject.NULL)
            put("confidence", result.confidence)
            put("corePercent", result.corePercent)
            put("source", result.source)
            put("version", result.version)
            put("at", result.at)
            put("categories", JSONArray().apply { result.categories.forEach { (name, score) -> put(JSONObject().put("name", name).put("score", score)) } })
        }
        preferences.edit().putString(result.languageID, json.toString()).commit()
    }

    fun get(languageID: String): PlacementResult? {
        val raw = preferences.getString(languageID, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val categories = mutableListOf<Pair<String, Int>>()
            val array = json.optJSONArray("categories") ?: JSONArray()
            for (index in 0 until array.length()) {
                val entry = array.getJSONObject(index)
                categories.add(entry.getString("name") to entry.getInt("score"))
            }
            PlacementResult(languageID = languageID, version = json.getInt("version"), band = json.getString("band"),
                categories = categories, listeningBand = json.optString("listeningBand").takeIf { it.isNotEmpty() },
                writingBand = json.optString("writingBand").takeIf { it.isNotEmpty() },
                confidence = json.getString("confidence"), corePercent = json.getInt("corePercent"),
                source = json.getString("source"), at = json.getLong("at"))
        }.getOrNull()
    }

    private companion object {
        const val PREFERENCES = "placement_results"
    }
}
