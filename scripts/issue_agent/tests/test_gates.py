import shutil
import subprocess
import tempfile
import time
import types
import unittest
from pathlib import Path
from unittest import mock

from issue_agent import config as config_module, gates, landing
from issue_agent.github import check_bucket


def git(cwd, *args):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


class RepoGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name) / "repo"
        self.repo.mkdir()
        git(self.repo, "init", "-q")
        for key, value in (("user.email", "t@example.com"), ("user.name", "t"), ("gc.auto", "0"),
                           ("maintenance.auto", "false"), ("core.fsmonitor", "false"), ("commit.gpgsign", "false")):
            git(self.repo, "config", key, value)
        (self.repo / "docs").mkdir()
        (self.repo / "docs" / "a.md").write_text("one\n")
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-qm", "base")
        self.base = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.repo, capture_output=True, text=True).stdout.strip()

    def tearDown(self):
        for _ in range(5):
            try:
                self.tmp.cleanup()
                return
            except OSError:
                time.sleep(0.2)
        shutil.rmtree(self.tmp.name, ignore_errors=True)

    def gates_for(self):
        scratch = Path(self.tmp.name) / "scratch"
        return gates.run_all(self.repo, self.base, "bug", scratch, scratch / "logs", "", 1)[0]

    def commit(self, path, text):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-qm", f"change {path}")

    def test_no_commits_fails(self):
        self.assertEqual([(g.name, g.status) for g in self.gates_for()], [("commits", "fail")])

    def test_dirty_tree_fails_first(self):
        (self.repo / "docs" / "a.md").write_text("dirty\n")
        self.assertEqual(self.gates_for()[0].name, "clean-tree")

    def test_forbidden_path_stops_every_other_gate(self):
        self.commit(".env", "TOKEN=x\n")
        result = self.gates_for()
        self.assertEqual([(g.name, g.status) for g in result], [("diff-policy", "fail")])

    def test_credential_in_an_added_line_stops_every_other_gate(self):
        self.commit("docs/a.md", "token " + "ghp_" + "A1b2" * 9 + "\n")
        result = self.gates_for()
        self.assertEqual([(g.name, g.status) for g in result], [("secret-scan", "fail")])
        self.assertIn("docs/a.md", result[0].detail)
        self.assertNotIn("ghp_", result[0].detail)

    def test_a_workflow_change_is_held_back_from_any_push(self):
        self.commit(".github/workflows/ci.yml", "on: push\n")
        statuses = {g.name: g.status for g in self.gates_for()}
        self.assertEqual(statuses["hold"], "hold")
        self.assertEqual(statuses["diff-policy"], "pass")

    def test_docs_only_change_skips_the_build(self):
        self.commit("docs/a.md", "two\n")
        statuses = {g.name: g.status for g in self.gates_for()}
        self.assertNotIn("verify", statuses)
        self.assertEqual(statuses["diff-policy"], "pass")
        self.assertEqual(statuses["pit"], "skip")
        self.assertEqual(statuses["reverse-verify"], "skip")

    def test_renames_report_both_paths(self):
        git(self.repo, "mv", "docs/a.md", "docs/b.md")
        git(self.repo, "commit", "-qm", "rename")
        paths = {p for _, p in gates.changes(self.repo, self.base)}
        self.assertEqual(paths, {"docs/a.md", "docs/b.md"})


class ReverseVerifyTest(unittest.TestCase):
    def test_classification(self):
        green = gates.classify_reverse(0, "BUILD SUCCESS", ["a.FooTest"])
        red = gates.classify_reverse(1, "Tests run: 3, Failures: 1, Errors: 0", ["a.FooTest"])
        errored = gates.classify_reverse(1, "Tests run: 3, Failures: 0, Errors: 2", ["a.FooTest"])
        uncompiled = gates.classify_reverse(1, "[ERROR] COMPILATION ERROR : cannot find symbol", ["a.FooTest"])
        summary_only = gates.classify_reverse(1, "Tests run: 3, Failures: 0, Errors: 0\nBUILD FAILURE", ["a.FooTest"])
        self.assertEqual([g.status for g in (green, red, errored, uncompiled, summary_only)],
                         ["fail", "pass", "pass", "inconclusive", "inconclusive"])


