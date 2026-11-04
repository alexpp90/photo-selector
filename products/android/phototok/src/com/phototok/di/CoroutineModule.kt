package com.phototok.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-lifetime coroutine scope for fire-and-forget work that must survive
 * ViewModel clearing (e.g. finalizing a pending file deletion on screen exit).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** The dispatcher for blocking file and provider I/O; tests replace the module that provides it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@IoDispatcher ioDispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + ioDispatcher)
}
