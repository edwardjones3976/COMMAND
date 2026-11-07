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
class AndroidClock(private val context: Context) : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()
    override fun zone(): java.time.ZoneId = java.time.ZoneId.systemDefault()

    override fun bootId(): String? = runCatching {
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, NO_BOOT_COUNT)
    }.getOrNull()?.takeIf { it != NO_BOOT_COUNT }?.toString()

    private companion object {
        const val NO_BOOT_COUNT = -1
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): CommandDb = CommandDb.open(context)

    @Provides @Singleton
    fun clock(@ApplicationContext context: Context): Clock = AndroidClock(context)

    @Provides @Singleton
    fun consentRevocationListener(): ConsentRevocationListener = NoOpConsentRevocationListener
}
