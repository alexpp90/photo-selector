package com.photoselectortoolbox.di

import android.content.Context
import androidx.room.Room
import com.photoselectortoolbox.data.cache.LearnDao
import com.photoselectortoolbox.data.cache.ScoreDao
import com.photoselectortoolbox.data.cache.ScoresDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideScoresDatabase(
        @ApplicationContext context: Context
    ): ScoresDatabase {
        return Room.databaseBuilder(
            context,
            ScoresDatabase::class.java,
            "scores_cache.db"
        )
            .addMigrations(*ScoresDatabase.MIGRATIONS)
            .build()
    }

    @Provides
    @Singleton
    fun provideScoreDao(database: ScoresDatabase): ScoreDao {
        return database.scoreDao()
    }

    @Provides
    @Singleton
    fun provideLearnDao(database: ScoresDatabase): LearnDao = database.learnDao()
}
