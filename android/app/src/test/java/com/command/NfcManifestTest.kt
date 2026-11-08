package com.command

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The NFC handshake reads a tag in reader mode, in the foreground only. Without the permission reader mode cannot start, so every NFC step
 * would run as typing; without `required="false"` the store would hide the app from phones that have no NFC, although every other handshake
 * works on them. No intent-filter for a discovered tag is declared: a tag never starts the app, it is read only while a screen asks for one.
 */
class NfcManifestTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun element(tag: String, name: String): String? =
        Regex("<$tag\\b[^>]*android:name=\"${Regex.escape(name)}\"[^>]*/?>").find(manifest)?.value

    @Test
    fun nfcPermissionDeclared() {
        assertTrue("the manifest asks for NFC", element("uses-permission", "android.permission.NFC") != null)
    }

    @Test
    fun nfcFeatureNotRequired() {
        val declared = element("uses-feature", "android.hardware.nfc")
        assertTrue("the manifest declares android.hardware.nfc", declared != null)
        assertTrue("android.hardware.nfc is not required", declared!!.contains("android:required=\"false\""))
    }

    @Test
    fun noDiscoveredIntentFilter() {
        for (action in listOf("NDEF_DISCOVERED", "TECH_DISCOVERED", "TAG_DISCOVERED")) {
            assertFalse("no activity is started by $action", manifest.contains("android.nfc.action.$action"))
        }
        assertFalse("no tech list is declared", manifest.contains("android.nfc.action.TECH_DISCOVERED") || manifest.contains("nfc_tech"))
    }
}
