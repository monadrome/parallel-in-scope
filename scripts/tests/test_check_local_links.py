"""Exercise the local-link gate against disposable working trees."""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-local-links.py"


class LocalLinksTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        (self.root / "design" / "archive" / "proposal").mkdir(parents=True)
        shutil.copyfile(SCRIPT, self.root / "scripts" / SCRIPT.name)

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def check(self):
        return subprocess.run([sys.executable, "scripts/check-local-links.py"], cwd=self.root,
                              capture_output=True, text=True)

    def test_live_document_linking_into_the_archive_fails(self):
        self.write("design/archive/proposal/P0001-dormant.md", "# Dormant\n")
        self.write("design/contract.md",
                   "# Contract\nSee [the old proposal](archive/proposal/P0001-dormant.md).\n")
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("live document links into the design archive", result.stdout)

    def test_valid_relative_links_pass(self):
        self.write("design/contract.md", "# Contract\nSee [other](other.md).\n")
        self.write("design/other.md", "# Other\n")
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(result.stdout.strip(), "Local documentation links: OK")

    def test_archive_sources_and_their_links_are_not_scanned(self):
        self.write("design/archive/proposal/P0001-dormant.md",
                   "# Dormant\nA [stale link](../gone.md) and [live](../P0002-x.md) stay frozen.\n")
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == "__main__":
    unittest.main()
