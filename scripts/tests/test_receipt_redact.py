"""Tests for scripts/receipt-redact.py: what may leave a device database for a public receipt.

Run from the repository root: python3 -m unittest discover -s scripts/tests -v
"""
import importlib.util
import io
import json
import os
import sqlite3
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = importlib.util.spec_from_file_location("receipt_redact", os.path.join(HERE, "..", "receipt-redact.py"))
redact = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(redact)

SYNTHETIC_ACTION = "Open the first thing and give it five minutes"
SECRET_TITLE = "Call the dentist about Maria"
SECRET_ACTION = "Find the insurance card"


def make_db(path, missions):
    """missions: list of (mission_id, title, category, first_action, [(occurrence_id, request_code, [(type, at_utc, payload)])])"""
    db = sqlite3.connect(path)
    db.executescript(
        """
        CREATE TABLE missions (id TEXT PRIMARY KEY, title TEXT NOT NULL, first_action TEXT NOT NULL, category TEXT NOT NULL);
        CREATE TABLE occurrences (id TEXT PRIMARY KEY, mission_id TEXT NOT NULL, scheduled_at_utc INTEGER NOT NULL, state TEXT NOT NULL,
                                  request_code INTEGER NOT NULL);
        CREATE TABLE events (seq INTEGER PRIMARY KEY AUTOINCREMENT, occurrence_id TEXT, mission_id TEXT, type TEXT NOT NULL,
                             at_utc INTEGER NOT NULL, elapsed_realtime INTEGER NOT NULL, boot_id TEXT, source TEXT NOT NULL,
                             payload_json TEXT NOT NULL);
        """
    )
    for mission_id, title, category, action, occurrences in missions:
        db.execute("INSERT INTO missions VALUES (?, ?, ?, ?)", (mission_id, title, action, category))
        for occurrence_id, code, events in occurrences:
            db.execute("INSERT INTO occurrences VALUES (?, ?, 0, 'DONE', ?)", (occurrence_id, mission_id, code))
            for kind, at, payload in events:
                db.execute(
                    "INSERT INTO events (occurrence_id, mission_id, type, at_utc, elapsed_realtime, boot_id, source, payload_json)"
                    " VALUES (?, ?, ?, ?, 0, 'boot-1', 'SYSTEM', ?)",
                    (occurrence_id, mission_id, kind, at, json.dumps(payload)),
                )
    db.commit()
    db.close()


TRAIL = [
    ("STATE_CHANGED", 1_700_000_000_000, {"from": "SCHEDULED", "to": "ARMED", "command": "Arm"}),
    ("ALARM_ARMED", 1_700_000_000_010, {"mode": "EXACT"}),
    ("STATE_CHANGED", 1_700_000_020_000, {"from": "ARMED", "to": "FIRING", "command": "Fire"}),
    ("ALARM_FIRED", 1_700_000_020_005, {}),
    ("STATE_CHANGED", 1_700_000_030_000, {"from": "FIRING", "to": "ACKNOWLEDGED", "command": "Acknowledge"}),
    ("CHECK_IN_ANSWERED", 1_700_000_090_000, {"answer": "DONE", "note": "free text here"}),
]


class RedactTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.db = os.path.join(self.dir.name, "command.db")

    def tearDown(self):
        self.dir.cleanup()

    def run_main(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = redact.main([self.db, *args])
        return code, out.getvalue(), err.getvalue()

    def test_emits_type_time_state_and_request_code_only(self):
        make_db(self.db, [("m-1", "reltrail", "Debug", SYNTHETIC_ACTION, [("occ-uuid-1", 4242, TRAIL)])])
        code, out, _ = self.run_main("--json")
        self.assertEqual(0, code)
        rows = json.loads(out)
        self.assertEqual(len(TRAIL), len(rows))
        for row in rows:
            self.assertEqual({"type", "time", "state", "request_code"}, set(row))
            self.assertEqual(4242, row["request_code"])
        self.assertEqual("2023-11-14T22:13:20.000Z", rows[0]["time"])
        for leaked in ("reltrail", "occ-uuid-1", "m-1", "boot-1", "free text", "EXACT", "Acknowledge", SYNTHETIC_ACTION):
            self.assertNotIn(leaked, out)

    def test_state_is_the_one_the_last_transition_reached(self):
        make_db(self.db, [("m-1", "reltrail", "Debug", SYNTHETIC_ACTION, [("occ-1", 7, TRAIL)])])
        _, out, _ = self.run_main("--json")
        states = [row["state"] for row in json.loads(out)]
        self.assertEqual(["ARMED", "ARMED", "FIRING", "FIRING", "ACKNOWLEDGED", "ACKNOWLEDGED"], states)

    def test_events_come_out_in_the_order_they_were_written(self):
        make_db(self.db, [
            ("m-1", "one", "Debug", SYNTHETIC_ACTION, [("occ-1", 1, TRAIL[:2])]),
            ("m-2", "two", "Debug", SYNTHETIC_ACTION, [("occ-2", 2, TRAIL[:1])]),
        ])
        _, out, _ = self.run_main("--json")
        self.assertEqual([1, 1, 2], [row["request_code"] for row in json.loads(out)])

    def test_a_database_with_any_real_mission_gives_nothing(self):
        make_db(self.db, [
            ("m-1", "reltrail", "Debug", SYNTHETIC_ACTION, [("occ-1", 7, TRAIL)]),
            ("m-2", SECRET_TITLE, "Health", SECRET_ACTION, [("occ-2", 8, TRAIL)]),
        ])
        code, out, err = self.run_main("--json")
        self.assertEqual(2, code)
        self.assertEqual("", out)
        self.assertNotIn(SECRET_TITLE, err)
        self.assertNotIn(SECRET_ACTION, err)

    def test_a_mission_filed_as_debug_but_with_its_own_first_step_is_real(self):
        make_db(self.db, [("m-1", "reltrail", "Debug", SECRET_ACTION, [("occ-1", 7, TRAIL)])])
        code, out, _ = self.run_main("--json")
        self.assertEqual(2, code)
        self.assertEqual("", out)

    def test_an_empty_database_is_refused(self):
        make_db(self.db, [])
        code, out, _ = self.run_main("--json")
        self.assertEqual(2, code)
        self.assertEqual("", out)

    def test_events_of_no_occurrence_are_left_out(self):
        make_db(self.db, [("m-1", "reltrail", "Debug", SYNTHETIC_ACTION, [("occ-1", 7, TRAIL[:1])])])
        db = sqlite3.connect(self.db)
        db.execute("INSERT INTO events (occurrence_id, mission_id, type, at_utc, elapsed_realtime, boot_id, source, payload_json)"
                   " VALUES (NULL, NULL, 'BOOT_RECONCILE', 5, 0, 'b', 'SYSTEM', '{}')")
        db.commit()
        db.close()
        _, out, _ = self.run_main("--json")
        self.assertEqual(["STATE_CHANGED"], [row["type"] for row in json.loads(out)])

    def test_markdown_is_a_table_of_the_same_four_columns(self):
        make_db(self.db, [("m-1", "reltrail", "Debug", SYNTHETIC_ACTION, [("occ-1", 7, TRAIL[:2])])])
        code, out, _ = self.run_main()
        self.assertEqual(0, code)
        lines = out.strip().splitlines()
        self.assertEqual("| type | time (UTC) | state | request_code |", lines[0])
        self.assertEqual("| STATE_CHANGED | 2023-11-14T22:13:20.000Z | ARMED | 7 |", lines[2])
        self.assertEqual(4, len(lines))


if __name__ == "__main__":
    unittest.main()
