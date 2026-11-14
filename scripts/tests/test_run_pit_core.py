"""Tests for the core PIT audit runner."""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
SCRIPT = SCRIPTS / "run-pit-core.sh"


class RunPitCoreTest(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.scripts = self.root / "scripts"
        self.scripts.mkdir()
        shutil.copy2(SCRIPT, self.scripts / SCRIPT.name)
        self.fake_bin = self.root / "fake-bin"
        self.fake_bin.mkdir()
        self.write_fake_mvn()
        self.init_repository()

    def write_fake_mvn(self):
        """Install a fake Maven that can fail or emit a report on demand."""
        mvn = self.fake_bin / "mvn"
        mvn.write_text(
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            "if [[ \"${1:-}\" == \"-version\" ]]; then\n"
            "  printf 'Apache Maven fake\\n'\n"
            "  exit 0\n"
            "fi\n"
            "if [[ \"${FAKE_MVN_WRITE_REPORT:-0}\" == 1 ]]; then\n"
            "  mkdir -p core/target/pit-reports\n"
            "  printf '<mutations current=\"true\"/>\\n' > core/target/pit-reports/mutations.xml\n"
            "fi\n"
            "exit \"${FAKE_MVN_EXIT:-0}\"\n",
            encoding="utf-8",
        )
        mvn.chmod(mvn.stat().st_mode | stat.S_IXUSR)

    def init_repository(self):
        """Create a clean repository so run metadata can call Git."""
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.name", "Test"], cwd=self.root, check=True)
        (self.root / "tracked.txt").write_text("tracked\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(["git", "commit", "-q", "-m", "fixture"], cwd=self.root, check=True)

    def run_script(self, **maven_environment):
        """Run batch A against the fake Maven and return its completed process."""
        environment = os.environ.copy()
        environment["PATH"] = f"{self.fake_bin}{os.pathsep}{environment['PATH']}"
        environment.update({key: str(value) for key, value in maven_environment.items()})
        return subprocess.run(
            [str(self.scripts / SCRIPT.name), "--batch", "A", "--out", "out"],
            cwd=self.root,
            env=environment,
            capture_output=True,
            text=True,
        )

    def test_failed_run_does_not_archive_a_pre_existing_xml(self):
        """A compile failure must not preserve an earlier run's report as current."""
        stale = self.root / "core" / "target" / "pit-reports" / "mutations.xml"
        stale.parent.mkdir(parents=True)
        stale.write_text('<mutations stale="true"/>\n', encoding="utf-8")

        result = self.run_script(FAKE_MVN_EXIT=1, FAKE_MVN_WRITE_REPORT=0)

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        batch = self.root / "out" / "batch-A"
        self.assertFalse((batch / "mutations.xml").exists())
        self.assertIn("mutations-xml-archived: false", (batch / "run-meta.txt").read_text(encoding="utf-8"))

    def test_successful_run_archives_its_new_xml(self):
        """A successful Maven invocation still archives the report it creates."""
        result = self.run_script(FAKE_MVN_EXIT=0, FAKE_MVN_WRITE_REPORT=1)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        batch = self.root / "out" / "batch-A"
        self.assertEqual(
            (batch / "mutations.xml").read_text(encoding="utf-8"),
            '<mutations current="true"/>\n',
        )
        self.assertIn("mutations-xml-archived: true", (batch / "run-meta.txt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
