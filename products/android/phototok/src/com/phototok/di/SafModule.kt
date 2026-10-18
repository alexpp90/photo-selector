package com.phototok.di

import android.content.Context
import com.photoselector.saf.FolderAccessManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The shared `:saf` module has no Hilt of its own: PhotoTok provides its one
 * [FolderAccessManager], the only holder of persisted folder grants (sole-caller rule).
 */
@Module
@InstallIn(SingletonComponent::class)
object SafModule {

    @Provides
    @Singleton
    fun provideFolderAccessManager(@ApplicationContext context: Context): FolderAccessManager =
        FolderAccessManager(context)
}
