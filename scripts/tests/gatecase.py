"""Shared harness for disposable-tree gate tests.

Each gate test copies the gate script and the shared ``proposal_md.py`` module into a
temporary working tree and runs the gate there by name, so a change to how gates are
staged or invoked lands in one place. Subclasses set ``SCRIPT`` and hook ``prepare()``
for extra tree setup; assertions, fixtures, and inputs stay in the test modules.
"""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]


class GateCase(unittest.TestCase):
    SCRIPT = ""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        self.install(SCRIPTS / self.SCRIPT, SCRIPTS / "proposal_md.py")
        self.prepare()

    def prepare(self):
        """Subclass hook for extra tree setup."""

    def install(self, *sources):
        for source in sources:
            shutil.copyfile(source, self.root / "scripts" / source.name)

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def check(self):
        return subprocess.run([sys.executable, f"scripts/{self.SCRIPT}"], cwd=self.root,
                              capture_output=True, text=True)

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)
