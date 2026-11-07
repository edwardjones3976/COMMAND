"""Tests for scripts/receipt-permissions.py: the receipt's permissions list must be the merged release manifest's.

Run from the repository root: python3 -m unittest discover -s scripts/tests -v
"""
import importlib.util
import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = importlib.util.spec_from_file_location("receipt_permissions", os.path.join(HERE, "..", "receipt-permissions.py"))
perms = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(perms)

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x">
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="com.aistudio.command.vtxrpl.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />
    <permission android:name="com.aistudio.command.vtxrpl.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android:protectionLevel="signature" />
    <application><activity android:name="A"><meta-data android:name="android.permission.NOT_A_USE" /></activity></application>
</manifest>
"""


NOTE = "INTERNET is declared and no running code uses it."


def receipt(*names, internet_note=True, note_text=NOTE):
    items = "".join(f"<li><code>{n}</code> what it is for</li>" for n in names)
    note = f'<p id="internet-unused"><strong>{note_text}</strong></p>' if internet_note else ""
    return f'<html lang="en"><body><p><code>android.permission.CAMERA</code> is not asked for.</p><ul id="permissions">{items}</ul>{note}</body></html>'


ALL = ("android.permission.SCHEDULE_EXACT_ALARM", "android.permission.INTERNET",
       "com.aistudio.command.vtxrpl.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")


class PermissionsTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.manifest = os.path.join(self.dir.name, "AndroidManifest.xml")
        with open(self.manifest, "w", encoding="utf-8") as f:
            f.write(MANIFEST)
        self.receipt = os.path.join(self.dir.name, "receipt.html")

    def tearDown(self):
        self.dir.cleanup()

    def write_receipt(self, text):
        with open(self.receipt, "w", encoding="utf-8") as f:
            f.write(text)

    def check(self):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = perms.main(["check", self.receipt, self.manifest])
        return code, out.getvalue() + err.getvalue()

    def test_the_manifest_asks_for_what_its_uses_permission_elements_name(self):
        self.assertEqual(sorted(ALL), perms.requested(self.manifest))

    def test_list_prints_one_name_per_line_with_its_sdk_limit(self):
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(0, perms.main(["list", self.manifest]))
        self.assertIn("android.permission.SCHEDULE_EXACT_ALARM (up to API 32)", out.getvalue().splitlines())

    def test_a_receipt_that_names_exactly_those_passes(self):
        self.write_receipt(receipt(*ALL))
        self.assertEqual((0, ""), (self.check()[0], ""))

    def test_a_permission_missing_from_the_receipt_fails_and_is_named(self):
        self.write_receipt(receipt(*ALL[:2]))
        code, text = self.check()
        self.assertEqual(1, code)
        self.assertIn("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", text)

    def test_a_permission_the_manifest_does_not_ask_for_fails(self):
        self.write_receipt(receipt(*ALL, "android.permission.RECORD_AUDIO"))
        code, text = self.check()
        self.assertEqual(1, code)
        self.assertIn("RECORD_AUDIO", text)

    def test_only_the_permissions_list_counts_not_names_elsewhere_on_the_page(self):
        self.write_receipt(receipt(*ALL))
        self.assertEqual(0, self.check()[0])

    def test_internet_declared_needs_the_statement_that_nothing_uses_it(self):
        self.write_receipt(receipt(*ALL, internet_note=False))
        code, text = self.check()
        self.assertEqual(1, code)
        self.assertIn("internet-unused", text)

    def test_an_empty_statement_does_not_count(self):
        self.write_receipt(receipt(*ALL, note_text=""))
        code, text = self.check()
        self.assertEqual(1, code)
        self.assertIn("internet-unused", text)

    def test_a_statement_that_does_not_say_nothing_uses_internet_does_not_count(self):
        self.write_receipt(receipt(*ALL, note_text="INTERNET is used to fetch voices."))
        self.assertEqual(1, self.check()[0])

    def test_a_receipt_without_a_list_fails(self):
        self.write_receipt('<html lang="en"><body></body></html>')
        self.assertEqual(1, self.check()[0])


if __name__ == "__main__":
    unittest.main()
