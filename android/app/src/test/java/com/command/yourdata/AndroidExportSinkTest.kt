package com.command.yourdata

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Where a copy goes, as the platform sees it: a document the person chose, which may be one that cannot be written. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidExportSinkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sink = AndroidExportSink(context)

    @Test
    fun `a document is opened from its start, so an older and longer one does not leave its tail behind`() {
        val file = File(context.cacheDir, "older.zip").apply { writeText("a much longer and older content") }

        sink.open(Uri.fromFile(file))!!.use { it.write("new".toByteArray()) }

        assertEquals("new", file.readText())
    }

    @Test
    fun `a document that cannot be opened is no stream, not an exception`() {
        assertNull(sink.open(Uri.parse("content://no.such.authority/document/1")))
    }

    @Test
    fun `removing what is not a document the provider can remove is harmless`() {
        sink.discard(Uri.parse("content://no.such.authority/document/1"))
        sink.discard(Uri.fromFile(File(context.cacheDir, "never-made.zip")))
        assertNotNull(sink)
    }
}
