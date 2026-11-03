package com.command.domain.model

/** Where a mission came from. Calendar-sourced missions are read-only copies; the calendar is never written to. */
enum class MissionSource { MANUAL, DEVICE_CALENDAR, GOOGLE_API }

enum class StartKind { ONE_OFF, RRULE }

/** A DEVICE mission follows the phone's current zone; a FIXED one stays in [zone_id]. Zone only, never a location. */
enum class ZoneMode { DEVICE, FIXED }

/** Whether a ring was delivered by the foreground service or degraded to a notification. */
enum class RingMode { FGS, NOTIFICATION_ONLY }

enum class EventSource { RECEIVER, SERVICE, WORKER, UI, USER, SYSTEM }

enum class ChallengeOutcome { SUCCESS, FAILED, CANCELLED, TIMEOUT, FALLBACK, ACCESSIBLE_ALT, SAFETY_SILENCE }

/** The six answers to a follow-up question, plus silence. */
enum class CheckInAnswer { STARTED, WORKING, DONE, NEED_RESET, DELAY, SKIP, NO_RESPONSE }

/**
 * Every grant the user can give or withdraw. Rows are append-only: the current state of a key is its latest row, and
 * the full history stays visible. Privacy, money, tracking and partner notification are never implied by a preset.
 */
enum class ConsentKey {
    NOTIFICATIONS, EXACT_ALARM, FULL_SCREEN, VOLUME_CONTROL,
    CALENDAR_READ, CALENDAR_EVENT_TEXT, GOOGLE_CALENDAR_API,
    CAMERA_QR, NFC, MIC_VOICE, MIC_VOICE_NETWORK,
    GEMINI_TEXT, GEMINI_TEXT_TITLES, GEMINI_VOICE, GEMINI_VOICE_TITLES,
    CLOUD_SYNC, APP_LAUNCHER,
}

enum class ConsentSource { USER, SYSTEM }

enum class VoiceEngine { LOCAL_TTS, GEMINI_STUDIO }

enum class RestKind { SLEEP, LEISURE, DRIVING }

enum class ConflictKind { OVERLAP, NESTED, BUFFER }

enum class SyncOp { UPSERT, DELETE }

/** Well-known keys of the settings table. */
object SettingKeys {
    const val ACTIVE_PROFILE_ID = "active_profile_id"
    const val NEXT_REQUEST_CODE = "next_request_code"
    const val VOLUME_RESTORE = "volume_restore"
    const val GLOBAL_PAUSE_UNTIL = "global_pause_until"
    const val LEGACY_IMPORT_DONE = "legacy_import_done"
    const val AI_DAILY_BUDGET = "ai_daily_budget"
    const val ALARM_ROUTE_POLICY = "alarm_route_policy"
    const val PERMISSION_SNAPSHOT = "permission_snapshot"
    const val LAST_PULL_AT = "last_pull_at"
}
