#!/usr/bin/env python3
"""The permissions a build asks for, read from its merged manifest, and a check that a release receipt lists exactly those.

    python3 scripts/receipt-permissions.py list  <merged AndroidManifest.xml>
    python3 scripts/receipt-permissions.py check <receipt.html> <merged AndroidManifest.xml>

The merged release manifest is what Gradle writes under android/app/build/intermediates/merged_manifest/release/ after assembleRelease: the
app's own manifest with every module's and library's merged in, so it is what the phone is asked for. `check` compares it with the names in
the receipt's <ul id="permissions"> (one <code> per item) and fails on any difference either way. When INTERNET is declared, the receipt must
also carry the element with id "internet-unused" that says no running code uses it, because that is what is true until a consented network
feature arrives (NoNetworkInRuntimeCodeTest guards the code half).
"""
import html.parser
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
USES = ("uses-permission", "uses-permission-sdk-23")


def _uses(manifest_path):
    root = ET.parse(manifest_path).getroot()
    return [el for el in root if el.tag in USES]


def requested(manifest_path):
    return sorted({el.get(ANDROID + "name") for el in _uses(manifest_path)})


def described(manifest_path):
    lines = []
    for el in sorted(_uses(manifest_path), key=lambda e: e.get(ANDROID + "name")):
        limit = el.get(ANDROID + "maxSdkVersion")
        lines.append(el.get(ANDROID + "name") + (f" (up to API {limit})" if limit else ""))
    return lines


class _Receipt(html.parser.HTMLParser):
    """Collects the <code> texts inside <ul id="permissions"> and the ids on the page."""

    def __init__(self):
        super().__init__()
        self.depth = 0  # > 0 while inside the permissions list
        self.in_code = False
        self.names = []
        self.ids = set()
        self.found_list = False
        self.note_tag = None  # the tag of the id="internet-unused" element while inside it
        self.note_depth = 0
        self.note = ""

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if attrs.get("id"):
            self.ids.add(attrs["id"])
        if attrs.get("id") == "internet-unused" and self.note_tag is None:
            self.note_tag, self.note_depth = tag, 1
        elif self.note_tag == tag:
            self.note_depth += 1
        if tag == "ul" and attrs.get("id") == "permissions":
            self.depth, self.found_list = 1, True
        elif self.depth and tag == "ul":
            self.depth += 1
        elif self.depth and tag == "code":
            self.in_code = True
            self.names.append("")

    def handle_endtag(self, tag):
        if self.note_tag == tag and self.note_depth:
            self.note_depth -= 1
        if tag == "code":
            self.in_code = False
        elif self.depth and tag == "ul":
            self.depth -= 1

    def handle_data(self, data):
        if self.note_depth:
            self.note += data
        if self.in_code:
            self.names[-1] += data


# What the id="internet-unused" element must say: it names INTERNET and says that nothing uses it ("no running code uses it", "no code that
# runs uses it"). An empty element, or one that says something else, does not count.
STATEMENT = re.compile(r"INTERNET\b.*\bno\b.*\buses it\b", re.IGNORECASE)


def check(receipt_path, manifest_path):
    """The problems found; empty when the receipt matches the manifest."""
    parser = _Receipt()
    with open(receipt_path, encoding="utf-8") as f:
        parser.feed(f.read())
    if not parser.found_list:
        return ['the receipt has no <ul id="permissions">']
    listed = {n.strip() for n in parser.names if n.strip()}
    asked = set(requested(manifest_path))
    problems = [f"asked for by the release manifest but not in the receipt: {n}" for n in sorted(asked - listed)]
    problems += [f"in the receipt but not asked for by the release manifest: {n}" for n in sorted(listed - asked)]
    if "android.permission.INTERNET" in asked and not STATEMENT.search(" ".join(parser.note.split())):
        problems.append('INTERNET is declared, so the receipt needs the element id="internet-unused" saying that no code that runs uses it')
    return problems


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) == 2 and argv[0] == "list":
        print("\n".join(described(argv[1])))
        return 0
    if len(argv) == 3 and argv[0] == "check":
        problems = check(argv[1], argv[2])
        for p in problems:
            print("receipt-permissions: " + p, file=sys.stderr)
        return 1 if problems else 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
