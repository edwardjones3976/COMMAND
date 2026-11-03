package com.command

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * From Android 11 an app sees only the apps its manifest asks about. The phone's speech engine is one the ring cannot speak
 * without, and without this entry the failure is silent: the engine is simply never found.
 */
class ManifestQueriesTest {

    private val queries = File("src/main/AndroidManifest.xml").readText().substringAfter("<queries>").substringBefore("</queries>")

    @Test
    fun `the phone's speech engine can be found, so a ring can speak on Android 11 and later`() {
        assertTrue("the manifest asks about speech engines", queries.contains("android.intent.action.TTS_SERVICE"))
    }
}
