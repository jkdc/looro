package com.jkdc.app.data

import com.jkdc.app.core.LevelTestEngine
import com.jkdc.app.core.LevelTestResult
import com.jkdc.app.network.LevelTestSelection
import com.jkdc.app.network.LevelTestSnapshotAdapter
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull

/** Durable local-first attempt lifecycle. Every mutation delegates to ExamHistoryDao's transaction. */
internal class LevelTestAttemptStore(private val db: SqlDatabase) {
    data class QuestionReview(
        val questionID: String,
        val response: String,
        val acceptedAnswers: List<String>,
        val correct: Boolean,
        val explanation: String,
        val passageID: String?,
    )

    data class Restored(
        val attemptID: String,
        val selection: LevelTestSelection,
        val position: Int,
        val startedAt: Long,
        val responses: Map<String, String>,
        val result: LevelTestResult?,
        val questionReviews: List<QuestionReview>,
        val syncState: String,
    )

    fun start(
        selection: LevelTestSelection,
        attemptID: String = UUID.randomUUID().toString(),
        ownerAccountID: String? = null,
        startedAt: Double = System.currentTimeMillis() / 1000.0,
    ): ExamHistory {
        val snapshot = Json.parseToJsonElement(selection.snapshotJSON).jsonObject
        val examID = requireNotNull(selection.examID) { "Exam snapshot has no immutable exam ID" }
        val contentID = requireNotNull(selection.contentID) { "Exam snapshot has no immutable content ID" }
        val record = ExamSnapshotRecord(
            examID = examID,
            contentID = contentID,
            contentHash = snapshot.getValue("contentHash").jsonPrimitive.content,
            schemaVersion = snapshot.getValue("schemaVersion").jsonPrimitive.content.toInt(),
            contentRevision = snapshot.getValue("contentRevision").jsonPrimitive.content.toInt(),
            gradingVersion = snapshot.getValue("gradingVersion").jsonPrimitive.content.toInt(),
            source = selection.source,
            languageID = selection.level.languageID,
            level = selection.level.level,
            weekID = selection.exam.weekIndex,
            contentJSON = selection.snapshotJSON,
            createdAt = startedAt,
        )
        return ExamHistoryDao.start(db, record, ExamAttemptRecord(attemptID, examID, ownerAccountID, startedAt))
    }

    fun saveAnswer(attemptID: String, questionID: String, response: String, position: Int, savedAt: Double): Long =
        ExamHistoryDao.saveAnswer(db, attemptID, questionID,
            buildJsonObject { put("value", response) }.toString(), position, savedAt)

    fun complete(
        attemptID: String,
        resultJSON: String,
        completedAt: Double,
        finalAnswers: Map<String, String> = emptyMap(),
        position: Int? = null,
    ): Boolean = ExamHistoryDao.complete(db, attemptID, resultJSON, completedAt, finalAnswers, position)

    fun load(attemptID: String, ownerAccountID: String? = null): Restored? {
        val history = ExamHistoryDao.load(db, attemptID, ownerAccountID) ?: return null
        val snapshotJSON = history.snapshot.text("content_json")
        val selection = LevelTestSnapshotAdapter.restore(snapshotJSON)
        val responses = history.answers.associate { answer ->
            answer.text("question_id") to Json.parseToJsonElement(answer.text("response_json"))
                .jsonObject.getValue("value").jsonPrimitive.content
        }
        val result = history.result?.let { row ->
            val json = Json.parseToJsonElement(row.text("result_json")).jsonObject
            LevelTestResult(
                languageID = json.getValue("languageID").jsonPrimitive.content,
                level = json.getValue("level").jsonPrimitive.content,
                weekIndex = json.getValue("weekId").jsonPrimitive.content.toLong(),
                overall = json.getValue("overall").jsonPrimitive.content.toInt(),
                sections = json.getValue("sections").jsonObject.map { (name, score) -> name to score.jsonPrimitive.content.toInt() },
                passed = json.getValue("passed").jsonPrimitive.content.toBooleanStrict(),
                nextBand = json["nextBand"]?.jsonPrimitive?.contentOrNull,
                weakSections = json.getValue("weakSections").jsonArray.map { it.jsonPrimitive.content },
                weakUnitIDs = json.getValue("weakUnitIDs").jsonArray.map { it.jsonPrimitive.content },
                at = json.getValue("at").jsonPrimitive.content.toLong(),
            )
        }
        val questionReviews = history.result?.let { row ->
            val json = Json.parseToJsonElement(row.text("result_json")).jsonObject
            json["questionReviews"]?.jsonArray?.map { value ->
                val review = value.jsonObject
                QuestionReview(
                    questionID = review.getValue("questionId").jsonPrimitive.content,
                    response = review.getValue("response").jsonPrimitive.content,
                    acceptedAnswers = review.getValue("acceptedAnswers").jsonArray.map { it.jsonPrimitive.content },
                    correct = review.getValue("correct").jsonPrimitive.content.toBooleanStrict(),
                    explanation = review.getValue("explanation").jsonPrimitive.content,
                    passageID = review["passageId"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }.orEmpty()
        return Restored(history.attempt.text("attempt_id"), selection, history.attempt.int("position"),
            (history.attempt.real("started_at") * 1000).toLong(), responses,
            result, questionReviews, history.attempt.text("sync_state"))
    }

    fun list(languageID: String, level: String, ownerAccountID: String?, limit: Int = 20): List<Restored> {
        require(limit in 1..100)
        val ownerPredicate = if (ownerAccountID == null) "a.owner_account_id IS NULL" else "a.owner_account_id = ?"
        val identityPredicate = ownerClause(db, "a")
        val arguments = buildList {
            add(languageID)
            add(level)
            if (ownerAccountID != null) add(ownerAccountID)
            addAll(ownerArguments(db))
            add(limit)
        }
        return db.query(
            "SELECT a.attempt_id, a.owner_account_id FROM exam_attempts a " +
                "JOIN exam_snapshot_ids i ON i.exam_id = a.exam_id " +
                "JOIN exam_snapshots s ON s.content_id = i.content_id " +
                "WHERE s.language_id = ? AND s.level = ? AND $ownerPredicate AND $identityPredicate " +
                "ORDER BY a.updated_at DESC, a.attempt_id LIMIT ?",
            arguments,
        ).mapNotNull { row -> load(row.text("attempt_id"), ownerAccountID) }
    }
}