class ReverseVerifyScopeTest(unittest.TestCase):
    def test_bug_fix_without_a_test_change_needs_a_human(self):
        gate = gates.reverse_verify(Path("/nonexistent"), "base", [("M", "src/main/java/a/B.java")], Path("/x"), Path("/l"), "", 1)
        self.assertEqual(gate.status, "inconclusive")
        skip = gates.reverse_verify(Path("/nonexistent"), "base", [("M", "docs/a.md")], Path("/x"), Path("/l"), "", 1)
        self.assertEqual(skip.status, "skip")


class PitReportTest(unittest.TestCase):
    def test_counts_killed_and_lists_survivors(self):
        xml = """<mutations>
          <mutation detected='true' status='KILLED'><mutatedClass>a.B</mutatedClass><mutatedMethod>m</mutatedMethod><lineNumber>3</lineNumber><mutator>X</mutator><description>negated</description></mutation>
          <mutation detected='false' status='SURVIVED'><mutatedClass>a.B</mutatedClass><mutatedMethod>n</mutatedMethod><lineNumber>9</lineNumber><mutator>Y</mutator><description>removed call</description></mutation>
          <mutation detected='false' status='NO_COVERAGE'><mutatedClass>a.C</mutatedClass><mutatedMethod>o</mutatedMethod><lineNumber>1</lineNumber><mutator>Z</mutator></mutation>
        </mutations>"""
        with tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False) as handle:
            handle.write(xml)
        summary = gates.parse_pit(Path(handle.name))
        Path(handle.name).unlink()
        self.assertEqual(summary["killed"], 1)
        self.assertEqual(summary["survivors"], ["SURVIVED a.B#n:9 removed call", "NO_COVERAGE a.C#o:1 Z"])


class ConfigTest(unittest.TestCase):
    def test_env_file_and_typed_overrides(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.env"
            path.write_text("# comment\nISSUE_AGENT_MAX_PENDING_REVIEWS=3\nISSUE_AGENT_CODEX_MODEL='deepseek-v4-pro'\n")
            settings = config_module.read_env_file(path)
        config = config_module.Config(Path("."), Path("."), "o/r", "dev/v0.3.0")
        config_module.apply_settings(config, {**settings, "ISSUE_AGENT_RUN_BUDGET_USD": "75.5", "ISSUE_AGENT_REPO": "x/y"})
        self.assertEqual((config.max_pending_reviews, config.codex_model, config.run_budget_usd, config.repo),
                         (3, "deepseek-v4-pro", 75.5, "o/r"))


class LandingTest(unittest.TestCase):
    def test_check_buckets(self):
        self.assertEqual([check_bucket("queued", None), check_bucket("completed", "success"),
                          check_bucket("completed", "skipped"), check_bucket("completed", "cancelled"),
                          check_bucket("completed", "failure")], ["pending", "pass", "pass", "fail", "fail"])

    def test_wait_ci_settles_only_on_a_stable_completed_check_set(self):
        polls = [
            [{"name": "build", "bucket": "pass", "link": ""}],
            [{"name": "build", "bucket": "pass", "link": ""}, {"name": "docs", "bucket": "pending", "link": ""}],
            [{"name": "build", "bucket": "pass", "link": ""}, {"name": "docs", "bucket": "fail", "link": "l"}],
            [{"name": "build", "bucket": "pass", "link": ""}, {"name": "docs", "bucket": "fail", "link": "l"}],
        ]
        gh = types.SimpleNamespace(commit_checks=mock.Mock(side_effect=polls))
        run = types.SimpleNamespace(gh=gh, config=types.SimpleNamespace(ci_timeout_min=5))
        with mock.patch.object(landing.time, "sleep"):
            state, failed = landing.wait_ci(run, "sha")
        self.assertEqual((state, [c["name"] for c in failed], gh.commit_checks.call_count), ("red", ["docs"], 3))

    def test_human_reasons_cover_paths_findings_and_inconclusive_gates(self):
        run = types.SimpleNamespace(human_paths=["M pom.xml"], unresolved=["reviewer returned no verdict"],
                                    gates=[gates.Gate("verify", "inconclusive", "javac did not run"), gates.Gate("pit", "pass")])
        reasons = landing.human_reasons(run)
        self.assertEqual(len(reasons), 3)
        self.assertTrue(all(reasons))


if __name__ == "__main__":
    unittest.main()
