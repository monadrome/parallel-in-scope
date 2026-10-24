"""Tests for scripts/check-race-claims.py.

These tests exercise the pure parts: exact mutation application, mutation
classification, and the registry shape. They never create a git worktree or run
Maven; the expensive reverse-verification is the script's own run.
"""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
REGISTRY = SCRIPTS.parent / "verification" / "race-claims" / "claims.json"


def load_script():
    spec = importlib.util.spec_from_file_location(
        "check_race_claims", SCRIPTS / "check-race-claims.py"
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


race = load_script()


class MutationTest(unittest.TestCase):
    def test_replaces_exactly_once(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Example.java").write_text("alpha\nbeta\n", encoding="utf-8")
            claim = {
                "id": "example",
                "file": "Example.java",
                "mutations": [{"find": "alpha", "replace": "gamma"}],
            }
            race.apply_mutations(root, claim)
            self.assertEqual((root / "Example.java").read_text(encoding="utf-8"), "gamma\nbeta\n")

    def test_ambiguous_find_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Example.java").write_text("alpha\nalpha\n", encoding="utf-8")
            claim = {
                "id": "example",
                "file": "Example.java",
                "mutations": [{"find": "alpha", "replace": "gamma"}],
            }
            with self.assertRaises(ValueError):
                race.apply_mutations(root, claim)

    def test_per_mutation_file_override(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "A.java").write_text("one\n", encoding="utf-8")
            (root / "B.java").write_text("two\n", encoding="utf-8")
            claim = {
                "id": "example",
                "file": "A.java",
                "mutations": [
                    {"find": "one", "replace": "ONE"},
                    {"file": "B.java", "find": "two", "replace": "TWO"},
                ],
            }
            race.apply_mutations(root, claim)
            self.assertEqual((root / "A.java").read_text(encoding="utf-8"), "ONE\n")
            self.assertEqual((root / "B.java").read_text(encoding="utf-8"), "TWO\n")


class ClassifyTest(unittest.TestCase):
    def test_passing_tests_are_unproven(self):
        result = subprocess.CompletedProcess(args=[], returncode=0, stdout="BUILD SUCCESS", stderr="")
        self.assertEqual(race.classify_mutation(result)[0], "UNPROVEN")

    def test_failing_tests_are_proven(self):
        result = subprocess.CompletedProcess(args=[], returncode=1, stdout="FAILURE", stderr="")
        self.assertEqual(race.classify_mutation(result)[0], "PROVEN")

    def test_compilation_error_is_invalid(self):
        result = subprocess.CompletedProcess(
            args=[], returncode=1, stdout="COMPILATION ERROR", stderr=""
        )
        self.assertEqual(race.classify_mutation(result)[0], "INVALID_MUTATION")

    def test_timeout_is_proven_timeout(self):
        result = subprocess.TimeoutExpired(cmd="mvn", timeout=1)
        self.assertEqual(race.classify_mutation(result)[0], "PROVEN_TIMEOUT")


class RegistryTest(unittest.TestCase):
    def test_registry_shape(self):
        data = json.loads(REGISTRY.read_text(encoding="utf-8"))
        claims = data["claims"]
        ids = [claim["id"] for claim in claims]
        self.assertEqual(len(ids), len(set(ids)))
        for claim in claims:
            self.assertIn(claim["method"], {"mutation", "existing-test", "needs-jcstress"})
            if claim["method"] == "mutation":
                self.assertTrue(claim.get("mutations"), claim["id"])
                self.assertTrue(claim.get("tests"), claim["id"])


if __name__ == "__main__":
    unittest.main()
