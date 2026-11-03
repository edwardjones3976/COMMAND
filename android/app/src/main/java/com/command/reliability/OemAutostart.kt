package com.command.reliability

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort shortcuts to the page where some phone makers let an app start after a restart. They are not part of Android and
 * can move between a maker's versions, so a shortcut is offered only when its page exists on this phone, and it is only a
 * convenience: nothing depends on it. The packages are listed in the manifest's queries so Android lets the app look for them.
 */
@Singleton
class OemAutostart @Inject constructor(@ApplicationContext private val context: Context) {

    /** A page that exists on this phone, and the name of the maker it belongs to. */
    data class Link(val maker: String, val intent: Intent)

    /** The page for this phone's maker, or null when the maker is not known to have one or the page is not here. */
    fun find(manufacturer: String = Build.MANUFACTURER): Link? {
        val maker = manufacturer.trim().lowercase()
        val entry = KNOWN.firstOrNull { maker in it.makers } ?: return null
        for (component in entry.pages) {
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) != null) return Link(entry.display, intent)
        }
        return null
    }

    /** Every package this list may look at; the manifest's queries must name each one, or Android hides it from the app. */
    internal fun packages(): Set<String> = KNOWN.flatMap { entry -> entry.pages.map { it.packageName } }.toSet()

    private class Entry(val display: String, val makers: Set<String>, val pages: List<ComponentName>)

    private companion object {
        val KNOWN = listOf(
            Entry(
                "Xiaomi", setOf("xiaomi", "redmi", "poco"),
                listOf(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            ),
            Entry(
                "Huawei", setOf("huawei", "honor"),
                listOf(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                ),
            ),
            Entry(
                "Oppo", setOf("oppo", "realme", "oneplus"),
                listOf(
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                ),
            ),
            Entry(
                "Vivo", setOf("vivo", "iqoo"),
                listOf(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            ),
        )
    }
}
