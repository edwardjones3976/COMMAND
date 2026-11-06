package com.command.data

import androidx.room.withTransaction
import com.command.data.db.CommandDb
import com.command.data.db.ProfileRow
import com.command.data.db.SettingRow
import com.command.domain.dials.ChainIssue
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.Dial
import com.command.domain.dials.DialEffects
import com.command.domain.dials.Dials
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.dials.issues
import com.command.domain.model.SettingKeys
import kotlinx.coroutines.flow.Flow
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

/** A profile as the dials screen sees it. What is stored is shown clamped to 1 to 5, as the screen can show nothing else. */
data class ProfileDials(val handshake: ProfileHandshake, val dials: Dials)

sealed interface SaveChainResult {
    data object Saved : SaveChainResult
    data class Invalid(val issues: List<ChainIssue>) : SaveChainResult
    data object NotFound : SaveChainResult
}

/** What choosing a director did. */
sealed interface DirectorResult {
    data class Chosen(val profileId: String, val personality: Personality, val voicePackId: String) : DirectorResult
    data object NoSuchPack : DirectorResult
    data object NoProfile : DirectorResult
}

/**
 * The id that the active-profile setting names. The setting is stored as JSON text, so the id comes with its quotes, and one with nothing in
 * it (a pair of quotes with nothing between them, or no text at all) names nothing. [ProfileResolver] reads it here, for the ring and the
 * screens alike.
 */
internal fun activeProfileIdOf(raw: String?): String? = raw?.trim('"')?.takeIf { it.isNotEmpty() }

/** The profiles as the screens see them: their handshake chains, who is in use, who speaks for them and which is active. */
@Singleton
class ProfileRepository @Inject constructor(private val db: CommandDb) {
    private val resolver = ProfileResolver(db)

    fun observe(): Flow<List<ProfileHandshake>> = db.profiles().observeAll().map { rows -> rows.map(::handshakeOf) }

    suspend fun get(id: String): ProfileHandshake? = db.profiles().get(id)?.let(::handshakeOf)

    /** One profile for as long as it is collected: a new value whenever its handshake changes, and null while there is no such profile. */
    fun observeHandshake(id: String): Flow<ProfileHandshake?> = db.profiles().observe(id).map { row -> row?.let(::handshakeOf) }.distinctUntilChanged()

    /** One profile and its dials for as long as it is collected, and null while there is no such profile. */
    fun observeDials(id: String): Flow<ProfileDials?> = db.profiles().observe(id).map { row -> row?.let { ProfileDials(handshakeOf(it), dialsOf(it)) } }.distinctUntilChanged()

    /** The id the active-profile setting names. It may name a profile that is gone: a list shows no profile as active then. */
    fun observeActiveId(): Flow<String?> = db.settings().observe(SettingKeys.ACTIVE_PROFILE_ID).map(::activeProfileIdOf).distinctUntilChanged()

    /** Sets one dial of one profile, and nothing else of it. True if it was written: there is such a profile and the value is one a dial can have. */
    suspend fun setDial(id: String, dial: Dial, value: Int): Boolean {
        if (value !in Dials.RANGE) return false
        val profiles = db.profiles()
        return when (dial) {
            Dial.FIRMNESS -> profiles.setFirmness(id, value)
            Dial.OBNOXIOUSNESS -> profiles.setObnoxiousness(id, value)
            Dial.ALERT_INTENSITY -> profiles.setAlertIntensity(id, value)
            Dial.FOLLOW_UP_PERSISTENCE -> profiles.setFollowUpPersistence(id, value)
            Dial.HANDSHAKE_DIFFICULTY -> profiles.setHandshakeDifficulty(id, value)
            Dial.SCHEDULE_RIGIDITY -> profiles.setScheduleRigidity(id, value)
            Dial.RECOVERY_STRUCTURE -> profiles.setRecoveryStructure(id, value)
        } > 0
    }

    /** The ids of the profiles that alarms are fired with (see [ProfileResolver.observeInUse]). Editing any other reaches no alarm, and the screens say so. */
    fun observeInUse(): Flow<Set<String>> = resolver.observeInUse()

    /** Whether alarms are fired with the profile [id]: the answer now, and again whenever it changes (see [observeInUse]). */
    fun observeInUse(id: String): Flow<Boolean> = observeInUse().map { id in it }.distinctUntilChanged()

    /** Saves [spec] as the profile's own chain, if it can be used. */
    suspend fun saveChain(id: String, spec: ChainSpec): SaveChainResult {
        val issues = spec.issues()
        if (issues.isNotEmpty()) return SaveChainResult.Invalid(issues)
        return if (db.profiles().setChainJson(id, ChainJson.encode(spec)) > 0) SaveChainResult.Saved else SaveChainResult.NotFound
    }

    /**
     * Makes the director of pack [packId] the one who speaks for a profile ([profileId], or the active one when it is null): the pack's
     * personality and the pack, together. Nothing changes when there is no such pack or no such profile.
     */
    suspend fun chooseDirector(packId: String, profileId: String? = null): DirectorResult = db.withTransaction {
        val pack = db.voicePacks().get(packId) ?: return@withTransaction DirectorResult.NoSuchPack
        val target = profileId ?: activeProfileIdOf(db.settings().get(SettingKeys.ACTIVE_PROFILE_ID)) ?: return@withTransaction DirectorResult.NoProfile
        if (db.profiles().setDirector(target, pack.defaultPersonality, pack.id) == 0) return@withTransaction DirectorResult.NoProfile
        DirectorResult.Chosen(target, pack.defaultPersonality, pack.id)
    }

    /** Makes [id] the active profile, if there is one by that name. The setting is one row, so there is never more than one active profile. */
    suspend fun activate(id: String): Boolean = db.withTransaction {
        if (db.profiles().get(id) == null) return@withTransaction false
        db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"$id\""))
        true
    }

    /** Goes back to the default chain of the profile's difficulty. True if the profile exists. */
    suspend fun useDefault(id: String): Boolean = db.profiles().setChainJson(id, null) > 0

    private fun dialsOf(row: ProfileRow) = Dials.coerced(
        listOf(row.firmness, row.obnoxiousness, row.alertIntensity, row.followUpPersistence, row.handshakeDifficulty, row.scheduleRigidity, row.recoveryStructure),
    )

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
