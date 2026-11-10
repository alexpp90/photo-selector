package com.photoselectortoolbox.di

import com.photoselectortoolbox.domain.analysis.CompositionAnalyzer
import com.photoselectortoolbox.domain.analysis.OpenCvCompositionAnalyzer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The analyzers Learn runs on demand behind their interfaces. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AnalysisModule {

    @Binds
    abstract fun bindCompositionAnalyzer(impl: OpenCvCompositionAnalyzer): CompositionAnalyzer
}
