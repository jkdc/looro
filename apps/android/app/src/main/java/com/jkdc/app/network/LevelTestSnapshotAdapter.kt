package com.jkdc.app.network

import com.jkdc.app.core.Exercise
import com.jkdc.app.core.LevelTestEngine
import com.jkdc.app.core.LevelTestItem
import com.jkdc.app.core.LevelTestLevel
import com.jkdc.app.core.LevelTestPassage
import com.jkdc.app.core.LevelTestSection
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.IsoFields
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class InvalidLevelTestSnapshot : IllegalArgumentException("invalid_level_test_snapshot")

internal data class LevelTestSelection(
    val level: LevelTestLevel,
    val exam: LevelTestEngine.Exam,
    val examID: String?,
    val contentID: String?,
    val source: String,
    val variant: Int?,
    val expiresAt: Instant?,
)

/** Maps the server’s already-composed snapshot without reordering or recomposing questions. */
internal object LevelTestSnapshotAdapter {
    private val supportedKinds = setOf("choice", "gap", "order", "written")
    private val sectionNames = mapOf(
        "recognition" to LevelTestSection.RECOGNITION,
        "reading" to LevelTestSection.READING,
        "listening" to LevelTestSection.LISTENING,
        "writing" to LevelTestSection.WRITING,
    )
    private const val SCHEMA_VERSION = 1
    private const val MAX_BODY_BYTES = 1_048_576

