package com.command.data

import com.command.data.db.CommandDb
import com.command.data.db.SettingRow
import com.command.domain.model.SettingKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The few settings that the screens themselves read and write: whether the first-run screens are done, and what was asked. */
@Singleton
class AppSettings @Inject constructor(private val db: CommandDb) {

    /** False until the person has been through the first-run screens; it emits again if that changes. */
    val onboardingDone: Flow<Boolean> = db.settings().observe(SettingKeys.ONBOARDING_DONE).map { it == TRUE }

    suspend fun markOnboardingDone() = db.settings().put(SettingRow(SettingKeys.ONBOARDING_DONE, TRUE))

    /**
     * Whether the runtime notification request has been shown. After it has, Android may refuse to show it again, so the way to
     * turn notifications on is the settings page.
     */
    suspend fun notificationsAsked(): Boolean = db.settings().get(SettingKeys.NOTIFICATIONS_ASKED) == TRUE

    suspend fun markNotificationsAsked() = db.settings().put(SettingRow(SettingKeys.NOTIFICATIONS_ASKED, TRUE))

    private companion object {
        const val TRUE = "true"
    }
}
