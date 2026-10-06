package com.batteryalert.guard.di

import android.content.Context
import androidx.room.Room
import com.batteryalert.guard.data.database.GuardDatabase
import com.batteryalert.guard.data.repository.BlackboxRepository
import com.batteryalert.guard.data.repository.RoomBlackboxRepository
import com.batteryalert.guard.domain.usecase.BlackboxSampler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The flight recorder's storage.
 *
 * The repository is bound through its interface so the diagnostic ViewModel cannot reach
 * past it to the DAO, and so a test can substitute an in-memory implementation without a
 * database.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun bindBlackboxRepository(impl: RoomBlackboxRepository): BlackboxRepository

    companion object {

        /**
         * One database for the process.
         *
         * A single connection matters more here than usual: the recorder writes and the
         * diagnostic view reads, and two `RoomDatabase` instances over the same file would
         * give the reader a stale snapshot of a log that is being written to while it is
         * being watched.
         */
        @Provides
        @Singleton
        fun provideGuardDatabase(@ApplicationContext context: Context): GuardDatabase =
            Room.databaseBuilder(context, GuardDatabase::class.java, GuardDatabase.NAME)
                // The blackbox is a bounded rolling buffer of diagnostic telemetry, not user
                // data — see GuardDatabase. Losing it to a schema change is cheaper than
                // carrying migrations for a table nobody reads twice.
                .fallbackToDestructiveMigration()
                .build()

        /**
         * The sampler is a singleton because it is *stateful*: it decides by comparing each
         * frame with the last one it kept. A fresh instance per injection would treat every
         * frame as the start of a session and log all of them.
         *
         * Provided rather than injected because its cadence is a constructor parameter with
         * a default — which is exactly what lets a test run the cadence at milliseconds
         * instead of seconds.
         */
        @Provides
        @Singleton
        fun provideBlackboxSampler(): BlackboxSampler = BlackboxSampler()
    }
}
