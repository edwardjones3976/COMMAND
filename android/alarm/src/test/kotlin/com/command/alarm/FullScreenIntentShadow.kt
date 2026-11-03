package com.command.alarm

import android.app.NotificationManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/** Robolectric cannot grant or refuse the full-screen-intent permission (Android 14+), so a test decides what the system says. */
@Implements(NotificationManager::class)
class FullScreenIntentShadow : ShadowNotificationManager() {

    @Implementation
    fun canUseFullScreenIntent(): Boolean = allowed

    companion object {
        @JvmStatic
        var allowed = true
    }
}
