package com.jkdc.app.data

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.jkdc.app.core.AccountSession
import com.jkdc.app.core.LocalLearnerIdentity
import com.jkdc.app.core.LessonState
import com.jkdc.app.core.ProgressAggregate
import com.jkdc.app.core.ProgressProjection
import com.jkdc.app.network.ExamAttemptOutboxSync
import com.jkdc.app.network.ProgressClient
import com.jkdc.app.network.ProgressFetchResult
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The learning schema must open verbatim through the shared statement list. */
class LearningDbTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = LearningDb.openJvm(connection)

    @After fun closeConnection() { connection.close() }

    @Test fun legacyBootstrapPersistsFirstAndLastForOwnerAndUploadsLastTimestamp() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val client = ProgressClient(server.url("/"), OkHttpClient())
            val session = AccountSession("12345678-1234-1234-1234-123456789012", "a".repeat(43), 100_000)
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"progress_sync_version_unavailable"}}"""))
            server.enqueue(MockResponse().setBody("""{"progress":[{"kind":"lesson","itemID":"en-l1","language":"en","firstAt":1740000000000,"lastAt":1740000001000,"attempts":2,"bestScore":80}]}"""))
            val result = client.fetchBootstrap(session, null) { true } as ProgressFetchResult.Success
            val rows = kotlinx.serialization.json.Json.parseToJsonElement(result.body).jsonObject["rows"]!!.jsonArray
            val rollups = decodeLegacyProgressRows(rows, session.accountID)
            assertTrue("The production legacy decoder must accept the server response", rollups != null)
            LearningDbWriter.remoteProgressPageApplied(db, session.accountID, rollups!!, null, 1, 1740000002000)
            val saved = RemoteProgressRollupsDao.all(db, session.accountID).single()
            assertEquals(1740000000000L, saved.firstAtMilliseconds)
            assertEquals(1740000001000L, saved.lastAtMilliseconds)
            assertTrue(RemoteProgressRollupsDao.all(db, "other-account").isEmpty())
            val remote = listOf(ProgressAggregate(saved.ownerAccountID, saved.languageID, saved.kind, saved.itemID,
                saved.firstAtMilliseconds, saved.lastAtMilliseconds, saved.attempts, saved.bestScore))
            val entries = com.jkdc.app.core.ProgressSync.entries(emptyList(), emptyList(), emptyList(), emptySet(),
                "en", ownerAccountID = session.accountID, remote = remote)
            assertEquals(1, entries.size)
            assertEquals(1740000000000L, entries.single().firstAtMilliseconds)
            assertEquals(1740000001000L, entries.single().atMilliseconds)
            fun payload(batch: List<com.jkdc.app.core.ProgressSyncEntry>): String = buildJsonObject {
                put("entries", buildJsonArray { batch.forEach { entry -> add(buildJsonObject {
                    put("kind", entry.kind); put("itemID", entry.itemID); put("language", entry.language)
                    put("firstAt", entry.firstAtMilliseconds); put("lastAt", entry.atMilliseconds)
                    put("attempts", entry.attempts); put("bestScore", entry.bestScore)
                }) } })
            }.toString()
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"progress_sync_version_unavailable"}}"""))
            server.enqueue(MockResponse().setBody("""{"merged":1}"""))
            val sent = com.jkdc.app.uploadProgressBatches(entries, null, 1,
                isCurrent = { true }, sendV2 = { client.sendV2(session, payload(it)) },
                sendLegacy = { client.sendLegacy(session, payload(it)) }, persistAcknowledgement = { _, _ -> false })
            assertTrue(sent)
            server.takeRequest(); server.takeRequest(); server.takeRequest()
            val uploaded = kotlinx.serialization.json.Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["entries"]!!.jsonArray.single().jsonObject
            assertEquals("1740000001000", uploaded["at"].toString())
            assertEquals(saved, RemoteProgressRollupsDao.all(db, session.accountID).single())
        } finally { server.shutdown() }
    }

    @Test fun invalidTimestampRowForSharedIdentityStaysLocalAndDoesNotBlockLegacyUpload() = runBlocking {
        val owner = "12345678-1234-1234-1234-123456789012"
        val nowMilliseconds = 1_750_000_000_000L
        val validAt = com.jkdc.app.core.ProgressTime.fromUnixMilliseconds(
            nowMilliseconds.toDouble(), nowMilliseconds.toDouble())!!
        LearnerOwnershipDao.activate(db, "member:$owner")
        val valid = com.jkdc.app.core.CompletionMark("exercise_set", "shared-item", validAt, 40, "en")
        val invalid = com.jkdc.app.core.CompletionMark("exercise_set", "shared-item", 1_700_000_000_000.0, 99, "en")
        CompletionsDao.add(db, valid)
        CompletionsDao.add(db, invalid)

        val entries = com.jkdc.app.core.ProgressSync.entries(emptyList(), CompletionsDao.all(db),
            emptyList(), emptySet(), "en", ownerAccountID = owner, nowUnixMilliseconds = nowMilliseconds)
        assertEquals(1, entries.size)
        assertEquals(40, entries.single().bestScore)
        assertEquals(listOf(valid, invalid), CompletionsDao.all(db))

        val server = MockWebServer().apply { start() }
        try {
            val client = ProgressClient(server.url("/"), OkHttpClient()) { nowMilliseconds }
            val session = AccountSession(owner, "a".repeat(43), 100_000)
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"progress_sync_version_unavailable"}}"""))
            server.enqueue(MockResponse().setBody("""{"merged":1}"""))
            val sent = com.jkdc.app.uploadProgressBatches(entries, null, 1,
                isCurrent = { true }, sendV2 = { client.sendV2(session, """{"entries":[{"kind":"${it.single().kind}","itemID":"${it.single().itemID}","language":"en","firstAt":${it.single().firstAtMilliseconds},"lastAt":${it.single().atMilliseconds},"attempts":${it.single().attempts},"bestScore":${it.single().bestScore}}]}""") },
                sendLegacy = { batch -> client.sendLegacy(session, """{"entries":[{"kind":"${batch.single().kind}","itemID":"${batch.single().itemID}","language":"en","firstAt":${batch.single().firstAtMilliseconds},"lastAt":${batch.single().atMilliseconds},"attempts":${batch.single().attempts},"bestScore":${batch.single().bestScore}}]}""") },
                persistAcknowledgement = { _, _ -> false })
            assertTrue(sent)
            server.takeRequest()
            val request = server.takeRequest()
            val uploaded = kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8())
                .jsonObject["entries"]!!.jsonArray.single().jsonObject
            assertEquals("shared-item", uploaded["itemID"]!!.jsonPrimitive.content)
            assertEquals("40", uploaded["bestScore"]!!.jsonPrimitive.content)
            assertEquals(listOf(valid, invalid), CompletionsDao.all(db))
        } finally { server.shutdown() }
    }

    @Test fun learnerRowsPersistOwnerKeysForEveryMutableLearnerTable() {
        val expected = setOf("preferences", "sessions", "fragments", "expressions", "lesson_state",
            "completions", "skill_errors", "usage_outbox", "assessment_tickets", "exam_attempts",
            "exam_answers", "exam_results", "exam_sync_outbox", "exam_sync_conflicts",
            "remote_progress_rollups", "progress_sync_state", "progress_recovery")
        expected.forEach { table ->
            val columns = db.query("PRAGMA table_info($table)").map { it.text("name") }.toSet()
            assertTrue("$table must persist a row owner", "owner_key" in columns)
        }
    }

    @Test fun accountSwitchKeepsPreferencesAndLessonStateInSeparatePartitions() {
        LearnerOwnershipDao.activate(db, "member:account-a")
        PreferencesDao.put(db, com.jkdc.app.core.Preferences(learningLanguageID = "en"))
        LessonStateDao.upsert(db, com.jkdc.app.core.LessonState("shared-lesson", attempts = 2, lastSeen = 100.0))

        LearnerOwnershipDao.activate(db, "member:account-b")
        assertEquals(null, PreferencesDao.get(db))
        assertTrue(LessonStateDao.all(db).isEmpty())
        PreferencesDao.put(db, com.jkdc.app.core.Preferences(learningLanguageID = "es"))
        LessonStateDao.upsert(db, com.jkdc.app.core.LessonState("shared-lesson", attempts = 7, lastSeen = 200.0))

        LearnerOwnershipDao.activate(db, "member:account-a")
        assertEquals("en", PreferencesDao.get(db)?.learningLanguageID)
        assertEquals(2, LessonStateDao.all(db).single().attempts)
        LearnerOwnershipDao.activate(db, "member:account-b")
        assertEquals("es", PreferencesDao.get(db)?.learningLanguageID)
        assertEquals(7, LessonStateDao.all(db).single().attempts)
    }

    @Test fun ownerlessDevelopmentRowsAreBackfilledOnlyForTheFirstActiveIdentity() {
        PreferencesDao.put(db, com.jkdc.app.core.Preferences(learningLanguageID = "en"))
        LearnerOwnershipDao.activate(db, "member:first-account")
        assertEquals("member:first-account", db.query("SELECT owner_key FROM preferences").single().text("owner_key"))
        assertEquals("en", PreferencesDao.get(db)?.learningLanguageID)

        LearnerOwnershipDao.activate(db, "member:later-account")
        assertEquals(null, PreferencesDao.get(db))
        assertEquals("member:first-account", db.query("SELECT owner_key FROM preferences").single().text("owner_key"))
    }

    @Test fun staleOwnerCallbackCannotReactivateItsPartitionOrRedirectItsWrite() {
        var currentIdentity = "member:account-a"
        LearnerOwnershipDao.activate(db, currentIdentity)
        currentIdentity = "member:account-b"
        LearnerOwnershipDao.activate(db, currentIdentity)

        val wrote = LearnerOwnershipDao.runForIdentity(db, "member:account-a",
            isCurrent = { currentIdentity == "member:account-a" }) {
            PreferencesDao.put(it, com.jkdc.app.core.Preferences(learningLanguageID = "en"))
        }

        assertFalse(wrote)
        assertEquals("member:account-b", LearnerOwnershipDao.activeIdentity(db))
        assertTrue(db.query("SELECT * FROM preferences").isEmpty())
    }

    @Test fun staleAccountDatabaseCallbackCannotReactivateAAfterTheAuthenticatedSessionMovesToB() {
        val sessionA = AccountSession("00000000-0000-0000-0000-00000000000a", "a".repeat(43), 10_000L)
        val sessionB = AccountSession("00000000-0000-0000-0000-00000000000b", "b".repeat(43), 10_000L)
        var liveSession = sessionB
        var liveGeneration = 8L
        LearnerOwnershipDao.activate(db, "member:${sessionB.accountID}")
        PreferencesDao.put(db, com.jkdc.app.core.Preferences(learningLanguageID = "es"))

        val accepted = LocalLearnerIdentity.activateDatabaseForAccountCallback(
            db = db,
            requestedSession = sessionA,
            requestedGeneration = 7L,
            currentSession = { liveSession },
            currentAccountID = { liveSession.accountID },
            currentGeneration = { liveGeneration },
        ) { target, identity ->
            LearnerOwnershipDao.activate(target, identity)
        }

        assertFalse(accepted)
        assertEquals("member:${sessionB.accountID}", LearnerOwnershipDao.activeIdentity(db))
        assertEquals("es", PreferencesDao.get(db)?.learningLanguageID)
        assertEquals("member:${sessionB.accountID}", db.query("SELECT owner_key FROM preferences").single().text("owner_key"))
    }

    @Test fun archiveExportAndResetAreRestrictedToTheActiveOwner() {
        LearnerOwnershipDao.activate(db, "member:archive-a")
        SessionsDao.upsert(db, com.jkdc.app.core.SessionRecord(id = "shared-session", languageID = "en",
            startedAt = 10.0, title = "A session"))
        LearnerOwnershipDao.activate(db, "member:archive-b")
        SessionsDao.upsert(db, com.jkdc.app.core.SessionRecord(id = "shared-session", languageID = "es",
            startedAt = 20.0, title = "B session"))
        assertEquals("B session", ArchiveMapper.export(db).sessions.single().title)
        LearningDbWriter.learningDataDeleted(db, com.jkdc.app.core.Preferences())
        assertTrue(ArchiveMapper.export(db).sessions.isEmpty())

        LearnerOwnershipDao.activate(db, "member:archive-a")
        assertEquals("A session", ArchiveMapper.export(db).sessions.single().title)
    }

    @Test fun pageAndCursorRollbackTogether() {
        val file = File.createTempFile("progress-page", ".db")
        try {
            var target = openFileBacked(file)
            val row = RemoteProgressRollup("owner-a", "es", "exercise", "item-a", 100, 200, 2, 40, 2)
            assertThrows(IllegalArgumentException::class.java) {
                LearningDbWriter.remoteProgressPageApplied(target, "owner-a", listOf(row), "cursor-1", -1, 300)
            }
            assertTrue(RemoteProgressRollupsDao.all(target, "owner-a").isEmpty())
            assertEquals(null, ProgressSyncStateDao.get(target, "owner-a", "download"))

            LearningDbWriter.remoteProgressPageApplied(target, "owner-a", listOf(row), "cursor-1", 1, 300)
            target.close()
            target = openFileBacked(file)
            assertEquals(listOf(row), RemoteProgressRollupsDao.all(target, "owner-a"))
            assertEquals("cursor-1", ProgressSyncStateDao.get(target, "owner-a", "download")?.cursor)
            target.close()
        } finally { file.delete() }
    }

    @Test fun remoteProgressBootstrapResumesAfterRestartAndKeepsAll1001Identities() = runBlocking {
        val file = File.createTempFile("progress-bootstrap-resume", ".db")
        val server = MockWebServer().apply { start() }
        var target: SqlDatabase? = null
        try {
            val client = ProgressClient(server.url("/"), OkHttpClient())
            val session = AccountSession("12345678-1234-1234-1234-123456789012", "a".repeat(43), 100_000)
            server.enqueue(MockResponse().setBody(progressPageJson(0, 1000, "cursor-1000", complete = false)))
            server.enqueue(MockResponse().setBody(progressPageJson(1000, 1, null, complete = true)))

            val initial = client.fetchBootstrap(session, null) { true } as ProgressFetchResult.Success
            val pages = kotlinx.serialization.json.Json.parseToJsonElement(initial.body).jsonObject["pages"]!!.jsonArray
            assertEquals(2, pages.size)
            val firstPage = pages.first().jsonObject
            target = openFileBacked(file)
            LearningDbWriter.remoteProgressPageApplied(
                target, session.accountID, decodeRemoteProgress(firstPage, session.accountID),
                firstPage["nextCursor"]!!.jsonPrimitive.content, 1, 3_000,
            )
            assertEquals(1000, RemoteProgressRollupsDao.all(target, session.accountID).size)

            target.close()
            target = openFileBacked(file)
            val savedCursor = ProgressSyncStateDao.get(target, session.accountID, "download")!!.cursor
            assertEquals("cursor-1000", savedCursor)
            server.enqueue(MockResponse().setBody(progressPageJson(1000, 1, null, complete = true)))
            val resumed = client.fetchBootstrap(session, savedCursor) { true } as ProgressFetchResult.Success
            val resumedPages = kotlinx.serialization.json.Json.parseToJsonElement(resumed.body).jsonObject["pages"]!!.jsonArray
            assertEquals(1, resumedPages.size)
            val finalPage = resumedPages.single().jsonObject
            LearningDbWriter.remoteProgressPageApplied(
                target, session.accountID, decodeRemoteProgress(finalPage, session.accountID), null, 2, 4_000,
            )

            val identities = RemoteProgressRollupsDao.all(target, session.accountID).map { it.itemID }.toSet()
            assertEquals(1001, identities.size)
            assertEquals((0..1000).map(::progressItemID).toSet(), identities)
            assertEquals(null, ProgressSyncStateDao.get(target, session.accountID, "download")!!.cursor)
        } finally {
            target?.close()
            server.shutdown()
            file.delete()
        }
    }

    @Test fun malformedLaterProgressPageLeavesPersistedRowsAndCursorUnchanged() = runBlocking {
        val file = File.createTempFile("progress-bootstrap-malformed", ".db")
        val server = MockWebServer().apply { start() }
        var target: SqlDatabase? = null
        try {
            val client = ProgressClient(server.url("/"), OkHttpClient())
            val session = AccountSession("12345678-1234-1234-1234-123456789012", "a".repeat(43), 100_000)
            target = openFileBacked(file)
            val existing = RemoteProgressRollup(session.accountID, "en", "lesson", "existing", 1_792_929_599_000, 1_792_929_600_000, 1, 80, 1)
            LearningDbWriter.remoteProgressPageApplied(target, session.accountID, listOf(existing), "saved-cursor", 3, 3_000)
            server.enqueue(MockResponse().setBody(progressPageJson(0, 1000, "cursor-1000", complete = false)))
            server.enqueue(MockResponse().setBody("""{"version":2,"progress":[{"kind":"lesson","itemID":"broken","language":"en","firstAt":1792929600000,"lastAt":1792929599000,"attempts":1,"bestScore":0}],"nextCursor":null,"complete":true}"""))

            val result = client.fetchBootstrap(session, null) { true }
            assertTrue(result is ProgressFetchResult.Failure)
            assertEquals(listOf(existing), RemoteProgressRollupsDao.all(target, session.accountID))
            assertEquals("saved-cursor", ProgressSyncStateDao.get(target, session.accountID, "download")!!.cursor)
        } finally {
            target?.close()
            server.shutdown()
            file.delete()
        }
    }

    private fun progressPageJson(start: Int, count: Int, nextCursor: String?, complete: Boolean): String {
        val rows = (start until start + count).joinToString(",") { index ->
            """{"kind":"lesson","itemID":"${progressItemID(index)}","language":"en","firstAt":1792929599000,"lastAt":1792929600000,"attempts":1,"bestScore":80}"""
        }
        val cursor = nextCursor?.let { "\"$it\"" } ?: "null"
        return """{"version":2,"progress":[$rows],"nextCursor":$cursor,"complete":$complete}"""
    }

    private fun progressItemID(index: Int) = "item-%04d".format(index)

    private fun decodeRemoteProgress(page: kotlinx.serialization.json.JsonObject, owner: String): List<RemoteProgressRollup> =
        page["progress"]!!.jsonArray.map { element ->
            val row = element.jsonObject
            RemoteProgressRollup(
                owner, row["language"]!!.jsonPrimitive.content, row["kind"]!!.jsonPrimitive.content,
                row["itemID"]!!.jsonPrimitive.content, row["firstAt"]!!.jsonPrimitive.content.toLong(),
                row["lastAt"]!!.jsonPrimitive.content.toLong(), row["attempts"]!!.jsonPrimitive.content.toInt(),
                row["bestScore"]!!.jsonPrimitive.content.toInt(), 1,
            )
        }

    @Test fun uncertainAckReplaysWithoutStarvation() {
        val row = RemoteProgressRollup("owner-a", "es", "exercise", "item-a", 100, 200, 2, 40, 2)
        LearningDbWriter.remoteProgressPageApplied(db, "owner-a", listOf(row), "page-1", 1, 300)
        repeat(3) { RemoteProgressRollupsDao.merge(db, row) }
        assertEquals(1, RemoteProgressRollupsDao.all(db, "owner-a").size)
        assertEquals(null, ProgressSyncStateDao.get(db, "owner-a", "upload"))
        LearningDbWriter.remoteProgressUploadAcknowledged(db, "owner-a", "upload-1", 1, 400)
        assertEquals("upload-1", ProgressSyncStateDao.get(db, "owner-a", "upload")?.cursor)
        assertEquals(row, RemoteProgressRollupsDao.all(db, "owner-a").single())
    }

    @Test fun upgradeFromV5PreservesExamOutboxAndConflicts() {
        val file = File.createTempFile("progress-v5-upgrade", ".db")
        val old = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        try {
            val legacy = JvmSqlDatabase(old)
            legacy.exec("PRAGMA foreign_keys = ON")
            val laterUpgrades = SchemaData.SCHEMA_UPGRADES.filterKeys { it >= 6 }.values.flatten().toSet()
            for (statement in SchemaData.LEARNING_SCHEMA_STATEMENTS) {
                if (statement !in laterUpgrades && statement != "PRAGMA user_version = 6" &&
                    statement != "PRAGMA user_version = 7") legacy.exec(statement)
            }
            legacy.insert("exam_snapshots", mapOf("content_id" to "content-a", "content_hash" to "hash-a",
                "schema_version" to 1, "content_revision" to 1, "grading_version" to 1, "source" to "bundled",
                "language_id" to "es", "level" to "A1", "week_id" to 1, "content_json" to "{}", "created_at" to 1.0))
            legacy.insert("exam_snapshot_ids", mapOf("exam_id" to "exam-a", "content_id" to "content-a", "created_at" to 1.0))
            legacy.insert("exam_attempts", mapOf("attempt_id" to "attempt-a", "exam_id" to "exam-a", "owner_account_id" to "owner-a",
                "state" to "completed", "sync_state" to "conflict", "position" to 0, "revision" to 1, "started_at" to 1.0,
                "updated_at" to 2.0, "completed_at" to 2.0))
            legacy.insert("exam_sync_outbox", mapOf("event_id" to "event-a", "attempt_id" to "attempt-a", "revision" to 1,
                "event_type" to "completed", "payload_json" to "{}", "delivery_state" to "pending", "created_at" to 2.0))
            legacy.insert("exam_sync_conflicts", mapOf("attempt_id" to "attempt-a", "owner_account_id" to "owner-a",
                "server_revision" to 2, "server_attempt_json" to "{}", "local_attempt_json" to "{}", "received_at" to 3.0,
                "resolution" to "takeover", "resolved_at" to 4.0))
            legacy.insert("completions", mapOf("kind" to "exercise", "item_id" to "pre-v6", "at" to 1_700_000_000.0,
                "best_score" to 40, "language_id" to "es"))
            legacy.insert("lesson_state", mapOf("lesson_id" to "legacy-lesson", "attempts" to 1,
                "last_seen" to 1_700_000_000.0))
            legacy.transaction { LearningDb.upgrade(it, 5) }
            legacy.exec("PRAGMA user_version = ${LearningDb.VERSION}")
            legacy.close()

            val reopened = openFileBacked(file)
            ProgressRecoveryDao.recover(reopened, System.currentTimeMillis())
            assertEquals("pending", reopened.query("SELECT delivery_state FROM exam_sync_outbox WHERE event_id = 'event-a'").single().text("delivery_state"))
            assertEquals("takeover", reopened.query("SELECT resolution FROM exam_sync_conflicts WHERE attempt_id = 'attempt-a'").single().text("resolution"))
            assertEquals(1_700_000_000.0, reopened.query("SELECT at FROM completions WHERE item_id = 'pre-v6'").single().real("at"), 0.0)
            assertEquals(1_700_000_000.0, reopened.query("SELECT last_seen FROM lesson_state WHERE lesson_id = 'legacy-lesson'").single().real("last_seen"), 0.0)
            assertEquals(null, reopened.query("SELECT at_source FROM completions WHERE item_id = 'pre-v6'").single().textOrNull("at_source"))
            assertEquals(null, reopened.query("SELECT last_seen_source FROM lesson_state WHERE lesson_id = 'legacy-lesson'").single().textOrNull("last_seen_source"))
            assertEquals(2L, reopened.query("SELECT COUNT(*) AS n FROM progress_recovery WHERE state = 'excluded'").single().long("n"))
            reopened.close()
        } finally { old.close(); file.delete() }
    }

    @Test fun recoveryRepairsExplicitUnixLessonEvidenceExactlyOnce() {
        val nowMilliseconds = 1_800_000_000_000L
        val unixSeconds = 1_750_000_000.125
        val expectedAppleSeconds = 771_692_800.125
        LearnerOwnershipDao.activate(db, "member:owner-a")
        LessonStateDao.upsert(db, LessonState("untagged-legacy", attempts = 1, lastSeen = unixSeconds))
        LessonStateDao.upsert(db, LessonState("evidenced-legacy", attempts = 1, lastSeen = unixSeconds),
            TimestampEvidence("ios.recordLessonAttempt", 1, "unix_seconds"))
        LessonStateDao.upsert(db, LessonState("valid-current", attempts = 1, lastSeen = expectedAppleSeconds))

        val androidContract = NoUpdateCountDatabase(db)
        runRecovery(androidContract, nowMilliseconds)
        runRecovery(androidContract, nowMilliseconds + 1)

        val untagged = db.query("SELECT last_seen, last_seen_source, last_seen_source_version, last_seen_unit " +
            "FROM lesson_state WHERE lesson_id = 'untagged-legacy'").single()
        assertEquals(unixSeconds, untagged.real("last_seen"), 0.0)
        assertEquals(null, untagged.textOrNull("last_seen_source"))
        val repaired = db.query("SELECT last_seen, last_seen_source_version, last_seen_unit " +
            "FROM lesson_state WHERE lesson_id = 'evidenced-legacy'").single()
        assertEquals(expectedAppleSeconds, repaired.real("last_seen"), 0.0001)
        assertEquals(1, repaired.int("last_seen_source_version"))
        assertEquals("unix_seconds", repaired.textOrNull("last_seen_unit"))
        assertEquals(listOf("evidenced-legacy", "valid-current"),
            LessonStateDao.syncEligible(db, nowMilliseconds).map { it.lessonID }.sorted())
        assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM progress_recovery WHERE " +
            "source_table = 'lesson_state' AND source_key = 'evidenced-legacy' AND state = 'repaired'")
            .single().long("n"))
    }

    @Test fun ambiguousTimestampIsRetainedAndValidRowsStillSync() {
        val ambiguous = com.jkdc.app.core.CompletionMark("exercise", "ambiguous", 123.0, 40, "es")
        CompletionsDao.add(db, ambiguous)
        LearningDbWriter.progressRecoveryRecorded(db, ProgressRecoveryRecord(null, "completions", "ambiguous", 1,
            "unresolved", "missing source version evidence", 500))
        LearningDbWriter.remoteProgressPageApplied(db, "owner-a", listOf(
            RemoteProgressRollup("owner-a", "es", "exercise", "valid", 100, 200, 2, 90, 2)), null, 1, 500)
        assertEquals(ambiguous, CompletionsDao.all(db).single())
        assertEquals(listOf("valid"), RemoteProgressRollupsDao.forLanguage(db, "owner-a", "es").map { it.itemID })
    }

    @Test fun newLocalCompletionWritesPersistTimestampEvidenceWithoutRelabelingOldRows() {
        val columns = db.query("PRAGMA table_info(completions)").map { it.text("name") }.toSet()
        assertTrue("completion timestamp provenance columns are required", setOf(
            "at_source", "at_source_version", "at_unit").all(columns::contains))

        val legacy = com.jkdc.app.core.CompletionMark("exercise", "legacy", 700_000_000.0, 40, "es")
        val current = com.jkdc.app.core.CompletionMark("exercise", "current", 800_000_000.0, 90, "es")
        CompletionsDao.add(db, legacy)
        LearningDbWriter.completions(db, listOf(legacy, current))

        val rows = db.query("SELECT item_id, at, at_source, at_source_version, at_unit FROM completions ORDER BY item_id")
        val old = rows.single { it.text("item_id") == "legacy" }
        assertEquals(700_000_000.0, old.real("at"), 0.0)
        assertEquals(null, old.textOrNull("at_source"))
        val new = rows.single { it.text("item_id") == "current" }
        assertEquals("android.recordCompletion", new.text("at_source"))
        assertEquals(2, new.int("at_source_version"))
        assertEquals("apple_reference_seconds", new.text("at_unit"))

        val lessonMark = com.jkdc.app.core.CompletionMark("lesson", "lesson-a", 900_000_000.0, 0, "es")
        LearningDbWriter.lessonAttempt(db, com.jkdc.app.core.LessonState("lesson-a", attempts = 1,
            lastSeen = 900_000_000.0), listOf(lessonMark), emptyList())
        val lesson = db.query("SELECT last_seen_source, last_seen_source_version, last_seen_unit FROM lesson_state WHERE lesson_id = 'lesson-a'").single()
        assertEquals("android.recordLessonAttempt", lesson.text("last_seen_source"))
        assertEquals(2, lesson.int("last_seen_source_version"))
        assertEquals("apple_reference_seconds", lesson.text("last_seen_unit"))
    }

    @Test fun recoverySecondRunIsIdempotent() {
        val marker = ProgressRecoveryRecord("owner-a", "completions", "item-a", 2,
            "unresolved", "ambiguous timestamp", 500)
        repeat(2) { LearningDbWriter.progressRecoveryRecorded(db, marker) }
        assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM progress_recovery").single().long("n"))
        assertEquals("unresolved", db.query("SELECT state FROM progress_recovery").single().text("state"))
    }

    @Test fun recoveryExcludesInvalidTimestampsWithoutRewritingLegacyValues() {
        val nowMilliseconds = System.currentTimeMillis()
        val valid = com.jkdc.app.core.CompletionMark("exercise", "valid", com.jkdc.app.core.nowSeconds(), 40, "es")
        val invalidLegacy = com.jkdc.app.core.CompletionMark("exercise", "invalid-legacy", 1_700_000_000.0, 40, "es")
        val invalidCurrent = com.jkdc.app.core.CompletionMark("exercise", "invalid-current", 1_700_000_000.0, 90, "es")
        CompletionsDao.add(db, invalidLegacy)
        LearningDbWriter.completions(db, listOf(invalidLegacy, invalidCurrent, valid))
        LearningDbWriter.remoteProgressPageApplied(db, "owner-a", listOf(
            RemoteProgressRollup("owner-a", "es", "exercise", "remote-valid", nowMilliseconds,
                nowMilliseconds, 1, 90, 2)), null, 1, nowMilliseconds)

        runRecovery(db, nowMilliseconds)
        runRecovery(db, nowMilliseconds + 1)

        val values = db.query("SELECT item_id, at FROM completions ORDER BY item_id")
            .associate { it.text("item_id") to it.real("at") }
        assertEquals(1_700_000_000.0, values["invalid-legacy"]!!, 0.0)
        assertEquals(1_700_000_000.0, values["invalid-current"]!!, 0.0)
        assertEquals(setOf("valid"), syncEligible(db, nowMilliseconds).map { it.itemID }.toSet())
        assertEquals(listOf(0L, 2L), db.query("SELECT source_version FROM progress_recovery WHERE state = 'excluded' ORDER BY source_version")
            .map { it.long("source_version") })
        assertEquals(listOf("remote-valid"), db.query("SELECT item_id FROM remote_progress_rollups WHERE owner_account_id = 'owner-a' AND language_id = 'es'")
            .map { it.text("item_id") })
    }

    @Test fun recoveryMarkerFailureRollsBackAndReplayIsIdempotent() {
        val file = File.createTempFile("progress-recovery", ".db")
        try {
            var target = openFileBacked(file)
            val now = System.currentTimeMillis()
            val good = com.jkdc.app.core.CompletionMark("exercise", "good", com.jkdc.app.core.nowSeconds(), 1, "es")
            val invalidLegacy = com.jkdc.app.core.CompletionMark("exercise", "invalid-legacy", 1_700_000_000.0, 1, "es")
            val invalidCurrent = com.jkdc.app.core.CompletionMark("exercise", "invalid-current", 1_700_000_000.0, 2, "es")
            CompletionsDao.add(target, invalidLegacy)
            LearningDbWriter.completions(target, listOf(invalidLegacy, invalidCurrent, good))
            val unixSeconds = 1_750_000_000.125
            LessonStateDao.upsert(target,
                LessonState("evidenced-legacy", attempts = 1, lastSeen = unixSeconds),
                TimestampEvidence("ios.recordLessonAttempt", 1, "unix_seconds"))
            target.exec("CREATE TRIGGER abort_recovery BEFORE INSERT ON progress_recovery " +
                "WHEN NEW.source_version = 1 BEGIN SELECT RAISE(ABORT, 'injected recovery failure'); END")
            assertThrows(Exception::class.java) { runRecovery(target, now) }
            assertEquals(0L, target.query("SELECT COUNT(*) AS n FROM progress_recovery").single().long("n"))
            assertEquals(unixSeconds, target.query("SELECT last_seen FROM lesson_state WHERE lesson_id = 'evidenced-legacy'")
                .single().real("last_seen"), 0.0)
            target.close()

            target = openFileBacked(file)
            assertEquals(1_700_000_000.0, target.query("SELECT at FROM completions WHERE item_id = 'invalid-legacy'")
                .single().real("at"), 0.0)
            target.exec("DROP TRIGGER abort_recovery")
            runRecovery(target, now)
            assertEquals(771_692_800.125, target.query(
                "SELECT last_seen FROM lesson_state WHERE lesson_id = 'evidenced-legacy'").single().real("last_seen"), 0.0001)
            assertEquals("repaired", target.query("SELECT state FROM progress_recovery WHERE " +
                "source_table = 'lesson_state' AND source_key = 'evidenced-legacy'").single().text("state"))
            val firstRun = target.query("SELECT source_version, state FROM progress_recovery ORDER BY source_version")
                .map { "${it.long("source_version")}:${it.text("state")}" }
            runRecovery(target, now + 1)
            assertEquals(firstRun, target.query("SELECT source_version, state FROM progress_recovery ORDER BY source_version")
                .map { "${it.long("source_version")}:${it.text("state")}" })
            target.close()
        } finally { file.delete() }
    }

    private fun runRecovery(target: SqlDatabase, nowMilliseconds: Long) {
        LearningDbWriter.progressRecoveryRun(target, nowMilliseconds)
    }

    private fun syncEligible(target: SqlDatabase, nowMilliseconds: Long): List<com.jkdc.app.core.CompletionMark> {
        return CompletionsDao.syncEligible(target, nowMilliseconds)
    }

    @Test fun projectionDoesNotAppendLocalEvents() {
        val mark = com.jkdc.app.core.CompletionMark("exercise", "item-a", 1.0, 40, "es")
        CompletionsDao.add(db, mark)
        val sessionsBefore = db.query("SELECT COUNT(*) AS n FROM sessions").single().long("n")
        val completionsBefore = CompletionsDao.all(db)
        val projection = ProgressProjection.merge("owner-a", "es", listOf(
            ProgressAggregate("owner-a", "es", "exercise", "item-a", 100, 200, 1, 40)), listOf(
            ProgressAggregate("owner-a", "es", "exercise", "item-a", 50, 300, 3, 90)))
        assertEquals(90, projection.single().bestScore)
        assertEquals(sessionsBefore, db.query("SELECT COUNT(*) AS n FROM sessions").single().long("n"))
        assertEquals(completionsBefore, CompletionsDao.all(db))
    }

    private fun openFileBacked(file: File): SqlDatabase {
        val target = JvmSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"))
        target.exec("PRAGMA foreign_keys = ON")
        val version = target.query("PRAGMA user_version").single().values.single() as Long
        when {
            version == 0L -> SchemaData.LEARNING_SCHEMA_STATEMENTS.forEach(target::exec)
            version == LearningDb.VERSION.toLong() -> Unit
            else -> {
                target.transaction { LearningDb.upgrade(it, version.toInt()) }
                target.exec("PRAGMA user_version = ${LearningDb.VERSION}")
            }
        }
        return target
    }

    @Test fun schemaOpensAtVersionSevenWithExactlyThePinnedTables() {
        assertEquals(7L, db.query("PRAGMA user_version").single().values.single())
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .map { it["name"] as String }
        // fragments_fts is the pinned v2 virtual table; fragments_fts_* are its
        // FTS4 shadow tables (SQLite internals, like sqlite_sequence).
        assertEquals(listOf("assessment_tickets", "completions", "exam_answers", "exam_attempts",
            "exam_results", "exam_snapshot_ids", "exam_snapshots", "exam_sync_conflicts", "exam_sync_outbox",
            "expressions", "fragments",
            "fragments_fts", "fragments_fts_docsize", "fragments_fts_segdir",
            "fragments_fts_segments", "fragments_fts_stat",
            "lesson_state", "meta", "preferences", "progress_recovery", "progress_sync_state",
            "remote_progress_rollups", "sessions", "skill_errors", "usage_outbox"), tables)
        assertFalse("words must never be stored; hidden words live in preferences", tables.contains("words"))
        val virtual = db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'fragments_fts'")
            .single().values.single() as String
        // FTS4 — not FTS5 — because Android's framework SQLite enables the
        // FTS3/FTS4 module only; sqlite-jdbc happens to bundle FTS5 too, so
        // this assertion is what keeps a future edit from regressing to it.
        assertTrue(virtual.contains("USING fts4"))
        assertTrue(virtual.contains("content='fragments'"))
        assertTrue(virtual.contains("tokenize='unicode61'"))
        assertFalse("FTS5 does not exist in the Android framework build",
            virtual.contains("fts5"))
        assertEquals(listOf("assessment_tickets_owner_insert", "completions_owner_insert",
            "exam_answers_owner_insert", "exam_attempts_owner_insert", "exam_results_owner_insert",
            "exam_snapshot_ids_no_update", "exam_snapshots_no_update", "exam_sync_conflicts_owner_insert",
            "exam_sync_outbox_owner_insert", "expressions_owner_insert", "fragments_fts_after_insert",
            "fragments_fts_after_update", "fragments_fts_before_delete", "fragments_fts_before_update",
            "fragments_owner_insert", "lesson_state_owner_insert", "preferences_owner_insert",
            "progress_sync_state_owner_insert", "remote_progress_rollups_owner_insert", "sessions_owner_insert",
            "skill_errors_owner_insert", "usage_outbox_owner_insert",
            ),
            db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name")
                .map { it["name"] as String })
    }

    @Test fun fts4IndexUsableThroughTheSchemaStatementsOnTheJvmBackend() {
        // The JVM backend (xerial sqlite-jdbc) bundles the same FTS3/FTS4
        // module the Android framework enables, so this proves the statements
        // AND the module are usable end to end: insert → indexed by the
        // trigger, edit → reindexed, cascade delete → unindexed.
        db.insert("sessions", mapOf("id" to "s1", "language_id" to "es", "started_at" to 0.0,
            "title" to "Hola"))
        db.insert("fragments", mapOf("id" to "f1", "session_id" to "s1", "sort_index" to 0,
            "speaker" to "user", "text" to "Humo de prueba", "revision" to 0,
            "start_ms" to 0L, "end_ms" to 1L, "received_at" to 1.0))
        assertEquals(1L, db.query(
            "SELECT COUNT(*) AS n FROM fragments_fts WHERE fragments_fts MATCH 'humo'")
            .single()["n"])
        db.update("UPDATE fragments SET text = 'Cambio limpio' WHERE id = 'f1'")
        assertEquals(0L, db.query(
            "SELECT COUNT(*) AS n FROM fragments_fts WHERE fragments_fts MATCH 'humo'")
            .single()["n"])
        assertEquals(1L, db.query(
            "SELECT COUNT(*) AS n FROM fragments_fts WHERE fragments_fts MATCH 'limpio'")
            .single()["n"])
        db.exec("DELETE FROM sessions")
        assertEquals(0L, db.query(
            "SELECT COUNT(*) AS n FROM fragments_fts WHERE fragments_fts MATCH 'limpio'")
            .single()["n"])
        // DEVICE CAVEAT: the JVM backend cannot prove what AOSP compiled into
        // the framework SQLite. The device-side probe (real emulator, real
        // framework SQLite opening this schema) is owned by the phase-G
        // task-2 e2e evidence run.
    }

    @Test fun upgradeFromV1KeepsFragmentsSearchableAndConvergesOnTheFreshInventory() {
        // a separate in-memory database: setUp's already holds a fresh v2
        val v1Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            val v1 = LearningDb.openJvmV1(v1Connection)
            v1.insert("sessions", mapOf("id" to "s1", "language_id" to "es", "started_at" to 1.0,
                "title" to "Historia"))
            v1.insert("fragments", mapOf("id" to "f1", "session_id" to "s1", "sort_index" to 0,
                "speaker" to "user", "text" to "palabra heredada", "revision" to 0,
                "start_ms" to 0L, "end_ms" to 1L, "received_at" to 1.0))
            assertThrows(SQLException::class.java) {
                v1.query("SELECT rowid FROM fragments_fts WHERE fragments_fts MATCH 'heredada'")
            }
            // what the Helper's onUpgrade does, verbatim: one transaction with
            // the delta, then the recorded version
            v1.transaction { LearningDb.upgrade(it, 1) }
            v1.exec("PRAGMA user_version = ${LearningDb.VERSION}")
            assertEquals(7L, v1.query("PRAGMA user_version").single().values.single())
            // the 'rebuild' indexed the fragment v1 already had, and a NEW write
            // (the insert trigger) is searchable too
            assertEquals(listOf("s1"), SearchDao.search(v1, "heredada", "es").map { it.session.id })
            v1.insert("fragments", mapOf("id" to "f2", "session_id" to "s1", "sort_index" to 1,
                "speaker" to "assistant", "text" to "respuesta nueva", "revision" to 0,
                "start_ms" to 1L, "end_ms" to 2L, "received_at" to 2.0))
            assertEquals(listOf("s1"), SearchDao.search(v1, "respuesta", "es").map { it.session.id })
            // upgrade and fresh install converge: same objects in sqlite_master,
            // same stored statements
            fun inventory(target: SqlDatabase) = target.query(
                "SELECT type, name, COALESCE(sql, '') AS sql FROM sqlite_master " +
                    "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")
                .map { Triple(it.text("type"), it.text("name"), it.text("sql")) }
            assertEquals(inventory(db), inventory(v1))
        } finally {
            v1Connection.close()
        }
    }

    @Test fun upgradeRejectsVersionsItCannotReach() {
        assertThrows(IllegalArgumentException::class.java) { LearningDb.upgrade(db, 0) }
        assertThrows(IllegalArgumentException::class.java) { LearningDb.upgrade(db, LearningDb.VERSION) }
        assertThrows(IllegalArgumentException::class.java) { LearningDb.upgrade(db, 99) }
    }

    @Test fun everySupportedPriorVersionUpgradesToTheFreshInventory() {
        fun inventory(target: SqlDatabase) = target.query(
            "SELECT type, name, COALESCE(sql, '') AS sql FROM sqlite_master " +
                "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")
            .map { Triple(it.text("type"), it.text("name"), it.text("sql")) }
        val expected = inventory(db)
        for (fromVersion in 1 until LearningDb.VERSION) {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            try {
                val old = JvmSqlDatabase(connection)
                old.exec("PRAGMA foreign_keys = ON")
                for (statement in SchemaData.LEARNING_SCHEMA_STATEMENTS) {
                    old.exec(statement)
                    if (statement == "PRAGMA user_version = $fromVersion") break
                }
                old.transaction { LearningDb.upgrade(it, fromVersion) }
                old.exec("PRAGMA user_version = ${LearningDb.VERSION}")
                assertEquals("upgrade from v$fromVersion", expected, inventory(old))
            } finally { connection.close() }
        }
    }

    @Test fun foreignKeysAreEnforcedOnTheConnection() {
        assertEquals(1L, db.query("PRAGMA foreign_keys").single().values.single())
    }

    @Test fun startingAndSavingAnExamKeepsItsSnapshotAnswersPositionAndOutboxTogether() {
        val started = ExamHistoryDao.start(db, sampleSnapshot("exam-1", "content-1"),
            ExamAttemptRecord("attempt-1", "exam-1", startedAt = 10.0))
        assertEquals("{\"question\":\"frozen\"}", started.snapshot.text("content_json"))
        assertEquals("in_progress", started.attempt.text("state"))
        assertEquals("created", db.query("SELECT event_type FROM exam_sync_outbox").single().text("event_type"))
        assertEquals(2L, ExamHistoryDao.saveAnswer(db, "attempt-1", "q1", "{\"answer\":\"a\"}", 1, 11.0))
        val resumed = ExamHistoryDao.load(db, "attempt-1")!!
        assertEquals(1L, resumed.attempt.long("position"))
        assertEquals("{\"answer\":\"a\"}", resumed.answers.single().text("response_json"))
        assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox").single().long("n"))
    }

    @Test fun completionIsAtomicAndIdempotentButCannotReplaceTheOriginalResult() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-2", "content-2"),
            ExamAttemptRecord("attempt-2", "exam-2", startedAt = 20.0))
        val result = "{\"score\":80,\"verdicts\":[true]}"
        assertTrue(ExamHistoryDao.complete(db, "attempt-2", result, 30.0))
        assertFalse(ExamHistoryDao.complete(db, "attempt-2", result, 31.0))
        assertThrows(IllegalStateException::class.java) {
            ExamHistoryDao.complete(db, "attempt-2", "{\"score\":90}", 32.0)
        }
        val history = ExamHistoryDao.load(db, "attempt-2")!!
        assertEquals("completed", history.attempt.text("state"))
        assertEquals(result, history.result!!.text("result_json"))
        assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox").single().long("n"))
    }

    @Test fun failedProgressOutboxRollsBackTheAnswerAndPosition() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-progress", "content-progress"),
            ExamAttemptRecord("attempt-progress", "exam-progress", startedAt = 21.0))
        db.exec("CREATE TRIGGER reject_progress BEFORE INSERT ON exam_sync_outbox " +
            "WHEN new.event_type = 'progress' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertThrows(SQLException::class.java) {
            ExamHistoryDao.saveAnswer(db, "attempt-progress", "q1", "{\"answer\":\"lost\"}", 9, 22.0)
        }
        val history = ExamHistoryDao.load(db, "attempt-progress")!!
        assertEquals(0L, history.attempt.long("position"))
        assertEquals(1L, history.attempt.long("revision"))
        assertTrue(history.answers.isEmpty())
        assertEquals(1L, count("exam_sync_outbox"))
    }

    @Test fun failedCompletionOutboxRollsBackTheResultAndState() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-complete", "content-complete"),
            ExamAttemptRecord("attempt-complete", "exam-complete", startedAt = 23.0))
        db.exec("CREATE TRIGGER reject_completion BEFORE INSERT ON exam_sync_outbox " +
            "WHEN new.event_type = 'completed' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertThrows(SQLException::class.java) {
            ExamHistoryDao.complete(db, "attempt-complete", "{\"score\":80}", 24.0)
        }
        val history = ExamHistoryDao.load(db, "attempt-complete")!!
        assertEquals("in_progress", history.attempt.text("state"))
        assertEquals(null, history.result)
        assertEquals(1L, history.attempt.long("revision"))
    }

    @Test fun localHistoryReadsAreScopedToTheActiveAccount() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-owner-a", "content-owner-a"),
            ExamAttemptRecord("attempt-owner-a", "exam-owner-a", "account-a", 25.0))
        assertEquals("account-a", ExamHistoryDao.load(db, "attempt-owner-a", "account-a")!!
            .attempt.text("owner_account_id"))
        assertEquals(null, ExamHistoryDao.load(db, "attempt-owner-a", "account-b"))
        assertEquals(null, ExamHistoryDao.load(db, "attempt-owner-a"))
    }

    @Test fun outboxAcknowledgementIsRevisionExactAndPreservesNewerEdits() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-sync", "content-sync"),
            ExamAttemptRecord("attempt-sync", "exam-sync", "account-sync", 25.0))
        val created = db.query("SELECT event_id FROM exam_sync_outbox WHERE revision = 1").single().text("event_id")
        assertTrue(ExamHistoryDao.acknowledgeOutbox(db, "account-sync", "attempt-sync", created, 1, 26.0))
        assertEquals("synced", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").single().text("sync_state"))

        assertEquals(2L, ExamHistoryDao.saveAnswer(db, "attempt-sync", "q1", "{\"answer\":\"first\"}", 1, 27.0))
        assertEquals("pending", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").single().text("sync_state"))
        assertEquals(3L, ExamHistoryDao.saveAnswer(db, "attempt-sync", "q1", "{\"answer\":\"newer\"}", 2, 28.0))
        val revisionTwo = db.query("SELECT event_id FROM exam_sync_outbox WHERE revision = 2").single().text("event_id")
        assertTrue(ExamHistoryDao.acknowledgeOutbox(db, "account-sync", "attempt-sync", revisionTwo, 2, 29.0))
        assertEquals("pending", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").single().text("sync_state"))
        assertEquals("pending", db.query("SELECT delivery_state FROM exam_sync_outbox WHERE revision = 3").single().text("delivery_state"))
        assertTrue(ExamHistoryDao.acknowledgeOutbox(db, "account-sync", "attempt-sync", revisionTwo, 2, 30.0))
        assertFalse(ExamHistoryDao.acknowledgeOutbox(db, "other-account", "attempt-sync", revisionTwo, 2, 30.0))
        val revisionThree = db.query("SELECT event_id FROM exam_sync_outbox WHERE revision = 3").single().text("event_id")
        assertTrue(ExamHistoryDao.acknowledgeOutbox(db, "account-sync", "attempt-sync", revisionThree, 3, 31.0))
        assertEquals("synced", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").single().text("sync_state"))
        assertTrue(ExamHistoryDao.markConflict(db, "account-sync", "attempt-sync"))
        assertEquals("conflict", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-sync'").single().text("sync_state"))
    }

    @Test fun examSyncDaoDoesNotDependOnAndroidUpdateRowCounts() {
        val owner = "account-no-row-count"
        ExamHistoryDao.start(db, sampleSnapshot("exam-no-count", "content-no-count"),
            ExamAttemptRecord("attempt-no-count", "exam-no-count", owner, 40.0))
        val androidContract = NoUpdateCountDatabase(db)
        val created = db.query("SELECT event_id FROM exam_sync_outbox WHERE attempt_id = ?", listOf("attempt-no-count"))
            .single().text("event_id")
        assertTrue(ExamHistoryDao.acknowledgeOutbox(androidContract, owner, "attempt-no-count", created, 1, 41.0))
        assertEquals("synced", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = ?", listOf("attempt-no-count"))
            .single().text("sync_state"))

        val server = """{"attemptId":"attempt-no-count","examId":"exam-no-count","revision":4,"state":"in_progress","position":0,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(androidContract, owner, "attempt-no-count", server, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictTakeover(androidContract, owner, "attempt-no-count", 43.0))
        assertEquals("takeover", db.query("SELECT event_type FROM exam_sync_outbox WHERE attempt_id = ? AND delivery_state = 'pending'",
            listOf("attempt-no-count")).single().text("event_type"))

        assertTrue(ExamHistoryDao.persistConflict(androidContract, owner, "attempt-no-count",
            server.replace("\"revision\":4", "\"revision\":6"), 44.0))
        assertTrue(ExamHistoryDao.resolveConflictFork(androidContract, owner, "attempt-no-count", "attempt-no-count-fork", 45.0))
    }

    @Test fun pendingOutboxOrdersEachAttemptByRevisionInsteadOfWallClock() {
        val owner = "account-clock-rollback"
        ExamHistoryDao.start(db, sampleSnapshot("exam-clock", "content-clock"),
            ExamAttemptRecord("attempt-clock", "exam-clock", owner, 100.0))
        ExamHistoryDao.saveAnswer(db, "attempt-clock", "q1", "{\"answer\":1}", 1, 101.0)
        ExamHistoryDao.saveAnswer(db, "attempt-clock", "q1", "{\"answer\":2}", 1, 99.0)
        assertEquals(listOf(1L, 2L, 3L), ExamHistoryDao.pendingOutbox(db, owner)
            .map { it.long("revision") })
    }

    @Test fun aConflictPersistsBothLocalAndServerBranchesBeforeResolution() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-branch", "content-branch"),
            ExamAttemptRecord("attempt-branch", "exam-branch", "account-branch", 40.0))
        ExamHistoryDao.saveAnswer(db, "attempt-branch", "q1", "{\"answer\":\"local\"}", 2, 41.0)
        val server = """{"attemptId":"attempt-branch","revision":2,"state":"in_progress","position":1,"answers":[{"questionId":"q1","response":{"answer":"server"}}]}"""
        assertTrue(ExamHistoryDao.persistConflict(db, "account-branch", "attempt-branch", server, 42.0))
        val conflict = ExamHistoryDao.conflict(db, "account-branch", "attempt-branch")!!
        assertEquals(2L, conflict.long("server_revision"))
        assertEquals(server, conflict.text("server_attempt_json"))
        assertTrue(conflict.text("local_attempt_json").contains("local"))
        assertEquals("conflict", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-branch'").single().text("sync_state"))
        assertFalse(ExamHistoryDao.persistConflict(db, "other-account", "attempt-branch", server, 43.0))
    }

    @Test fun explicitTakeoverAdoptsServerBranchAndSupersedesLocalEvents() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-takeover", "content-takeover"),
            ExamAttemptRecord("attempt-takeover", "exam-takeover", "account-takeover", 40.0))
        ExamHistoryDao.saveAnswer(db, "attempt-takeover", "q1", "{\"answer\":\"local\"}", 2, 41.0)
        val server = """{"attemptId":"attempt-takeover","examId":"exam-takeover","revision":4,"state":"in_progress","position":1,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[{"questionId":"q1","revision":3,"response":{"answer":"server"}}]}"""
        assertTrue(ExamHistoryDao.persistConflict(db, "account-takeover", "attempt-takeover", server, 43.0))
        assertTrue(ExamHistoryDao.resolveConflictTakeover(db, "account-takeover", "attempt-takeover", 44.0))
        val attempt = db.query("SELECT revision, sync_state FROM exam_attempts WHERE attempt_id = 'attempt-takeover'").single()
        assertEquals(5L, attempt.long("revision")); assertEquals("pending", attempt.text("sync_state"))
        assertEquals("server", db.query("SELECT response_json FROM exam_answers WHERE attempt_id = 'attempt-takeover'")
            .single().text("response_json").let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["answer"]!!.jsonPrimitive.content })
        assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox WHERE attempt_id = 'attempt-takeover' AND delivery_state = 'superseded'").single().long("n"))
        val takeover = db.query("SELECT event_type, revision FROM exam_sync_outbox WHERE attempt_id = 'attempt-takeover' AND delivery_state = 'pending'").single()
        assertEquals("takeover", takeover.text("event_type")); assertEquals(5L, takeover.long("revision"))
        assertEquals("takeover", ExamHistoryDao.conflict(db, "account-takeover", "attempt-takeover")!!.text("resolution"))
    }

    @Test fun takeoverCanResolveASecondConflictAfterTheServerAdvancesAgain() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-repeat", "content-repeat"),
            ExamAttemptRecord("attempt-repeat", "exam-repeat", "account-repeat", 40.0))
        val first = """{"attemptId":"attempt-repeat","examId":"exam-repeat","revision":2,"state":"in_progress","position":0,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, "account-repeat", "attempt-repeat", first, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictTakeover(db, "account-repeat", "attempt-repeat", 43.0))
        val second = first.replace("\"revision\":2", "\"revision\":4").replace("00:00:42", "00:00:44")
        assertTrue(ExamHistoryDao.persistConflict(db, "account-repeat", "attempt-repeat", second, 44.0))
        assertEquals(null, ExamHistoryDao.conflict(db, "account-repeat", "attempt-repeat")!!["resolution"])
        assertTrue(ExamHistoryDao.resolveConflictTakeover(db, "account-repeat", "attempt-repeat", 45.0))
    }

    @Test fun takingOverNullableServerResultDoesNotBlockLaterCompletion() {
        ExamHistoryDao.start(db, sampleSnapshot("exam-null-result", "content-null-result"),
            ExamAttemptRecord("attempt-null-result", "exam-null-result", "account-null-result", 40.0))
        val server = """{"attemptId":"attempt-null-result","examId":"exam-null-result","revision":2,"state":"in_progress","position":0,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, "account-null-result", "attempt-null-result", server, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictTakeover(db, "account-null-result", "attempt-null-result", 43.0))
        assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM exam_results WHERE attempt_id = 'attempt-null-result'").single().long("n"))
        assertTrue(ExamHistoryDao.complete(db, "attempt-null-result", "{\"score\":80}", 44.0))
    }

    @Test fun explicitForkCreatesNewPendingAttemptWithLocalAnswersAndFrozenSnapshot() = runBlocking {
        val forkOwner = "11111111-1111-4111-8111-111111111111"
        ExamHistoryDao.start(db, sampleSnapshot("exam-fork", "content-fork"),
            ExamAttemptRecord("attempt-fork-source", "exam-fork", forkOwner, 40.0))
        ExamHistoryDao.saveAnswer(db, "attempt-fork-source", "q1", "{\"answer\":\"local\"}", 2, 41.0)
        val server = """{"attemptId":"attempt-fork-source","examId":"exam-fork","revision":3,"state":"in_progress","position":1,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, forkOwner, "attempt-fork-source", server, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictFork(db, forkOwner, "attempt-fork-source", "attempt-fork-copy", 43.0))
        val fork = db.query("SELECT state, sync_state, exam_id FROM exam_attempts WHERE attempt_id = 'attempt-fork-copy'").single()
        assertEquals("in_progress", fork.text("state")); assertEquals("pending", fork.text("sync_state")); assertEquals("exam-fork", fork.text("exam_id"))
        assertEquals("local", kotlinx.serialization.json.Json.parseToJsonElement(
            db.query("SELECT response_json FROM exam_answers WHERE attempt_id = 'attempt-fork-copy'").single().text("response_json")
        ).jsonObject["answer"]!!.jsonPrimitive.content)
        val created = db.query("SELECT event_type FROM exam_sync_outbox WHERE attempt_id = 'attempt-fork-copy'").single()
        assertEquals("created", created.text("event_type"))
        assertEquals("fork", ExamHistoryDao.conflict(db, forkOwner, "attempt-fork-source")!!.text("resolution"))
        assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM exam_sync_outbox WHERE attempt_id = 'attempt-fork-source' " +
            "AND delivery_state = 'superseded'").single().long("n"))
        assertEquals(2L, ExamHistoryDao.saveAnswer(db, "attempt-fork-copy", "q1", "{\"answer\":\"later\"}", 1, 44.0))
        var uploadedAttempt: kotlinx.serialization.json.JsonObject? = null
        val sentEvents = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val sync = ExamAttemptOutboxSync(
            sendCreation = { _, _, attempt ->
                uploadedAttempt = attempt
                buildJsonObject { put("accepted", buildJsonArray { add(buildJsonObject {
                    put("attemptId", "attempt-fork-copy"); put("revision", 1)
                }) }) }
            },
            sendEvent = { _, event ->
                sentEvents += event
                buildJsonObject { put("acks", buildJsonArray { add(buildJsonObject {
                    put("eventId", event.getValue("eventId")); put("attemptId", event.getValue("attemptId"))
                    put("revision", event.getValue("revision")); put("status", "accepted")
                }) }) }
            },
            acknowledge = { account, id, event, revision, acceptedRevision, at ->
                ExamHistoryDao.acknowledgeOutbox(db, account, id, event, revision, at, acceptedRevision)
            },
            conflict = { _, _, _, _ -> error("Unexpected conflict") },
        )
        sync.drain(db, AccountSession(forkOwner, "A".repeat(43), 9_999_999_999_999L)) { 44.0 }
        val uploadedAnswer = uploadedAttempt!!["answers"]!!.jsonArray.single().jsonObject
        assertEquals("q1", uploadedAnswer["questionId"]!!.jsonPrimitive.content)
        assertTrue(uploadedAnswer["response"].toString().contains("local"))
        assertEquals(1L, uploadedAttempt!!["revision"]!!.jsonPrimitive.content.toLong())
        assertEquals(2L, sentEvents.single()["revision"]!!.jsonPrimitive.content.toLong())
        assertTrue(sentEvents.single()["payload"].toString().contains("later"))
    }

    @Test fun legacyForkCreationEventWithoutFrozenAttemptStillReplays() = runBlocking {
        val owner = "11111111-1111-4111-8111-111111111111"
        ExamHistoryDao.start(db, sampleSnapshot("exam-fork-legacy", "content-fork-legacy"),
            ExamAttemptRecord("attempt-fork-source-legacy", "exam-fork-legacy", owner, 40.0))
        ExamHistoryDao.saveAnswer(db, "attempt-fork-source-legacy", "q1", "{\"answer\":\"local\"}", 2, 41.0)
        val server = """{"attemptId":"attempt-fork-source-legacy","examId":"exam-fork-legacy","revision":3,"state":"in_progress","position":1,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, owner, "attempt-fork-source-legacy", server, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictFork(db, owner, "attempt-fork-source-legacy", "attempt-fork-legacy", 43.0))
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(
            db.query("SELECT payload_json FROM exam_sync_outbox WHERE attempt_id = 'attempt-fork-legacy' AND event_type = 'created'").single().text("payload_json")
        ).jsonObject - "initialAttempt"
        assertEquals("true", payload["forkedLocalState"]?.jsonPrimitive?.content)
        assertEquals(1, db.query("SELECT question_id FROM exam_answers WHERE attempt_id = 'attempt-fork-legacy'").size)
        db.transaction { tx ->
            tx.update("UPDATE exam_sync_outbox SET payload_json = ? WHERE attempt_id = ? AND event_type = 'created'",
                listOf(kotlinx.serialization.json.JsonObject(payload).toString(), "attempt-fork-legacy"))
        }
        assertEquals(2L, ExamHistoryDao.saveAnswer(db, "attempt-fork-legacy", "q1", "{\"answer\":\"later\"}", 1, 43.5))
        var uploaded: kotlinx.serialization.json.JsonObject? = null
        val sync = ExamAttemptOutboxSync(
            sendCreation = { _, _, attempt ->
                uploaded = attempt
                buildJsonObject { put("accepted", buildJsonArray { add(buildJsonObject {
                    put("attemptId", "attempt-fork-legacy"); put("revision", attempt.getValue("revision"))
                }) }) }
            },
            sendEvent = { _, _ -> error("The accepted creation snapshot must cover its earlier edits") },
            acknowledge = { account, id, event, revision, acceptedRevision, at ->
                ExamHistoryDao.acknowledgeOutbox(db, account, id, event, revision, at, acceptedRevision)
            },
            conflict = { _, _, _, _ -> error("Unexpected conflict") },
        )

        sync.drain(db, AccountSession(owner, "A".repeat(43), 9_999_999_999_999L)) { 44.0 }

        assertEquals(2L, uploaded!!.getValue("revision").jsonPrimitive.content.toLong())
        assertTrue(uploaded!!.getValue("answers").jsonArray.isNotEmpty())
        assertEquals("synced", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-fork-legacy'").single().text("sync_state"))
    }

    @Test fun legacyForkCreationRetryKeepsSnapshotAfterLostAckAndOfflineEdit() = runBlocking {
        val owner = "11111111-1111-4111-8111-111111111111"
        ExamHistoryDao.start(db, sampleSnapshot("exam-fork-retry", "content-fork-retry"),
            ExamAttemptRecord("attempt-fork-source-retry", "exam-fork-retry", owner, 40.0))
        ExamHistoryDao.saveAnswer(db, "attempt-fork-source-retry", "q1", "{\"answer\":\"original\"}", 2, 41.0)
        val server = """{"attemptId":"attempt-fork-source-retry","examId":"exam-fork-retry","revision":3,"state":"in_progress","position":1,"startedAt":"1970-01-01T00:00:40.000Z","updatedAt":"1970-01-01T00:00:42.000Z","answers":[],"result":null}"""
        assertTrue(ExamHistoryDao.persistConflict(db, owner, "attempt-fork-source-retry", server, 42.0))
        assertTrue(ExamHistoryDao.resolveConflictFork(db, owner, "attempt-fork-source-retry", "attempt-fork-retry", 43.0))
        db.transaction { tx ->
            val row = tx.query("SELECT payload_json FROM exam_sync_outbox WHERE attempt_id = ? AND event_type = 'created'", listOf("attempt-fork-retry")).single()
            val payload = kotlinx.serialization.json.Json.parseToJsonElement(row.text("payload_json")).jsonObject - "initialAttempt"
            tx.update("UPDATE exam_sync_outbox SET payload_json = ? WHERE attempt_id = ? AND event_type = 'created'",
                listOf(kotlinx.serialization.json.JsonObject(payload).toString(), "attempt-fork-retry"))
        }
        var firstCreation: kotlinx.serialization.json.JsonObject? = null
        var creationCalls = 0
        fun sync() = ExamAttemptOutboxSync(
            sendCreation = { _, _, attempt ->
                creationCalls++
                if (firstCreation == null) {
                    firstCreation = attempt
                    error("Simulated lost creation ACK")
                }
                assertEquals(firstCreation, attempt)
                buildJsonObject { put("accepted", buildJsonArray { add(buildJsonObject {
                    put("attemptId", "attempt-fork-retry"); put("revision", attempt.getValue("revision"))
                }) }) }
            },
            sendEvent = { _, event ->
                assertEquals(2L, event.getValue("revision").jsonPrimitive.content.toLong())
                assertTrue(event.getValue("payload").toString().contains("edited-after-lost-ack"))
                buildJsonObject { put("acks", buildJsonArray { add(buildJsonObject {
                    put("eventId", event.getValue("eventId")); put("attemptId", event.getValue("attemptId"))
                    put("revision", event.getValue("revision")); put("status", "accepted")
                }) }) }
            },
            acknowledge = { account, id, event, revision, acceptedRevision, at ->
                ExamHistoryDao.acknowledgeOutbox(db, account, id, event, revision, at, acceptedRevision)
            },
            conflict = { _, _, _, _ -> error("Unexpected conflict") },
        )

        var lostAck = false
        try {
            sync().drain(db, AccountSession(owner, "A".repeat(43), 9_999_999_999_999L)) { 44.0 }
        } catch (_: IllegalStateException) {
            lostAck = true
        }
        assertTrue(lostAck)
        ExamHistoryDao.saveAnswer(db, "attempt-fork-retry", "q1", "{\"answer\":\"edited-after-lost-ack\"}", 1, 44.5)
        sync().drain(db, AccountSession(owner, "A".repeat(43), 9_999_999_999_999L)) { 45.0 }

        assertEquals(2, creationCalls)
        assertEquals(1L, firstCreation!!.getValue("revision").jsonPrimitive.content.toLong())
        assertTrue(firstCreation!!.getValue("answers").jsonArray.single().toString().contains("original"))
        assertEquals("synced", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = 'attempt-fork-retry'").single().text("sync_state"))
    }

    @Test fun conflictPausesOnlyItsAttemptAndKeepsItPausedAcrossDrains() = runBlocking {
        val owner = "11111111-1111-4111-8111-111111111111"
        val attemptA = "attempt-conflict-a"
        val attemptB = "attempt-conflict-b"
        ExamHistoryDao.start(db, sampleSnapshot("exam-conflict-a", "content-conflict-a"),
            ExamAttemptRecord(attemptA, "exam-conflict-a", owner, 100.0))
        ExamHistoryDao.start(db, sampleSnapshot("exam-conflict-b", "content-conflict-b"),
            ExamAttemptRecord(attemptB, "exam-conflict-b", owner, 100.0))
        for (attemptID in listOf(attemptA, attemptB)) {
            val created = ExamHistoryDao.pendingOutbox(db, owner).single {
                it.text("attempt_id") == attemptID && it.text("event_type") == "created"
            }
            assertTrue(ExamHistoryDao.acknowledgeOutbox(db, owner, attemptID, created.text("event_id"),
                created.long("revision"), 101.0, 1))
        }
        ExamHistoryDao.saveAnswer(db, attemptA, "q1", "{\"answer\":\"a1\"}", 1, 102.0)
        ExamHistoryDao.saveAnswer(db, attemptA, "q2", "{\"answer\":\"a2\"}", 2, 103.0)
        ExamHistoryDao.saveAnswer(db, attemptB, "q1", "{\"answer\":\"b1\"}", 1, 102.0)

        val sent = mutableListOf<String>()
        val sync = ExamAttemptOutboxSync(
            sendCreation = { _, _, _ -> error("Creation events were already acknowledged") },
            sendEvent = { _, event ->
                val id = event.getValue("attemptId").jsonPrimitive.content
                val eventID = event.getValue("eventId").jsonPrimitive.content
                sent += eventID
                if (id == attemptA && event.getValue("revision").jsonPrimitive.content.toLong() == 2L) {
                    buildJsonObject { put("conflict", buildJsonObject { put("attempt", buildJsonObject {
                        put("attemptId", attemptA); put("revision", 9)
                    }) }) }
                } else {
                    buildJsonObject { put("acks", buildJsonArray { add(buildJsonObject {
                        put("eventId", eventID); put("attemptId", id)
                        put("revision", event.getValue("revision")); put("status", "accepted")
                    }) }) }
                }
            },
            acknowledge = { account, id, event, revision, acceptedRevision, at ->
                ExamHistoryDao.acknowledgeOutbox(db, account, id, event, revision, at, acceptedRevision)
            },
            conflict = { account, id, _, _ -> ExamHistoryDao.markConflict(db, account, id) },
        )
        val session = AccountSession(owner, "A".repeat(43), 9_999_999_999_999L)

        val bEventID = ExamHistoryDao.pendingOutbox(db, owner).single {
            it.text("attempt_id") == attemptB && it.text("event_type") == "progress"
        }.text("event_id")

        sync.drain(db, session) { 104.0 }
        val aStates = db.query("SELECT revision, delivery_state FROM exam_sync_outbox WHERE attempt_id = ? " +
            "AND event_type = 'progress' ORDER BY revision", listOf(attemptA))
        assertEquals(listOf(2L, 3L), aStates.map { it.long("revision") })
        assertEquals(listOf("pending", "pending"), aStates.map { it.text("delivery_state") })
        assertTrue(sent.contains(bEventID))
        assertEquals("acknowledged", db.query("SELECT delivery_state FROM exam_sync_outbox WHERE event_id = ?",
            listOf(bEventID)).single().text("delivery_state"))
        assertTrue(ExamHistoryDao.pendingOutbox(db, owner).none { it.text("attempt_id") == attemptA })
        assertEquals("conflict", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = ?", listOf(attemptA))
            .single().text("sync_state"))

        val sentAfterConflict = sent.toList()
        sync.drain(db, session) { 105.0 }
        assertEquals(sentAfterConflict, sent)
        assertEquals(listOf("pending", "pending"), db.query(
            "SELECT delivery_state FROM exam_sync_outbox WHERE attempt_id = ? AND event_type = 'progress' ORDER BY revision",
            listOf(attemptA)).map { it.text("delivery_state") })
    }

    @Test fun outboxSyncCreatesInitialAttemptThenSendsRevisionEventsAndAcknowledgesExactRows() = runBlocking {
        val owner = "11111111-1111-4111-8111-111111111111"
        val attemptID = "22222222-2222-4222-8222-222222222222"
        ExamHistoryDao.start(db, sampleSnapshot("exam-sync-flow", "content-sync-flow"),
            ExamAttemptRecord(attemptID, "exam-sync-flow", owner, 100.125))
        ExamHistoryDao.saveAnswer(db, attemptID, "q1", "{\"answer\":\"local\"}", 1, 101.0)
        ExamHistoryDao.complete(db, attemptID, "{\"score\":80}", 102.0)
        val delivered = mutableListOf<String>()
        val sync = ExamAttemptOutboxSync(
            sendCreation = { session, snapshot, initial ->
                assertEquals(owner, session.accountID)
                assertEquals(attemptID, initial.getValue("attemptId").toString().trim('"'))
                assertEquals(1L, initial.getValue("revision").toString().toLong())
                assertEquals("content-sync-flow", snapshot.getValue("contentId").toString().trim('"'))
                delivered += "created"
                buildJsonObject { put("accepted", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject { put("attemptId", attemptID); put("revision", 1) })
                }) }
            },
            sendEvent = { _, event ->
                val type = event.getValue("type").toString().trim('"')
                delivered += type
                buildJsonObject { put("acks", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("eventId", event.getValue("eventId")); put("attemptId", event.getValue("attemptId"))
                        put("revision", event.getValue("revision")); put("status", "accepted")
                    })
                }) }
            },
            acknowledge = { account, id, event, revision, acceptedRevision, at ->
                ExamHistoryDao.acknowledgeOutbox(db, account, id, event, revision, at, acceptedRevision)
            },
            conflict = { _, _, _, _ -> error("Unexpected conflict") },
        )
        sync.drain(db, AccountSession(owner, "A".repeat(43), 9_999_999_999_999L)) { 103.0 }
        assertEquals(listOf("created", "progress", "completed"), delivered)
        assertTrue(ExamHistoryDao.pendingOutbox(db, owner).isEmpty())
        assertEquals("synced", db.query("SELECT sync_state FROM exam_attempts WHERE attempt_id = ?", listOf(attemptID))
            .single().text("sync_state"))
    }

    @Test fun failedOutboxInsertRollsBackAnExamStart() {
        db.exec("CREATE TRIGGER reject_exam_outbox BEFORE INSERT ON exam_sync_outbox BEGIN " +
            "SELECT RAISE(ABORT, 'test failure'); END")
        assertThrows(SQLException::class.java) {
            ExamHistoryDao.start(db, sampleSnapshot("exam-3", "content-3"),
                ExamAttemptRecord("attempt-3", "exam-3", startedAt = 40.0))
        }
        assertEquals(0L, (count("exam_snapshots") as Number).toLong())
        assertEquals(0L, (count("exam_snapshot_ids") as Number).toLong())
        assertEquals(0L, (count("exam_attempts") as Number).toLong())
    }

    @Test fun equalContentSnapshotsRetainDistinctExamIDs() {
        val first = sampleSnapshot("exam-a", "content-shared")
        val firstAttempt = ExamAttemptRecord("attempt-a", "exam-a", startedAt = 50.0)
        ExamHistoryDao.start(db, first, firstAttempt)
        ExamHistoryDao.start(db, first, firstAttempt)
        ExamHistoryDao.start(db, first.copy(examID = "exam-b"),
            ExamAttemptRecord("attempt-b", "exam-b", startedAt = 51.0))
        assertEquals(1L, (count("exam_snapshots") as Number).toLong())
        assertEquals(2L, (count("exam_snapshot_ids") as Number).toLong())
        assertEquals(2L, (count("exam_sync_outbox") as Number).toLong())
        assertEquals("exam-b", ExamHistoryDao.load(db, "attempt-b")!!.attempt.text("exam_id"))
    }

    @Test fun deletingASessionCascadesToItsFragments() {
        db.insert("sessions", mapOf("id" to "s1", "language_id" to "es", "started_at" to 0.0, "title" to "Hola"))
        db.insert("fragments", mapOf("id" to "f1", "session_id" to "s1", "sort_index" to 0,
            "speaker" to "user", "text" to "Hola", "revision" to 0,
            "start_ms" to 0L, "end_ms" to 100L, "received_at" to 1.0))
        assertEquals(1L, count("fragments"))
        db.exec("DELETE FROM sessions")
        assertEquals(0L, count("fragments"))
    }

    @Test fun insertResolvesConflictsWithTheRequestedStrategy() {
        db.insert("meta", mapOf("key" to "k", "value" to "first"))
        assertEquals(-1L, db.insert("meta", mapOf("key" to "k", "value" to "second"), ConflictStrategy.IGNORE))
        assertEquals("first", db.query("SELECT value FROM meta WHERE key = 'k'").single()["value"])
        assertThrows(SQLException::class.java) {
            db.insert("meta", mapOf("key" to "k", "value" to "third"), ConflictStrategy.ABORT)
        }
    }

    @Test fun updateReportsAffectedRowsOnTheJvmBackend() {
        db.insert("meta", mapOf("key" to "a", "value" to "1"))
        db.insert("meta", mapOf("key" to "b", "value" to "1"))
        db.insert("meta", mapOf("key" to "c", "value" to "2"))
        assertEquals(2L, db.update("UPDATE meta SET value = '9' WHERE value = ?", listOf("1")))
        assertEquals(2L, db.query("SELECT COUNT(*) AS n FROM meta WHERE value = '9'").single()["n"])
    }

    @Test fun transactionCommitsTheWorkDoneInsideTheBlock() {
        db.transaction { it.insert("meta", mapOf("key" to "k", "value" to "kept")) }
        assertEquals("kept", db.query("SELECT value FROM meta WHERE key = 'k'").single()["value"])
    }

    @Test fun transactionRollsBackOnFailureAndRejectsNesting() {
        assertThrows(IllegalStateException::class.java) {
            db.transaction { d ->
                d.insert("meta", mapOf("key" to "k", "value" to "lost"))
                error("boom")
            }
        }
        assertEquals(0L, count("meta"))
        assertThrows(IllegalStateException::class.java) {
            db.transaction { it.transaction { } }
        }
        assertEquals(0L, count("meta"))
    }

    @Test fun nullBindArgumentsAreRejectedOnQueryAndUpdate() {
        db.insert("meta", mapOf("key" to "k", "value" to "v"))
        assertThrows(IllegalArgumentException::class.java) {
            db.query("SELECT value FROM meta WHERE key != ?", listOf<Any?>(null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            db.update("UPDATE meta SET value = 'x' WHERE key != ?", listOf<Any?>(null))
        }
        // inserts still carry null values: that is column data, not a comparison
        db.insert("completions", mapOf("kind" to "lesson", "item_id" to "i1", "at" to 1.0, "language_id" to null))
        assertEquals(1L, count("completions"))
    }

    private fun count(table: String) = db.query("SELECT COUNT(*) AS n FROM $table").single()["n"]

    private class NoUpdateCountDatabase(private val delegate: SqlDatabase) : SqlDatabase {
        override fun exec(statement: String) = delegate.exec(statement)
        override fun insert(table: String, row: Map<String, Any?>, strategy: ConflictStrategy) =
            delegate.insert(table, row, strategy)
        override fun query(sql: String, args: List<Any?>) = delegate.query(sql, args)
        override fun update(sql: String, args: List<Any?>): Long {
            delegate.update(sql, args)
            return -1L
        }
        override fun <T> transaction(block: (SqlDatabase) -> T): T =
            delegate.transaction { tx -> block(NoUpdateCountDatabase(tx)) }
        override fun close() = delegate.close()
    }

    private fun sampleSnapshot(examID: String, contentID: String) = ExamSnapshotRecord(
        examID, contentID, "hash-$contentID", 1, 1, 1, "bundled", "es", "B1", 202641,
        "{\"question\":\"frozen\"}", 1.0)
}
