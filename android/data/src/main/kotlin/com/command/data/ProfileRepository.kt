package com.command.data

import com.command.data.db.CommandDb
import com.command.data.db.ProfileRow
import com.command.domain.dials.ChainIssue
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.DialEffects
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.dials.issues
import com.command.domain.model.SettingKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A profile as the handshake screens see it. [own] is the chain the person saved for it, or null when it asks for the default chain of its
 * [difficulty]; [storedUnusable] says that something is stored that cannot be used (text this version cannot read, or a chain that breaks a
 * limit), so the default is in force and the editor can say so.
 */
data class ProfileHandshake(
    val id: String,
    val name: String,
    val origin: PresetOrigin,
    val personality: Personality,
    val difficulty: Int,
    val own: ChainSpec?,
    val storedUnusable: Boolean,
) {
    /** The chain that is in force for this profile. */
    val chain: ChainSpec get() = own ?: DialEffects.defaultChain(difficulty)
}

sealed interface SaveChainResult {
    data object Saved : SaveChainResult
    data class Invalid(val issues: List<ChainIssue>) : SaveChainResult
    data object NotFound : SaveChainResult
}

/**
 * The id that the active-profile setting names. The setting is stored as JSON text, so the id comes with its quotes, and one with nothing in
 * it (a pair of quotes with nothing between them, or no text at all) names nothing. The ring ([OccurrenceRepository]) and the screens
 * ([ProfileRepository.observeInUse]) both read it here, so that the profile an alarm is fired with and the profile that is said to be in use
 * cannot be told apart by how each one reads the setting.
 */
internal fun activeProfileIdOf(raw: String?): String? = raw?.trim('"')?.takeIf { it.isNotEmpty() }

/** The profiles' handshake chains: read for the screens, saved by the editor. Everything else about a profile belongs to the profile screens of M10b. */
@Singleton
class ProfileRepository @Inject constructor(private val db: CommandDb) {

    fun observe(): Flow<List<ProfileHandshake>> = db.profiles().observeAll().map { rows -> rows.map(::handshakeOf) }

    suspend fun get(id: String): ProfileHandshake? = db.profiles().get(id)?.let(::handshakeOf)

    /**
     * The ids of the profiles that alarms are fired with: the active profile (every mission that names none, and so every new one, has it)
     * and any that a mission names (a mission that was removed names nothing; the hidden mission behind a test alarm is removed too, and
     * carries the profile of the mission the test was started from, which that mission names as well). Until the profile screens of M10b
     * let a person choose, that is the active one only; editing another profile reaches no alarm, and the screens say so. It follows the
     * database: it is read again whenever the setting or a mission changes. It can hold an id that no profile has (a mission that names one
     * that is gone rings with the active profile), which no list shows.
     */
    fun observeInUse(): Flow<Set<String>> =
        combine(db.settings().observe(SettingKeys.ACTIVE_PROFILE_ID), db.missions().observeActive()) { active, missions ->
            buildSet {
                activeProfileIdOf(active)?.let { add(it) }
                missions.mapNotNullTo(this) { it.profileId }
            }
        }

    /** Whether alarms are fired with the profile [id]: the answer now, and again whenever it changes (see [observeInUse]). */
    fun observeInUse(id: String): Flow<Boolean> = observeInUse().map { id in it }.distinctUntilChanged()

    /** Saves [spec] as the profile's own chain, if it can be used. */
    suspend fun saveChain(id: String, spec: ChainSpec): SaveChainResult {
        val issues = spec.issues()
        if (issues.isNotEmpty()) return SaveChainResult.Invalid(issues)
        return if (db.profiles().setChainJson(id, ChainJson.encode(spec)) > 0) SaveChainResult.Saved else SaveChainResult.NotFound
    }

    /** Goes back to the default chain of the profile's difficulty. True if the profile exists. */
    suspend fun useDefault(id: String): Boolean = db.profiles().setChainJson(id, null) > 0

    private fun handshakeOf(row: ProfileRow): ProfileHandshake {
        val own = ChainJson.decode(row.handshakeChainJson)
        return ProfileHandshake(
            id = row.id,
            name = row.name,
            origin = row.origin,
            personality = row.personality,
            difficulty = row.handshakeDifficulty,
            own = own,
            storedUnusable = own == null && !row.handshakeChainJson.isNullOrBlank(),
        )
    }
}
