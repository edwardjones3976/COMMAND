#!/usr/bin/env bash
#
# Reliability cases that need the outside of the app: a killed process, a restart, deep idle, a revoked exact-alarm permission and the
# six-hourly backstop. It works on any one attached device or emulator and needs adb and python3. It erases the app's data on that device:
# never point it at a phone whose alarms matter.
#
#   DEBUG_APK=... NOEXACT_APK=... scripts/reliability.sh [case ...]
#
# Cases: kill kill9 forcestop idle revoke backstop reboot (default: all, in that order).
# Environment:
#   DEBUG_APK, NOEXACT_APK   the two APKs (default: the Gradle output paths)
#   MAX_LATE_MS              the most an alarm may be late on this device (default 15000; see docs/VERIFICATION.html)
#   REPORT                   where the result table is written (default: reliability-report.md)
#
# Every case runs whatever the one before it did. Exit status: 0 only when every case that ran passed.
set -u
export MSYS_NO_PATHCONV=1

PKG=com.aistudio.command.vtxrpl
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEBUG_APK="${DEBUG_APK:-$ROOT/android/app/build/outputs/apk/debug/app-debug.apk}"
NOEXACT_APK="${NOEXACT_APK:-$ROOT/android/app/build/outputs/apk/noexact/app-noexact.apk}"
MAX_LATE_MS="${MAX_LATE_MS:-15000}"
REPORT="${REPORT:-reliability-report.md}"
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

install_apk() { adb install -r -t "$1" >/dev/null; }

arm() { # arm <title of one word> <seconds> : prints the occurrence's id once it is armed
  # Sent again if nothing was armed: a broadcast sent while the last case's ring screen is being torn down can be dropped with the dying process
  # (measured on API 37: "Clearing invalid pending cold start").
  local id="" left=36
  while [ -z "$id" ] && [ "$left" -gt 0 ]; do
    if [ $((left % 12)) -eq 0 ]; then adb shell am broadcast -a com.command.debug.RING -p "$PKG" -f 32 --ei seconds "$2" --es title "$1" >/dev/null; fi
    sleep 2
    left=$((left - 2))
    id="$(sql "SELECT o.id FROM occurrences o JOIN missions m ON m.id = o.mission_id WHERE m.title = '$1' AND o.state = 'ARMED'" | head -1)"
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
  [ -n "$cases" ] || cases="kill kill9 forcestop idle revoke backstop reboot"
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
      kill | kill9 | forcestop | idle | revoke | backstop | reboot) ;;
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
