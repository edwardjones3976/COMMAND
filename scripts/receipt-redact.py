#!/usr/bin/env python3
"""The task-loop trail for a public release receipt, from a copy of the app's database.

    python3 scripts/receipt-redact.py <command.db> [--json]

Prints, for every event of every occurrence, in the order they were written: the event type, its time (UTC), the state the occurrence had
reached by then, and the occurrence's request code. Nothing else leaves the database: no title, first step, id, payload, boot id or note.

It reads only a run of synthetic missions, the ones the debug build's ring starter makes (category "Debug" with its fixed first step). A
database holding any other mission, or none, gives no output and exit status 2: a real person's alarms never go into a receipt, redacted or not.
Keep the -wal and -shm files next to the database copy, or the latest events are not in it.
"""
import argparse
import datetime
import json
import sqlite3
import sys

SYNTHETIC_CATEGORY = "Debug"
# The first step DebugRingReceiver gives every mission it makes (android/app/src/debug/java/com/command/debug/DebugRingReceiver.kt).
SYNTHETIC_FIRST_ACTION = "Open the first thing and give it five minutes"
COLUMNS = ("type", "time", "state", "request_code")


def utc(ms):
    moment = datetime.datetime.fromtimestamp(ms / 1000, tz=datetime.timezone.utc)
    return moment.strftime("%Y-%m-%dT%H:%M:%S.") + f"{moment.microsecond // 1000:03d}Z"


def trail(db):
    """The redacted rows, or None when the database is not a synthetic run."""
    missions = db.execute("SELECT category, first_action FROM missions").fetchall()
    if not missions or any(m != (SYNTHETIC_CATEGORY, SYNTHETIC_FIRST_ACTION) for m in missions):
        return None
    rows = db.execute(
        "SELECT e.occurrence_id, e.type, e.at_utc, e.payload_json, o.request_code"
        " FROM events e JOIN occurrences o ON o.id = e.occurrence_id ORDER BY e.seq"
    ).fetchall()
    reached = {}
    out = []
    for occurrence, kind, at, payload, code in rows:
        if kind == "STATE_CHANGED":
            try:
                to = json.loads(payload).get("to")
            except (ValueError, AttributeError):
                to = None
            if isinstance(to, str) and to.isupper() and to.replace("_", "").isalpha():
                reached[occurrence] = to
        out.append({"type": kind, "time": utc(at), "state": reached.get(occurrence, ""), "request_code": int(code)})
    return out


def markdown(rows):
    lines = ["| type | time (UTC) | state | request_code |", "|---|---|---|---|"]
    lines += [f"| {r['type']} | {r['time']} | {r['state']} | {r['request_code']} |" for r in rows]
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("database")
    parser.add_argument("--json", action="store_true", help="a JSON list instead of a Markdown table")
    args = parser.parse_args(argv)
    try:
        db = sqlite3.connect(args.database)
        try:
            rows = trail(db)
        finally:
            db.close()
    except sqlite3.Error as error:
        print(f"receipt-redact: cannot read the database ({type(error).__name__})", file=sys.stderr)
        return 2
    if rows is None:
        print("receipt-redact: refused: the database holds no missions or a mission the debug ring starter did not make", file=sys.stderr)
        return 2
    print(json.dumps(rows, indent=1) if args.json else markdown(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
