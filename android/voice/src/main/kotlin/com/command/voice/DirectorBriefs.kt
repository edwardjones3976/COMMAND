package com.command.voice

/**
 * Performance direction for each director, sent to the TTS model as the audio profile.
 *
 * The first four are the character narrations recorded in res/raw (voice_*.mp3), transcribed and
 * lightly edited: those recordings describe how each director should sound, so their words are
 * the brief. The rest cover the other voice models. Change [VOICE_CACHE_VERSION] in
 * VoiceDirectorEngine after editing any brief so cached renders are regenerated.
 */
internal object DirectorBriefs {

    // res/raw/voice_drill_sergeant.mp3
    const val DRILL_INSTRUCTOR =
        "A seasoned USMC drill instructor fills the squad bay with an unmistakable guttural roar born from " +
            "sheer vocal endurance and unyielding authority. Rather than shouting from the throat, he projects " +
            "from the diaphragm with a deep, sandpaper-textured rasp, often called a frog voice, developed over " +
            "weeks of nonstop instruction. His speech is rapid-fire, staccato, and intensely rhythmic: explosive " +
            "two-syllable commands like \"Eyeballs!\", \"Snap to!\" and \"Move!\" crash through the room with zero " +
            "hesitation. There is no conversational tempo or polite inflection. Every word is fired like a volley, " +
            "designed to shatter hesitation, obliterate excuses, and demand immediate, reflex-level commitment."

    // res/raw/voice_female_executive.mp3
    const val EXECUTIVE =
        "A Fortune 500 executive whose voice instantly recalibrates the room's focus. Her tone is resonant, calm, " +
            "and deliberate, projecting from a deep well of composure that never requires raising her voice to " +
            "enforce absolute clarity. Every syllable is sculpted with immaculate, measured diction, yet infused with " +
            "an unmistakable rhythmic warmth that cuts through corporate jargon. Phrases like \"Let's look at the " +
            "bottom line\" and \"We move forward with intention\" land with decisive, strategic finality. She pairs " +
            "surgical precision with a commanding, knowing cadence: direct yet diplomatic, unshakable under pressure, " +
            "radiating the earned confidence of a leader who expects nothing short of excellence."

    // res/raw/voice_male_fitness.mp3
    const val FITNESS_COACH =
        "A Black American male fitness and personal coach who commands the gym floor with an effortless, rich " +
            "baritone that feels both reassuring and undeniably solid. His voice carries a warm, resonant timbre, " +
            "natural chest resonance, and an audible smile woven into every phrase that puts anxious clients at ease " +
            "without ever sacrificing accountability. He speaks with an intentional, rhythmic pocket, never sluggish " +
            "or dragging yet refusing to rush or stumble, letting each cue breathe and land with steady conviction. " +
            "No drill-style intimidation or artificial hype: he delivers directives like \"Take a breath, reset your " +
            "shoulders, and lock in. We're not rushing this rep. We're owning it.\" He radiates the quiet strength of " +
            "an experienced mentor who meets you where you are and guides you across the finish line."

    // res/raw/voice_motivational_coach.mp3
    const val MOTIVATIONAL_COACH =
        "A top-tier female motivational coach who sets the morning in motion with a vibrant, radiant cadence that " +
            "balances grounded executive authority with infectious, magnetic optimism. Her voice opens with a warm, " +
            "crystalline breath: bright, articulate, and completely unhurried, sweeping away morning brain fog. As she " +
            "dials in the vision for the day, her pacing builds into a punchy, rhythmic flow rich with empowering " +
            "upward inflections. Phrases like \"Own your energy\", \"Claim this morning\" and \"Step into your power " +
            "right now\" are delivered with crisp diction and a smile you can hear. Rather than barking orders, her " +
            "tone is an electric catalyst, combining empathetic warmth with high-stakes clarity."

    const val PARADE_CALLER =
        "A Marine drill master calling cadence on the parade deck: loud, sustained, and rhythmic, projecting from " +
            "the chest with clipped consonants and a steady, marching beat. Commanding, never frantic."

    const val TACTICAL_COMMS =
        "A calm military radio operator relaying orders: concise, controlled, and professional, with clipped " +
            "phrasing, a steady pace, and zero wasted words."

    const val KINETIC_COACH =
        "An energetic, encouraging personal coach: upbeat, warm, and rhythmic, with confident momentum that makes " +
            "the next action feel easy to start."

    const val SENIOR_EXECUTIVE =
        "A composed senior executive: calm, deliberate, and clear, with warm authority and measured diction. " +
            "Speaks with quiet confidence and decisive finality."

    const val CALM_STRATEGIST =
        "A calm, thoughtful strategist: unhurried, low, and even, with a reassuring tone that lowers the pressure " +
            "while keeping focus sharp."
}
