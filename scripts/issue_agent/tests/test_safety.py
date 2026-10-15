import json
import subprocess
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

from issue_agent import agents, schemas
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

        def fake_run(args, **kwargs):
            captured["args"] = args
            kwargs["stdout"].write(json.dumps({"type": "result", "structured_output": {"ok": True}}))
            return subprocess.CompletedProcess(args, 0)

        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.object(agents.subprocess, "run", side_effect=fake_run), \
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


if __name__ == "__main__":
    unittest.main()
