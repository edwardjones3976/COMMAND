package com.command.yourdata

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import com.command.alarm.DataEraser
import com.command.alarm.ErasureReport
import com.command.data.DataExporter
import com.command.data.DataOverview
import com.command.data.ExportSummary
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** What the screen asks of the two jobs, so that it can be tested without a database. */
interface DataRights {
    suspend fun overview(): DataOverview
    suspend fun export(destination: () -> OutputStream): ExportSummary
    suspend fun erase(): ErasureReport
}

@Singleton
class AndroidDataRights @Inject constructor(
    private val exporter: DataExporter,
    private val eraser: DataEraser,
) : DataRights {
    override suspend fun overview(): DataOverview = exporter.overview()
    override suspend fun export(destination: () -> OutputStream): ExportSummary = exporter.export(destination)
    override suspend fun erase(): ErasureReport = eraser.erase()
}

/** Where a copy goes: a document the person chose. */
interface ExportSink {
    /** Opens [uri] for writing from its start, or returns null if the document cannot be written. */
    fun open(uri: Uri): OutputStream?

    /** Removes what a save that failed left at [uri], where the provider allows it. */
    fun discard(uri: Uri)
}

class AndroidExportSink @Inject constructor(@ApplicationContext private val context: Context) : ExportSink {
    override fun open(uri: Uri): OutputStream? = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()

    override fun discard(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }
}

/** Ends the app once everything is erased: its singletons still hold a closed database until then. */
fun interface ProcessReset {
    fun clearAndExit()
}

class AndroidProcessReset @Inject constructor(@ApplicationContext private val context: Context) : ProcessReset {
    override fun clearAndExit() {
        // The same as the person choosing "Clear data" for the app in Android's settings: it ends the process, takes back the permissions
        // and clears the notifications and channels. If the platform refuses, the process is ended all the same.
        val cleared = runCatching { context.getSystemService(ActivityManager::class.java)?.clearApplicationUserData() == true }.getOrDefault(false)
        if (!cleared) Process.killProcess(Process.myPid())
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class YourDataModule {
    @Binds abstract fun dataRights(impl: AndroidDataRights): DataRights
    @Binds abstract fun exportSink(impl: AndroidExportSink): ExportSink
    @Binds abstract fun processReset(impl: AndroidProcessReset): ProcessReset
}
