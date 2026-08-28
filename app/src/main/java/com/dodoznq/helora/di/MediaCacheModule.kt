package com.dodoznq.helora.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.dodoznq.helora.data.preferences.UserPreferencesRepository
import com.dodoznq.helora.data.stream.StreamCacheConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@Module
@InstallIn(SingletonComponent::class)
object MediaCacheModule {

    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    fun provideStreamCacheDatabaseProvider(@ApplicationContext context: Context): DatabaseProvider =
        StandaloneDatabaseProvider(context)

    /**
     * Reads the user's saved cache size limit once, synchronously, at singleton-creation
     * time. [LeastRecentlyUsedCacheEvictor]'s limit is fixed for the life of the [SimpleCache]
     * instance, so there's no later point to apply a preference change to this evictor — the
     * one-time blocking read here (this provider itself only runs once per process) is what
     * lets a saved limit take effect on the next app start instead of never.
     */
    @OptIn(UnstableApi::class)
    @Provides
    @Singleton
    fun provideStreamCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
        userPreferencesRepository: UserPreferencesRepository
    ): Cache {
        val maxBytes = runBlocking { userPreferencesRepository.streamCacheMaxBytesFlow.first() }
        return SimpleCache(
            File(context.cacheDir, StreamCacheConfig.DIR_NAME),
            LeastRecentlyUsedCacheEvictor(maxBytes),
            databaseProvider
        )
    }
}
