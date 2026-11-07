#!/usr/bin/env bash
#
# Reliability cases that need the outside of the app: a killed process, a restart, deep idle, a revoked exact-alarm permission, the
# six-hourly backstop, the task loop walked end to end from a synthetic mission (its redacted trail is written for the release receipt), and
# on Android 17 and later the ring and the volume restore with background-audio hardening enforced. It works on any one attached device or
# emulator and needs adb and python3. It erases the app's data on that device: never point it at a phone whose alarms matter.
#
#   DEBUG_APK=... NOEXACT_APK=... scripts/reliability.sh [case ...]
#
# Cases: kill kill9 forcestop idle revoke backstop trail hardening reboot (default: all, in that order).
# Environment:
#   DEBUG_APK, NOEXACT_APK   the two APKs (default: the Gradle output paths)
#   MAX_LATE_MS              the most an alarm may be late on this device (default 15000; see docs/VERIFICATION.html)
#   REPORT                   where the result table is written (default: reliability-report.md)
#   TRAIL                    where the trail case writes the redacted task-loop trail (default: task-loop-trail.md)
#
# Every case runs whatever the one before it did. Exit status: 0 only when every case that ran passed.
set -u
export MSYS_NO_PATHCONV=1

PKG=com.aistudio.command.vtxrpl
# Git Bash on Windows gives python a path it can open only in its C:/ form (pwd -W); elsewhere pwd -W fails and plain pwd is used.
ROOT="$(cd "$(dirname "$0")/.." && { pwd -W 2>/dev/null || pwd; })"
DEBUG_APK="${DEBUG_APK:-$ROOT/android/app/build/outputs/apk/debug/app-debug.apk}"
NOEXACT_APK="${NOEXACT_APK:-$ROOT/android/app/build/outputs/apk/noexact/app-noexact.apk}"
MAX_LATE_MS="${MAX_LATE_MS:-15000}"
REPORT="${REPORT:-reliability-report.md}"
TRAIL="${TRAIL:-task-loop-trail.md}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILED=0

say() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
# Every read of the device has its own limit: adb can block for good while a device is rebooting.
ash() { timeout 30 adb shell "$@" | tr -d '\r'; }

# Records a case's outcome: result <case> <PASS|FAIL> <what was seen>.
result() {
  say "$1: $2 - $3"
  printf '| %s | %s | %s |\n' "$1" "$2" "$3" >>"$REPORT"
  if [ "$2" = FAIL ]; then FAILED=1; fi
}

# WorkManager keeps its database outside the backed-up files.
db_path() { if [ "$1" = androidx.work.workdb ]; then echo "no_backup/$1"; else echo "databases/$1"; fi; }

pull_db() { # pull_db <name> : copies a database and its journal files out of the app
  for suffix in "" -wal -shm; do
    rm -f "$WORK/$1$suffix"
    timeout 30 adb exec-out run-as "$PKG" cat "$(db_path "$1")$suffix" >"$WORK/$1$suffix" 2>/dev/null
    if [ ! -s "$WORK/$1$suffix" ] || grep -q "No such file" "$WORK/$1$suffix"; then rm -f "$WORK/$1$suffix"; fi
  done
}

sql_on() { # sql_on <database name> <sql> : one row per line, columns joined by |
  pull_db "$1"
  python3 -c '
import sqlite3, sys
if len(sys.argv) < 3: sys.exit(2)
try:
    for row in sqlite3.connect(sys.argv[1]).execute(sys.argv[2]):
        print("|".join("" if v is None else str(v) for v in row))
except sqlite3.Error:
    pass
' "$WORK/$1" "$2"
}
sql() { sql_on command.db "$1"; }

wait_for() { # wait_for <seconds> <command...> : true as soon as the command succeeds
  local end=$((SECONDS + $1)); shift
  while [ "$SECONDS" -lt "$end" ]; do
    if "$@"; then return 0; fi
    sleep 3
  done
  "$@"
}

