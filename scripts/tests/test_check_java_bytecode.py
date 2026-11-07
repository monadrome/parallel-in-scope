"""Tests for scripts/check-java-bytecode.py."""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1]
SCRIPT = SCRIPTS / "check-java-bytecode.py"


class CheckJavaBytecodeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.classes = Path(self.temp.name) / "classes"
        self.classes.mkdir()
        self.addCleanup(self.temp.cleanup)

    def run_check(self):
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(self.classes), "--release", "8"],
            capture_output=True,
            text=True,
        )

    def test_accepts_java_8_class_files(self):
        (self.classes / "Good.class").write_bytes(bytes.fromhex("cafebabe 0000 0034"))
        result = self.run_check()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("verified 1 class files", result.stdout)

    def test_rejects_a_different_class_file_version(self):
        (self.classes / "Newer.class").write_bytes(bytes.fromhex("cafebabe 0000 0035"))
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("expected major version 52", result.stderr)

    def test_rejects_an_empty_directory(self):
        result = self.run_check()
        self.assertEqual(result.returncode, 2)
        self.assertIn("contains no class files", result.stderr)

    def test_reports_a_malformed_class_file_as_input_error(self):
        (self.classes / "Broken.class").write_bytes(b"not a class")
        result = self.run_check()
        self.assertEqual(result.returncode, 2)
        self.assertIn("cannot read class file", result.stderr)
        self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main()
