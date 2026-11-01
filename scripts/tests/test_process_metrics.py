"""Tests for scripts/process-metrics.py.

Run directories are built as minimal fixtures in temporary directories; the record tests use a
throwaway git repository. No test calls ``gh`` or reads the real review roots.
"""

import contextlib
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]


def load_script():
    spec = importlib.util.spec_from_file_location("process_metrics", SCRIPTS / "process-metrics.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


metrics = load_script()


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def bench_run(root: Path, run_id: str, status: str = "completed", evaluation: dict | None = None) -> Path:
    run_dir = root / run_id
    write_json(run_dir / "run.json", {"schemaVersion": metrics.BENCH_RUN_SCHEMA, "runId": run_id})
    attempts = [{"attempt": 1, "status": status, "durationMs": 1200}]
    write_json(run_dir / "state.json", {"status": status, "steps": {"reviews": {"kimi": {"status": status, "attempts": attempts}}}})
    if evaluation is not None:
        write_json(run_dir / "derived" / "evaluation" / "v2.json", {"systems": {}, "union": {"clusters": {"total": 0}}})
        write_json(run_dir / "derived" / "evaluation" / "v10.json", evaluation)
    return run_dir


REPORT = """# Refactoring review

## Dispositions

| Merged id | Rule | Symbol | Tier (detector -> final) | Disposition | Reason |
|---|---|---|---|---|---|
| R1 | M05 | `a` | T1 -> T1 | applied (`abc1234`) | landed |
| R2 | S02 | `b` | T0 -> T0 | Deferred | over the limit |
| R3 | S03 | `c` | T0 -> T0 | moved elsewhere | unclear |

## Verification

| Merged id | Disposition |
|---|---|
| R9 | applied |
"""


def refactoring_run(root: Path, name: str, head: str, findings: list[dict], report: str | None = REPORT) -> Path:
    run_dir = root / name
    write_json(run_dir / "plan.json", {"head": head})
    write_json(run_dir / "merged.json", {"findings": findings, "totals": {"incompleteAgents": 0}})
    if report is not None:
        (run_dir / "report.md").write_text(report, encoding="utf-8")
    return run_dir


FINDINGS = [
    {"id": "R1", "rules": ["M05"], "tier": "T1", "scope": "in-diff", "mechanizable": True},
    {"id": "R2", "rules": ["S02", "S02"], "tier": "T0", "scope": "in-diff", "mechanizable": False},
    {"id": "R3", "rules": ["S03", "M05"], "tier": "T0", "scope": "in-diff"},
]


class DispositionTest(unittest.TestCase):
    def test_reads_only_the_dispositions_table_and_normalizes_the_first_word(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "report.md"
            report.write_text(REPORT, encoding="utf-8")
            self.assertEqual(metrics.report_dispositions(report), {"R1": "applied", "R2": "deferred", "R3": "unknown"})

    def test_missing_report_yields_no_dispositions(self):
        self.assertEqual(metrics.report_dispositions(Path("/nonexistent/report.md")), {})


class PitTest(unittest.TestCase):
    def test_every_status_outside_the_conclusive_set_is_inconclusive(self):
        statuses = ["KILLED"] * 5 + ["SURVIVED", "NO_COVERAGE", "NON_VIABLE", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR", "STARTED"]
        body = "".join(f'<mutation detected="false" status="{s}"><x/></mutation>' for s in statuses)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "mutations.xml"
            path.write_text(f"<?xml version='1.0'?><mutations>{body}</mutations>", encoding="utf-8")
            snapshot = metrics.pit_snapshot(path, "mutations.xml")
        self.assertEqual(snapshot["total"], 12)
        self.assertEqual(snapshot["inconclusive"], 4)
        self.assertEqual(snapshot["inconclusiveRate"], round(4 / 12, 4))
        self.assertEqual(snapshot["statuses"]["KILLED"], 5)

    def test_empty_report_has_no_rate(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "mutations.xml"
            path.write_text("<mutations/>", encoding="utf-8")
            self.assertIsNone(metrics.pit_snapshot(path, "x")["inconclusiveRate"])

    def test_directory_search_keeps_only_mutation_reports(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "a.xml").write_text("<mutations></mutations>", encoding="utf-8")
            (root / "pom.xml").write_text("<project/>", encoding="utf-8")
            self.assertEqual([p.name for p in metrics.pit_files(root)], ["a.xml"])


class BenchSnapshotTest(unittest.TestCase):
    def test_uses_the_numerically_latest_evaluation(self):
        evaluation = {
            "systems": {"kimi": {"findings": {"total": 3, "confirmed": 1}, "usage": {"totalTokens": 40}}},
            "union": {"clusters": {"total": 3, "confirmed": 1}},
            "usage": {"totalTokens": 40, "systemsWithUsage": 1, "costsByCurrency": [{"currency": "USD", "cost": 0.5}]},
        }
        with tempfile.TemporaryDirectory() as temporary:
            snapshot = metrics.bench_snapshot(bench_run(Path(temporary), "r1", evaluation=evaluation))
        self.assertEqual(snapshot["clusters"], {"total": 3, "confirmed": 1})
        self.assertEqual(snapshot["systems"], [
            {"agent": "kimi", "status": "completed", "attempts": 1, "durationMs": 1200,
             "findings": {"total": 3, "confirmed": 1}, "usage": {"totalTokens": 40}},
        ])

    def test_run_without_evaluation_keeps_unknowns_null(self):
        with tempfile.TemporaryDirectory() as temporary:
            snapshot = metrics.bench_snapshot(bench_run(Path(temporary), "r1", status="timed_out"))
        self.assertEqual(snapshot["status"], "timed_out")
        self.assertIsNone(snapshot["clusters"])
        self.assertIsNone(snapshot["usage"])
        self.assertIsNone(snapshot["systems"][0]["findings"])


class RankingTest(unittest.TestCase):
    def test_counts_each_rule_once_per_finding_with_its_disposition(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            snapshot = metrics.refactoring_snapshot(refactoring_run(root, "rr", "h1", FINDINGS), root)
        ranking = metrics.rule_ranking([snapshot], {"M05": "Duplication across files"})
        self.assertEqual([row["rule"] for row in ranking], ["M05", "S02", "S03"])
        m05, s02 = ranking[0], ranking[1]
        self.assertEqual((m05["title"], m05["findings"], m05["applied"], m05["unknown"], m05["mechanizable"]),
                         ("Duplication across files", 2, 1, 1, 1))
        self.assertEqual((s02["findings"], s02["deferred"]), (1, 1))

    def test_scan_skips_runs_a_record_already_holds(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bench_run(root / "bench", "recorded")
            bench_run(root / "bench", "fresh")
            refactoring_run(root / "rr", "a", "h1", FINDINGS)
            refactoring_run(root / "rr", "b", "h2", FINDINGS[:1])
            scanned = metrics.scan_runs([root], root)
            record = {
                "id": "x", "routes": ["defect"], "change": {"commits": ["fix: a"]},
                "reviews": {"adversarial": [metrics.bench_snapshot(root / "bench" / "recorded")],
                            "refactoring": [metrics.refactoring_snapshot(root / "rr" / "a", root)]},
                "gates": {"failures": {"mvn test": 2}}, "pit": [],
                "interventions": {"asks": 1, "corrections": 0},
                "usage": {"totalTokens": None, "cost": None, "currency": None},
            }
            report = metrics.build_report([record], scanned, {})
        self.assertEqual(report["unrecordedRuns"], {"adversarial": 1, "refactoring": 1})
        self.assertEqual(report["reviewYield"]["runs"], 2)
        self.assertEqual(sum(row["findings"] for row in report["rules"]), 5)
        self.assertEqual(report["changes"][0]["reviewRounds"], 1)
        self.assertEqual(report["localGateFailures"], {"mvn test": 2})


class CiTest(unittest.TestCase):
    def test_counts_failed_steps_only_for_failed_runs(self):
        calls = []

        def gh(args):
            calls.append(args)
            if args[:2] == ["run", "list"]:
                return json.dumps([
                    {"databaseId": 1, "conclusion": "failure", "workflowName": "CI"},
                    {"databaseId": 2, "conclusion": "success", "workflowName": "CI"},
                    {"databaseId": 3, "conclusion": "failure", "workflowName": "Release"},
                ])
            if args[2] == "1":
                return json.dumps({"jobs": [{"name": "build", "conclusion": "failure", "steps": [
                    {"name": "checkout", "conclusion": "success"}, {"name": "test", "conclusion": "failure"}]}]})
            return json.dumps({"jobs": []})

        result = metrics.ci_failures(3, gh)
        self.assertEqual(result["workflows"], {"CI": {"runs": 2, "failures": 1}, "Release": {"runs": 1, "failures": 1}})
        self.assertEqual(result["failedSteps"], [{"step": "CI / build / test", "count": 1},
                                                 {"step": "Release / (no failed step reported)", "count": 1}])
        self.assertNotIn(["run", "view", "2", "--json", "jobs"], calls)


class RecordTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.repo = Path(self.temporary.name)
        run = lambda *args: subprocess.run(["git", "-C", str(self.repo), *args], check=True, capture_output=True)
        run("init", "-q", "-b", "main")
        run("config", "user.email", "t@example.com")
        run("config", "user.name", "t")
        (self.repo / "a.txt").write_text("a\n", encoding="utf-8")
        run("add", "a.txt")
        run("commit", "-q", "-m", "base")
        run("tag", "base")
        (self.repo / "a.txt").write_text("a\nb\n", encoding="utf-8")
        run("commit", "-q", "-am", "fix: add b")

    def tearDown(self):
        self.temporary.cleanup()

    def record(self, *extra):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = metrics.main(["--repo", str(self.repo), "record", "--topic", "add-b", "--route", "defect",
                                 "--base", "base", "--date", "2026-11-05", *extra])
        return code, out.getvalue()

    def test_writes_a_record_with_the_change_and_self_reported_counts(self):
        code, out = self.record("--gate-failure", "mvn test=1", "--gate-failure", "mvn test=2", "--asks", "1")
        self.assertEqual(code, 0)
        record = json.loads((self.repo / metrics.LEDGER_DIR / "2026-11-05-add-b.json").read_text(encoding="utf-8"))
        self.assertEqual(json.loads(out)["id"], "2026-11-05-add-b")
        self.assertEqual(record["schemaVersion"], metrics.SCHEMA)
        self.assertEqual(record["change"], {"commits": ["fix: add b"], "files": 1, "insertions": 1, "deletions": 0})
        self.assertEqual(record["gates"]["failures"], {"mvn test": 3})
        self.assertEqual(record["interventions"], {"asks": 1, "corrections": 0})
        self.assertEqual(record["usage"], {"totalTokens": None, "cost": None, "currency": None})

    def test_refuses_to_overwrite_without_force(self):
        self.assertEqual(self.record()[0], 0)
        self.assertEqual(self.record()[0], 2)
        self.assertEqual(self.record("--force")[0], 0)

    def test_rejects_cost_without_currency_and_malformed_counts(self):
        self.assertEqual(self.record("--cost", "1.5")[0], 2)
        self.assertEqual(self.record("--gate-failure", "mvn test")[0], 2)
        self.assertFalse((self.repo / metrics.LEDGER_DIR).exists())

    def test_rejects_a_directory_that_is_not_a_bench_run(self):
        (self.repo / "not-a-run").mkdir()
        self.assertEqual(self.record("--adversarial", str(self.repo / "not-a-run"))[0], 2)

    def test_report_reads_the_written_record(self):
        self.record()
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(metrics.main(["--repo", str(self.repo), "report", "--json"]), 0)
        report = json.loads(out.getvalue())
        self.assertEqual(report["records"], 1)
        self.assertEqual(report["changes"][0]["id"], "2026-11-05-add-b")


if __name__ == "__main__":
    unittest.main()
