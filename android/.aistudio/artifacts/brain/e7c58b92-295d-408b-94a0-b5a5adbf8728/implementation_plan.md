# Implementation Plan: Professional Hybrid Studio Engine & Crisp Broadcast Audio Mastering

Upgrade the audio pipeline from raw system TTS to a **Professional Hybrid Studio Voice Engine** paired with a **Crisp Broadcast Audio Mastering Suite** (85Hz rumble cut, 4kHz broadcast presence exciter, 6.8kHz de-esser, dynamic vocal leveler, and seamless master-stem crossfading).

---

## User Review & Critical Decisions

> [!IMPORTANT]
> Based on your answers:
> 1. **Hybrid Studio Engine**: The app will fuse **pristine master voice recordings for core directives and signature hooks** with **broadcast-mastered AI speech for dynamic task variables**—ensuring the vocal delivery has natural human cadence, authentic inflection, and zero robotic TTS feel.
> 2. **Crisp Broadcast Studio Mastering**: All synthesized and composite audio passes through a dedicated **broadcast mastering chain**:
>    - **85 Hz High-Pass Filter**: Removes low-end mud and rumble.
>    - **3.8 kHz – 4.5 kHz Presence Exciter**: Imparts crisp, punchy studio articulation.
>    - **6.8 kHz De-Esser**: Tames sibilance and harsh consonant artifacts.
>    - **Studio Vocal Leveler / Compressor**: 3:1 ratio, 15ms attack, automatic makeup gain for broadcast loudness consistency.
>    - **Zero-Echo Acoustic Gate**: Dead-silent studio floor with zero synthetic room reverb or comb-filtering artifacts.

---

## 1. Overview & Core Concept

- **What It Does**: Replaces thin, robotic Android TTS with a dual-tier studio audio engine. When a directive, alarm, or soundboard pad plays, the director opens with high-fidelity human vocal stems extracted directly from the studio master recordings (capturing authentic breath, warmth, and cadence), seamlessly crossfaded with context-aware broadcast-mastered speech for the user's specific mission details.
- **Target Audience**: High-performing professionals and disciplined athletes who demand premium audio production without distracting robotic speech artifacts.
- **Key Value**: Delivers the commanding presence of an executive boardroom, Olympic training facility, or military briefing room directly to the user's ears.

---

## 2. Audio Architecture & Signal Flow

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                  HYBRID STUDIO ENGINE & BROADCAST MASTERING                  │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  [1] Directive Trigger (Alarm / Reveille / Soundboard Pad / Carousel)         │
│  ├── Mission Title: "Q3 Board Deck" | Phase: REVEILLE | Director: Eleanor    │
│  │                                                                           │
│  [2] Hybrid Unit Assembler                                                   │
│  ├── Stem A: Pristine Master Hook (Real Human Voice Recording)               │
│  │   Example: "Commitment window locked." (Director Eleanor Vance master)    │
│  ├── Stem B: Contextual Dynamic Variable (Mastered Synthesis)                │
│  │   Example: "Initialize workspace for 'Q3 Board Deck' now."                │
│  │                                                                           │
│  [3] Studio Crossfade & Splice Engine                                        │
│  ├── 25ms Equal-Power Crossfade at acoustic pause boundary                   │
│  ├── Zero phase cancellation, seamless breath continuation                   │
│  │                                                                           │
│  [4] Crisp Broadcast Audio Mastering Strip (32-Bit Float PCM)                │
│  ├── 1. 85 Hz High-Pass (12dB/oct Butterworth rumble cut)                     │
│  ├── 2. 3.8 kHz Presence Exciter (+3.5 dB broad Q peaking bell)              │
│  ├── 3. 6.8 kHz Precision De-Esser (Fast-attack notch limiter)               │
│  ├── 4. Broadcast Compressor / Leveler (3:1 ratio, 15ms attack, 80ms release)│
│  ├── 5. Acoustic Gate (-48 dB floor, 0% artificial reverb for zero echo)    │
│  └── 6. Brickwall Peak Limiter (-0.3 dBFS ceiling)                           │
│                                                                              │
│  [5] Output Streaming                                                        │
│  ├── AudioTrack 44.1kHz Stereo with Live Studio VU Level Metering            │
│  └── UI Telemetry: "BROADCAST MASTERED • 4.2kHz PRESENCE • ZERO ECHO"        │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. The Four Mastered Director Profiles

