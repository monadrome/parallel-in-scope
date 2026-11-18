"""Shared Markdown visibility and proposal-identity primitives for the design gates.

Both ``check-design-routes.py`` and ``check-proposal.py`` read proposal files through the
same rules: HTML comments and code fences hide content, the abstract section heading is
``## 摘要`` or ``## Abstract``, and proposal filenames are ``P<NNNN>-<kebab-slug>.md``.
Keeping the primitives here means one edit changes both gates; the gates' messages still
diverge where their granularity differs by design.
"""

import re

NAME_RE = re.compile(r"^P(\d{4})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$")
SUMMARY_RE = re.compile(
    r"^## (?:摘要|Abstract)\s*\n(.*?)(?=^#{1,2} |\Z)",
    re.MULTILINE | re.DOTALL,
)
FENCE_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
COMMENT_RE = re.compile(r"<!--.*?(?:-->|\Z)", re.DOTALL)


def unfenced_lines(text: str):
    """Visible lines with HTML comments and code fences removed, 1-indexed."""
    fence = None
    for number, line in enumerate(COMMENT_RE.sub("", text).splitlines(), 1):
        match = FENCE_RE.match(line)
        if fence is not None:
            if (match and match[1][0] == fence[0] and len(match[1]) >= len(fence)
                    and not match[2].strip()):
                fence = None
            continue
        if match:
            fence = match[1]
            continue
        yield number, line
