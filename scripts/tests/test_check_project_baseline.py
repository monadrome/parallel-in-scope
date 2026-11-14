"""Tests for scripts/check-project-baseline.py."""

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "check-project-baseline.py"


def load_script():
    spec = importlib.util.spec_from_file_location("check_project_baseline", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


baseline = load_script()


class ProjectBaselineTest(unittest.TestCase):
    def test_stale_reference_check_catches_any_other_development_line(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = []
            for index in range(5):
                path = root / f"live-{index}.md"
                path.write_text("origin/dev/v0.6.0\n", encoding="utf-8")
                files.append(path)

            errors = []
            with mock.patch.object(baseline, "ROOT", root), \
                    mock.patch.object(baseline, "AGENTS", files[0]), \
                    mock.patch.object(baseline, "CONFIG", files[1]), \
                    mock.patch.object(baseline, "JAVA_GATE", files[2]), \
                    mock.patch.object(baseline, "COMPLEXITY_SKILL", files[3]), \
                    mock.patch.object(baseline, "DOC", files[4]):
                baseline.validate_stale_references("origin/dev/v0.5.0", errors)

            self.assertEqual(len(errors), 5)
            self.assertTrue(all("origin/dev/v0.6.0" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
