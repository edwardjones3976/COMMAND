"""Publication-safety checks for the COMMAND scaffold."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SECRET_NAME = re.compile(r"(?i)(^|/)(\.env($|\.)|id_rsa|credentials\.json|.*\.dpapi)$")
SECRET_BODY = re.compile(
    r"(?i)(-----BEGIN (RSA |OPENSSH )?PRIVATE KEY-----|"
    r"ghp_[A-Za-z0-9]{20,}|"
    r"github_pat_[A-Za-z0-9_]{20,}|"
    r"sk-[A-Za-z0-9]{20,})"
)


class TestScaffold(unittest.TestCase):
    def test_required_files_exist(self) -> None:
        for rel in (
            "README.md",
            "AGENTS.md",
            "LICENSE",
            ".gitignore",
            "docs/index.html",
            "docs/status.html",
            "docs/status.css",
        ):
            path = ROOT / rel
            self.assertTrue(path.is_file(), f"missing {rel}")
            self.assertGreater(path.stat().st_size, 0, f"empty {rel}")

    def test_html_has_csp(self) -> None:
        for rel in ("docs/index.html", "docs/status.html"):
            text = (ROOT / rel).read_text(encoding="utf-8")
            self.assertIn("Content-Security-Policy", text)
            self.assertIn("default-src 'none'", text)
            self.assertIn('lang="en"', text)

    def test_license_reserves_rights(self) -> None:
        text = (ROOT / "LICENSE").read_text(encoding="utf-8")
        self.assertIn("All rights reserved", text)

    def test_gitignore_covers_secrets(self) -> None:
        text = (ROOT / ".gitignore").read_text(encoding="utf-8")
        for token in (".env", "*.dpapi", "*.pem"):
            self.assertIn(token, text)

    def test_tracked_tree_has_no_secret_files_or_bodies(self) -> None:
        skip_dirs = {".git", "__pycache__", ".venv", "venv"}
        for path in ROOT.rglob("*"):
            if any(part in skip_dirs for part in path.parts):
                continue
            if not path.is_file():
                continue
            rel = path.relative_to(ROOT).as_posix()
            self.assertIsNone(SECRET_NAME.search(rel), f"secret-shaped name: {rel}")
            if path.suffix.lower() in {
                ".png",
                ".jpg",
                ".jpeg",
                ".gif",
                ".webp",
                ".ico",
            }:
                continue
            try:
                body = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            self.assertIsNone(
                SECRET_BODY.search(body),
                f"secret-shaped content: {rel}",
            )


if __name__ == "__main__":
    unittest.main()
