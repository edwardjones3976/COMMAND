package com.command

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The QR handshake needs the camera, and a phone without one must still be able to install the app. Without the permission Android refuses
 * the camera every time, so QR would always run as typing; without `required="false"` the store would hide the app from phones that have
 * no camera, although every handshake but QR works on them.
 */
class CameraManifestTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun element(tag: String, name: String): String? =
        Regex("<$tag\\b[^>]*android:name=\"${Regex.escape(name)}\"[^>]*/?>").find(manifest)?.value

    @Test
    fun `the app asks for the camera, so the QR handshake can be granted it`() {
        assertTrue("the manifest asks for CAMERA", element("uses-permission", "android.permission.CAMERA") != null)
    }

    @Test
    fun `a phone without a camera, or with a camera that cannot focus, can still install the app`() {
        // The CAMERA permission also implies android.hardware.camera.autofocus as required, which camera and camera.any do not override.
        for (feature in listOf("android.hardware.camera", "android.hardware.camera.any", "android.hardware.camera.autofocus")) {
            val declared = element("uses-feature", feature)
            assertTrue("the manifest declares $feature", declared != null)
            assertTrue("$feature is not required", declared!!.contains("android:required=\"false\""))
        }
    }
}
