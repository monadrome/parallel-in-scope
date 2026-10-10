"""Tests for scripts/check-api-surface.py.

The fixtures are JDK 25 `javap -protected -v` output, as the script's own run_javap prints it,
for small classes compiled with `javac --release 8`; their declaration lines show the sources.
No JDK is needed to run these tests.
"""

import contextlib
import importlib.util
import io
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).resolve().parent / "fixtures" / "api_surface"


def load_script():
    spec = importlib.util.spec_from_file_location("check_api_surface", SCRIPTS / "check-api-surface.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


api = load_script()


def fixture(name):
    return (FIXTURES / f"{name}.txt").read_text(encoding="utf-8")


def surface_of(name):
    return api.surface(api.parse_javap(fixture(name)))


def owners(lines):
    return {line.split(": ", 1)[0] for line in lines}


def block(text, class_file):
    """The javap output for one class file, located by its path suffix."""
    parts = text.split("Classfile ")[1:]
    return next(part for part in parts if part.splitlines()[0].endswith(class_file))


def git(cwd, *args):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


class ExtractorTest(unittest.TestCase):
    def setUp(self):
        self.lines = surface_of("visibility")

    def test_bridge_and_synthetic_members_are_dropped(self):
        bridge = "  public int compare(java.lang.Object, java.lang.Object);\n"
        self.assertIn(bridge + "    descriptor: (Ljava/lang/Object;Ljava/lang/Object;)I\n"
                      "    flags: (0x1041) ACC_PUBLIC, ACC_BRIDGE, ACC_SYNTHETIC\n", fixture("visibility"))
        self.assertIn("fixture.Api: public int compare(java.lang.String, java.lang.String)", self.lines)
        self.assertNotIn("fixture.Api: public int compare(java.lang.Object, java.lang.Object)", self.lines)

        task = "  public java.lang.Runnable task();\n    descriptor: ()Ljava/lang/Runnable;\n    flags: (0x0001) ACC_PUBLIC\n"
        self.assertIn(task, fixture("visibility"))
        self.assertIn("fixture.Api: public java.lang.Runnable task()", self.lines)
        synthetic = fixture("visibility").replace(task, task.replace("ACC_PUBLIC", "ACC_PUBLIC, ACC_SYNTHETIC"))
        self.assertNotIn("fixture.Api: public java.lang.Runnable task()", api.surface(api.parse_javap(synthetic)))

    def test_protected_members_are_kept_and_package_private_ones_dropped(self):
        self.assertIn("fixture.Api: protected fixture.Api(java.lang.String)", self.lines)
        self.assertIn("fixture.Api: protected volatile long seen", self.lines)
        self.assertIn(
            "fixture.Api: protected <T extends java.lang.Comparable<? super T>> T max(java.util.List<? extends T>)"
            " throws java.lang.IllegalStateException",
            self.lines,
        )
        joined = "\n".join(self.lines)
        for hidden in ("packageCounter", "packageHelper", "secret", "privateHelper", "fixture.Api(int)"):
            self.assertNotIn(hidden, joined)

    def test_member_access_comes_from_the_flags_not_from_javaps_own_filtering(self):
        task = "  public java.lang.Runnable task();\n    descriptor: ()Ljava/lang/Runnable;\n    flags: (0x0001) ACC_PUBLIC\n"
        for listed in ("  java.lang.Runnable task();\n    descriptor: ()Ljava/lang/Runnable;\n    flags: (0x0000)\n",
                       "  private java.lang.Runnable task();\n    descriptor: ()Ljava/lang/Runnable;\n"
                       "    flags: (0x0002) ACC_PRIVATE\n"):
            lines = api.surface(api.parse_javap(fixture("visibility").replace(task, listed)))
            self.assertFalse([line for line in lines if "task()" in line], listed)

    def test_nested_types_count_only_when_every_enclosing_type_is_public(self):
        self.assertEqual(owners(self.lines), {"fixture.Api", "fixture.Api$Nested", "fixture.Api$Callback"})
        self.assertIn("fixture.Api$Nested: public void visible()", self.lines)
        inner = block(fixture("visibility"), "fixture/Hidden$Inner.class")
        self.assertIn("\npublic class fixture.Hidden$Inner\n", inner)
        self.assertIn("  public static #17= #7 of #15;", inner)
        self.assertIn("\nclass fixture.Hidden\n", block(fixture("visibility"), "fixture/Hidden.class"))

    def test_a_protected_nested_type_is_dropped_although_its_own_header_says_public(self):
        nested = block(fixture("visibility"), "fixture/Api$ProtectedNested.class")
        self.assertIn("\npublic class fixture.Api$ProtectedNested\n", nested)
        self.assertIn("  flags: (0x0021) ACC_PUBLIC, ACC_SUPER\n", nested)
        self.assertIn("  protected static #17= #7 of #15;", nested)
        self.assertNotIn("fixture.Api$ProtectedNested", owners(self.lines))
        self.assertNotIn("fixture.Api$PackageNested", owners(self.lines))

    def test_synchronized_native_and_strictfp_are_not_signature(self):
        for member in ("public void lock()", "public void nativeCall()", "public double exact(double)"):
            self.assertIn(f"fixture.Api: {member}", self.lines)
        self.assertIn("fixture.Api$Callback: public default void twice()", self.lines)

    def test_type_heads_and_supertypes_are_separate_lines(self):
        self.assertIn("fixture.Api: public class fixture.Api", self.lines)
        self.assertIn("fixture.Api: implements java.util.Comparator<java.lang.String>", self.lines)
        self.assertIn("fixture.Api$Callback: extends java.lang.Runnable", self.lines)
        self.assertNotIn("fixture.Api: extends java.lang.Object", self.lines)

    def test_class_files_skip_package_info_and_anonymous_or_local_classes(self):
        with tempfile.TemporaryDirectory() as tmp:
            tree = Path(tmp) / "fixture"
            tree.mkdir()
            for name in ("Api.class", "Api$Nested.class", "Api$1.class", "Api$1Local.class",
                         "package-info.class", "module-info.class", "notes.txt"):
                (tree / name).write_bytes(b"")
            self.assertEqual([p.name for p in api.class_files(Path(tmp))], ["Api$Nested.class", "Api.class"])

    def test_extract_fails_when_javap_skips_a_file_or_finds_no_public_api(self):
        hidden_only = "Classfile " + block(fixture("visibility"), "fixture/Hidden.class")
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "Hidden.class").write_bytes(b"")
            (Path(tmp) / "Other.class").write_bytes(b"")
            for text, reason in ((hidden_only, "javap described 1 of 2"),
                                 (hidden_only + "Classfile " + block(fixture("visibility"), "fixture/Hidden$Inner.class"),
                                  "no public API")):
                with mock.patch.object(api, "run_javap", return_value=text), \
                        self.assertRaisesRegex(api.ApiSurfaceError, reason):
                    api.extract(Path(tmp))

    def test_unrecognized_javap_output_fails_instead_of_passing_quietly(self):
        text = fixture("visibility")
        for broken in (
            text.replace("    flags: (0x0001) ACC_PUBLIC\n", "    access: (0x0001) ACC_PUBLIC\n", 1),
            text.replace("  protected static #17= #7 of #15;", "  protected static #17 = #7 of #15;", 1),
            text.replace("Constant pool:\n", "Constants:\n", 1),
            "javap: unexpected banner\n" + text,
        ):
            with self.assertRaises(api.ApiSurfaceError):
                api.parse_javap(broken)


