package com.photoselectortoolbox.di

import com.photoselectortoolbox.data.repository.CacheRepository
import com.photoselectortoolbox.data.repository.CacheRepositoryImpl
import com.photoselectortoolbox.data.repository.FakeImageRepository
import com.photoselectortoolbox.data.repository.ImageRepository
import com.photoselectortoolbox.data.repository.LearnRepository
import com.photoselectortoolbox.data.repository.LearnRepositoryImpl
import com.photoselectortoolbox.data.repository.RepositoryExifReader
import com.photoselectortoolbox.data.source.FakeLocalImageSource
import com.photoselectortoolbox.data.source.LocalImageSource
import com.photoselectortoolbox.domain.usecase.PhotoExifReader
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class]
)
abstract class TestRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindImageRepository(
        impl: FakeImageRepository
    ): ImageRepository

    @Binds
    @Singleton
    abstract fun bindCacheRepository(
        impl: CacheRepositoryImpl
    ): CacheRepository

    @Binds
    @Singleton
    abstract fun bindLocalImageSource(
        impl: FakeLocalImageSource
    ): LocalImageSource

    // As RepositoryModule: the real Learn repository over the test database, and the scan's EXIF
    // read through the fake image repository (shoot debrief SD-1a, SD-1b).
    @Binds
    @Singleton
    abstract fun bindLearnRepository(
        impl: LearnRepositoryImpl
    ): LearnRepository

    @Binds
    abstract fun bindPhotoExifReader(
        impl: RepositoryExifReader
    ): PhotoExifReader
}
