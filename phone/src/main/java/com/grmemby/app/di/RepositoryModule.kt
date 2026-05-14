package com.grmemby.app.di

import android.content.Context
import com.grmemby.app.watchparty.WatchPartyRepository
import com.grmemby.data.repository.MediaRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideMediaRepository(
        @ApplicationContext context: Context
    ): MediaRepository {
        return MediaRepository(context)
    }

    @Provides
    @Singleton
    fun provideWatchPartyRepository(): WatchPartyRepository {
        return WatchPartyRepository()
    }
}
