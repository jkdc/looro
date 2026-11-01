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
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class InvalidLevelTestSnapshot : IllegalArgumentException("invalid_level_test_snapshot")

internal data class LevelTestSelection(
    val level: LevelTestLevel,
    val exam: LevelTestEngine.Exam,
    val examID: String?,
    val contentID: String?,
    val source: String,
    val variant: Int?,
    val expiresAt: Instant?,
    val snapshotJSON: String = "",
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
        return adapt(json, language, level, weekID, variant).copy(snapshotJSON = body)
    }

    /** Restores an attempt from its immutable persisted wire snapshot, independent of today's catalog. */
    fun restore(snapshotJSON: String): LevelTestSelection {
        if (snapshotJSON.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) invalid()
        val snapshot = try { kotlinx.serialization.json.Json.parseToJsonElement(snapshotJSON) as? JsonObject ?: invalid() }
            catch (_: InvalidLevelTestSnapshot) { throw InvalidLevelTestSnapshot() }
            catch (_: Exception) { invalid() }
        val variant = snapshot["variant"]?.let(::int) ?: 1
        return decode(snapshotJSON, string(snapshot["languageID"]), string(snapshot["level"]),
            long(snapshot["weekId"]), variant)
    }

    fun adapt(snapshot: JsonObject, language: String, level: String, weekID: Long, variant: Int): LevelTestSelection {
        if (int(snapshot["schemaVersion"]) != SCHEMA_VERSION ||
            string(snapshot["languageID"]) != language || string(snapshot["level"]) != level ||
            long(snapshot["weekId"]) != weekID || variant !in 1..5) invalid()

        val source = string(snapshot["source"]).takeIf { it == "bundled" || it == "deterministic" || it == "llm" } ?: invalid()
        val snapshotVariant = snapshot["variant"]?.let(::int)
        if (snapshotVariant != null && snapshotVariant != variant ||
            snapshotVariant == null && (source == "llm" || variant != 1)) invalid()

        val version = int(snapshot["version"]).takeIf { it > 0 } ?: invalid()
        val contentRevision = int(snapshot["contentRevision"]).takeIf { it > 0 } ?: invalid()
        val gradingVersion = int(snapshot["gradingVersion"]).takeIf { it > 0 } ?: invalid()
        val examID = string(snapshot["examId"]).takeIf { it.isNotBlank() && it.length <= 256 } ?: invalid()
        val contentID = string(snapshot["contentId"]).takeIf { it.isNotBlank() && it.length <= 256 } ?: invalid()
        val hash = string(snapshot["contentHash"])
        if (!hash.matches(Regex("[a-f0-9]{64}")) || contentID != "sha256:$hash") invalid()
        val payloadHash = MessageDigest.getInstance("SHA-256")
            .digest(canonical(JsonObject(snapshot - setOf("examId", "generationId", "contentId", "contentHash")))
                .toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (hash != payloadHash) invalid()
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
        return LevelTestSelection(
            localLevel, exam, examID, contentID, source, variant, expiresAt,
            kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), snapshot),
        )
    }

    /** Builds the same immutable v1 payload accepted by the API for an offline bundled exam. */
    fun fromBundled(level: LevelTestLevel, exam: LevelTestEngine.Exam): LevelTestSelection {
        val year = (exam.weekIndex / 100).toInt()
        val week = (exam.weekIndex % 100).toInt()
        if (week !in 1..53) invalid()
        val expires = try {
            LocalDate.of(year, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
                .with(DayOfWeek.MONDAY).plusWeeks(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        } catch (_: Exception) { invalid() }
        val payload = buildJsonObject {
            put("schemaVersion", 1); put("languageID", level.languageID); put("level", level.level)
            put("version", level.version); put("weekId", exam.weekIndex)
            put("expiresAt", expires.toString())
            put("thresholds", buildJsonObject { level.thresholds.toSortedMap().forEach { (key, value) -> put(key, value) } })
            put("passages", JsonArray(exam.passageById.values.sortedBy { it.id }.map { passage -> buildJsonObject {
                put("id", passage.id); put("title", passage.title); put("text", passage.text)
            } }))
            put("readingPassageIds", JsonArray(exam.readingPassageIds.map(::JsonPrimitive)))
            put("listeningPassageIds", JsonArray(exam.listeningPassageIds.map(::JsonPrimitive)))
            put("items", JsonArray(exam.items.map { item -> buildJsonObject {
                put("id", item.id); put("section", item.section.name.lowercase())
                put("kind", item.exercise.kind); put("prompt", item.exercise.prompt)
                put("options", JsonArray(item.exercise.options.map(::JsonPrimitive)))
                put("answers", JsonArray(item.exercise.answers.map(::JsonPrimitive)))
                put("explain", item.exercise.explain)
                item.unitID?.let { put("unitID", it) }; item.passageId?.let { put("passageId", it) }
                item.speech?.let { put("speech", it) }
            } }))
            put("contentRevision", level.version); put("gradingVersion", 1); put("source", "bundled")
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical(payload).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val contentID = "sha256:$hash"
        val snapshot = JsonObject(payload + mapOf(
            "examId" to JsonPrimitive(contentID), "contentId" to JsonPrimitive(contentID),
            "contentHash" to JsonPrimitive(hash),
        ))
        return adapt(snapshot, level.languageID, level.level, exam.weekIndex, 1)
    }

    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        else -> element
    }

    /** Canonical hash of a snapshot's content: identical exams serialized by
     *  different producers (server JSON vs app-built JsonObject) hash equal
     *  even when their bytes differ. Null when [contentJSON] is not a valid
     *  snapshot object. */
    internal fun canonicalHash(contentJSON: String): String? = try {
        val payload = Json.parseToJsonElement(contentJSON).jsonObject
        val content = JsonObject(payload.filterKeys { key ->
            key !in setOf("examId", "generationId", "contentId", "contentHash")
        })
        MessageDigest.getInstance("SHA-256")
            .digest(canonical(content).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

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