    fun decode(body: String, language: String, level: String, weekID: Long, variant: Int): LevelTestSelection {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) invalid()
        val json = try { kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject ?: invalid() }
            catch (_: InvalidLevelTestSnapshot) { throw InvalidLevelTestSnapshot() }
            catch (_: Exception) { invalid() }
        return adapt(json, language, level, weekID, variant)
    }

    fun adapt(snapshot: JsonObject, language: String, level: String, weekID: Long, variant: Int): LevelTestSelection {
        if (int(snapshot["schemaVersion"]) != SCHEMA_VERSION ||
            string(snapshot["languageID"]) != language || string(snapshot["level"]) != level ||
            long(snapshot["weekId"]) != weekID || variant !in 1..5) invalid()

        val source = string(snapshot["source"]).takeIf { it == "deterministic" || it == "llm" } ?: invalid()
        val snapshotVariant = snapshot["variant"]?.let(::int)
        if (snapshotVariant != null && snapshotVariant != variant ||
            snapshotVariant == null && (source != "deterministic" || variant != 1)) invalid()

        val version = int(snapshot["version"]).takeIf { it > 0 } ?: invalid()
        val contentRevision = int(snapshot["contentRevision"]).takeIf { it > 0 } ?: invalid()
        val gradingVersion = int(snapshot["gradingVersion"]).takeIf { it > 0 } ?: invalid()
        val examID = string(snapshot["examId"]).takeIf { it.isNotBlank() && it.length <= 256 } ?: invalid()
        val contentID = string(snapshot["contentId"]).takeIf { it.isNotBlank() && it.length <= 256 } ?: invalid()
        val hash = string(snapshot["contentHash"])
        if (!hash.matches(Regex("[a-f0-9]{64}")) || contentID != "sha256:$hash") invalid()
        val expiresAt = parseExpiry(string(snapshot["expiresAt"]), weekID)

        val thresholdsJson = snapshot["thresholds"] as? JsonObject ?: invalid()
        val thresholds = thresholdsJson.mapValues { (_, value) -> int(value) }
        val allowedThresholds = setOf("overall", "recognition", "reading", "listening", "writing", "grammar", "vocab")
        if (thresholds["overall"] == null || thresholds.keys.any { it !in allowedThresholds } ||
            thresholds.values.any { it !in 0..100 }) invalid()

        val passages = (snapshot["passages"] as? JsonArray)?.map { element ->
            val obj = element as? JsonObject ?: invalid()
            LevelTestPassage(
                string(obj["id"]).requiredText(),
                string(obj["title"]).requiredText(allowEmpty = true),
                string(obj["text"]).requiredText(),
            )
        } ?: invalid()
        if (passages.isEmpty() || passages.size > 64 || passages.map { it.id }.distinct().size != passages.size) invalid()
        val passageById = passages.associateBy { it.id }
        val readingIDs = stringList(snapshot["readingPassageIds"])
        val listeningIDs = stringList(snapshot["listeningPassageIds"])
        if (readingIDs.size != 2 || listeningIDs.size != 2 ||
            readingIDs.distinct().size != readingIDs.size || listeningIDs.distinct().size != listeningIDs.size ||
            readingIDs.any { it !in passageById } || listeningIDs.any { it !in passageById } ||
            readingIDs.intersect(listeningIDs.toSet()).isNotEmpty()) invalid()

        val items = (snapshot["items"] as? JsonArray)?.map { element -> parseItem(element, passageById) } ?: invalid()
        if (items.size != 25 || items.map { it.id }.distinct().size != items.size) invalid()
        val counts = items.groupingBy { it.section }.eachCount()
        if (counts[LevelTestSection.RECOGNITION] != LevelTestEngine.RECOGNITION_COUNT ||
            counts[LevelTestSection.READING] != LevelTestEngine.READING_PASSAGES * LevelTestEngine.READING_PER_PASSAGE ||
            counts[LevelTestSection.LISTENING] != LevelTestEngine.LISTENING_PASSAGES * LevelTestEngine.LISTENING_PER_PASSAGE ||
            counts[LevelTestSection.WRITING] != LevelTestEngine.WRITING_COUNT) invalid()
        if (items.filter { it.section == LevelTestSection.READING }.mapNotNull { it.passageId }.toSet() != readingIDs.toSet() ||
            items.filter { it.section == LevelTestSection.LISTENING }.mapNotNull { it.passageId }.toSet() != listeningIDs.toSet()) invalid()

        val localLevel = LevelTestLevel(language, level, version, thresholds, passages, items)
        val exam = LevelTestEngine.Exam(weekID, level, items, passageById, readingIDs, listeningIDs)
        if (contentRevision != version || gradingVersion <= 0) invalid()
        return LevelTestSelection(localLevel, exam, examID, contentID, source, variant, expiresAt)
    }

    private fun parseItem(element: JsonElement, passages: Map<String, LevelTestPassage>): LevelTestItem {
        val obj = element as? JsonObject ?: invalid()
        val id = string(obj["id"]).requiredText()
        if (id.length > 256) invalid()
        val section = sectionNames[string(obj["section"])] ?: invalid()
        val kind = string(obj["kind"])
        if (kind !in supportedKinds) invalid()
        val prompt = string(obj["prompt"]).requiredText()
        val options = optionalStringList(obj["options"])
        val answers = stringList(obj["answers"])
        if (answers.isEmpty() || answers.any { it.isBlank() } || options.distinct().size != options.size) invalid()
        if (kind == "choice" && (options.size < 2 || answers.any { it !in options })) invalid()
        if (kind != "choice" && options.size > 8) invalid()
        val passageId = obj["passageId"]?.let { string(it).requiredText() }
        if (section == LevelTestSection.READING && (passageId == null || passageId !in passages)) invalid()
        if (section == LevelTestSection.LISTENING && (passageId == null || passageId !in passages)) invalid()
        if (section != LevelTestSection.READING && section != LevelTestSection.LISTENING &&
            passageId != null && passageId !in passages) invalid()
        val speech = obj["speech"]?.let { string(it).requiredText() }
        if (section == LevelTestSection.LISTENING && speech == null) invalid()
        val unitID = obj["unitID"]?.let { string(it).requiredText() }
        val explain = string(obj["explain"]).requiredText(allowEmpty = true)
        return LevelTestItem(id, section, Exercise(id, kind, prompt, options, answers, explain), unitID, passageId, speech)
    }

    private fun parseExpiry(value: String, weekID: Long): Instant {
        val instant = try { Instant.parse(value) } catch (_: Exception) { invalid() }
        val year = (weekID / 100).toInt()
        val week = (weekID % 100).toInt()
        if (week !in 1..53) invalid()
        val monday = try {
            LocalDate.of(year, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong()).with(DayOfWeek.MONDAY)
        } catch (_: Exception) { invalid() }
        if (monday.get(IsoFields.WEEK_BASED_YEAR) != year ||
            monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) != week.toInt()) invalid()
        val expected = monday.plusWeeks(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        if (instant != expected) invalid()
        return instant
    }

    private fun stringList(value: JsonElement?): List<String> =
        (value as? JsonArray)?.map { string(it).requiredText() } ?: invalid()

    private fun optionalStringList(value: JsonElement?): List<String> =
        if (value == null) emptyList() else stringList(value)

    private fun string(value: JsonElement?): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()

    private fun int(value: JsonElement?): Int =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull() ?: invalid()

    private fun long(value: JsonElement?): Long =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: invalid()

    private fun String.requiredText(allowEmpty: Boolean = false): String =
        takeIf { it.length <= 16_384 && (allowEmpty || isNotBlank()) } ?: invalid()

    private fun invalid(): Nothing = throw InvalidLevelTestSnapshot()
}
