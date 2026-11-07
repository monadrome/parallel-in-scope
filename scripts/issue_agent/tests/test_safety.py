import json
import os
import subprocess
import sys
import tempfile
import time
import types
import unittest
from pathlib import Path
from unittest import mock

from issue_agent import agents, schemas
from issue_agent.proc import run_logged
from issue_agent.github import GitHub


def fake_config(tmp, base_branch="dev/v0.3.0"):
    return types.SimpleNamespace(
        repo="o/r", base_branch=base_branch, state_dir=Path(tmp), gh_bin="gh", claude_bin="claude", claude_model="m")


class AuthorityTest(unittest.TestCase):
    def test_pull_requests_and_merges_never_target_a_non_dev_branch(self):
        with tempfile.TemporaryDirectory() as tmp:
            for branch in ("main", "release/v1", "dev/linqh", "dev/v0.3.0-clean"):
                gh = GitHub(fake_config(tmp, branch))
                gh._gh = mock.Mock(side_effect=AssertionError("gh must not run"))
                with self.assertRaises(RuntimeError, msg=branch):
                    gh.create_pr("auto/issue-1-x-1", "fix: x", "body")
                with self.assertRaises(RuntimeError, msg=branch):
                    gh.merge_pr(1, "sha", "fix: x (#1)", "body")

    def test_agent_environment_has_no_usable_github_credentials(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(agents.config_module, "claude_provider_env", return_value={}):
            env = agents.agent_env(fake_config(tmp), java_home="/jdk")
        self.assertEqual(env["GH_TOKEN"], "issue-agent-no-github-access")
        self.assertEqual(env["GITHUB_TOKEN"], "issue-agent-no-github-access")
        self.assertEqual((env["GIT_CONFIG_KEY_0"], env["GIT_CONFIG_VALUE_0"]), ("credential.helper", ""))
        self.assertEqual(env["GIT_TERMINAL_PROMPT"], "0")
        self.assertTrue(env["PATH"].startswith("/jdk/bin"))


class SessionArgumentsTest(unittest.TestCase):
    def capture(self, read_only):
        captured = {}

        class FakeProcess:
            returncode = 0
            pid = -1

            def wait(self, timeout=None):
                return 0

            def poll(self):
                return self.returncode

        def fake_popen(args, **kwargs):
            captured["args"] = args
            kwargs["stdout"].write(json.dumps({"type": "result", "structured_output": {"ok": True}}))
            return FakeProcess()

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(agents.subprocess, "Popen", side_effect=fake_popen), \
                mock.patch.object(agents, "terminate_process_group"), \
                mock.patch.object(agents.config_module, "claude_provider_env", return_value={}):
            result = agents.claude(fake_config(tmp), "prompt", schemas.TRIAGE, Path(tmp), Path(tmp) / "x.log", 1.0, 1, read_only)
        self.assertTrue(result.ok)
        return captured["args"]

    def test_triage_sessions_are_restricted_to_read_only_tools(self):
        args = self.capture(read_only=True)
        self.assertIn("--restricted", args)
        self.assertEqual(args[args.index("--tools") + 1], agents.READ_ONLY_TOOLS)
        self.assertNotIn("bypassPermissions", args)
        self.assertIn("--bare", args)

    def test_working_sessions_deny_push_github_and_network_tools(self):
        args = self.capture(read_only=False)
        denied = args[args.index("--disallowedTools") + 1:]
        for tool in ("Bash(git push:*)", "Bash(gh:*)", "Bash(curl:*)", "WebFetch", "WebSearch"):
            self.assertIn(tool, denied)
        self.assertIn("--strict-mcp-config", args)


class ProcessCleanupTest(unittest.TestCase):
    def test_claude_normal_exit_kills_descendants(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fake = root / "claude.py"
            fake.write_text(
                "#!/usr/bin/env python3\n"
                "import json, pathlib, signal, subprocess, sys, time\n"
                "child = subprocess.Popen([sys.executable, '-c', "
                "'import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)'])\n"
                "pathlib.Path('child.pid').write_text(str(child.pid))\n"
                "print(json.dumps({'type':'result','structured_output':{'ok':True}}), flush=True)\n",
                encoding="utf-8",
            )
            fake.chmod(0o755)
            config = fake_config(root)
            config.claude_bin = str(fake)
            result = agents.claude(config, "prompt", schemas.TRIAGE, root, root / "claude.log", 1.0, 1, True)
            self.assertTrue(result.ok)
            child_pid = int((root / "child.pid").read_text(encoding="utf-8"))
            for _ in range(20):
                try:
                    os.kill(child_pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.05)
            with self.assertRaises(ProcessLookupError):
                os.kill(child_pid, 0)

    def test_run_logged_kills_descendants_after_timeout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            script = root / "child.py"
            script.write_text(
                "import pathlib, signal, subprocess, sys, time\n"
                "child = subprocess.Popen([sys.executable, '-c', "
                "'import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)'])\n"
                "pathlib.Path(sys.argv[1]).write_text(str(child.pid))\n"
                "time.sleep(60)\n",
                encoding="utf-8",
            )
            with self.assertRaises(subprocess.TimeoutExpired):
                run_logged([sys.executable, str(script), str(root / "child.pid")], root / "run.log", cwd=root, timeout=0.3)
            child_pid = int((root / "child.pid").read_text(encoding="utf-8"))
            for _ in range(20):
                try:
                    os.kill(child_pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.05)
            with self.assertRaises(ProcessLookupError):
                os.kill(child_pid, 0)


if __name__ == "__main__":
    unittest.main()
