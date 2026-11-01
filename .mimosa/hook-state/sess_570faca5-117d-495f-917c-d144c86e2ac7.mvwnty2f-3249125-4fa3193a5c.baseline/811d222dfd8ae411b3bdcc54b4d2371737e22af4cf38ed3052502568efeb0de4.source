package com.jkdc.app.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import com.jkdc.app.core.AccountSession
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

sealed interface ProgressFetchResult {
    data class Success(val body: String) : ProgressFetchResult
    data object VersionUnavailable : ProgressFetchResult
    data object Cancelled : ProgressFetchResult
    data class Failure(val cause: Throwable? = null) : ProgressFetchResult
}

sealed interface ProgressSendResult {
    data class Acknowledged(val merged: Int) : ProgressSendResult
    data object VersionUnavailable : ProgressSendResult
    data object Cancelled : ProgressSendResult
    data class Failure(val cause: Throwable? = null) : ProgressSendResult
}

/** Authenticated transport for the existing managed progress endpoint. */
class ProgressClient internal constructor(
    private val origin: HttpUrl,
    transport: OkHttpClient,
    private val nowUnixMilliseconds: () -> Long = System::currentTimeMillis,
) {
    constructor(config: ManagedAccountConfiguration) : this(config.origin, OkHttpClient())
    private val client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).callTimeout(25, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun send(session: AccountSession, batch: String): Boolean =
        when (val result = sendV2(session, batch)) { is ProgressSendResult.Acknowledged -> true; else -> false }

    suspend fun sendV2(session: AccountSession, entries: String): ProgressSendResult {
        val entriesArray = try { json.parseToJsonElement(entries).jsonObject["entries"]?.toString() ?: entries }
            catch (_: Exception) { entries }
        val entryRows = try { json.parseToJsonElement(entriesArray).jsonArray } catch (_: Exception) { return ProgressSendResult.Failure() }
        if (entryRows.size > 200 || entryRows.any { value ->
                val row = value.jsonObject
                val kind = (row["kind"] as? JsonPrimitive)?.contentOrNull
                val item = (row["itemID"] as? JsonPrimitive)?.contentOrNull
                val first = (row["firstAt"] as? JsonPrimitive)?.let(::safeUnixMilliseconds)
                val last = (row["lastAt"] as? JsonPrimitive)?.let(::safeUnixMilliseconds)
                val attempts = (row["attempts"] as? JsonPrimitive)?.intOrNull
                val score = (row["bestScore"] as? JsonPrimitive)?.intOrNull
                kind.isNullOrEmpty() || item.isNullOrEmpty() || first == null || last == null || first <= 0 || last < first ||
                    attempts == null || attempts !in 1..10_000 || score == null || score !in 0..100
            }) return ProgressSendResult.Failure()
        val request = Request.Builder().url(endpoint()).header("Accept", "application/json")
            .header("Cache-Control", "no-store").header("Authorization", "Bearer ${session.accessToken}")
            .post("{\"version\":2,\"entries\":$entriesArray}".toRequestBody("application/json".toMediaType())).build()
        return execute(request, ProgressSendResult.Failure()) { response ->
            val body = response.body?.string().orEmpty()
            if (isVersionUnavailable(response.code, body)) ProgressSendResult.VersionUnavailable
            else if (!response.isSuccessful) ProgressSendResult.Failure()
            else try {
                val root = json.parseToJsonElement(body).jsonObject
                val merged = root["merged"] as? JsonPrimitive
                val count = merged?.intOrNull
                if (count == null || merged.isString || count != entryRows.size) ProgressSendResult.Failure()
                else ProgressSendResult.Acknowledged(count)
            } catch (e: Exception) { ProgressSendResult.Failure(e) }
        }
    }

    suspend fun fetch(session: AccountSession): String? =
        (fetchV2(session) as? ProgressFetchResult.Success)?.body

    suspend fun fetchV2(session: AccountSession, cursor: String? = null): ProgressFetchResult {
        val url = endpoint().newBuilder().addQueryParameter("version", "2")
            .apply { if (cursor != null) addQueryParameter("cursor", cursor) }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("Cache-Control", "no-store").header("Authorization", "Bearer ${session.accessToken}").get().build()
        return execute(request, ProgressFetchResult.Failure()) { response ->
            val body = response.body?.string().orEmpty()
            if (isVersionUnavailable(response.code, body)) ProgressFetchResult.VersionUnavailable
            else if (!response.isSuccessful) ProgressFetchResult.Failure()
            else if (validPage(body)) ProgressFetchResult.Success(body) else ProgressFetchResult.Failure()
        }
    }


    suspend fun fetchBootstrap(
        session: AccountSession,
        initialCursor: String?,
        onPageAccepted: (suspend (String, String?, Boolean) -> Boolean)? = null,
        isCurrent: suspend () -> Boolean,
    ): ProgressFetchResult {
        val pages = mutableListOf<String>()
        var cursor = initialCursor
        repeat(10_000) { index ->
            if (!isCurrent()) return ProgressFetchResult.Failure()
            when (val result = fetchV2(session, cursor)) {
                is ProgressFetchResult.Success -> {
                    if (!isCurrent()) return ProgressFetchResult.Failure()
                    val root = try { json.parseToJsonElement(result.body).jsonObject } catch (_: Exception) { return ProgressFetchResult.Failure() }
                    val complete = (root["complete"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: return ProgressFetchResult.Failure()
                    val next = if (root["nextCursor"] == kotlinx.serialization.json.JsonNull) null else (root["nextCursor"] as? JsonPrimitive)?.contentOrNull ?: return ProgressFetchResult.Failure()
                    if (!complete && (index == 9_999 || next == cursor)) return ProgressFetchResult.Failure()
                    if (onPageAccepted == null) pages += result.body
                    else if (!onPageAccepted(result.body, next, complete)) return ProgressFetchResult.Failure()
                    if (complete) return ProgressFetchResult.Success("{\"legacy\":false,\"pages\":[${pages.joinToString(",")}]}")
                    cursor = next
                }
                ProgressFetchResult.VersionUnavailable -> {
                    if (index != 0 || !isCurrent()) return ProgressFetchResult.Failure()
                    return fetchLegacy(session, initialCursor, isCurrent)
                }
                else -> return result
            }
        }
        return ProgressFetchResult.Failure()
    }

    private suspend fun fetchLegacy(session: AccountSession, preserveCursor: String?, isCurrent: suspend () -> Boolean): ProgressFetchResult {
        val request = Request.Builder().url(endpoint()).header("Accept", "application/json")
            .header("Cache-Control", "no-store").header("Authorization", "Bearer ${session.accessToken}").get().build()
        val result = execute(request, ProgressFetchResult.Failure()) { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) ProgressFetchResult.Failure()
            else try {
                val rows = json.parseToJsonElement(body).jsonObject["progress"]?.jsonArray ?: return@execute ProgressFetchResult.Failure()
                if (rows.size > 1000) return@execute ProgressFetchResult.Failure()
                // Preserve only a strict, complete legacy envelope; never interpret an arbitrary success as fallback data.
                val normalizedRows = rows.map { value ->
                    val row = value.jsonObject
                    for (field in listOf("kind", "itemID", "attempts", "bestScore", "language")) if (row[field] == null) return@execute ProgressFetchResult.Failure()
                    val kind = row["kind"] as? JsonPrimitive ?: return@execute ProgressFetchResult.Failure()
                    val item = row["itemID"] as? JsonPrimitive ?: return@execute ProgressFetchResult.Failure()
                    if (!kind.isString || kind.content !in legacyKinds || !item.isString || !legacySlug.matches(item.content)) return@execute ProgressFetchResult.Failure()
                    val language = row["language"]
                    if (language != kotlinx.serialization.json.JsonNull &&
                        ((language as? JsonPrimitive)?.let { it.isString && it.content in legacyLanguages } != true)) return@execute ProgressFetchResult.Failure()
                    val oldAt = row["at"] as? JsonPrimitive
                    val first = if (row.containsKey("firstAt") || row.containsKey("lastAt")) row["firstAt"] as? JsonPrimitive else oldAt
                    val last = if (row.containsKey("firstAt") || row.containsKey("lastAt")) row["lastAt"] as? JsonPrimitive else oldAt
                    val firstAt = first?.let(::safeUnixMilliseconds) ?: return@execute ProgressFetchResult.Failure()
                    val lastAt = last?.let(::safeUnixMilliseconds) ?: return@execute ProgressFetchResult.Failure()
                    if (lastAt < firstAt) return@execute ProgressFetchResult.Failure()
                    val attempts = row["attempts"] as? JsonPrimitive ?: return@execute ProgressFetchResult.Failure()
                    val score = row["bestScore"] as? JsonPrimitive ?: return@execute ProgressFetchResult.Failure()
                    if (attempts.isString || attempts.intOrNull !in 1..10_000 || score.isString || score.intOrNull !in 0..100) return@execute ProgressFetchResult.Failure()
                    JsonObject(row + mapOf("firstAt" to JsonPrimitive(firstAt), "lastAt" to JsonPrimitive(lastAt)))
                }
                ProgressFetchResult.Success("{\"legacy\":true,\"preserveCursor\":${JsonPrimitive(preserveCursor).toString()},\"rows\":${normalizedRows.joinToString(prefix="[", postfix="]")}}")
            } catch (_: Exception) { ProgressFetchResult.Failure() }
        }
        return if (isCurrent()) result else ProgressFetchResult.Failure()
    }

    suspend fun sendLegacy(session: AccountSession, entriesJson: String): ProgressSendResult {
        val array = try { json.parseToJsonElement(entriesJson).jsonObject["entries"]?.jsonArray ?: return ProgressSendResult.Failure() }
            catch (_: Exception) { return ProgressSendResult.Failure() }
        val entries = array.map { value ->
            val row = value.jsonObject
            "{\"kind\":${row["kind"]},\"itemID\":${row["itemID"]},\"at\":${row["lastAt"]},\"attempts\":${row["attempts"]},\"bestScore\":${row["bestScore"]},\"language\":${row["language"]}}"
        }
        val request = Request.Builder().url(endpoint()).header("Accept", "application/json")
            .header("Authorization", "Bearer ${session.accessToken}")
            .post("{\"entries\":[${entries.joinToString(",")}] }".toRequestBody("application/json".toMediaType())).build()
        return execute(request, ProgressSendResult.Failure()) { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) ProgressSendResult.Failure() else try {
                val merged = json.parseToJsonElement(body).jsonObject["merged"] as? JsonPrimitive
                val count = merged?.intOrNull
                if (count == null || merged.isString) ProgressSendResult.Failure() else ProgressSendResult.Acknowledged(count)
            } catch (e: Exception) { ProgressSendResult.Failure(e) }
        }
    }

    private fun endpoint() = origin.newBuilder().addPathSegments("v1/progress").build()

    private val legacySlug = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private val legacyKinds = setOf(
        com.jkdc.app.core.ProgressEngine.LESSON, com.jkdc.app.core.ProgressEngine.CONVERSATION_THEME,
        com.jkdc.app.core.ProgressEngine.EXERCISE_SET, com.jkdc.app.core.ProgressEngine.LISTENING,
        com.jkdc.app.core.ProgressEngine.READING, com.jkdc.app.core.ProgressEngine.PRONUNCIATION,
        com.jkdc.app.core.ProgressEngine.RECALL_ROUND, com.jkdc.app.core.ProgressEngine.WORDS_PRACTICE,
        com.jkdc.app.core.ProgressEngine.ASSESSMENT, com.jkdc.app.core.ProgressEngine.SCENARIO_PRACTICE,
    )
    private val legacyLanguages = com.jkdc.app.core.LanguageRegistry.all.map { it.id }.toSet() + "legacy"

    private fun isVersionUnavailable(status: Int, body: String): Boolean = status == 503 && try {
        val error = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
        error?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull } == "progress_sync_version_unavailable"
    } catch (_: Exception) { false }

    private fun validPage(body: String): Boolean {
        return try {
        val root = json.parseToJsonElement(body).jsonObject
        val versionValue = root["version"] as? JsonPrimitive ?: return false
        val version = if (versionValue.isString) null else versionValue.intOrNull
        val progress = root["progress"]?.jsonArray ?: return false
        if (!root.containsKey("nextCursor")) return false
        val cursorElement = root["nextCursor"]
        val cursor = if (cursorElement == kotlinx.serialization.json.JsonNull) null else {
            val value = cursorElement as? JsonPrimitive ?: return false
            if (!value.isString) return false
            value.content
        }
        val completeValue = root["complete"] as? JsonPrimitive ?: return false
        if (completeValue.isString) return false
        val complete = completeValue.content.toBooleanStrictOrNull() ?: return false
        if (version != 2 || complete != (cursor == null) || progress.size > 1000 || (cursor != null && (cursor.isEmpty() || cursor.length > 2048))) return false
        var previous: Triple<String, String, String?>? = null
        fun compare(a: Triple<String, String, String?>, b: Triple<String, String, String?>): Int {
            val kindOrder = a.first.compareTo(b.first)
            if (kindOrder != 0) return kindOrder
            val itemOrder = a.second.compareTo(b.second)
            if (itemOrder != 0) return itemOrder
            val aLanguage = a.third
            val bLanguage = b.third
            return when {
                aLanguage == bLanguage -> 0
                aLanguage == null -> 1
                bLanguage == null -> -1
                else -> aLanguage.compareTo(bLanguage)
            }
        }
        progress.forEach { value ->
            val row = value.jsonObject
            val kind = (row["kind"] as? JsonPrimitive)?.contentOrNull ?: return false
            val item = (row["itemID"] as? JsonPrimitive)?.contentOrNull ?: return false
            if (kind.isEmpty() || item.isEmpty() || !row.containsKey("language")) return false
            val language = (row["language"] as? JsonPrimitive)?.contentOrNull
            if (row["language"] != null && row["language"] != kotlinx.serialization.json.JsonNull && language == null) return false
            if (language != null && language.isEmpty()) return false
            val firstAt = (row["firstAt"] as? JsonPrimitive)?.let(::safeUnixMilliseconds) ?: return false
            val lastAt = (row["lastAt"] as? JsonPrimitive)?.let(::safeUnixMilliseconds) ?: return false
            if (lastAt < firstAt) return false
            val attemptsValue = row["attempts"] as? JsonPrimitive ?: return false
            val scoreValue = row["bestScore"] as? JsonPrimitive ?: return false
            val attempts = if (attemptsValue.isString) null else attemptsValue.intOrNull
            val score = if (scoreValue.isString) null else scoreValue.intOrNull
            if (attempts == null || attempts !in 1..10_000 || score == null || score !in 0..100) return false
            val key = Triple(kind, item, language)
            if (previous?.let { compare(it, key) >= 0 } == true) return false
            previous = key
        }
        true
        } catch (_: Exception) { false }
    }

    private fun safeUnixMilliseconds(value: JsonPrimitive): Long? {
        if (value.isString) return null
        val number = value.doubleOrNull ?: return null
        if (!number.isFinite() || number % 1.0 != 0.0 || kotlin.math.abs(number) > 9_007_199_254_740_991.0) return null
        val milliseconds = number.toLong()
        if (milliseconds.toDouble() != number) return null
        return milliseconds.takeIf {
            com.jkdc.app.core.ProgressTime.fromUnixMilliseconds(it.toDouble(), nowUnixMilliseconds().toDouble()) != null
        }
    }

    private suspend fun <T> execute(request: Request, failure: T, parse: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(failure)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (continuation.isActive) continuation.resume(parse(it))
                    }
                }
            })
        }
}