1. **GySgt. Marcus Stone (USMC Drill Instructor)**
   - **Master Stems**: Battle-tested commands (*"LISTEN UP!"*, *"EYEBALLS!"*, *"SNAP TO RIGHT NOW!"*, *"MOVE, MOVE, MOVE!"*).
   - **Mastering Profile**: Maximum vocal compression (4:1), tight mid-range punch ($2.2\text{ kHz}$ bite), zero mud, instant transient attack.

2. **Coach Jordan Vance (FBA Male Fitness & Personal Coach)**
   - **Master Stems**: Unhurried, smooth baritone guidance (*"Take a breath, reset your shoulders..."*, *"Hit that handshake with an easy smile"*, *"We're not rushing this rep—we're owning it"*).
   - **Mastering Profile**: Silky low-mid warmth ($220\text{ Hz}$ warmth boost), crisp consonant intelligibility ($4.2\text{ kHz}$ air), warm tube saturation.

3. **Director Eleanor Vance (Fortune 500 Executive)**
   - **Master Stems**: Boardroom-grade strategic composure (*"Commitment window locked"*, *"Scheduled milestone active"*, *"Let's look at the bottom line"*).
   - **Mastering Profile**: Transparent studio leveling, surgical de-essing at $6.5\text{ kHz}$, focused high-mid authority.

4. **Coach Maya Lin (Motivational Coach)**
   - **Master Stems**: Radiant morning energy (*"Eyes up! Claim this morning!"*, *"Hit that handshake with total conviction"*, *"Step straight into your power!"*).
   - **Mastering Profile**: Crystalline vocal top-end ($5\text{ kHz}$ brilliance), ultra-clean noise gate, dynamic upward leveling.

---

## 4. Implementation Steps

### Step 1: Master Voice Stem Extractor & Segment Catalog (`VoiceDirectorEngine.kt`)
- Catalog key signature phrases and callouts directly from the four bundled reference recordings (`audio_drill_sergeant.mp3`, `audio_fba_male_fitness.mp3`, `audio_female_motivational.mp3`, `audio_fba_executive.mp3`).
- Implement `extractMasterStemAudio(directorId, phraseCategory)` to retrieve raw master PCM segments for intro hooks, execution phase triggers, and soundboard directives.

### Step 2: Crisp Broadcast Audio Mastering Strip (`BroadcastVocalMastering.kt`)
- Build a dedicated broadcast DSP processor in Kotlin:
  - 2nd-order Butterworth 85Hz High-Pass Filter.
  - Parametric Presence Exciter (boost around 4.0 kHz, Q=1.8).
  - Lookahead De-Esser for consonant sibilance reduction.
  - RMS/Peak Vocal Compressor with soft-knee transition and automatic makeup gain.
  - Zero-Echo Acoustic Gate preventing any synthetic reverb or room muddying.
  - Brickwall Peak Limiter locking output within $[-0.3, +0.3]\text{ dBFS}$.

### Step 3: Hybrid Concatenation & Crossfade Engine
- Implement `playHybridMasterDirective(directorId, phase, customMissionTitle, customAction)`:
  - Plays the authentic human master stem for the phrase opener/hook.
  - Splices the broadcast-mastered dynamic speech for mission specifics with a 25ms equal-power crossfade.
  - Emits real-time mastering telemetry (`VU meter level`, `Compression gain reduction dB`, `Presence lift active`).

### Step 4: UI Audio Polishing in Voice Studio & Reveille Screen
- Add a **"Studio Audio Quality: Crisp Broadcast"** status pill with dynamic VU meters in `VoiceStudioScreen` and `ReveilleOverlayScreen`.
- Ensure all 16 soundboard MPC pads utilize the hybrid master stems + broadcast mastering chain for crisp, immediate playback with zero latency.

### Step 5: Verification & Automated Tests
- Extend Robolectric unit tests in `ExampleRobolectricTest.kt` to verify:
  - 85Hz rumble attenuation and 4kHz presence boost.
  - Compressor gain reduction and brickwall peak limiting under hot signal conditions.
  - Hybrid stem extraction and crossfade sample continuity.
- Run `compile_applet` and `gradle :app:testDebugUnitTest` to guarantee a flawless build.
