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
    def validate_script_and_docs(self, adapter_text):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = {
                "JAVA_GATE": root / "java-conventions.py",
                "COMPLEXITY_SKILL": root / "complexity.md",
                "DOC": root / "java-conventions.md",
                "ADAPTER": root / "vformation.yaml",
            }
            paths["JAVA_GATE"].write_text('DEFAULT_UPSTREAM = "origin/dev/v0.5.0"\n', encoding="utf-8")
            paths["COMPLEXITY_SKILL"].write_text("origin/dev/v0.5.0\n", encoding="utf-8")
            paths["DOC"].write_text("check-java-conventions.py\norigin/dev/v0.5.0\n", encoding="utf-8")
            paths["ADAPTER"].write_text(adapter_text, encoding="utf-8")
            errors = []
            with mock.patch.multiple(baseline, **paths):
                baseline.validate_script_and_docs("origin/dev/v0.5.0", errors)
            return errors

    def test_adapter_base_ref_accepts_quoted_scalar_and_inline_comment(self):
        self.assertEqual(self.validate_script_and_docs('base_ref: "origin/dev/v0.5.0"  # current line\n'), [])

    def test_adapter_base_ref_reports_mismatch(self):
        errors = self.validate_script_and_docs("base_ref: origin/dev/v0.4.0\n")
        self.assertIn("adapter declares 'origin/dev/v0.4.0'", " ".join(errors))

    def test_adapter_base_ref_reports_missing_declaration(self):
        errors = self.validate_script_and_docs("name: vformation\n")
        self.assertTrue(any("adapter does not declare base_ref" in error for error in errors))

    def test_stale_reference_check_catches_any_other_development_line(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = []
            for index in range(6):
                path = root / f"live-{index}.md"
                path.write_text("origin/dev/v0.6.0\n", encoding="utf-8")
                files.append(path)

            errors = []
            with mock.patch.object(baseline, "ROOT", root), \
                    mock.patch.object(baseline, "AGENTS", files[0]), \
                    mock.patch.object(baseline, "CONFIG", files[1]), \
                    mock.patch.object(baseline, "JAVA_GATE", files[2]), \
                    mock.patch.object(baseline, "COMPLEXITY_SKILL", files[3]), \
                    mock.patch.object(baseline, "DOC", files[4]), \
                    mock.patch.object(baseline, "ADAPTER", files[5]):
                baseline.validate_stale_references("origin/dev/v0.5.0", errors)

            self.assertEqual(len(errors), 6)
            self.assertTrue(all("origin/dev/v0.6.0" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
