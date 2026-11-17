"""Exercise the ADR gate against disposable working trees."""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-adr.py"

VALID = """# ADR 0001: Title

- Status: Accepted
- Date: 2026-01-01
- Decision scope: scope
- Stability: stable
- Category: core
- Supersedes: None

## Context

## Decision

## Alternatives Considered

## Consequences
"""


class AdrConformanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        (self.root / "adr").mkdir()
        shutil.copyfile(SCRIPT, self.root / "scripts" / SCRIPT.name)

    def write(self, name, text):
        (self.root / "adr" / name).write_text(text, encoding="utf-8")

    def check(self):
        return subprocess.run([sys.executable, "scripts/check-adr.py"], cwd=self.root,
                              capture_output=True, text=True)

    def test_valid_record_passes(self):
        self.write("0001-title.md", VALID)
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "ADR conformance: OK")

    def test_missing_fields_and_sections_fail(self):
        self.write("0001-title.md", "# ADR 0001: Title\n\n## Context\n")
        errors = self.check().stderr
        for expected in ("missing metadata field: Status", "missing metadata field: Stability",
                         "missing metadata field: Category",
                         "missing required section: Decision",
                         "missing required section: Alternatives Considered"):
            self.assertIn(expected, errors)

    def test_invalid_values_fail(self):
        self.write("0001-title.md", VALID.replace("Accepted", "Done")
                   .replace("Stability: stable", "Stability: released")
                   .replace("Category: core", "Category: kernel")
                   .replace("2026-01-01", "January 2026"))
        errors = self.check().stderr
        self.assertIn("invalid Status: Done", errors)
        self.assertIn("invalid Stability: released", errors)
        self.assertIn("invalid Category: kernel", errors)
        self.assertIn("Date is not YYYY-MM-DD", errors)

    def test_non_record_files_are_ignored(self):
        self.write("AGENTS.md", "# Rules\n")
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_fields_after_a_note_blockquote_are_not_read(self):
        self.write("0001-title.md", VALID.replace(
            "## Context", "> Dated note.\n\n## Context"))
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
