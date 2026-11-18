"""Exercise the proposal gate against disposable working trees."""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-proposal.py"

VALID = """# P0001: Title

## 蒸馏摘要

- Motivation, invariants, and rejected directions with reasons.

## Body

More detail.
"""

VALID_EN = VALID.replace("## 蒸馏摘要", "## Distillation summary")


class ProposalConformanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        (self.root / "design" / "proposal").mkdir(parents=True)
        shutil.copyfile(SCRIPT, self.root / "scripts" / SCRIPT.name)

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def check(self):
        return subprocess.run([sys.executable, "scripts/check-proposal.py"], cwd=self.root,
                              capture_output=True, text=True)

    def test_valid_proposal_passes_and_reports_line_counts(self):
        self.write("design/proposal/P0001-title.md", VALID)
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Proposal conformance: OK", result.stdout)
        self.assertIn("design/proposal/P0001-title.md:", result.stdout)

    def test_english_summary_heading_passes(self):
        self.write("design/proposal/P0001-title.md", VALID_EN)
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_and_empty_summary_fail(self):
        self.write("design/proposal/P0001-title.md", "# P0001: Title\n\n## Body\n\nText.\n")
        self.write("design/proposal/P0002-other.md",
                   VALID.replace("- Motivation, invariants, and rejected directions with reasons.\n", ""))
        errors = self.check().stderr
        self.assertIn("P0001-title.md: missing distillation summary section", errors)
        self.assertIn("P0002-other.md: empty distillation summary section", errors)

    def test_summary_heading_inside_fence_or_comment_does_not_count(self):
        self.write("design/proposal/P0001-title.md",
                   "# P0001: Title\n\n```\n## 蒸馏摘要\n\nBody.\n```\n")
        self.write("design/proposal/P0002-other.md",
                   "# P0002: Title\n\n<!-- ## 蒸馏摘要\n\nBody.\n-->\n")
        errors = self.check().stderr
        self.assertIn("P0001-title.md: missing distillation summary section", errors)
        self.assertIn("P0002-other.md: missing distillation summary section", errors)

    def test_line_number_citation_fails(self):
        self.write("design/proposal/P0001-title.md",
                   VALID + "\nEvidence in SynchronousExecutionTest:222,497 and docs en:32.\n")
        errors = self.check().stderr
        self.assertIn("line-number citation", errors)
        self.assertIn("SynchronousExecutionTest:222,497", errors)

    def test_citation_inside_fence_does_not_count_and_plain_numbers_pass(self):
        self.write("design/proposal/P0001-title.md",
                   VALID + "\n```\nSynchronousExecutionTest:222\n```\n"
                   "Ratios like 16:9 and MUST: 3 with a space are prose.\n")
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_invalid_filenames_fail(self):
        for name in ("P002-x.md", "p0002-x.md", "P0002_X.md", "P0002-.md"):
            self.write(f"design/proposal/{name}", VALID)
        errors = self.check().stderr
        self.assertEqual(errors.count("invalid proposal filename"), 4, errors)

    def test_duplicate_ids_fail_within_and_across_locations(self):
        self.write("design/proposal/P0001-one.md", VALID)
        self.write("design/proposal/P0001-two.md", VALID)
        self.write("design/proposal/P0002-active.md", VALID)
        self.write("design/archive/proposal/P0002-archived.md", "# P0002: Old\n\nNo summary needed.\n")
        errors = self.check().stderr
        self.assertEqual(errors.count("duplicate proposal id: P0001"), 1, errors)
        self.assertEqual(errors.count("duplicate proposal id: P0002"), 1, errors)

    def test_archived_files_are_identity_only(self):
        self.write("design/archive/proposal/P0001-old.md",
                   "# P0001: Old\n\nNo summary; SynchronousExecutionTest:222 is history.\n")
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("design/archive/proposal/P0001-old.md:", result.stdout)

    def test_archived_invalid_filename_fails(self):
        self.write("design/archive/proposal/old-notes.md", "# Notes\n")
        errors = self.check().stderr
        self.assertIn("invalid proposal filename", errors)


if __name__ == "__main__":
    unittest.main()
