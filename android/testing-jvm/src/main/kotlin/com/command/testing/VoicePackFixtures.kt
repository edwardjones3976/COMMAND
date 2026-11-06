package com.command.testing

import com.command.domain.voice.BuiltInVoicePacks
import com.command.domain.voice.VoicePackEntity

/** The ten voice packs, which the voice tests are written against. They are production data now ([BuiltInVoicePacks]). */
object VoicePackFixtures {
    fun defaultVoicePacks(): List<VoicePackEntity> = BuiltInVoicePacks.all
}
