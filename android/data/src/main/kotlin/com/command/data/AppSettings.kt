package com.command.data

import com.command.data.db.CommandDb
import com.command.data.db.SettingRow
import com.command.domain.model.SettingKeys
import com.command.domain.voice.RoutePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /** Where speech may come out; the tone and the vibration ring whatever it says. The default speaks on the speaker too. */
    suspend fun routePolicy(): RoutePolicy = routePolicyOf(db.settings().get(SettingKeys.ALARM_ROUTE_POLICY))

    /** The same, followed: it emits again when the setting is written, so a screen shows what the phone will do and nothing else. */
    fun observeRoutePolicy(): Flow<RoutePolicy> =
        db.settings().observe(SettingKeys.ALARM_ROUTE_POLICY).map(::routePolicyOf).distinctUntilChanged()

    private fun routePolicyOf(stored: String?): RoutePolicy =
        stored?.trim('"')?.let { name -> RoutePolicy.values().firstOrNull { it.name == name } } ?: RoutePolicy.SPEAKER_AND_HEADSET

    suspend fun setRoutePolicy(policy: RoutePolicy) = db.settings().put(SettingRow(SettingKeys.ALARM_ROUTE_POLICY, "\"${policy.name}\""))

    /** The alarm volume the app raised for a ring and what it was before, kept so it can be put back whatever happens to the app. */
    suspend fun volumeRestore(): VolumeRestore? {
        val fields = db.settings().get(SettingKeys.VOLUME_RESTORE)?.let { Codec.decodePayload(it) } ?: return null
        return VolumeRestore(
            occurrenceId = fields["occurrence"] ?: return null,
            original = fields["original"]?.toIntOrNull() ?: return null,
            set = fields["set"]?.toIntOrNull() ?: return null,
            atUtc = fields["at"]?.toLongOrNull() ?: return null,
        )
    }

    suspend fun saveVolumeRestore(record: VolumeRestore) = db.settings().put(
        SettingRow(
            SettingKeys.VOLUME_RESTORE,
            Codec.encodePayload(mapOf("occurrence" to record.occurrenceId, "original" to "${record.original}", "set" to "${record.set}", "at" to "${record.atUtc}")),
        ),
    )

    suspend fun clearVolumeRestore() = db.settings().remove(SettingKeys.VOLUME_RESTORE)

    private companion object {
        const val TRUE = "true"
    }
}

/** What the alarm volume was ([original]) before the app raised it to [set] for [occurrenceId]'s ring, and when. */
data class VolumeRestore(val occurrenceId: String, val original: Int, val set: Int, val atUtc: Long)
