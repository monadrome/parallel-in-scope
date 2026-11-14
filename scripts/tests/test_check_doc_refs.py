"""Tests for scripts/check-doc-refs.py."""

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parent


def load_script():
    spec = importlib.util.spec_from_file_location("check_doc_refs", SCRIPTS / "check-doc-refs.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


docs = load_script()


class CheckDocRefsTest(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True, capture_output=True)

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def test_hash_retrieval_is_a_violation(self):
        self.write("design/contract.md", "# Contract\nSee `git show abc1234:design/old.md`.\n")
        self.assertEqual(
            docs.violations(self.root),
            [
                "design/contract.md:2: hash-based retrieval: See `git show abc1234:design/old.md`.",
                "design/contract.md:2: commit-hash reference: See `git show abc1234:design/old.md`.",
            ],
        )

    def test_line_number_suffix_is_a_violation(self):
        self.write("design/contract.md", "# Contract\nSee Checkpoints.java:525.\nAlso guide.md:12-18.\n")
        self.assertEqual(len(docs.violations(self.root)), 2)

    def test_provenance_hashes_are_a_violation(self):
        self.write(
            "design/contract.md",
            "# Contract\nRemoved in `abc1234`.\nLanded in 7e2ceae.\nRemoved in 0.3.0 (`9784312`).\n",
        )
        self.assertEqual(
            docs.violations(self.root),
            [
                "design/contract.md:2: commit-hash reference: Removed in `abc1234`.",
                "design/contract.md:3: commit-hash reference: Landed in 7e2ceae.",
                "design/contract.md:4: commit-hash reference: Removed in 0.3.0 (`9784312`).",
            ],
        )

    def test_hash_arguments_to_other_git_commands_are_violations(self):
        self.write(
            "design/contract.md",
            "# Contract\nRun `git worktree add /tmp/x 2b2e3ad0`.\nThen `git checkout 9784312`.\n"
            "Plain `git diff --check` is fine.\n",
        )
        self.assertEqual(
            [error.split(": ")[1] for error in docs.violations(self.root)],
            ["hash-based retrieval", "commit-hash reference", "hash-based retrieval"],
        )

    def test_digit_hashes_need_commit_context(self):
        self.write(
            "design/contract.md",
            "# Contract\nRemoved in 0.3.0 (9784312).\nBuffers hold `8388608` bytes and `10000000` ns.\n",
        )
        self.assertEqual(
            docs.violations(self.root),
            ["design/contract.md:2: commit-hash reference: Removed in 0.3.0 (9784312)."],
        )

    def test_hex_like_words_and_relative_links_are_allowed(self):
        self.write(
            "design/contract.md",
            "# Contract\nSee [decision-log.md](decision-log.md), `deadbeef`, `123456`, 1234567 items, and #1e75d3aa.\n"
            "Also design/abc1234-notes.md and v0.4.0-abc12345x.\n",
        )
        self.assertEqual(docs.violations(self.root), [])

    def test_analysis_inputs_and_pinned_baselines_are_exempt(self):
        self.write("design/analysis/input.md", "See `git show abc1234:x.md` and Foo.java:9.\n")
        self.write("BACKLOG.md", "See Foo.java:9.\n")
        self.write("todo.md", "See Foo.java:9.\n")
        self.assertEqual(docs.violations(self.root), [])

    def test_git_history_retrieval_wording_is_a_violation(self):
        self.write("design/contract.md", "# Contract\n全文从 git 历史取回。\n")
        self.assertEqual(
            docs.violations(self.root),
            ["design/contract.md:2: git-history retrieval: 全文从 git 历史取回。"],
        )

    def test_refactoring_review_rules_are_scanned(self):
        self.write("refactoring-review/lenses/structure.md", "# Lens\nSee Foo.java:9.\n")
        self.assertEqual(len(docs.violations(self.root)), 1)

    def test_consumer_module_documents_are_scanned(self):
        self.write("demo/README.en.md", "# Demo\nRemoved in `abc1234`.\n")
        self.write("integrations/README.en.md", "# Integrations\nSee Foo.java:9.\n")
        errors = docs.violations(self.root)
        self.assertEqual(len(errors), 2)
        self.assertIn("demo/README.en.md:2: commit-hash reference", errors[0])
        self.assertIn("integrations/README.en.md:2: line-number reference", errors[1])

    def test_deleted_documents_are_not_read_from_the_index(self):
        path = self.write("adr/0013-old.md", "# Old\nRemoved in `abc1234`.\n")
        subprocess.run(["git", "add", str(path)], cwd=self.root, check=True)
        path.unlink()
        self.assertEqual(docs.violations(self.root), [])

    def test_repository_documents_are_clean(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPTS / "check-doc-refs.py")],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
