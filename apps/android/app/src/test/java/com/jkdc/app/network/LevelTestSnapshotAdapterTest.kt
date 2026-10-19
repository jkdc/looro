package com.jkdc.app.network

import com.jkdc.app.core.LevelTestSection
import com.jkdc.app.core.LevelTestEngine
import com.jkdc.app.core.LevelTestsRegistry
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LevelTestSnapshotAdapterTest {
    private val expectedWeek = 202642L

    @Test
    fun `valid hybrid snapshot preserves server order and metadata`() {
        val snapshot = snapshot()
        val selection = LevelTestSnapshotAdapter.adapt(snapshot, "en", "A1", expectedWeek, 1)

        assertEquals("server-q-0", selection.exam.items.first().id)
        assertEquals("server-q-24", selection.exam.items.last().id)
        assertEquals(expectedWeek, selection.exam.weekIndex)
        assertEquals("exam-1", selection.examID)
        assertEquals("llm", selection.source)
        assertEquals(listOf("read-a", "read-b"), selection.exam.readingPassageIds)
        assertEquals(listOf("listen-a", "listen-b"), selection.exam.listeningPassageIds)
        assertEquals(LevelTestSection.RECOGNITION, selection.exam.items.first().section)
    }

    @Test
    fun `decoded snapshot retains the exact canonical JSON used for future sync`() {
        val raw = kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), snapshot())
        val selection = LevelTestSnapshotAdapter.decode(raw, "en", "A1", expectedWeek, 1)

        assertEquals(raw, selection.snapshotJSON)
    }

    @Test
    fun `stored snapshot restores the same immutable exam and server identity`() {
        val raw = kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), snapshot())
        val restored = LevelTestSnapshotAdapter.restore(raw)

        assertEquals(raw, restored.snapshotJSON)
        assertEquals("exam-1", restored.examID)
        assertEquals("server-q-0", restored.exam.items.first().id)
        assertEquals("Question 0", restored.exam.items.first().exercise.prompt)
    }

    @Test
    fun `bundled selection produces a server-compatible immutable snapshot`() {
        val level = requireNotNull(LevelTestsRegistry.level("en", "A1"))
        val selection = LevelTestSnapshotAdapter.fromBundled(level, LevelTestEngine.compose(level, expectedWeek))
        val json = kotlinx.serialization.json.Json.parseToJsonElement(selection.snapshotJSON).jsonObject

        assertEquals("bundled", json.getValue("source").jsonPrimitive.content)
        assertEquals(selection.contentID, json.getValue("contentId").jsonPrimitive.content)
        assertEquals(selection.examID, selection.contentID)
        assertEquals(64, json.getValue("contentHash").jsonPrimitive.content.length)
        val hashInput = JsonObject(json - setOf("examId", "contentId", "contentHash"))
        val canonicalHash = MessageDigest.getInstance("SHA-256")
            .digest(canonical(hashInput).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("sha256:$canonicalHash", selection.contentID)
        assertEquals(expectedWeek, selection.exam.weekIndex)
        assertEquals(25, selection.exam.items.size)
    }

    @Test
    fun `payload changes with stale declared hash reject the entire snapshot`() {
        val changed = snapshot().toMutableMap().apply {
            val items = (getValue("items") as JsonArray).toMutableList()
            items[0] = (items[0] as JsonObject).toMutableMap().apply {
                put("prompt", JsonPrimitive("Changed after hashing"))
            }.let(::JsonObject)
            put("items", JsonArray(items))
        }

        assertThrows(InvalidLevelTestSnapshot::class.java) {
            LevelTestSnapshotAdapter.adapt(JsonObject(changed), "en", "A1", expectedWeek, 1)
        }
    }

    @Test
    fun `duplicate question ids reject the entire snapshot`() {
        val snapshot = snapshot().toMutableMap().apply {
            val items = (getValue("items") as JsonArray).toMutableList()
            items[1] = (items[1] as JsonObject).toMutableMap().let { duplicate ->
                duplicate["id"] = JsonPrimitive("server-q-0")
                JsonObject(duplicate)
            }
            put("items", JsonArray(items))
        }

        assertThrows(InvalidLevelTestSnapshot::class.java) {
            LevelTestSnapshotAdapter.adapt(JsonObject(snapshot), "en", "A1", expectedWeek, 1)
        }
    }

    @Test
    fun `missing passage reference rejects the entire snapshot`() {
        val snapshot = snapshot().toMutableMap().apply {
            val items = (getValue("items") as JsonArray).toMutableList()
            items[12] = (items[12] as JsonObject).toMutableMap().let { reading ->
                reading["passageId"] = JsonPrimitive("missing-passage")
                JsonObject(reading)
            }
            put("items", JsonArray(items))
        }

        assertThrows(InvalidLevelTestSnapshot::class.java) {
            LevelTestSnapshotAdapter.adapt(JsonObject(snapshot), "en", "A1", expectedWeek, 1)
        }
    }

    @Test
    fun `requested identity variant and schema must match`() {
        assertThrows(InvalidLevelTestSnapshot::class.java) {
            LevelTestSnapshotAdapter.adapt(snapshot(), "es", "A1", expectedWeek, 1)
        }
        assertThrows(InvalidLevelTestSnapshot::class.java) {
            LevelTestSnapshotAdapter.adapt(snapshot(), "en", "A1", expectedWeek, 2)
        }
        val unsupported = snapshot().toMutableMap().apply { put("schemaVersion", JsonPrimitive(99)) }
        assertThrows(InvalidLevelTestSnapshot::class.java) {
            LevelTestSnapshotAdapter.adapt(JsonObject(unsupported), "en", "A1", expectedWeek, 1)
        }
    }

    @Test
    fun `invalid answer options and missing required fields reject the snapshot`() {
        val invalidOptions = snapshot().toMutableMap().apply {
            val items = (getValue("items") as JsonArray).toMutableList()
            items[0] = (items[0] as JsonObject).toMutableMap().let { choice ->
                choice["options"] = JsonArray(listOf(JsonPrimitive("B")))
                JsonObject(choice)
            }
            put("items", JsonArray(items))
        }
        assertThrows(InvalidLevelTestSnapshot::class.java) {
            LevelTestSnapshotAdapter.adapt(JsonObject(invalidOptions), "en", "A1", expectedWeek, 1)
        }

        val missingThresholds = snapshot().toMutableMap().apply { remove("thresholds") }
        assertThrows(InvalidLevelTestSnapshot::class.java) {
            LevelTestSnapshotAdapter.adapt(JsonObject(missingThresholds), "en", "A1", expectedWeek, 1)
        }
    }

    internal fun snapshot(): JsonObject {
        val passages = listOf("read-a", "read-b", "listen-a", "listen-b")
        val items = buildJsonArray {
            repeat(25) { index ->
                val section = when (index) {
                    in 0..11 -> "recognition"
                    in 12..17 -> "reading"
                    in 18..21 -> "listening"
                    else -> "writing"
                }
                val passage = when (index) {
                    in 12..14 -> "read-a"
                    in 15..17 -> "read-b"
                    in 18..19 -> "listen-a"
                    in 20..21 -> "listen-b"
                    else -> null
                }
                add(buildJsonObject {
                    put("id", "server-q-$index")
                    put("section", section)
                    put("kind", if (section == "writing") "written" else "choice")
                    put("prompt", "Question $index")
                    put("options", if (section == "writing") JsonArray(emptyList()) else JsonArray(listOf(JsonPrimitive("A"), JsonPrimitive("B"))))
                    put("answers", JsonArray(listOf(JsonPrimitive(if (section == "writing") "answer" else "A"))))
                    put("explain", "Explanation")
                    passage?.let { put("passageId", it) }
                    if (section == "listening") put("speech", "Spoken passage")
                })
            }
        }
        val unsignedSnapshot = buildJsonObject {
            put("schemaVersion", 1)
            put("languageID", "en")
            put("level", "A1")
            put("version", 1)
            put("weekId", expectedWeek)
            put("expiresAt", "2026-10-19T00:00:00.000Z")
            put("thresholds", buildJsonObject {
                put("overall", 70); put("recognition", 60); put("reading", 50); put("listening", 50); put("writing", 50)
            })
            put("passages", buildJsonArray { passages.forEachIndexed { index, id ->
                add(buildJsonObject { put("id", id); put("title", "Passage $index"); put("text", "Passage text") })
            } })
            put("readingPassageIds", JsonArray(listOf(JsonPrimitive("read-a"), JsonPrimitive("read-b"))))
            put("listeningPassageIds", JsonArray(listOf(JsonPrimitive("listen-a"), JsonPrimitive("listen-b"))))
            put("items", items)
            put("variant", 1)
            put("examId", "exam-1")
            put("generationId", "generation-1")
            put("contentId", "sha256:pending")
            put("contentHash", "pending")
            put("contentRevision", 1)
            put("gradingVersion", 1)
            put("source", "llm")
        }
        val payloadHash = MessageDigest.getInstance("SHA-256")
            .digest(canonical(JsonObject(unsignedSnapshot - setOf("examId", "generationId", "contentId", "contentHash")))
                .toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return JsonObject(unsignedSnapshot + mapOf(
            "contentId" to JsonPrimitive("sha256:$payloadHash"),
            "contentHash" to JsonPrimitive(payloadHash),
        ))
    }

    private fun canonical(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(element.map(::canonical))
            else -> element
        }
}