def declared(declaration):
    return api.ClassFile("fixture.T", declaration, frozenset({"ACC_PUBLIC", "ACC_SUPER"}), {}, [])


class DifferTest(unittest.TestCase):
    def test_reordering_and_classfile_noise_are_not_a_change(self):
        self.assertNotEqual(fixture("evolving-base"), fixture("evolving-reordered"))
        self.assertEqual(api.diff(surface_of("evolving-base"), surface_of("evolving-reordered")), ([], []))

    def test_a_changed_signature_is_removed_plus_added_and_breaks(self):
        removed, added = api.diff(surface_of("evolving-base"), surface_of("evolving-changed"))
        self.assertEqual(removed, ["fixture.Evolving: public T pick(java.util.List<T>)"])
        self.assertEqual(added, ["fixture.Evolving: public T pick(java.util.Collection<? extends T>)"])
        self.assertFalse(api.evaluate(removed, api.migration_guides("0.3.0"), set()).passed)

    def test_additions_alone_pass_and_are_listed(self):
        removed, added = api.diff(surface_of("evolving-base"), surface_of("evolving-added"))
        self.assertEqual((removed, added), ([], ["fixture.Evolving: public T pickOr(java.util.List<T>, T)"]))
        verdict = api.evaluate(removed, api.migration_guides("0.3.0"), set())
        self.assertTrue(verdict.passed)
        report = api.render("0123456789abcdef", removed, added, verdict)
        self.assertIn("+ fixture.Evolving: public T pickOr(java.util.List<T>, T)", report)
        self.assertNotIn("Removed or changed", report)
        self.assertIn("Result: pass", report)

    def test_adding_a_supertype_is_an_addition_and_dropping_one_a_removal(self):
        plain = api.surface([declared("public class fixture.T")])
        closeable = api.surface([declared("public class fixture.T implements java.lang.AutoCloseable")])
        generic = api.surface([declared("public class fixture.T extends java.lang.Object implements java.lang.AutoCloseable")])
        self.assertEqual(api.diff(plain, closeable), ([], ["fixture.T: implements java.lang.AutoCloseable"]))
        self.assertEqual(api.diff(closeable, plain), (["fixture.T: implements java.lang.AutoCloseable"], []))
        self.assertEqual(api.diff(closeable, generic), ([], []))


