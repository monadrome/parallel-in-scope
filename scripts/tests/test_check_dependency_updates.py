"""Tests for scripts/check-dependency-updates.py.

All fixtures are local; these tests never touch the network. The integration test
builds a temporary repository root from the fixtures so collect_maven's three-POM
walk can run without the real project tree.
"""

import contextlib
import importlib.util
import io
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "dependency_audit"


def load_script():
    spec = importlib.util.spec_from_file_location(
        "check_dependency_updates", SCRIPTS / "check-dependency-updates.py"
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


updates = load_script()


def make_root(directory: Path) -> Path:
    root = directory
    shutil.copy(FIXTURES / "pom.xml", root / "pom.xml")
    (root / "demo").mkdir(parents=True, exist_ok=True)
    (root / "demo" / "pom.xml").write_text(
        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        "<modelVersion>4.0.0</modelVersion><groupId>example</groupId>"
        "<artifactId>demo</artifactId><version>1.0.0</version></project>",
        encoding="utf-8",
    )
    verification = root / "verification" / "maven-central-consumer"
    verification.mkdir(parents=True, exist_ok=True)
    (verification / "pom.xml").write_text(
        '<project xmlns="http://maven.apache.org/POM/4.0.0">'
        "<modelVersion>4.0.0</modelVersion><groupId>example</groupId>"
        "<artifactId>verification</artifactId><version>1.0.0</version></project>",
        encoding="utf-8",
    )
    workflows = root / ".github" / "workflows"
    workflows.mkdir(parents=True, exist_ok=True)
    shutil.copy(FIXTURES / "workflow.yml", workflows / "fixture.yml")
    (root / "mkdocs").mkdir(exist_ok=True)
    shutil.copy(FIXTURES / "requirements.txt", root / "mkdocs" / "requirements.txt")
    return root


class VersionTest(unittest.TestCase):
    def test_stable_filter(self):
        self.assertTrue(updates.is_stable("34.0.0-jre"))
        self.assertTrue(updates.is_stable("v6"))
        self.assertFalse(updates.is_stable("35.0.0-jre-SNAPSHOT"))
        self.assertFalse(updates.is_stable("1.0.0-RC1"))
        self.assertFalse(updates.is_stable("1.0.0-alpha1"))
        self.assertFalse(updates.is_stable("1.1.0.CR1"))

    def test_version_key(self):
        self.assertEqual(updates.version_key("33.6.0-jre"), (33, 6, 0))
        self.assertEqual(updates.version_key("v7.0.1"), (7, 0, 1))
        self.assertEqual(updates.version_key("1"), (1,))
        self.assertEqual(updates.version_key("1.2.3.4"), (1, 2, 3, 4))

    def test_classify_delta(self):
        self.assertEqual(updates.classify_delta("33.6.0-jre", "33.7.2-jre"), "minor")
        self.assertEqual(updates.classify_delta("33.6.0-jre", "34.0.0-jre"), "major")
        self.assertEqual(updates.classify_delta("1.0.0", "1.0.1"), "patch")
        self.assertEqual(updates.classify_delta("1.0.0", "1.0.0"), "none")
        self.assertEqual(updates.classify_delta("2.0.0", "1.0.0"), "unknown")
        self.assertEqual(updates.classify_delta("v6", "v7.0.1"), "major")
        self.assertEqual(updates.classify_delta("1.2.3.4", "1.2.3.5"), "patch")
        self.assertEqual(updates.classify_delta("1.2.3", "1.2.3.0"), "qualifier")

    def test_select_latest_tag_requires_stable(self):
        self.assertEqual(updates.select_latest_tag(["v1.0.0-RC1", "v0.9.0"]), "v0.9.0")
        self.assertIsNone(updates.select_latest_tag(["v1.0.0-RC1"]))

    def test_action_delta(self):
        self.assertEqual(updates.action_delta("v6", "v7.0.1"), ("major", "needs_review"))
        self.assertEqual(updates.action_delta("v6", "v6.5.0"), ("none", "current"))
        self.assertEqual(updates.action_delta("v6.1.0", "v6.2.0"), ("minor", "outdated"))
        self.assertEqual(updates.action_delta("v4", "v4.39.1"), ("none", "current"))


class PomTest(unittest.TestCase):
    def test_resolve_property(self):
        self.assertEqual(updates.resolve("${guava.version}", {"guava.version": "33.0.0-jre"}), "33.0.0-jre")
        self.assertEqual(updates.resolve("${missing}", {}), "${missing}")

    def test_parse_pom(self):
        entries, properties = updates.parse_pom(FIXTURES / "pom.xml")
        self.assertEqual(properties["guava.version"], "33.0.0-jre")
        guava = next(entry for entry in entries if entry["artifact"] == "guava")
        self.assertEqual(guava["version"], "33.0.0-jre")
        managed = next(entry for entry in entries if entry["artifact"] == "managed")
        self.assertIsNone(managed["version"])
        surefire = next(entry for entry in entries if entry["artifact"] == "maven-surefire-plugin")
        self.assertEqual(surefire["group"], "org.apache.maven.plugins")

    def test_parse_management(self):
        management, imports = updates.parse_management(FIXTURES / "pom.xml")
        self.assertEqual(management[("dependency", "org.example", "managed")], "1.2.3")
        self.assertEqual(imports, [("org.example", "fixture-bom", "9.9.9")])

    def test_latest_stable_prefers_same_suffix(self):
        metadata = (FIXTURES / "maven-metadata.xml").read_bytes()
        self.assertEqual(updates.latest_stable(metadata, "33.0.0-jre"), "34.0.0-jre")
        self.assertEqual(updates.latest_stable(metadata, "33.0.0-android"), "34.0.0-android")
        self.assertIn(updates.latest_stable(metadata, "33.0.0"), {"34.0.0-jre", "34.0.0-android"})

    def test_latest_stable_does_not_cross_suffix(self):
        metadata = b"<metadata><versioning><versions><version>34.0.0-android</version></versions></versioning></metadata>"
        self.assertIsNone(updates.latest_stable(metadata, "33.0.0-jre"))

    def test_latest_stable_returns_none_without_release(self):
        metadata = b"<metadata><versioning><versions><version>1.0.0-SNAPSHOT</version></versions></versioning></metadata>"
        self.assertIsNone(updates.latest_stable(metadata, "1.0.0"))


class ParsingTest(unittest.TestCase):
    def test_parse_uses(self):
        text = (FIXTURES / "workflow.yml").read_text(encoding="utf-8")
        self.assertEqual(
            updates.parse_uses(text),
            [
                ("actions/checkout", "v6"),
                ("actions/setup-java", "v5"),
                ("github/codeql-action", "v4"),
                ("actions/checkout", "v4"),
            ],
        )

    def test_parse_requirements(self):
        text = (FIXTURES / "requirements.txt").read_text(encoding="utf-8")
        self.assertEqual(
            updates.parse_requirements(text),
            [("mkdocs", "1.6.1"), ("mkdocs-material", "9.7.7")],
        )


class CollectionTest(unittest.TestCase):
    def test_collect_maven_dedupes_managed_plugin(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = make_root(Path(temporary))
            items = updates.collect_maven(root, timeout=1, offline=True)
        surefire = [item for item in items if item.component.endswith("maven-surefire-plugin")]
        self.assertEqual(len(surefire), 1)
        self.assertEqual(surefire[0].current, "3.0.0")
        guava = next(item for item in items if item.component == "com.google.guava:guava")
        self.assertEqual(guava.current, "33.0.0-jre")
        managed = next(item for item in items if item.component == "org.example:managed")
        self.assertEqual(managed.current, "1.2.3")
        self.assertEqual(managed.status, "unknown")
        self.assertIn("dependencyManagement", managed.reason)

    def test_collect_maven_unversioned_without_management_is_unknown(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = make_root(Path(temporary))
            (root / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                "<modelVersion>4.0.0</modelVersion><groupId>example</groupId>"
                "<artifactId>root</artifactId><version>1.0.0</version>"
                "<dependencies><dependency><groupId>org.example</groupId>"
                "<artifactId>orphan</artifactId></dependency></dependencies></project>",
                encoding="utf-8",
            )
            items = updates.collect_maven(root, timeout=1, offline=True)
        orphan = next(item for item in items if item.component == "org.example:orphan")
        self.assertEqual(orphan.status, "unknown")
        self.assertIn("unversioned", orphan.reason)

    def test_fetch_failure_marks_unknown(self):
        with mock.patch.object(updates, "fetch", side_effect=RuntimeError("boom")):
            item = updates.check_maven(
                "maven-dependency", "root", "com.google.guava", "guava", "33.0.0-jre", 1, False
            )
        self.assertEqual(item.status, "unknown")
        self.assertIn("boom", item.reason)

    def test_offline_marks_unknown(self):
        item = updates.check_maven(
            "maven-dependency", "root", "com.google.guava", "guava", "33.0.0-jre", 1, True
        )
        self.assertEqual(item.status, "unknown")
        self.assertIn("offline", item.reason)


class TokenTest(unittest.TestCase):
    def test_environment_token_wins(self):
        with mock.patch.dict("os.environ", {"GITHUB_TOKEN": "env-token"}, clear=True):
            self.assertEqual(updates.github_token(), "env-token")

    def test_gh_fallback(self):
        with mock.patch.dict("os.environ", {}, clear=True):
            with mock.patch.object(updates.subprocess, "run") as run:
                run.return_value = mock.Mock(stdout="gh-token\n")
                self.assertEqual(updates.github_token(), "gh-token")

    def test_no_token(self):
        with mock.patch.dict("os.environ", {}, clear=True):
            with mock.patch.object(
                updates.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "gh")
            ):
                self.assertIsNone(updates.github_token())


class PypiTest(unittest.TestCase):
    def test_missing_requirements_is_unknown(self):
        with tempfile.TemporaryDirectory() as temporary:
            items = updates.collect_pypi(Path(temporary), timeout=1, offline=True)
        self.assertEqual(len(items), 1)
        self.assertEqual(items[0].status, "unknown")
        self.assertIn("missing", items[0].reason)

    def test_unsupported_line_is_unknown(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "mkdocs").mkdir()
            (root / "mkdocs" / "requirements.txt").write_text(
                "mkdocs==1.6.1\nunpinned-package\n", encoding="utf-8"
            )
            items = updates.collect_pypi(root, timeout=1, offline=True)
        unsupported = next(item for item in items if item.component == "unpinned-package")
        self.assertEqual(unsupported.status, "unknown")
        self.assertIn("unsupported", unsupported.reason)


class ExceptionsTest(unittest.TestCase):
    def test_apply_exceptions_matches_module(self):
        items = [
            updates.Item(
                "maven-dependency",
                "demo",
                "org.junit.jupiter:junit-jupiter",
                "5.14.4",
                "6.1.3",
                "major",
                "needs_review",
            ),
            updates.Item(
                "maven-dependency",
                "root",
                "org.junit.jupiter:junit-jupiter",
                "6.1.3",
                "6.1.3",
                "none",
                "current",
            ),
        ]
        exceptions = [
            {
                "kind": "maven-dependency",
                "component": "org.junit.jupiter:junit-jupiter",
                "module": "demo",
                "reason": "ADR 0011",
            }
        ]
        updates.apply_exceptions(items, exceptions)
        self.assertEqual(items[0].status, "accepted")
        self.assertIn("ADR 0011", items[0].reason)
        self.assertEqual(items[1].status, "current")

    def test_load_exceptions_missing(self):
        with tempfile.TemporaryDirectory() as temporary:
            self.assertEqual(updates.load_exceptions(Path(temporary)), [])

    def test_load_exceptions_invalid(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            (root / "scripts" / "dependency-audit-exceptions.json").write_text("{bad", encoding="utf-8")
            with self.assertRaises(updates.FatalAuditError):
                updates.load_exceptions(root)


class RenderTest(unittest.TestCase):
    def setUp(self):
        self.items = [
            updates.Item(
                "maven-dependency",
                "root",
                "com.google.guava:guava",
                "33.6.0-jre",
                "33.7.2-jre",
                "minor",
                "outdated",
            ),
            updates.Item(
                "pypi",
                "mkdocs",
                "mkdocs-material",
                "9.7.7",
                "",
                "unknown",
                "unknown",
                "PyPI lookup failed",
            ),
        ]
        self.meta = {
            "ref": "dev/v0.4.0",
            "commit": "abc1234",
            "generated_at": "2026-10-28T03:00:00Z",
            "date": "2026-10-28",
        }

    def test_markdown_cell_sanitizes(self):
        cell = updates.markdown_cell("evil\n@user|`x")
        self.assertNotIn("\n", cell)
        self.assertIn("@\u200buser", cell)
        self.assertIn("\\|", cell)
        self.assertIn("\\`", cell)

    def test_markdown_sections(self):
        rendered = updates.render_markdown(self.items, self.meta)
        self.assertIn("## Maven dependencies", rendered)
        self.assertIn("## Unknown / failed checks", rendered)
        self.assertIn("com.google.guava:guava", rendered)
        self.assertIn("PyPI lookup failed", rendered)

    def test_json_shape(self):
        payload = json.loads(updates.render_json(self.items, self.meta))
        self.assertEqual(payload["audited_ref"], "dev/v0.4.0")
        self.assertEqual(payload["summary"]["outdated"], 1)
        self.assertEqual(len(payload["items"]), 2)

    def test_offline_main_exit_codes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = make_root(Path(temporary))
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                self.assertEqual(updates.main(["--root", str(root), "--offline", "--format", "json"]), 0)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(
                    updates.main(["--root", str(root), "--offline", "--format", "json", "--fail-on-unknown"]), 2
                )
        payload = json.loads(stdout.getvalue())
        self.assertTrue(payload["items"])
        self.assertIn("unknown", payload["summary"])


if __name__ == "__main__":
    unittest.main()
