package com.phototok.di

import android.content.Context
import com.phototok.domain.CardTransferJournal
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** The card-transfer journal lives in `filesDir`, so it survives the view model and the process. */
@Module
@InstallIn(SingletonComponent::class)
object JournalModule {

    @Provides
    @Singleton
    fun provideCardTransferJournal(@ApplicationContext context: Context): CardTransferJournal =
        CardTransferJournal(File(context.filesDir, "card_transfer_journal.tsv"))
}