class GateTest(unittest.TestCase):
    GUIDES = ("docs/en/migration-v0.3.md", "docs/zh/migration-v0.3.md")

    def test_a_removal_needs_both_migration_guides(self):
        removed = ["fixture.Evolving: public T pick(java.util.List<T>)"]
        self.assertFalse(api.evaluate(removed, self.GUIDES, set()).passed)
        for one in self.GUIDES:
            verdict = api.evaluate(removed, self.GUIDES, {one})
            self.assertFalse(verdict.passed)
            self.assertIn(next(g for g in self.GUIDES if g != one), verdict.message)
        self.assertTrue(api.evaluate(removed, self.GUIDES, set(self.GUIDES)).passed)

    def test_the_version_selects_the_release_line_guides(self):
        self.assertEqual(api.migration_guides("0.3.0-SNAPSHOT"), self.GUIDES)
        self.assertEqual(api.migration_guides("0.4.0"), ("docs/en/migration-v0.4.md", "docs/zh/migration-v0.4.md"))
        self.assertEqual(api.migration_guides("1.12-rc1")[0], "docs/en/migration-v1.12.md")
        for bad in ("${revision}", "v0.3.0", "0"):
            with self.assertRaises(api.ApiSurfaceError):
                api.migration_guides(bad)

    def test_the_project_version_is_the_poms_own_not_the_parents(self):
        pom = (b'<?xml version="1.0" encoding="UTF-8"?>\n<project xmlns="http://maven.apache.org/POM/4.0.0">'
               b"<parent><version>9.9.9</version></parent><version> 0.3.0-SNAPSHOT </version></project>")
        self.assertEqual(api.project_version(pom), "0.3.0-SNAPSHOT")
        self.assertEqual(api.project_version(b"<project><version>0.4.0</version></project>"), "0.4.0")
        for bad in (b"<project><parent><version>1.0</version></parent></project>", b"<project>"):
            with self.assertRaises(api.ApiSurfaceError):
                api.project_version(bad)


POM = '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>{}</version></project>\n'


