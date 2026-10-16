from __future__ import annotations

import json
import re
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

from . import policy
from .issues import Comment, Issue, issue_from_node
from .proc import CommandError, run

ISSUE_FIELDS = """
number title body state authorAssociation
author { login __typename }
labels(first: 50) { nodes { name } }
comments(first: 100) { nodes { id databaseId body authorAssociation author { login __typename } } }
timelineItems(itemTypes: [LABELED_EVENT], last: 50) {
  nodes { ... on LabeledEvent { actor { login __typename } label { name } } }
}
"""
LIST_QUERY = """
query($owner: String!, $name: String!, $cursor: String) {
  repository(owner: $owner, name: $name) {
    issues(states: OPEN, first: 50, after: $cursor, orderBy: {field: CREATED_AT, direction: ASC}) {
      pageInfo { hasNextPage endCursor }
      nodes { %s }
    }
  }
}
""" % ISSUE_FIELDS
ONE_QUERY = """
query($owner: String!, $name: String!, $number: Int!) {
  repository(owner: $owner, name: $name) { issue(number: $number) { %s } }
}
""" % ISSUE_FIELDS
RUN_LINK_RE = re.compile(r"/actions/runs/(\d+)")
PASSING_CONCLUSIONS = frozenset({"success", "neutral", "skipped"})
CLAIM_PREFIX = "tags/issue-agent-claim/"
CLAIM_FIELD_RE = re.compile(r"^(machine|run|host|created_epoch)=(\S+)$", re.M)


