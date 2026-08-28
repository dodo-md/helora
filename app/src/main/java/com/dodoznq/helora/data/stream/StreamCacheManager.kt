package com.dodoznq.helora.data.stream

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import com.dodoznq.helora.di.DispatcherProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/** Read and clear access to the Media3 stream disk cache, off the main thread. */
@OptIn(UnstableApi::class)
@Singleton
class StreamCacheManager @Inject constructor(
    private val cache: Cache,
    private val dispatcherProvider: DispatcherProvider
) {

    suspend fun currentSizeBytes(): Long = withContext(dispatcherProvider.io) {
        cache.cacheSpace
    }

    /**
     * Removes every cached resource through the [Cache] API rather than deleting files
     * directly. SimpleCache keeps a separate index database describing what's on disk;
     * deleting files out from under it desyncs that index, which corrupts the cache the
     * next time it's opened.
     */
    suspend fun clear() = withContext(dispatcherProvider.io) {
        cache.keys.forEach { key -> cache.removeResource(key) }
    }
}