class MainTest(unittest.TestCase):
    """main() end to end: a scratch git repository, with javap replayed from the fixtures."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.repo = self.tmp / "repo"
        self.repo.mkdir()
        git(self.repo, "init", "-q")
        for key, value in (("user.email", "t@example.com"), ("user.name", "t"), ("commit.gpgsign", "false"),
                           ("gc.auto", "0"), ("core.fsmonitor", "false")):
            git(self.repo, "config", key, value)
        self.write("pom.xml", POM.format("0.3.0-SNAPSHOT"))
        self.write("docs/en/migration-v0.3.md", "# Migrating to v0.3\n")
        self.write("docs/zh/migration-v0.3.md", "# 迁移到 v0.3\n")
        self.base = self.commit("base")
        self.summary = self.tmp / "summary.md"

    def tearDown(self):
        for _ in range(5):
            try:
                shutil.rmtree(self.tmp)
                return
            except OSError:
                time.sleep(0.2)
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write(self, path, text):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def commit(self, message):
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-qm", message, "--allow-empty")
        return git(self.repo, "rev-parse", "HEAD")

    def tree(self, scenario):
        tree = self.tmp / "classes" / scenario
        (tree / "fixture").mkdir(parents=True, exist_ok=True)
        (tree / "fixture" / "Evolving.class").write_bytes(b"")
        return tree

    def run_check(self, base_scenario, head_scenario, *extra):
        trees = {self.tree(base_scenario): base_scenario, self.tree(head_scenario): head_scenario}
        replay = lambda files: fixture(trees[files[0].parent.parent])
        out, err = io.StringIO(), io.StringIO()
        argv = ["--base-classes", str(self.tmp / "classes" / base_scenario),
                "--head-classes", str(self.tmp / "classes" / head_scenario), "--base", self.base, *extra]
        with mock.patch.object(api, "ROOT", self.repo), mock.patch.object(api, "run_javap", side_effect=replay), \
                mock.patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(self.summary)}), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = api.main(argv)
        summary = self.summary.read_text(encoding="utf-8") if self.summary.exists() else ""
        return code, out.getvalue(), err.getvalue(), summary

    def document(self, *guides):
        notes = {"docs/en/migration-v0.3.md": "# Migrating to v0.3\n\n- pick takes a Collection\n",
                 "docs/zh/migration-v0.3.md": "# 迁移到 v0.3\n\n- pick 改为接受 Collection\n"}
        for guide in guides:
            self.write(guide, notes[guide])

    def test_a_removal_without_migration_notes_fails(self):
        code, out, err, summary = self.run_check("evolving-base", "evolving-changed")
        self.assertEqual(code, 1, err)
        self.assertIn("Result: fail", summary)
        self.assertIn("- fixture.Evolving: public T pick(java.util.List<T>)", summary)
        self.assertIn("+ fixture.Evolving: public T pick(java.util.Collection<? extends T>)", summary)
        self.assertIn("old form under Removed and its new form under Added", summary)
        self.assertEqual(out, summary)

    def test_a_removal_with_one_guide_changed_fails(self):
        self.document("docs/en/migration-v0.3.md")
        self.commit("document in English only")
        code, _, err, summary = self.run_check("evolving-base", "evolving-changed")
        self.assertEqual(code, 1, err)
        self.assertIn("not updated: docs/zh/migration-v0.3.md", summary)

    def test_a_removal_with_both_guides_changed_passes(self):
        self.document("docs/en/migration-v0.3.md", "docs/zh/migration-v0.3.md")
        self.commit("document both")
        code, _, err, summary = self.run_check("evolving-base", "evolving-changed")
        self.assertEqual(code, 0, err)
        self.assertIn("Result: pass", summary)
        self.assertIn("- fixture.Evolving: public T pick(java.util.List<T>)", summary)

    def test_a_pure_removal_report_has_no_added_section(self):
        code, _, err, summary = self.run_check("evolving-added", "evolving-base")
        self.assertEqual(code, 1, err)
        self.assertIn("- fixture.Evolving: public T pickOr(java.util.List<T>, T)", summary)
        self.assertNotIn("Added", summary)

    def test_a_deleted_guide_is_not_a_migration_note(self):
        self.document("docs/en/migration-v0.3.md")
        (self.repo / "docs/zh/migration-v0.3.md").unlink()
        self.commit("delete the Chinese guide")
        code, _, err, summary = self.run_check("evolving-base", "evolving-changed")
        self.assertEqual(code, 1, err)
        self.assertIn("not updated: docs/zh/migration-v0.3.md", summary)

    def test_a_guide_changed_and_reverted_in_the_pull_request_does_not_count(self):
        self.document("docs/en/migration-v0.3.md", "docs/zh/migration-v0.3.md")
        self.commit("document both")
        self.write("docs/en/migration-v0.3.md", "# Migrating to v0.3\n")
        self.write("docs/zh/migration-v0.3.md", "# 迁移到 v0.3\n")
        self.commit("revert the notes")
        code, _, err, _ = self.run_check("evolving-base", "evolving-changed")
        self.assertEqual(code, 1, err)

    def test_the_guides_follow_the_head_version(self):
        self.write("pom.xml", POM.format("0.4.0-SNAPSHOT"))
        self.document("docs/en/migration-v0.3.md", "docs/zh/migration-v0.3.md")
        self.commit("bump to 0.4 but document 0.3")
        code, _, err, summary = self.run_check("evolving-base", "evolving-changed")
        self.assertEqual(code, 1, err)
        self.assertIn("update docs/en/migration-v0.4.md and docs/zh/migration-v0.4.md", summary)

    def test_pure_additions_pass_and_are_listed(self):
        code, _, err, summary = self.run_check("evolving-base", "evolving-added")
        self.assertEqual((code, err), (0, ""))
        self.assertIn("0 removed or changed, 1 added", summary)
        self.assertIn("+ fixture.Evolving: public T pickOr(java.util.List<T>, T)", summary)
        self.assertNotIn("Removed or changed", summary)

    def test_an_unchanged_surface_passes(self):
        code, _, err, summary = self.run_check("evolving-base", "evolving-reordered")
        self.assertEqual((code, err), (0, ""))
        self.assertIn("0 removed or changed, 0 added", summary)

    def test_an_option_shaped_base_is_rejected_before_git_sees_it(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            api.parse_args(["--base-classes", "a", "--head-classes", "b", "--base=--output=/tmp/x"])
        self.assertEqual(raised.exception.code, 2)

    def test_a_missing_class_tree_fails_loudly_and_writes_no_summary(self):
        argv = ["--base-classes", str(self.tmp / "missing"), "--head-classes", str(self.tree("evolving-base")),
                "--base", self.base]
        err = io.StringIO()
        with mock.patch.object(api, "ROOT", self.repo), \
                mock.patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(self.summary)}), \
                contextlib.redirect_stderr(err):
            code = api.main(argv)
        self.assertEqual(code, 2)
        self.assertIn("no class files under", err.getvalue())
        self.assertFalse(self.summary.exists())


if __name__ == "__main__":
    unittest.main()
