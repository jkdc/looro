package com.jkdc.app.network

import com.jkdc.app.core.LevelTestEngine
import com.jkdc.app.core.LevelTestLevel
import com.jkdc.app.core.LevelTestsRegistry
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

internal sealed interface LevelTestCatalogStatus {
    data class Remote(val source: String, val cached: Boolean) : LevelTestCatalogStatus
    data class BundledFallback(val reason: RemoteLevelTestFailure?) : LevelTestCatalogStatus
}

internal class LevelTestCatalogRepository(
    origin: HttpUrl?,
    private val cache: LevelTestCatalogSnapshotStore,
    transport: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val client = origin?.let { LevelTestRemoteClient(it, transport) }
    private data class AvailableSelection(val selection: LevelTestSelection, val expiresAtMillis: Long)
    private val availableSelections = java.util.concurrent.ConcurrentHashMap<String, AvailableSelection>()
    private val mutableStatuses = MutableStateFlow<Map<String, LevelTestCatalogStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, LevelTestCatalogStatus>> = mutableStatuses

    fun select(language: String, level: String, weekID: Long = utcWeekID()): LevelTestSelection? {
        val key = key(language, level)
        val available = availableSelections[key]
        if (available != null && available.expiresAtMillis > nowMillis() &&
            available.selection.exam.weekIndex == weekID) return available.selection
        val bundled = LevelTestsRegistry.level(language, level) ?: return null
        return bundledSelection(bundled, weekID)
    }

    suspend fun refresh(language: String, level: String, weekID: Long = utcWeekID()): LevelTestCatalogStatus =
        withContext(ioDispatcher) {
            val key = key(language, level)
            val fallback = LevelTestsRegistry.level(language, level)?.let { bundledSelection(it, weekID) }
            val remote = client
            if (remote == null || fallback == null) {
                return@withContext publish(key, LevelTestCatalogStatus.BundledFallback(null))
            }

            val cached = cache.read(language, level, VARIANT)
            val cachedSelection = cached?.let { decode(it.body, language, level, weekID) }
            val etag = cachedSelection?.let { cached?.etag }
            when (val response = remote.fetch(language, level, weekID, VARIANT, etag)) {
                is RemoteLevelTestResponse.Updated -> {
                    val selection = decode(response.body, language, level, weekID)
                        ?: return@withContext publish(key, LevelTestCatalogStatus.BundledFallback(RemoteLevelTestFailure.INVALID_RESPONSE))
                    val expiry = expiry(nowMillis(), response.maxAgeSeconds, selection)
                    val stored = response.maxAgeSeconds != null && response.maxAgeSeconds > 0 &&
                        cache.write(CachedLevelTestSnapshot(language, level, VARIANT, response.body, response.etag, expiry))
                    if (stored) availableSelections[key] = AvailableSelection(selection, expiry)
                    else availableSelections.remove(key)
                    publish(key, LevelTestCatalogStatus.Remote(selection.source, stored))
                }
                is RemoteLevelTestResponse.NotModified -> {
                    if (cached == null || cachedSelection == null || etag == null) {
                        return@withContext publish(key, LevelTestCatalogStatus.BundledFallback(RemoteLevelTestFailure.INVALID_RESPONSE))
                    }
                    val expiry = expiry(nowMillis(), response.maxAgeSeconds, cachedSelection)
                    val stored = response.maxAgeSeconds != null && response.maxAgeSeconds > 0 &&
                        cache.write(cached.copy(expiresAtMillis = expiry))
                    if (stored) availableSelections[key] = AvailableSelection(cachedSelection, expiry)
                    else availableSelections.remove(key)
                    publish(key, LevelTestCatalogStatus.Remote(cachedSelection.source, stored))
                }
                is RemoteLevelTestResponse.Failed ->
                    publish(key, LevelTestCatalogStatus.BundledFallback(response.reason))
            }
        }

    private fun expiry(now: Long, maxAgeSeconds: Long?, selection: LevelTestSelection): Long {
        val httpExpiry = maxAgeSeconds?.let { now + it.coerceAtMost(MAX_CACHE_SECONDS) * 1_000 } ?: now
        return minOf(httpExpiry, selection.expiresAt?.toEpochMilli() ?: httpExpiry)
    }

    private fun decode(body: String, language: String, level: String, weekID: Long): LevelTestSelection? =
        try { LevelTestSnapshotAdapter.decode(body, language, level, weekID, VARIANT) }
        catch (_: InvalidLevelTestSnapshot) { null }

    private fun bundledSelection(level: LevelTestLevel, weekID: Long) =
        LevelTestSnapshotAdapter.fromBundled(level, LevelTestEngine.compose(level, weekID))

    private fun publish(key: String, status: LevelTestCatalogStatus): LevelTestCatalogStatus {
        mutableStatuses.value = mutableStatuses.value + (key to status)
        return status
    }

    private fun key(language: String, level: String) = "$language|$level"

    companion object {
        private const val VARIANT = 1
        private const val MAX_CACHE_SECONDS = 86_400L
        fun utcWeekID(date: LocalDate = LocalDate.now(ZoneOffset.UTC)): Long = LevelTestEngine.weekIndex(date)
    }
}