class GitHub:
    def __init__(self, config):
        self.config = config
        self.owner, self.name = config.repo.split("/", 1)
        self._permissions: dict = {}
        self._login: Optional[str] = None

    def _gh(self, *args: str, stdin: Optional[str] = None, check: bool = True, timeout: float = 180):
        self.config.state_dir.mkdir(parents=True, exist_ok=True)
        return run([self.config.gh_bin, *args], cwd=self.config.state_dir, stdin=stdin, check=check, timeout=timeout)

    def graphql(self, query: str, **variables) -> dict:
        args = ["api", "graphql", "-f", f"query={query}"]
        for key, value in variables.items():
            args += ["-F" if isinstance(value, int) else "-f", f"{key}={value}"]
        return json.loads(self._gh(*args).stdout)

    @property
    def login(self) -> str:
        if self._login is None:
            self._login = self._gh("api", "user", "--jq", ".login").stdout.strip()
        return self._login

    def open_issues(self) -> List[Issue]:
        issues, cursor = [], None
        while True:
            variables = {"owner": self.owner, "name": self.name}
            if cursor:
                variables["cursor"] = cursor
            page = self.graphql(LIST_QUERY, **variables)["data"]["repository"]["issues"]
            issues += [issue_from_node(node) for node in page["nodes"]]
            if not page["pageInfo"]["hasNextPage"]:
                return issues
            cursor = page["pageInfo"]["endCursor"]

    def issue(self, number: int) -> Issue:
        node = self.graphql(ONE_QUERY, owner=self.owner, name=self.name, number=number)["data"]["repository"]["issue"]
        if node is None:
            raise RuntimeError(f"#{number} is not an issue in {self.config.repo}")
        return issue_from_node(node)

    def permission(self, user: str) -> str:
        if user not in self._permissions:
            path = f"repos/{self.config.repo}/collaborators/{user}/permission"
            result = self._gh("api", path, "--jq", ".permission", check=False)
            self._permissions[user] = result.stdout.strip() if result.returncode == 0 else "none"
        return self._permissions[user]

    def ensure_labels(self, definitions: Iterable[Tuple[str, str, str]]) -> List[str]:
        listing = self._gh("label", "list", "--repo", self.config.repo, "--limit", "200", "--json", "name,color,description")
        existing = {label["name"]: label for label in json.loads(listing.stdout)}
        changed = []
        for name, color, description in definitions:
            current = existing.get(name)
            if current and current["color"].lower() == color and current["description"] == description:
                continue
            self._gh("label", "create", name, "--repo", self.config.repo, "--color", color, "--description", description, "--force")
            changed.append(name)
        return changed

    def edit_labels(self, number: int, add: Iterable[str] = (), remove: Iterable[str] = ()) -> None:
        args = ["issue", "edit", str(number), "--repo", self.config.repo]
        for label in sorted(add):
            args += ["--add-label", label]
        for label in sorted(remove):
            args += ["--remove-label", label]
        if len(args) > 5:
            self._gh(*args)

    def upsert_comment(self, number: int, body: str, existing: Optional[Comment] = None) -> None:
        if existing is not None:
            path = f"repos/{self.config.repo}/issues/comments/{existing.database_id}"
            self._gh("api", "-X", "PATCH", path, "-F", "body=@-", stdin=body)
        else:
            self._gh("issue", "comment", str(number), "--repo", self.config.repo, "--body-file", "-", stdin=body)

    def close_issue(self, number: int, reason: str, comment: str) -> None:
        self._gh("issue", "close", str(number), "--repo", self.config.repo, "--reason", reason, "--comment", comment)

    def _require_dev_line(self) -> None:
        if not policy.DEV_LINE_RE.match(self.config.base_branch):
            raise RuntimeError(f"refusing to target {self.config.base_branch}: agent PRs merge into a dev line only")

    def create_pr(self, head: str, title: str, body: str) -> int:
        self._require_dev_line()
        args = ["pr", "create", "--repo", self.config.repo, "--base", self.config.base_branch, "--head", head, "--title", title]
        url = self._gh(*args, "--body-file", "-", stdin=body).stdout.strip().splitlines()[-1]
        return int(url.rstrip("/").rsplit("/", 1)[1])

    def pr_comment(self, pr: int, body: str) -> None:
        self._gh("pr", "comment", str(pr), "--repo", self.config.repo, "--body-file", "-", stdin=body)

    def pr_edit_body(self, pr: int, body: str) -> None:
        self._gh("pr", "edit", str(pr), "--repo", self.config.repo, "--body-file", "-", stdin=body)

    def pr_to_draft(self, pr: int) -> None:
        self._gh("pr", "ready", str(pr), "--repo", self.config.repo, "--undo", check=False)

    def commit_checks(self, sha: str) -> List[dict]:
        path = f"repos/{self.config.repo}/commits/{sha}/check-runs?per_page=100"
        runs = json.loads(self._gh("api", path).stdout).get("check_runs") or []
        return [
            {"name": r["name"], "bucket": check_bucket(r.get("status"), r.get("conclusion")), "link": r.get("html_url") or ""}
            for r in runs
        ]

    def pr_state(self, pr: int) -> Tuple[str, str]:
        view = self._gh("pr", "view", str(pr), "--repo", self.config.repo, "--json", "state,mergeCommit")
        data = json.loads(view.stdout)
        return data.get("state") or "", ((data.get("mergeCommit") or {}).get("oid") or "")

    def failed_log(self, link: str) -> str:
        match = RUN_LINK_RE.search(link or "")
        if not match:
            return ""
        result = self._gh("run", "view", match.group(1), "--repo", self.config.repo, "--log-failed", check=False, timeout=300)
        return result.stdout[-6000:]

    def merge_pr(self, pr: int, head_sha: str, subject: str, body: str) -> str:
        self._require_dev_line()
        base = self.pr_base(pr)
        if base != self.config.base_branch:
            raise RuntimeError(f"PR #{pr} now targets {base}, not {self.config.base_branch}; refusing to merge")
        args = ["pr", "merge", str(pr), "--repo", self.config.repo, "--squash", "--match-head-commit", head_sha]
        self._gh(*args, "--subject", subject, "--body-file", "-", stdin=body)
        merged_into = self.pr_base(pr)
        if merged_into != self.config.base_branch:
            raise RuntimeError(f"PR #{pr} was retargeted to {merged_into} while merging; check that branch by hand")
        view = ["pr", "view", str(pr), "--repo", self.config.repo, "--json", "mergeCommit", "--jq", ".mergeCommit.oid"]
        return self._gh(*view).stdout.strip()

    def claim_ref(self, number: int) -> str:
        return f"{CLAIM_PREFIX}issue-{number}"

    def create_claim(self, number: int, tree_sha: str, note: str) -> Optional[str]:
        created = self._gh("api", "-X", "POST", f"repos/{self.config.repo}/git/commits", "-f", f"message={note}", "-f", f"tree={tree_sha}")
        commit = json.loads(created.stdout)["sha"]
        path = f"repos/{self.config.repo}/git/refs"
        result = self._gh("api", "-X", "POST", path, "-f", f"ref=refs/{self.claim_ref(number)}", "-f", f"sha={commit}", check=False)
        return commit if result.returncode == 0 else None

    def claim_sha(self, number: int) -> str:
        args = ("api", f"repos/{self.config.repo}/git/ref/{self.claim_ref(number)}", "--jq", ".object.sha")
        result = self._gh(*args, check=False)
        if result.returncode == 0:
            return result.stdout.strip()
        if "HTTP 404" in (result.stderr or "") + (result.stdout or ""):
            return ""
        raise CommandError([self.config.gh_bin, *args], result)

    def holds_claim(self, number: int, sha: str) -> bool:
        return bool(sha) and self.claim_sha(number) == sha

    def claims(self) -> Dict[int, dict]:
        listing = self._gh("api", f"repos/{self.config.repo}/git/matching-refs/{CLAIM_PREFIX}")
        found = {}
        for ref in json.loads(listing.stdout or "[]"):
            name = ref.get("ref", "").rsplit("/issue-", 1)
            if len(name) != 2 or not name[1].isdigit():
                continue
            commit = self._gh("api", f"repos/{self.config.repo}/git/commits/{ref['object']['sha']}", check=False)
            data = json.loads(commit.stdout) if commit.returncode == 0 else {}
            claim = parse_claim(data.get("message") or "", (data.get("author") or {}).get("date") or "")
            found[int(name[1])] = {**claim, "sha": ref["object"]["sha"]}
        return found

    def release_claim(self, number: int, sha: str) -> bool:
        if not self.holds_claim(number, sha):
            return False
        self._gh("api", "-X", "DELETE", f"repos/{self.config.repo}/git/refs/{self.claim_ref(number)}", check=False)
        return True

    def pr_base(self, pr: int) -> str:
        view = ["pr", "view", str(pr), "--repo", self.config.repo, "--json", "baseRefName", "--jq", ".baseRefName"]
        return self._gh(*view).stdout.strip()

    def delete_branch(self, branch: str) -> None:
        self._gh("api", "-X", "DELETE", f"repos/{self.config.repo}/git/refs/heads/{branch}", check=False)

    def open_prs_with_prefix(self, prefix: str) -> List[Tuple[int, str]]:
        listing = self._gh("pr", "list", "--repo", self.config.repo, "--state", "open", "--limit", "100", "--json", "number,headRefName")
        return [(p["number"], p["headRefName"]) for p in json.loads(listing.stdout) if p["headRefName"].startswith(prefix)]

    def close_pr(self, pr: int, comment: str) -> None:
        self._gh("pr", "close", str(pr), "--repo", self.config.repo, "--comment", comment, check=False)


def parse_claim(message: str, author_date: str) -> dict:
    fields = dict(CLAIM_FIELD_RE.findall(message))
    fields["created"] = author_date
    return fields


def check_bucket(status: Optional[str], conclusion: Optional[str]) -> str:
    if status != "completed":
        return "pending"
    return "pass" if conclusion in PASSING_CONCLUSIONS else "fail"


def failed_checks(checks: Sequence[dict]) -> List[dict]:
    return [check for check in checks if check.get("bucket") == "fail"]
