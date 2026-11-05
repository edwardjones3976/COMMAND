package com.command.reliability

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OemAutostartTest {

    private lateinit var context: Context
    private lateinit var oem: OemAutostart

    private val xiaomi = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        oem = OemAutostart(context)
    }

    @Test
    fun `a maker that is not known to have such a page gets no shortcut`() {
        assertNull(oem.find("Google"))
        assertNull(oem.find("samsung"))
        assertNull(oem.find(""))
    }

    @Test
    fun `a known maker without the page on this phone gets no shortcut either`() {
        assertNull("the page has to exist here, not only be known", oem.find("Xiaomi"))
    }

    @Test
    fun `a known maker whose page exists is offered it, however the maker spells its name`() {
        shadowOf(context.packageManager).addActivityIfNotPresent(xiaomi)

        for (name in listOf("Xiaomi", "XIAOMI", " xiaomi ", "Redmi", "POCO")) {
            val link = oem.find(name)
            assertNotNull("$name should find the Xiaomi page", link)
            assertEquals("Xiaomi", link!!.maker)
            assertEquals(xiaomi, link.intent.component)
        }
    }

    @Test
    fun `the makers that share a page are mapped to it`() {
        val huawei = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        val oppo = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
        val vivo = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        for (page in listOf(huawei, oppo, vivo)) shadowOf(context.packageManager).addActivityIfNotPresent(page)

        assertEquals("Huawei", oem.find("HONOR")!!.maker)
        assertEquals("Oppo", oem.find("realme")!!.maker)
        assertEquals("Oppo", oem.find("OnePlus")!!.maker)
        assertEquals("Vivo", oem.find("iQOO")!!.maker)
    }

    @Test
    fun `every package that may be looked up is named in the manifest's queries, or Android would hide it`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        val missing = oem.packages().filterNot { queries.contains("android:name=\"$it\"") }
        assertTrue("add to <queries>: $missing", missing.isEmpty())
    }
}
