package com.photoselectortoolbox.di

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
 * An application-lifetime coroutine scope for work that must outlive the screen
 * that started it.
 *
 * Two things in the selector need this and cannot use `viewModelScope`:
 *
 * 1. **Optimistic filing.** Move and Copy are applied to the list before the
 *    file system confirms them, so the transfer is still running after the frame
 *    has left the screen. Cancelling it because the user navigated away would
 *    leave a half-written file in the Selection.
 * 2. **Deferred deletion.** A delete is only committed to disk when its undo
 *    window closes — which is frequently *after* the selector is gone. On
 *    `viewModelScope` the commit would be cancelled and the photograph would
 *    silently reappear on the next folder open.
 *
 * `SupervisorJob` so one failed transfer does not cancel the others, and
 * `Dispatchers.IO` because everything launched here is file or network work.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** The dispatcher for file and database work (agent rule 3); tests inject a `TestDispatcher`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** The dispatcher for CPU-bound maths (OpenCV, dHash); tests inject a `TestDispatcher`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
