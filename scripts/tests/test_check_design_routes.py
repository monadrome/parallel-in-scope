"""Exercise the design gate against disposable Git working trees."""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-design-routes.py"


class DesignRoutesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        (self.root / "design" / "proposal").mkdir(parents=True)
        shutil.copyfile(SCRIPT, self.root / "scripts" / SCRIPT.name)
        self.git("init", "-q")
        self.write("design/contract.md", "# Contract\n")
        self.git("add", "design/contract.md")
        self.routes()

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def routes(self, live="| [contract](contract.md) | Contract |\n", proposals="", prose=""):
        self.write("design/AGENTS.md", f"# Routes\n{prose}\n## 活契约\n{live}\n## 在途提案\n{proposals}")

    def proposal(self, name="P0001-request.md", summary="## 蒸馏摘要\n- Purpose and constraints\n"):
        self.write(f"design/proposal/{name}", f"# Proposal\n{summary}")

    def check(self):
        return subprocess.run([sys.executable, "scripts/check-design-routes.py"], cwd=self.root,
                              capture_output=True, text=True)

    def test_routes_cover_untracked_proposals_and_tracked_contracts(self):
        self.proposal()
        missing = self.check()
        self.assertEqual(missing.returncode, 1)
        self.assertIn("proposal/P0001-request.md: document missing", missing.stderr)
        self.routes(proposals="| [request](proposal/P0001-request.md) | Request |\n")
        self.assertEqual(self.check().returncode, 0)
        self.routes(live="", proposals="| [request](proposal/P0001-request.md) | Request |\n")
        self.assertIn("contract.md: document missing", self.check().stderr)

    def test_landed_proposal_must_disappear_but_historical_reference_is_valid(self):
        self.proposal()
        self.routes(proposals="| [request](proposal/P0001-request.md) | Request |\n")
        self.write("design/contract.md", "# Contract\nSource: `proposal:P0001`\n")
        residual = self.check()
        self.assertEqual(residual.returncode, 1)
        self.assertIn("landed proposal P0001 still exists", residual.stderr)
        (self.root / "design/proposal/P0001-request.md").unlink()
        self.routes()
        historical = self.check()
        self.assertEqual(historical.returncode, 0, historical.stderr)
        self.assertEqual(historical.stdout.strip(), "Design route table: OK")

    def test_proposal_and_analysis_references_do_not_mark_a_landing(self):
        self.proposal(summary="## 蒸馏摘要\nSee proposal:P0001\n")
        self.routes(proposals="| [request](proposal/P0001-request.md) | Request |\n")
        self.write("design/analysis.md", "# Input\nproposal:P0001\n")
        self.assertEqual(self.check().returncode, 0)

    def test_new_routed_contracts_are_checked_before_staging(self):
        self.proposal()
        self.write("design/new.md", "# Contract\nproposal:P0001\n")
        self.routes(live="| [new](new.md) | New |\n| [contract](contract.md) | Contract |\n",
                    proposals="| [request](proposal/P0001-request.md) | Request |\n")
        self.assertIn("new.md: landed proposal P0001 still exists", self.check().stderr)

    def test_deleted_index_entries_do_not_block_migration(self):
        (self.root / "design/contract.md").unlink()
        self.routes(live="")
        self.assertEqual(self.check().returncode, 0)

    def test_missing_route_targets_still_fail(self):
        self.routes(proposals="| [gone](proposal/P0001-gone.md) | Gone |\n")
        self.assertIn("missing target: proposal/P0001-gone.md", self.check().stderr)

    def test_prose_links_do_not_replace_routes_or_allow_wrong_sections(self):
        self.proposal()
        self.routes(live="| [contract](contract.md) | Contract |\n"
                         "| [request](proposal/P0001-request.md) | Request |\n",
                    prose="[request](proposal/P0001-request.md)")
        errors = self.check().stderr
        self.assertIn("wrong route section", errors)
        self.assertIn("proposal/P0001-request.md: document missing", errors)

    def test_invalid_and_duplicate_ids_and_empty_summaries_fail(self):
        for name in ("request.md", "P0001-first.md", "P0001-second.md", "nested/P0002-child.md"):
            self.proposal(name, "## 蒸馏摘要\n\n## Details\nSome details\n")
        errors = self.check().stderr
        self.assertIn("invalid proposal filename", errors)
        self.assertIn("duplicate proposal id: P0001", errors)
        self.assertIn("missing or empty distillation summary", errors)

    def test_reference_ids_are_exact(self):
        self.proposal()
        self.routes(proposals="| [request](proposal/P0001-request.md) | Request |\n")
        self.write("design/contract.md", "# Contract\nproposal:P00010 proposal:P0001extra\n")
        self.assertEqual(self.check().returncode, 0)

    def test_fenced_example_rows_do_not_replace_routes(self):
        self.proposal()
        for fence in ("~~~", "   ```", "````"):
            with self.subTest(fence=fence):
                self.routes(proposals=f"{fence}markdown\n"
                                      "| [request](proposal/P0001-request.md) | Example |\n"
                                      f"{fence}\n")
                result = self.check()
                self.assertEqual(result.returncode, 1)
                self.assertIn("proposal/P0001-request.md: document missing", result.stderr)

    def test_summary_links_do_not_replace_a_proposals_own_row(self):
        self.proposal()
        self.proposal("P0002-other.md")
        self.routes(proposals="| [other](proposal/P0002-other.md) | "
                              "See [request](proposal/P0001-request.md) |\n")
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("proposal/P0001-request.md: document missing", result.stderr)

    def test_fenced_summary_template_is_not_a_distillation_summary(self):
        self.proposal(summary="```markdown\n## 蒸馏摘要\nCopy this template\n```\n")
        self.routes(proposals="| [request](proposal/P0001-request.md) | Request |\n")
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("missing or empty distillation summary", result.stderr)

    def test_commented_routes_and_inline_code_are_not_links(self):
        self.proposal()
        for row in ("<!--\n| [request](proposal/P0001-request.md) | Example |\n-->\n",
                    "| `[request](proposal/P0001-request.md)` | Example |\n",
                    "| ``[request](proposal/P0001-request.md)`` | Example |\n"):
            with self.subTest(row=row):
                self.routes(proposals=row)
                result = self.check()
                self.assertEqual(result.returncode, 1)
                self.assertIn("proposal/P0001-request.md: document missing", result.stderr)

    def test_commented_summary_template_is_not_a_summary(self):
        self.proposal(summary="<!--\n## 蒸馏摘要\nCopy this template\n-->\n")
        self.routes(proposals="| [request](proposal/P0001-request.md) | Request |\n")
        self.assertIn("missing or empty distillation summary", self.check().stderr)


if __name__ == "__main__":
    unittest.main()
