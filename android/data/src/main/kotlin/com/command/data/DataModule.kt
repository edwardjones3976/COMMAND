package com.command.data

import android.content.Context
import com.command.data.db.CommandDb
import com.command.domain.time.Clock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The real clock: wall time from the system, monotonic time from SystemClock.elapsedRealtime. */
class AndroidClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()
    override fun zone(): java.time.ZoneId = java.time.ZoneId.systemDefault()
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): CommandDb = CommandDb.open(context)

    @Provides @Singleton
    fun clock(): Clock = AndroidClock()

    @Provides @Singleton
    fun consentRevocationListener(): ConsentRevocationListener = NoOpConsentRevocationListener
}