# The number of this app's alarm-clock alarms the platform holds: the header of one names the package and a showIntent follows it.
alarm_clocks() {
  ash dumpsys alarm | awk -v pkg="$PKG" '
    /Alarm\{/ { n = (index($0, pkg) > 0) ? 8 : 0 }
    n > 0 { if ($0 ~ /showIntent=/) { c++; n = 0 } else n-- }
    END { print c + 0 }'
}
has_alarm_clock() { [ "$(alarm_clocks)" -gt 0 ]; }
no_alarm_clock() { [ "$(alarm_clocks)" -eq 0 ]; }

# A clean app, with the permissions a ring uses. pm clear also ends a ring left over from the case before.
reset_app() {
  adb shell pm clear "$PKG" >/dev/null
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null
  adb shell appops set "$PKG" USE_FULL_SCREEN_INTENT allow 2>/dev/null
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard
  adb shell input keyevent KEYCODE_HOME
}

# Retried once: just after boot the package service can still refuse an install ("Failure [0]", measured on API 37).
install_apk() { adb install -r -t "$1" >/dev/null || { sleep 20; adb install -r -t "$1" >/dev/null; }; }

arm() { # arm <title of one word> <seconds> [more am arguments] : prints the occurrence's id once it is armed
  # Sent again if nothing was armed: a broadcast sent while the last case's ring screen is being torn down can be dropped with the dying process
  # (measured on API 37: "Clearing invalid pending cold start").
  local id="" left=36 title="$1" seconds="$2"
  shift 2
  while [ -z "$id" ] && [ "$left" -gt 0 ]; do
    if [ $((left % 12)) -eq 0 ]; then adb shell am broadcast -a com.command.debug.RING -p "$PKG" -f 32 --ei seconds "$seconds" --es title "$title" "$@" >/dev/null; fi
    sleep 2
    left=$((left - 2))
    id="$(sql "SELECT o.id FROM occurrences o JOIN missions m ON m.id = o.mission_id WHERE m.title = '$title' AND o.state = 'ARMED'" | head -1)"
  done
  printf '%s' "$id"
}

state_of() { sql "SELECT state FROM occurrences WHERE id = '$1'"; }
state_is() { [ "$(state_of "$1")" = "$2" ]; }
event_count() { sql "SELECT COUNT(*) FROM events WHERE type = '$1' AND ${2:-1 = 1}"; }
event_seen() { [ "$(event_count "$1" "${2:-1 = 1}")" -gt 0 ]; }
late_ms() { sql "SELECT e.at_utc - o.scheduled_at_utc FROM events e JOIN occurrences o ON o.id = e.occurrence_id WHERE e.occurrence_id = '$1' AND e.type = 'ALARM_FIRED' ORDER BY e.seq LIMIT 1"; }
# Nothing in the app writes occurrences.ring_mode yet (reported on the PR), so the path is read from the platform and the event log.
ring_path() { # ring_path <id> : FGS when the service is in the foreground and the ring did not degrade
  if event_seen RING_DEGRADED "occurrence_id = '$1'"; then echo DEGRADED
  elif ash dumpsys activity services "$PKG" | grep -q 'isForeground=true'; then echo FGS
  else echo NONE; fi
}
arm_mode() { sql "SELECT arm_mode FROM occurrences WHERE id = '$1'"; }
arm_mode_is() { [ "$(arm_mode "$1")" = "$2" ]; }
app_pid() { ash pidof "$PKG"; }
app_dead() { [ -z "$(app_pid)" ]; }
app_alive() { [ -n "$(app_pid)" ]; }
open_app() { adb shell am start -W -n "$PKG/com.command.MainActivity" >/dev/null; }

# An alarm is on time when it fired on the service path within the limit. How late it was is always reported.
judge_ring() { # judge_ring <case> <id> <what happened before>
  local late mode
  late="$(late_ms "$2")"
  mode="$(ring_path "$2")"
  if [ -n "$late" ] && [ "$late" -le "$MAX_LATE_MS" ] && [ "$mode" = FGS ]; then
    result "$1" PASS "$3; fired ${late} ms late, path=$mode"
  else
    result "$1" FAIL "$3; late_ms='$late' (limit $MAX_LATE_MS), path='$mode', state='$(state_of "$2")'"
  fi
}

case_kill() {
  reset_app
  local id; id="$(arm relkill 40)"
  [ -n "$id" ] || { result kill FAIL "the debug receiver armed nothing"; return; }
  adb shell input keyevent KEYCODE_HOME
  adb shell am kill "$PKG"
  local killed=no
  if wait_for 15 app_dead; then killed=yes; fi
  if ! wait_for 90 state_is "$id" FIRING; then result kill FAIL "never fired; process killed=$killed"; return; fi
  [ "$killed" = yes ] || { result kill FAIL "am kill left the process alive, so the case proves nothing"; return; }
  judge_ring kill "$id" "process dead before the alarm"
}

case_kill9() {
  reset_app
  local id pid; id="$(arm relkillnine 8)"
  [ -n "$id" ] || { result kill9 FAIL "the debug receiver armed nothing"; return; }
  if ! wait_for 60 state_is "$id" FIRING; then result kill9 FAIL "never fired"; return; fi
  sleep 3
  pid="$(app_pid)"
  adb shell run-as "$PKG" kill -9 "$pid"
  sleep 2
  if [ "$(app_pid)" = "$pid" ]; then result kill9 FAIL "kill -9 did not end pid $pid"; return; fi
  # START_REDELIVER_INTENT brings the service back; Android's restart back-off can be many seconds.
  if ! wait_for 120 app_alive; then result kill9 FAIL "the process never came back"; return; fi
  sleep 5
  local fired stale; fired="$(event_count ALARM_FIRED "occurrence_id = '$id'")"; stale="$(state_of "$id")"
  if [ "$stale" = FIRING ] && [ "$fired" = 1 ]; then
    result kill9 PASS "pid $pid killed mid-ring; process restarted, still FIRING, one ALARM_FIRED"
  else
    result kill9 FAIL "after the restart state='$stale', ALARM_FIRED count='$fired'"
  fi
}

case_forcestop() {
  reset_app
  local id; id="$(arm relstop 1800)"
  [ -n "$id" ] || { result forcestop FAIL "the debug receiver armed nothing"; return; }
  if ! wait_for 15 has_alarm_clock; then result forcestop FAIL "no alarm clock in dumpsys after arming"; return; fi
  adb shell am force-stop "$PKG"
  if ! wait_for 15 no_alarm_clock; then result forcestop FAIL "force-stop left the alarm in place, so the case proves nothing"; return; fi
  open_app
  if ! wait_for 60 event_seen ALARM_LOST "occurrence_id = '$id'"; then result forcestop FAIL "no ALARM_LOST after reopening"; return; fi
  if wait_for 20 has_alarm_clock; then
    result forcestop PASS "alarm gone after force-stop; ALARM_LOST logged and the alarm clock back after reopening"
  else
    result forcestop FAIL "ALARM_LOST logged but no alarm clock back"
  fi
}

# With an alarm clock this near the device will not enter deep idle (force-idle answers "stopped at INACTIVE", and lowering device_idle's
# min_time_to_alarm did not change that), so idle comes first and the alarm is armed from inside it. Arming moved the device out of IDLE in the
# runs measured, so this shows that an alarm armed from deep idle fires on time. It does NOT show one firing while the device is still IDLE.
case_idle() {
  reset_app
  adb shell dumpsys battery unplug
  adb shell dumpsys deviceidle enable >/dev/null
  adb shell input keyevent KEYCODE_SLEEP
  sleep 3
  say "idle: $(ash dumpsys deviceidle force-idle)"
  local idle after="" id="" fired=yes awake=""
  idle="$(ash dumpsys deviceidle get deep)"
  if [ "$idle" = IDLE ]; then
    id="$(arm relidle 45)"
    after="$(ash dumpsys deviceidle get deep)"
    if [ -n "$id" ]; then wait_for 120 state_is "$id" FIRING || fired=no; else fired=no; fi
    awake="$(ash dumpsys power | grep -m1 -E 'mWakefulness=' | tr -d ' ')"
  fi
  adb shell dumpsys deviceidle unforce >/dev/null
  adb shell dumpsys battery reset
  adb shell input keyevent KEYCODE_WAKEUP
  adb shell wm dismiss-keyguard
  if [ "$idle" != IDLE ]; then result idle FAIL "the device did not enter deep idle (it says '$idle'), so the case proves nothing"; return; fi
  if [ "$fired" = no ]; then result idle FAIL "armed from deep idle (state after arming: $after, id '$id'); did not fire within 120 s"; return; fi
  judge_ring idle "$id" "armed from deep idle (state after arming: $after); $awake after firing"
}

case_revoke() {
  if [ ! -f "$NOEXACT_APK" ]; then result revoke FAIL "no noexact APK at $NOEXACT_APK"; return; fi
  install_apk "$NOEXACT_APK" || { result revoke FAIL "the noexact APK did not install"; return; }
  reset_app
  adb shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow
  local id; id="$(arm relrevoke 1800)"
  local before="" after="" back="" clock_after="" fired_mode="" outcome=FAIL note=""
  if [ -n "$id" ]; then
    before="$(arm_mode "$id")"
    # Denying the permission kills the app and, as this reports, the platform may drop its exact alarms.
    adb shell appops set "$PKG" SCHEDULE_EXACT_ALARM deny
    sleep 3
    open_app
    wait_for 60 arm_mode_is "$id" INEXACT
    after="$(arm_mode "$id")"
    clock_after="$(alarm_clocks)"
    # An inexact alarm still rings: the platform may hold it back, so this waits longer than for an exact one.
    local inexact; inexact="$(arm relinexact 10)"
    if [ -n "$inexact" ] && wait_for 150 state_is "$inexact" FIRING; then
      fired_mode="$(ring_path "$inexact"), $(late_ms "$inexact") ms late"
    else
      fired_mode="did not fire within 150 s"
    fi
    # Giving it back tells the app through the system's own broadcast.
    adb shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow
    wait_for 60 arm_mode_is "$id" EXACT
    back="$(arm_mode "$id")"
    if [ "$before" = EXACT ] && [ "$after" = INEXACT ] && [ "$clock_after" = 0 ] && [ "$back" = EXACT ] \
      && { [ "${fired_mode#FGS}" != "$fired_mode" ] || [ "${fired_mode#DEGRADED}" != "$fired_mode" ]; }; then outcome=PASS; fi
    note="arm_mode $before -> $after (alarm clocks held: $clock_after) -> $back after allow; inexact ring: $fired_mode"
  else
    note="the debug receiver armed nothing"
  fi
  result revoke "$outcome" "$note"
  adb shell pm clear "$PKG" >/dev/null
  # A later case needs the debug build's own permissions back.
  adb uninstall "$PKG" >/dev/null
  install_apk "$DEBUG_APK" || result revoke FAIL "the debug APK did not go back on"
}

# The backstop's first run is six hours off, so the case rewrites WorkManager's own record of when it was queued: with the app stopped, the
# stored request says it was queued more than a period ago, and the next process start reschedules what is due.
case_backstop() {
  reset_app
  open_app
  local spec=""
  periodic_row() { spec="$(sql_on androidx.work.workdb "SELECT id, interval_duration FROM workspec WHERE interval_duration > 0" | head -1)"; [ -n "$spec" ]; }
  if ! wait_for 60 periodic_row; then result backstop FAIL "no periodic request in WorkManager's database after the app opened"; return; fi
  local interval="${spec##*|}" work_id="${spec%%|*}"
  if [ "$interval" != 21600000 ]; then result backstop FAIL "period is $interval ms, not six hours"; return; fi
  local id; id="$(arm relbackstop 1800)"
  [ -n "$id" ] || { result backstop FAIL "the debug receiver armed nothing"; return; }
  adb shell am force-stop "$PKG"
  wait_for 15 no_alarm_clock

  pull_db androidx.work.workdb
  python3 -c '
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
db.execute("UPDATE workspec SET initial_delay = 0, last_enqueue_time = last_enqueue_time - 2 * interval_duration, period_count = 1 WHERE id = ?", (sys.argv[2],))
db.commit()
db.execute("PRAGMA journal_mode = DELETE")
db.close()
' "$WORK/androidx.work.workdb" "$work_id"
  adb shell run-as "$PKG" rm -f no_backup/androidx.work.workdb-wal no_backup/androidx.work.workdb-shm
  adb shell "run-as $PKG sh -c 'cat > no_backup/androidx.work.workdb'" <"$WORK/androidx.work.workdb"

  # Starts the process without opening the app, so nothing here runs the app's own startup reconcile. This broadcast with no id does nothing itself.
  adb shell am broadcast -a com.command.debug.CHECKIN_DUE -p "$PKG" -f 32 >/dev/null
  # The proof is WorkManager's count of periods, 1 as written above. It is not which trigger logged ALARM_LOST: after a force-stop Android redelivers
  # BOOT_COMPLETED, so a BOOT reconcile runs at the same moment and either may find the alarm missing (measured both ways).
  periodic_ran() { [ "$(sql_on androidx.work.workdb "SELECT period_count FROM workspec WHERE id = '$work_id'")" = 2 ]; }
  if ! wait_for 60 periodic_ran; then
    local job; job="$(sql_on androidx.work.workdb "SELECT system_id FROM SystemIdInfo WHERE work_spec_id = '$work_id'" | head -1)"
    [ -z "$job" ] || adb shell cmd jobscheduler run -f "$PKG" "$job" >/dev/null
    if ! wait_for 120 periodic_ran; then
      say "backstop: workspec now: $(sql_on androidx.work.workdb "SELECT state, initial_delay, last_enqueue_time, period_count, run_attempt_count FROM workspec WHERE id = '$work_id'")"
      result backstop FAIL "period is six hours, but the request made due did not run (job '$job')"
      return
    fi
  fi
  if wait_for 20 has_alarm_clock; then
    result backstop PASS "period 21600000 ms; the request made due ran with the app unopened (period_count 1 to 2); the alarm clock is back, by this run or the redelivered BOOT's"
  else
    result backstop FAIL "the periodic run happened but no alarm clock came back"
  fi
}

# The screen as uiautomator sees it, for the cases that walk the app as a person would. ui_find <text> prints the centre "x y" of the first
# element whose text or description contains <text>, and fails when there is none.
ui_find() {
  adb shell uiautomator dump /sdcard/reliability-ui.xml >/dev/null 2>&1
  timeout 30 adb exec-out cat /sdcard/reliability-ui.xml >"$WORK/ui.xml" 2>/dev/null
  python3 -c '
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except (ET.ParseError, OSError):
    sys.exit(1)
for node in root.iter("node"):
    if sys.argv[2] in (node.get("text") or "") or sys.argv[2] in (node.get("content-desc") or ""):
        x1, y1, x2, y2 = map(int, re.findall("[0-9]+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
' "$WORK/ui.xml" "$1"
}
ui_wait() { # ui_wait <seconds> <text> : the centre of <text> once it is on screen
  local end=$((SECONDS + $1)) at
  while [ "$SECONDS" -lt "$end" ]; do
    if at="$(ui_find "$2")"; then echo "$at"; return 0; fi
    sleep 2
  done
  return 1
}
ui_tap() { local at; at="$(ui_wait "${2:-30}" "$1")" || return 1; adb shell input tap $at; }
ui_hold() { local at; at="$(ui_wait 30 "$1")" || return 1; adb shell input swipe $at $at "$2"; } # ui_hold <text> <ms>

# The task loop the spec's release receipt asks evidence of: a synthetic mission rings, is answered by holding, the person says they are
# starting (a self-reported start: the timed five-minute starter is its own, optional button), the first check-in is asked and answered
# Done. That occurrence's trail, cut by receipt-redact.py to event type, time, state and
# request code, is written to $TRAIL for the receipt. The Nudge profile is used because its handshake is a 2 s hold.
case_trail() {
  reset_app
  local id step=""
  id="$(arm reltrail 20 --es profile builtin_nudge)"
  [ -n "$id" ] || { result trail FAIL "the debug receiver armed nothing"; return; }
  if ! wait_for 90 state_is "$id" FIRING; then result trail FAIL "never fired"; return; fi
  # Unlocked, the ring is a notification whose Open brings up the ring screen. Its heads-up folds away after a few seconds (measured on API 37),
  # so the shade is opened to reach it.
  local tries=0
  until [ "$tries" -ge 3 ]; do
    adb shell cmd statusbar expand-notifications
    if ui_tap Open 15; then break; fi
    tries=$((tries + 1))
  done
  [ "$tries" -lt 3 ] || step="no Open on the ring notification"
  [ -n "$step" ] || ui_hold "Hold to answer" 4000 || step="no hold button on the ring screen"
  # An answered ring moves on from ACKNOWLEDGED to GUIDED_START at once (measured: 11 ms apart), so the transition is what is looked for.
  [ -n "$step" ] || wait_for 20 event_seen STATE_CHANGED "occurrence_id = '$id' AND payload_json LIKE '%ACKNOWLEDGED%'" || step="holding did not answer the ring (state $(state_of "$id"))"
  [ -n "$step" ] || ui_tap "I am starting" || step="no 'I am starting' after the answer"
  [ -n "$step" ] || wait_for 20 state_is "$id" STARTED_SELF_REPORTED || step="'I am starting' did not record a start (state $(state_of "$id"))"
  if [ -z "$step" ]; then
    adb shell am broadcast -a com.command.debug.CHECKIN_DUE -p "$PKG" -f 32 --es id "$id" >/dev/null
    wait_for 30 event_seen CHECK_IN_ASKED "occurrence_id = '$id'" || step="the check-in was not asked"
  fi
  if [ -z "$step" ]; then
    adb shell am start -W -n "$PKG/com.command.MainActivity" -a com.command.action.OPEN_CHECK_IN --es com.command.extra.OCCURRENCE_ID "$id" >/dev/null
    ui_tap Done || step="no Done on the check-in"
  fi
  [ -n "$step" ] || wait_for 20 state_is "$id" COMPLETED_SELF_REPORTED || step="answering Done did not complete it (state $(state_of "$id"))"
  adb shell input keyevent KEYCODE_HOME
  pull_db command.db
  if ! python3 "$ROOT/scripts/receipt-redact.py" "$WORK/command.db" >"$WORK/trail.md"; then
    result trail FAIL "${step:-the loop ran}; receipt-redact.py refused the database"
    return
  fi
  {
    echo "### Task-loop trail, API $(ash getprop ro.build.version.sdk), debug APK $(sha256sum "$DEBUG_APK" | cut -d' ' -f1)"
    echo
    cat "$WORK/trail.md"
  } >"$TRAIL"
  if [ -n "$step" ]; then
    result trail FAIL "$step; the partial trail is in $TRAIL"
  else
    result trail PASS "rang, held, I am starting, check-in asked, Done: $(($(wc -l <"$WORK/trail.md") - 2)) events in $TRAIL"
  fi
}

# The alarm stream's level and its highest, from the platform, as "level highest".
alarm_volume() { ash cmd media_session volume --stream 4 --get | sed -n 's/.*volume is \([0-9]*\) in range \[[0-9]*\.\.\([0-9]*\)\].*/\1 \2/p'; }
volume_is() { [ "$(alarm_volume | cut -d' ' -f1)" = "$1" ]; }
# A started player of alarm usage: what "the ring plays" means on an emulator with no audio device.
alarm_playing() { ash dumpsys audio | grep -E 'usage=USAGE_ALARM' | grep -q 'state:started'; }

# Android 17's background-audio hardening, enforced (risk 7 of the plan): the ring, and the restore of the alarm volume after a ring that died.
# The ring is a foreground service started from an alarm clock; the restore runs from the reconcile that the next start of the process queues,
# with no foreground service, which is the call Android 17 may drop without an error. The volume consent is given and the level set low; the
# ring raises it to the highest; the process is force-stopped mid-ring so that only the stored record is left; starting the process without its
# screen must put the level back. It runs once with enforcement on ("enable", which silences what breaks the rule) and once with "throw" (which
# makes the breaking call throw, so nothing passes quietly), each its own row. Before Android 17 there is nothing to enforce, and the case says so.
case_hardening() {
  local api; api="$(ash getprop ro.build.version.sdk)"
  if [ "${api:-0}" -lt 37 ]; then result hardening N/A "API $api has no background-audio hardening to enforce"; return; fi
  local mode
  for mode in ${HARDENING_MODES:-enable throw}; do hardening_run "$mode"; done
  adb shell cmd audio clear-hardening >/dev/null 2>&1
}

hardening_run() { # hardening_run <enable|throw>
  local name="hardening-$1"
  reset_app
  adb logcat -c
  # The Android 17 image (API 37, google_apis r08) names it set-hardening; the plan's set-enable-hardening is not a command there.
  local low max id raised="" playing=no restored="" refused=""
  # The ring starts from the level the device has, below its highest (the API 37 image starts at 6 of 7). The shell cannot be relied on to
  # lower it: on API 37 the platform refuses the shell's own volume change too (measured: "AudioHardening volume control ... ignored for
  # com.android.server.media"), so the case only tries once, before enforcement.
  adb shell cmd audio clear-hardening >/dev/null 2>&1
  low="$(alarm_volume | cut -d' ' -f1)"; max="$(alarm_volume | cut -d' ' -f2)"
  if [ -n "$max" ] && [ "$low" = "$max" ]; then timeout 20 adb shell cmd audio set-volume 4 $((max - 1)) >/dev/null 2>&1; low="$(alarm_volume | cut -d' ' -f1)"; fi
  if [ -z "$max" ] || [ "$low" = "$max" ]; then result "$name" FAIL "the alarm volume is at its highest ('$(alarm_volume)') and could not be lowered, so a raise cannot be seen"; return; fi
  local set; set="$(ash cmd audio set-hardening "$1" 2>&1)"
  if printf '%s' "$set" | grep -qi -E 'unknown|error|invalid'; then result "$name" FAIL "cmd audio set-hardening $1 was refused: $set"; return; fi
  local enforced="set-hardening $1 accepted"
  id="$(arm relharden 15 --ez volume true)"
  [ -n "$id" ] || { result "$name" FAIL "the debug receiver armed nothing"; return; }
  if ! wait_for 90 state_is "$id" FIRING; then result "$name" FAIL "never fired with hardening enforced ($enforced)"; return; fi
  wait_for 15 alarm_playing && playing=yes
  wait_for 15 volume_is "$max" && raised=yes
  local path; path="$(ring_path "$id")"
  local logged; logged="$(timeout 30 adb logcat -d | grep -c -i -E 'AudioHardening|would be muted|would be ignored')"
  adb shell am force-stop "$PKG"
  sleep 3
  local after_stop; after_stop="$(alarm_volume | cut -d' ' -f1)"
  adb logcat -c
  # After a force-stop Android redelivers BOOT_COMPLETED when the process next starts, and that reconcile restores what a dead ring raised.
  adb shell am broadcast -a com.command.debug.CHECKIN_DUE -p "$PKG" -f 32 >/dev/null
  wait_for 90 volume_is "$low" && restored=yes
  if event_seen VOLUME_SET_REFUSED "occurrence_id = '$id'"; then
    refused="$(sql "SELECT payload_json FROM events WHERE type = 'VOLUME_SET_REFUSED' AND occurrence_id = '$id'" | head -1)"
  fi
  # The background reconcile also re-arms the alarms, so it must finish whatever the volume did: a failed or retried run is a FAIL even if the
  # level comes back later (before amendment 39, throw mode failed it and opening the app still restored the level).
  local reconciles; reconciles="$(timeout 30 adb logcat -d | grep -E 'Worker result [A-Z]+ for Work .*ReconcileAlarmsWorker' | sed -n 's/.*Worker result \([A-Z]*\) .*/\1/p' | sort | uniq -c | tr -s ' ' | tr '\n' ',')"
  local reconcile_ok=yes
  if [ -z "$reconciles" ] || printf '%s' "$reconciles" | grep -q -E 'RETRY|FAILURE'; then reconcile_ok=no; fi
  # Enforced, the platform drops that restore without an error (measured). The record of what to put back must then outlive it, so that the
  # app's own screen, which hardening allows, puts the level back when it is opened.
  local on_open=""
  if [ -z "$restored" ]; then
    open_app
    wait_for 60 volume_is "$low" && on_open=yes
    adb shell input keyevent KEYCODE_HOME
  fi
  local seen="$enforced; ring path=$path, alarm player started=$playing, raised $low to $max=${raised:-no}"
  seen="$seen; $logged hardening log lines; level after force-stop $after_stop; put back to $low with the app unopened=${restored:-no}"
  [ -z "$refused" ] || seen="$seen (VOLUME_SET_REFUSED $refused)"
  [ -n "$restored" ] || seen="$seen; put back when the app was opened=${on_open:-no}"
  seen="$seen; background reconciles: ${reconciles:-none seen}"
  # Put back in the background; or dropped there, with the refusal logged (so the record was kept, not cleared as a floor) and done on opening.
  local volume_ok=no
  if [ "$restored" = yes ] || { [ -n "$refused" ] && [ "$on_open" = yes ]; }; then volume_ok=yes; fi
  if [ "$path" = FGS ] && [ "$playing" = yes ] && [ "$raised" = yes ] && [ "$volume_ok" = yes ] && [ "$reconcile_ok" = yes ]; then
    result "$name" PASS "$seen"
  else
    result "$name" FAIL "$seen"
  fi
}

booted() { [ "$(ash getprop sys.boot_completed)" = 1 ] && ash service list | grep -q 'activity:'; }

# Last, because a reboot that hangs leaves nothing to test on. The timeout is the point: on the dev box this has hung for good.
case_reboot() {
  reset_app
  local id; id="$(arm relreboot 1800)"
  [ -n "$id" ] || { result reboot FAIL "the debug receiver armed nothing"; return; }
  timeout 60 adb reboot || { result reboot FAIL "adb reboot itself did not return in 60 s"; return; }
  sleep 15
  if ! timeout 300 adb wait-for-device; then result reboot FAIL "the device did not come back in 300 s"; return; fi
  if ! wait_for 300 booted; then result reboot FAIL "sys.boot_completed never reached 1 in 300 s"; return; fi
  adb shell wm dismiss-keyguard
  if ! wait_for 240 event_seen BOOT_RECONCILE "payload_json LIKE '%BOOT%'"; then result reboot FAIL "no BOOT_RECONCILE{BOOT} in 240 s"; return; fi
  if wait_for 20 has_alarm_clock; then
    result reboot PASS "real reboot; BOOT_RECONCILE logged and the alarm clock back, app never opened"
  else
    result reboot FAIL "BOOT_RECONCILE logged but no alarm clock back"
  fi
}

main() {
  local cases="$*"
  [ -n "$cases" ] || cases="kill kill9 forcestop idle revoke backstop trail hardening reboot"
  adb wait-for-device
  adb logcat -G 16M
  {
    echo "### Reliability on API $(ash getprop ro.build.version.sdk) ($(ash getprop ro.product.model))"
    echo
    echo "Debug APK SHA-256: $(sha256sum "$DEBUG_APK" | cut -d' ' -f1)"
    echo
    echo "| case | result | seen |"
    echo "|---|---|---|"
  } >"$REPORT"
  install_apk "$DEBUG_APK" || { result setup FAIL "the debug APK did not install from $DEBUG_APK"; return 1; }
  for name in $cases; do
    case "$name" in
      kill | kill9 | forcestop | idle | revoke | backstop | trail | hardening | reboot) ;;
      *) result "$name" FAIL "no such case"; continue ;;
    esac
    say "=== $name"
    adb logcat -c
    "case_$name"
    timeout 60 adb logcat -d >"${LOG_DIR:-.}/logcat-case-$name.txt"
  done
  return "$FAILED"
}

main "$@"
