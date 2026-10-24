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
        self.write("design/contract.md", "# Contract\nSee `git show abc1234:design/archive/x.md`.\n")
        self.assertEqual(
            docs.violations(self.root),
            ["design/contract.md:2: hash-based retrieval: See `git show abc1234:design/archive/x.md`."],
        )

    def test_line_number_suffix_is_a_violation(self):
        self.write("design/contract.md", "# Contract\nSee Checkpoints.java:525.\nAlso guide.md:12-18.\n")
        self.assertEqual(len(docs.violations(self.root)), 2)

    def test_relative_links_and_bare_provenance_hashes_are_allowed(self):
        self.write(
            "design/contract.md",
            "# Contract\nSee [archive/x.md](archive/x.md); removed in `abc1234`.\n",
        )
        self.assertEqual(docs.violations(self.root), [])

    def test_archive_inputs_and_pinned_baselines_are_exempt(self):
        self.write("design/archive/old.md", "See `git show abc1234:x.md` and Foo.java:9.\n")
        self.write("design/analysis/input.md", "See Foo.java:9.\n")
        self.write("BACKLOG.md", "See Foo.java:9.\n")
        self.write("todo.md", "See Foo.java:9.\n")
        self.assertEqual(docs.violations(self.root), [])

    def test_git_history_retrieval_wording_is_a_violation(self):
        self.write("design/contract.md", "# Contract\n全文从 git 历史取回。\n")
        self.assertEqual(
            docs.violations(self.root),
            ["design/contract.md:2: git-history retrieval: 全文从 git 历史取回。"],
        )

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
