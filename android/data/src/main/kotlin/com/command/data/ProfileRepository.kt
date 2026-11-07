package com.command.data

import androidx.room.withTransaction
import com.command.data.db.CommandDb
import com.command.data.db.ProfileRow
import com.command.data.db.SettingRow
import com.command.data.db.VoicePackRow
import com.command.domain.dials.ChainIssue
import com.command.domain.dials.ChainSpec
import com.command.domain.dials.Dial
import com.command.domain.dials.DialEffects
import com.command.domain.dials.Dials
import com.command.domain.dials.Personality
import com.command.domain.dials.PresetOrigin
import com.command.domain.dials.issues
import com.command.domain.model.SettingKeys
import com.command.domain.voice.BuiltInVoicePacks
import com.command.domain.voice.SpeechVoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * A voice pack as the voice screen sees it. [voice] is what the ring asks of the phone's engine for this pack, and [personality] whose words it
 * goes with; [trimDb] is its loudness trim as stored.
 */
data class VoicePackChoice(val id: String, val name: String, val personality: Personality, val trimDb: Float, val voice: SpeechVoice)

/** A profile as the voice screen sees it: its dials (the alert intensity sets a preview's loudness), who speaks for it, and who could. */
data class ProfileVoice(val dials: ProfileDials, val voicePackId: String?, val packs: List<VoicePackChoice>)

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
    /** Dial writes land in the order they were asked for, from whichever screen: a Mutex is fair, and this class is one for the whole app. */
    private val dialWrites = Mutex()

    /** A seam for tests: runs in its turn, before a dial is written, so that a write can be made as slow as a test needs. */
    internal var beforeDialWrite: suspend () -> Unit = {}

    /** The same for the choice of a voice and for its trim: the choice tapped last, and the trim set last, are the ones that stay. */
    private val voiceWrites = Mutex()

    /** A seam for tests: runs in its turn, before a voice is written, so that a write can be made as slow as a test needs. */
    internal var beforeVoiceWrite: suspend () -> Unit = {}

    private val resolver = ProfileResolver(db)

    fun observe(): Flow<List<ProfileHandshake>> = db.profiles().observeAll().map { rows -> rows.map(::handshakeOf) }

    suspend fun get(id: String): ProfileHandshake? = db.profiles().get(id)?.let(::handshakeOf)

    /** One profile for as long as it is collected: a new value whenever its handshake changes, and null while there is no such profile. */
    fun observeHandshake(id: String): Flow<ProfileHandshake?> = db.profiles().observe(id).map { row -> row?.let(::handshakeOf) }.distinctUntilChanged()

    /** One profile and its dials for as long as it is collected, and null while there is no such profile. */
    fun observeDials(id: String): Flow<ProfileDials?> = db.profiles().observe(id).map { row -> row?.let { ProfileDials(handshakeOf(it), it.storedDials()) } }.distinctUntilChanged()

    /** The id the active-profile setting names. It may name a profile that is gone: a list shows no profile as active then. */
    fun observeActiveId(): Flow<String?> = db.settings().observe(SettingKeys.ACTIVE_PROFILE_ID).map(::activeProfileIdOf).distinctUntilChanged()

    /** Sets one dial of one profile, and nothing else of it. True if it was written: there is such a profile and the value is one a dial can have. */
    suspend fun setDial(id: String, dial: Dial, value: Int): Boolean {
        if (value !in Dials.RANGE) return false
        val profiles = db.profiles()
        return dialWrites.withLock {
            beforeDialWrite()
            when (dial) {
                Dial.FIRMNESS -> profiles.setFirmness(id, value)
                Dial.OBNOXIOUSNESS -> profiles.setObnoxiousness(id, value)
                Dial.ALERT_INTENSITY -> profiles.setAlertIntensity(id, value)
                Dial.FOLLOW_UP_PERSISTENCE -> profiles.setFollowUpPersistence(id, value)
                Dial.HANDSHAKE_DIFFICULTY -> profiles.setHandshakeDifficulty(id, value)
                Dial.SCHEDULE_RIGIDITY -> profiles.setScheduleRigidity(id, value)
                Dial.RECOVERY_STRUCTURE -> profiles.setRecoveryStructure(id, value)
            } > 0
        }
    }

    /** One profile with who speaks for it and every pack it could choose, for as long as it is collected, and null while there is no such profile. */
    fun observeVoice(id: String): Flow<ProfileVoice?> = combine(db.profiles().observe(id), db.voicePacks().observeAll()) { row, packs ->
        row?.let { ProfileVoice(ProfileDials(handshakeOf(it), it.storedDials()), it.voicePackId, packs.map(::choiceOf)) }
    }.distinctUntilChanged()

    /**
     * Sets the loudness trim of one pack, for every profile that speaks with it, from the next ring on: a ring keeps what it was fired with. True if it
     * was written: there is such a pack and the value is one a trim can have (amendment 2: 0 to +8 dB, above the alert intensity's loudness).
     */
    suspend fun setVoiceTrim(packId: String, trimDb: Float): Boolean {
        if (trimDb.isNaN() || trimDb !in 0f..BuiltInVoicePacks.MAX_TRIM_DB) return false
        return voiceWrites.withLock {
            beforeVoiceWrite()
            db.voicePacks().setLoudnessTrim(packId, trimDb) > 0
        }
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
    suspend fun chooseDirector(packId: String, profileId: String? = null): DirectorResult = voiceWrites.withLock {
        beforeVoiceWrite()
        db.withTransaction {
            val pack = db.voicePacks().get(packId) ?: return@withTransaction DirectorResult.NoSuchPack
            val target = profileId ?: activeProfileIdOf(db.settings().get(SettingKeys.ACTIVE_PROFILE_ID)) ?: return@withTransaction DirectorResult.NoProfile
            if (db.profiles().setDirector(target, pack.defaultPersonality, pack.id) == 0) return@withTransaction DirectorResult.NoProfile
            DirectorResult.Chosen(target, pack.defaultPersonality, pack.id)
        }
    }

    /** Makes [id] the active profile, if there is one by that name. The setting is one row, so there is never more than one active profile. */
    suspend fun activate(id: String): Boolean = db.withTransaction {
        if (db.profiles().get(id) == null) return@withTransaction false
        db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"$id\""))
        true
    }

    /** Goes back to the default chain of the profile's difficulty. True if the profile exists. */
    suspend fun useDefault(id: String): Boolean = db.profiles().setChainJson(id, null) > 0

    private fun choiceOf(row: VoicePackRow) = VoicePackChoice(row.id, row.name, row.defaultPersonality, row.loudnessTrimDb, row.speechVoice())

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

/** What a pack asks of the phone's engine, for the ring and for its preview alike. */
internal fun VoicePackRow.speechVoice(): SpeechVoice = SpeechVoice(locale, pitch, rate, BuiltInVoicePacks.isFemale(id))

/**
 * The dials of a stored row, for the ring and for the screens alike. What is out of range costs the setting and never the ring: it is clamped, and
 * an alert level that cannot be read is the loudest, as it is for the spoken line.
 */
internal fun ProfileRow.storedDials(): Dials = Dials.coerced(
    listOf(
        firmness,
        obnoxiousness,
        alertIntensity.takeIf { it in Dials.RANGE } ?: Dials.RANGE.last,
        followUpPersistence,
        handshakeDifficulty,
        scheduleRigidity,
        recoveryStructure,
    ),
)
