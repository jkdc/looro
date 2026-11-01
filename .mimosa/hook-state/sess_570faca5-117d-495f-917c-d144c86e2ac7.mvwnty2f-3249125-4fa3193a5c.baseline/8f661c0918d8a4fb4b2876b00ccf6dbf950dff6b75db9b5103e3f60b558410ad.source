package com.jkdc.app.core

import android.content.Context
import org.json.JSONObject

/** Local cache of the last level-verification result per (language, level)
    (level-tests). The synced audit trail is the `assessment` ledger
    completion; this store only feeds the Learn card (last score, date,
    pass state, weak units). Re-derivable by re-taking the weekly test. */
class LevelTestHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(result: LevelTestResult) {
        val json = JSONObject().apply {
            put("weekIndex", result.weekIndex)
            put("overall", result.overall)
            put("passed", result.passed)
            put("nextBand", result.nextBand ?: JSONObject.NULL)
            put("weakSections", org.json.JSONArray(result.weakSections))
            put("weakUnitIDs", org.json.JSONArray(result.weakUnitIDs))
            put("at", result.at)
        }
        preferences.edit().putString(key(result.languageID, result.level), json.toString()).commit()
    }

    fun get(languageID: String, level: String): LevelTestResult? {
        val raw = preferences.getString(key(languageID, level), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            LevelTestResult(languageID = languageID, level = level, weekIndex = json.getLong("weekIndex"),
                overall = json.getInt("overall"), sections = emptyList(), passed = json.getBoolean("passed"),
                nextBand = json.optString("nextBand").takeIf { it.isNotEmpty() },
                weakSections = List(json.optJSONArray("weakSections")?.length() ?: 0) { i ->
                    json.optJSONArray("weakSections")!!.getString(i)
                },
                weakUnitIDs = List(json.optJSONArray("weakUnitIDs")?.length() ?: 0) { i ->
                    json.optJSONArray("weakUnitIDs")!!.getString(i)
                }, at = json.getLong("at"))
        }.getOrNull()
    }

    private fun key(languageID: String, level: String) = "${languageID}|${level.lowercase()}"

    private companion object {
        const val PREFERENCES = "level_test_history"
    }
}
