"""Tests for scripts/refactoring-review.py.

Each test builds a throwaway git repository whose base commit carries the real
``refactoring-review/`` rules, so the thresholds and templates under test are the shipped ones.
"""

import contextlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parent
RULES = ROOT / "refactoring-review"
MAIN = "src/main/java/io/github/monadrome/parallelinscope"
TEST = "src/test/java/io/github/monadrome/parallelinscope"


def load_script():
    spec = importlib.util.spec_from_file_location("refactoring_review", SCRIPTS / "refactoring-review.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


review = load_script()
complexity = review.complexity_metrics
CONFIG = json.loads((RULES / "config.json").read_text(encoding="utf-8"))
LIMITS = CONFIG["limits"]


def java(name, lines, statement="int value{index} = {index};"):
    body = "\n".join("        " + statement.format(index=index) for index in range(lines))
    return f"package p;\n\nclass {name} {{\n    void run() {{\n{body}\n    }}\n}}\n"


class RepoTestCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        # Hermetic git: user-level config (trace2 daemons, hooks) can write into .git after a
        # command returns and race the temporary directory's cleanup.
        environment = mock.patch.dict(os.environ, {"GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"})
        environment.start()
        self.addCleanup(environment.stop)
        self.git("init", "-q", "-b", "main")
        shutil.copytree(RULES, self.root / "refactoring-review")
        self.write("README.md", "# repo\n")
        self.commit("base")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        return subprocess.run(
            ["git", "-c", "user.name=t", "-c", "user.email=t@t", *args],
            cwd=self.root,
            check=True,
            capture_output=True,
            text=True,
        ).stdout

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD").strip()

    def run_script(self, *args):
        out = io.StringIO()
        err = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            status = review.main(["--repo", str(self.root), *args])
        return status, (json.loads(out.getvalue()) if out.getvalue() else None), err.getvalue()

    def commit_medium_change(self):
        """Commit two 60-line main files, one using volatile, so the plan runs in per-lens mode."""
        self.write(f"{MAIN}/A.java", java("A", 60, "volatile int value{index};"))
        self.write(f"{MAIN}/B.java", java("B", 60))
        self.commit("medium")

    def plan(self, *extra):
        run_dir = Path(self.temp.name) / "run"
        status, summary, err = self.run_script("plan", "--base", self.base, "--output-dir", str(run_dir), *extra)
        self.assertEqual(status, 0, err)
        return run_dir, summary, json.loads((run_dir / "plan.json").read_text(encoding="utf-8"))


class GateTest(RepoTestCase):
    def test_documentation_only_change_is_skipped(self):
        self.write("docs/en/guide.md", "# guide\n")
        self.write("scripts/notes.txt", "notes\n")
        self.commit("docs")
        run_dir, summary, plan = self.plan()
        self.assertFalse(summary["required"])
        self.assertEqual(plan["agents"], [])
        self.assertFalse((run_dir / "agents").exists())

    def test_comment_only_java_change_is_skipped(self):
        self.write(f"{MAIN}/A.java", java("A", 3))
        self.commit("code")
        self.base = self.git("rev-parse", "HEAD").strip()
        text = (self.root / MAIN / "A.java").read_text(encoding="utf-8")
        self.write(f"{MAIN}/A.java", text.replace("    void run() {", "    // explains run\n    void run() {"))
        self.commit("comment")
        _, summary, plan = self.plan()
        self.assertFalse(summary["required"])
        self.assertEqual(plan["skippedFiles"], [{"path": f"{MAIN}/A.java", "reason": "no changed code lines"}])

    def test_uncommitted_code_change_is_refused(self):
        self.write(f"{MAIN}/A.java", java("A", 3))
        self.commit("code")
        self.write(f"{MAIN}/A.java", java("A", 4))
        status, _, err = self.run_script("plan", "--base", self.base, "--output-dir", str(Path(self.temp.name) / "r"))
        self.assertEqual(status, 2)
        self.assertIn("commit the behavior change first", err)


class PlanModeTest(RepoTestCase):
    def test_small_change_gets_one_detector_with_every_triggered_lens(self):
        self.write(f"{MAIN}/A.java", java("A", 10))
        self.commit("small")
        run_dir, summary, plan = self.plan()
        self.assertEqual(plan["evaluation"]["stage"], "gray-evaluation")
        metrics = json.loads((run_dir / "metrics.json").read_text(encoding="utf-8"))
        self.assertEqual(metrics["stage"], "gray-evaluation")
        self.assertIn("not a quality score", metrics["purpose"])
        self.assertEqual(plan["mode"], "single")
        self.assertEqual([a["id"] for a in plan["agents"]], ["all"])
        self.assertEqual(plan["agents"][0]["lenses"], ["D", "S", "V"])
        self.assertEqual(plan["agents"][0]["budget"], LIMITS["singleAgentBudget"])
        brief = (run_dir / "agents" / "all" / "brief.md").read_text(encoding="utf-8")
        self.assertNotIn("{{", brief)
        self.assertIn("### S01 Mysterious name", brief)
        self.assertIn("### V01", brief)
        self.assertIn("## R1 Behavior preservation is the definition", brief)

    def test_medium_change_gets_one_detector_per_triggered_lens(self):
        self.commit_medium_change()
        _, _, plan = self.plan()
        self.assertEqual(plan["mode"], "per-lens")
        ids = [a["id"] for a in plan["agents"]]
        self.assertEqual(ids, ["M", "C", "D", "S", "V"])
        agents = {a["id"]: a for a in plan["agents"]}
        self.assertEqual(agents["C"]["files"], [f"{MAIN}/A.java"])
        self.assertTrue(agents["M"]["wholeDiff"])
        self.assertGreaterEqual(agents["M"]["budget"], LIMITS["wholeDiffLensMinBudget"])
        self.assertEqual(sum(a["budget"] for a in plan["agents"]), LIMITS["findingBudget"])

    def test_large_change_is_sharded_capped_and_reports_uncovered_work(self):
        for index in range(8):
            self.write(f"{MAIN}/dir{index}/F{index}.java", java(f"F{index}", 150))
            self.write(f"{TEST}/dir{index}/F{index}Test.java", java(f"F{index}Test", 40))
        self.commit("large")
        _, _, plan = self.plan()
        self.assertEqual(plan["mode"], "sharded")
        self.assertEqual(len(plan["agents"]), LIMITS["maxAgents"])
        self.assertEqual(plan["agents"][0]["id"], "M")
        self.assertEqual(len(plan["agents"][0]["files"]), 16)
        self.assertTrue(plan["uncovered"])
        for agent in plan["agents"][1:]:
            self.assertLessEqual(agent["codeLines"], LIMITS["shardLines"])

    def test_reviewers_rotate_deterministically(self):
        self.commit_medium_change()
        _, _, first = self.plan("--reviewers", "kiro,claude")
        _, _, second = self.plan("--reviewers", "kiro,claude", "--force")
        reviewers = [a["reviewer"] for a in first["agents"]]
        self.assertEqual(reviewers, [a["reviewer"] for a in second["agents"]])
        self.assertEqual(set(reviewers), {"kiro", "claude"})
        self.assertNotEqual(reviewers[0], reviewers[1])


class RoutesAndRulesTest(RepoTestCase):
    def test_routes_follow_paths_and_changed_lines(self):
        self.write(f"{MAIN}/queue/Q.java", java("Q", 5))
        self.write(f"{MAIN}/W.java", java("W", 5, "Thread.currentThread().interrupt(); int v{index};"))
        self.commit("routes")
        _, _, plan = self.plan()
        routes = plan["agents"][0]["routes"]
        self.assertIn("src/main/java/io/github/monadrome/parallelinscope/queue/AGENTS.md", routes)
        self.assertIn("adr/0006-queues-ship-with-core.md", routes)
        self.assertIn("design/interruption-contract.md", routes)
        self.assertIn("design/AGENTS.md", routes)
        self.assertNotIn("adr/0012-contract-and-implementation-comment-layers.md", routes)

    def test_rules_come_from_the_base_commit(self):
        lens = self.root / "refactoring-review" / "lenses" / "structure.md"
        lens.write_text(lens.read_text(encoding="utf-8") + "\nHEAD-ONLY-MARKER\n", encoding="utf-8")
        self.write(f"{MAIN}/A.java", java("A", 5))
        self.commit("change rules and code")
        run_dir, _, plan = self.plan()
        self.assertEqual(plan["rulesSource"], "base")
        brief = (run_dir / "agents" / "all" / "brief.md").read_text(encoding="utf-8")
        self.assertNotIn("HEAD-ONLY-MARKER", brief)

    def test_head_fills_only_keys_the_base_config_predates(self):
        config_path = self.root / "refactoring-review" / "config.json"
        base_config = json.loads(config_path.read_text(encoding="utf-8"))
        del base_config["limits"]["minShardLines"]
        config_path.write_text(json.dumps(base_config), encoding="utf-8")
        self.base = self.commit("older rules")
        head_config = dict(base_config, limits=dict(base_config["limits"], minShardLines=40, maxAgents=99))
        config_path.write_text(json.dumps(head_config), encoding="utf-8")
        self.write(f"{MAIN}/A.java", java("A", 5))
        self.commit("newer rules and code")
        run_dir, _, plan = self.plan()
        self.assertEqual(plan["configKeysFromHead"], ["limits.minShardLines"])
        self.assertEqual(plan["limits"]["maxAgents"], LIMITS["maxAgents"])
        copied = json.loads((run_dir / "rules" / "refactoring-review" / "config.json").read_text(encoding="utf-8"))
        self.assertEqual(copied["limits"]["minShardLines"], 40)

    def test_rules_fall_back_to_head_when_the_base_has_none(self):
        self.git("rm", "-r", "-q", "refactoring-review")
        self.base = self.commit("drop rules")
        shutil.copytree(RULES, self.root / "refactoring-review")
        self.write(f"{MAIN}/A.java", java("A", 5))
        self.commit("introduce rules")
        _, _, plan = self.plan()
        self.assertEqual(plan["rulesSource"], "head")


class MergeTest(RepoTestCase):
    def setUp(self):
        super().setUp()
        self.commit_medium_change()
        self.run_dir, _, self.plan_data = self.plan()

    def finding(self, **overrides):
        finding = {
            "file": f"{MAIN}/A.java",
            "symbol": "A#run",
            "rule": "S02",
            "tier": "T0",
            "refactoring": "Extract Function",
            "rationale": "names the step",
            "guardingTests": ["ATest"],
        }
        finding.update(overrides)
        return finding

    def write_findings(self, agent, findings):
        path = self.run_dir / "agents" / agent / "findings.json"
        path.write_text(json.dumps({"agent": agent, "summary": "s", "findings": findings}), encoding="utf-8")

    def test_merge_deduplicates_raises_tier_and_flags_problems(self):
        self.write_findings("S", [self.finding(), self.finding(rule="S99"), {"file": "x"}])
        self.write_findings("C", [self.finding(rule="C02", tier="T2", refactoring="extract   function!")])
        self.write_findings("M", [self.finding(refactoring="Inline Function", rule="M06")])
        self.write_findings("V", [self.finding(file=f"{MAIN}/B.java", rule="V01")] * 5)
        self.write_findings("D", [])
        status, summary, _ = self.run_script("merge", str(self.run_dir))
        self.assertEqual(status, 0)
        merged = json.loads((self.run_dir / "merged.json").read_text(encoding="utf-8"))
        first = next(f for f in merged["findings"] if f["refactoring"] == "extract   function!")
        self.assertEqual(first["tier"], "T2")
        self.assertEqual(first["rules"], ["C02", "S02", "S99"])
        self.assertIn("unknown-rule", first["flags"])
        self.assertEqual([s["agent"] for s in first["sources"]], ["C", "S", "S"])
        agents = {a["id"]: a for a in merged["agents"]}
        self.assertEqual(agents["S"]["invalid"][0]["index"], 2)
        budget = next(a["budget"] for a in self.plan_data["agents"] if a["id"] == "V")
        self.assertEqual(agents["V"]["budgetDropped"], 5 - budget)
        self.assertEqual(merged["conflicts"][0]["symbol"], "A#run")
        self.assertEqual(summary["incompleteAgents"], 0)

    def test_missing_or_invalid_detector_output_fails_the_merge(self):
        self.write_findings("S", [])
        (self.run_dir / "agents" / "C" / "findings.json").write_text("not json", encoding="utf-8")
        status, summary, _ = self.run_script("merge", str(self.run_dir))
        self.assertEqual(status, 1)
        statuses = {a["id"]: a["status"] for a in summary["agents"]}
        self.assertEqual(
            statuses, {"M": "missing", "C": "invalid", "D": "missing", "S": "completed", "V": "missing"}
        )


FAKE_REVIEWER = """
import json, pathlib, sys
prompt = sys.argv[1]
agent = prompt.split("detector `", 1)[1].split("`", 1)[0]
mode = sys.argv[2]
if mode == "edit":
    pathlib.Path("touched.txt").write_text("x")
print("thinking...")
print(json.dumps({"agent": agent, "summary": "s", "findings": [] if mode != "prose" else None}))
"""


class DetectTest(RepoTestCase):
    def setUp(self):
        super().setUp()
        self.commit_medium_change()
        self.run_dir, _, self.plan_data = self.plan()
        script = Path(self.temp.name) / "fake_reviewer.py"
        script.write_text(FAKE_REVIEWER, encoding="utf-8")
        self.mode = "ok"
        review.REVIEWERS["fake"] = lambda prompt, repo, agent_dir, last: [sys.executable, str(script), prompt, self.mode]
        self.addCleanup(review.REVIEWERS.pop, "fake")

    def test_detect_runs_every_detector_and_saves_findings(self):
        status, result, err = self.run_script("detect", str(self.run_dir), "--reviewer", "fake", "--concurrency", "2")
        self.assertEqual(status, 0, err)
        self.assertEqual({d["agent"] for d in result["detectors"]}, {a["id"] for a in self.plan_data["agents"]})
        for agent in self.plan_data["agents"]:
            saved = json.loads((self.run_dir / "agents" / agent["id"] / "findings.json").read_text(encoding="utf-8"))
            self.assertEqual(saved["agent"], agent["id"])
        status, _, _ = self.run_script("merge", str(self.run_dir))
        self.assertEqual(status, 0)

    def test_unparsable_output_and_snapshot_edits_fail(self):
        self.mode = "prose"
        status, result, _ = self.run_script("detect", str(self.run_dir), "--reviewer", "fake", "--only", "M")
        self.assertEqual(status, 1)
        self.assertEqual(result["detectors"][0]["status"], "unparsable")
        self.mode = "edit"
        status, result, _ = self.run_script("detect", str(self.run_dir), "--reviewer", "fake", "--only", "S")
        self.assertEqual(status, 1)
        self.assertTrue(result["snapshotModified"])
        self.assertEqual(result["detectors"][0]["attempt"], 1)

    def test_detect_refuses_a_working_tree_away_from_the_plan_head(self):
        self.write(f"{MAIN}/C.java", java("C", 3))
        self.commit("later")
        status, _, err = self.run_script("detect", str(self.run_dir), "--reviewer", "fake")
        self.assertEqual(status, 2)
        self.assertIn("not the plan head", err)


class VerifyTest(RepoTestCase):
    def setUp(self):
        super().setUp()
        self.write(f"{MAIN}/A.java", java("A", 50))
        self.write(f"{TEST}/ATest.java", java("ATest", 5, "assertEquals({index}, {index});"))
        self.commit("feat: behavior")
        self.run_dir, _, _ = self.plan()

    def test_small_refactor_commit_passes(self):
        text = (self.root / MAIN / "A.java").read_text(encoding="utf-8")
        self.write(f"{MAIN}/A.java", text.replace("value1 = 1", "renamed1 = 1"))
        self.commit("refactor: rename")
        status, result, _ = self.run_script("verify", str(self.run_dir))
        self.assertEqual(status, 0, result)
        self.assertFalse(result["growthExceeded"])

    def test_growth_oracle_edits_and_other_commits_fail(self):
        self.write(f"{MAIN}/A.java", java("A", 50, "long other{index} = {index};"))
        self.write(f"{TEST}/ATest.java", java("ATest", 5, "assertEquals({index}, {index} + 0);"))
        self.commit("fix: sneak a behavior change in")
        status, result, _ = self.run_script("verify", str(self.run_dir))
        self.assertEqual(status, 1)
        self.assertTrue(result["growthExceeded"])
        self.assertTrue(result["oracleEdits"])
        self.assertEqual(result["nonRefactorCommits"], ["fix: sneak a behavior change in"])


class PureFunctionTest(unittest.TestCase):
    def test_complexity_collector_reports_gray_stage_candidates(self):
        source = """class A {
    void run(int value) {
        if (value > 0) { while (value-- > 0) { } }
    }
}
"""
        metrics = complexity.collect_file("src/main/java/io/github/monadrome/parallelinscope/A.java", source)
        self.assertEqual(len(metrics), 1)
        self.assertEqual(metrics[0].symbol, "A#run(int value)")
        self.assertEqual(metrics[0].cyclomatic, 3)
        self.assertEqual(metrics[0].max_nesting, 2)

    def test_complexity_collector_uses_added_lines_and_thresholds(self):
        source = """class A {
    void run(int value) {
        if (value > 0) { while (value-- > 0) { } }
    }
}
"""
        diff = """diff --git a/A.java b/A.java
--- a/A.java
+++ b/A.java
@@ -2,1 +2,1 @@
+    void run(int value) {
"""
        config = {
            "complexity": {
                "thresholds": {
                    "cyclomatic": 3,
                    "codeLines": 80,
                    "maxNesting": 4,
                    "deltaCyclomatic": 2,
                    "deltaCodeLines": 10,
                    "deltaMaxNesting": 1,
                },
                "excludeGlobs": [],
                "maxCandidatesPerAgent": 5,
            }
        }
        result = complexity.collect(Path("."), {}, {"A.java": source}, diff, config)
        self.assertEqual(result["stage"], "gray-evaluation")
        self.assertEqual(result["summary"]["touchedCandidates"], 1)

    def test_parse_diff_separates_code_comment_and_blank_lines(self):
        patch = "\n".join(
            [
                "diff --git a/x/A.java b/x/A.java",
                "--- a/x/A.java",
                "+++ b/x/A.java",
                "@@ -1,0 +1,4 @@",
                "+// note",
                "+int a;",
                "+",
                "++++counter;",
                "@@ -9 +9,0 @@",
                "- * old javadoc",
            ]
        )
        [changed] = review.parse_diff(patch, CONFIG)
        self.assertEqual((changed.code_lines, changed.comment_lines), (2, 2))
        self.assertEqual(changed.changed_code, ["int a;", "+++counter;"])

    def test_shards_keep_small_directories_together(self):
        files = [
            review.ChangedFile(path, path, ".java", "main", lines)
            for path, lines in (("a/X.java", 50), ("a/Y.java", 50), ("b/Z.java", 150), ("c/W.java", 500))
        ]
        shards = [[f.path for f in piece] for piece in review.shard(files, 200)]
        self.assertEqual(shards, [["a/X.java", "a/Y.java"], ["b/Z.java"], ["c/W.java"]])

    def test_tiny_shards_join_the_smallest_other_shard(self):
        files = [
            review.ChangedFile(path, path, ".java", "main", lines)
            for path, lines in (("s/A.java", 1), ("s/B.java", 1), ("s/Big.java", 600), ("t/C.java", 150))
        ]
        shards = [[f.path for f in piece] for piece in review.shard(files, 200, 40)]
        self.assertEqual(shards, [["s/Big.java"], ["s/A.java", "s/B.java", "t/C.java"]])


if __name__ == "__main__":
    unittest.main()
