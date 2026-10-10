from __future__ import annotations

import dataclasses
import hashlib
import json
import re
from typing import Callable, List, Mapping, Optional, Tuple

TRUSTED_ASSOCIATIONS = frozenset({"OWNER", "MEMBER", "COLLABORATOR"})
PRIVILEGED_PERMISSIONS = frozenset({"admin", "maintain", "write"})
MARKER_RE = re.compile(r"<!-- issue-agent:(?P<kind>[a-z]+) (?P<data>\{.*?\}) -->", re.S)
MENTION_RE = re.compile(r"@(?=[A-Za-z0-9])")
MARKER_PREFIX = "<!-- issue-agent:"


@dataclasses.dataclass
class Comment:
    node_id: str
    database_id: int
    author: str
    association: str
    is_bot: bool
    body: str


@dataclasses.dataclass
class Issue:
    number: int
    title: str
    body: str
    author: str
    association: str
    author_is_bot: bool
    labels: List[str]
    comments: List[Comment]
    state: str = "OPEN"
    ready_actor: str = ""
    ready_authorized: bool = False
    triage_current: bool = False

    @property
    def trusted_author(self) -> bool:
        return is_trusted(self.association, self.author_is_bot)


def is_trusted(association: str, is_bot: bool) -> bool:
    return association in TRUSTED_ASSOCIATIONS and not is_bot


def _login(node: Optional[Mapping]) -> Tuple[str, bool]:
    if not node:
        return "ghost", False
    return node.get("login") or "ghost", node.get("__typename") == "Bot"


def issue_from_node(node: Mapping) -> Issue:
    author, author_is_bot = _login(node.get("author"))
    comments = []
    for raw in (node.get("comments") or {}).get("nodes") or []:
        login, is_bot = _login(raw.get("author"))
        comments.append(Comment(raw["id"], raw["databaseId"], login, raw.get("authorAssociation") or "NONE", is_bot, raw.get("body") or ""))
    ready_actor = ""
    for event in (node.get("timelineItems") or {}).get("nodes") or []:
        if (event.get("label") or {}).get("name") == "agent/ready":
            ready_actor = _login(event.get("actor"))[0]
    return Issue(
        number=node["number"],
        title=node.get("title") or "",
        body=node.get("body") or "",
        author=author,
        association=node.get("authorAssociation") or "NONE",
        author_is_bot=author_is_bot,
        labels=[label["name"] for label in (node.get("labels") or {}).get("nodes") or []],
        comments=comments,
        state=node.get("state") or "OPEN",
        ready_actor=ready_actor,
    )


def render_marker(kind: str, data: Mapping) -> str:
    payload = json.dumps(dict(data), sort_keys=True, separators=(",", ":")).replace("--", "-\\u002d")
    return f"{MARKER_PREFIX}{kind} {payload} -->"


def is_runner_comment(comment: Comment, runner_login: str) -> bool:
    return comment.author == runner_login and MARKER_PREFIX in comment.body


def find_marker(issue: Issue, kind: str, runner_login: str) -> Optional[Tuple[Comment, dict]]:
    found = None
    for comment in issue.comments:
        if comment.author != runner_login:
            continue
        for match in MARKER_RE.finditer(comment.body):
            if match.group("kind") == kind:
                try:
                    found = (comment, json.loads(match.group("data")))
                except ValueError:
                    continue
    return found


def human_comments(issue: Issue, runner_login: str) -> List[Comment]:
    return [c for c in issue.comments if not is_runner_comment(c, runner_login)]


def trusted_view(issue: Issue, runner_login: str) -> dict:
    trusted = issue.trusted_author
    return {
        "title": issue.title if trusted else "",
        "body": issue.body if trusted else "",
        "comments": [
            {"author": c.author, "body": c.body}
            for c in human_comments(issue, runner_login)
            if is_trusted(c.association, c.is_bot)
        ],
    }


def content_hash(issue: Issue, runner_login: str) -> str:
    material = {
        "title": issue.title,
        "body": issue.body,
        "comments": [[c.author, c.body] for c in human_comments(issue, runner_login)],
    }
    return hashlib.sha256(json.dumps(material, sort_keys=True).encode("utf-8")).hexdigest()[:16]


def annotate(issue: Issue, permission_of: Callable[[str], str], runner_login: str) -> Issue:
    issue.ready_authorized = bool(issue.ready_actor) and permission_of(issue.ready_actor) in PRIVILEGED_PERMISSIONS
    marker = find_marker(issue, "triage", runner_login)
    issue.triage_current = bool(marker) and marker[1].get("hash") == content_hash(issue, runner_login)
    return issue


def format_comments(comments: List[Mapping], tagged: bool = False) -> str:
    if not comments:
        return "(none)"
    blocks = []
    for comment in comments:
        tag = f" ({comment['association']})" if tagged else ""
        blocks.append(f"--- @{comment['author']}{tag}\n{comment['body'].strip()}")
    return "\n\n".join(blocks)


def all_comments(issue: Issue, runner_login: str) -> List[dict]:
    return [{"author": c.author, "association": c.association, "body": c.body} for c in human_comments(issue, runner_login)]


def strip_markers(text: str) -> str:
    return MARKER_RE.sub("", text).strip()


def sanitize(text: str, limit: int = 4000) -> str:
    cleaned = text.replace("<!--", "&lt;!--").replace("-->", "--&gt;")
    return MENTION_RE.sub("@​", cleaned)[:limit]
