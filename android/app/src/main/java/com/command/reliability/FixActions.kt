package com.command.reliability

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.command.domain.reliability.Fix

/**
 * What happens when the person taps the button for a [Fix]. Notifications are asked for with Android's own request while it can
 * still be shown ([canAskNotifications]), and [onNotificationAnswered] runs when it comes back, granted or not; everything else
 * opens the Android screen that holds the setting. Nothing is changed by the app itself.
 */
@Composable
fun rememberFixHandler(canAskNotifications: Boolean, onNotificationAnswered: () -> Unit): (Fix) -> Unit {
    val context = LocalContext.current
    val canAsk by rememberUpdatedState(canAskNotifications)
    val answered by rememberUpdatedState(onNotificationAnswered)
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { answered() }
    return remember(context, request) {
        { fix: Fix ->
            if (fix == Fix.ASK_NOTIFICATIONS && canAsk) {
                request.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                SystemLinks.intentFor(context, fix)?.let { SystemLinks.open(context, it) }
            }
        }
    }
}
