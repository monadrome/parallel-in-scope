Continue issue #$number on branch `$branch`. The previous round ended and the $kind step reported problems:

$details

For each problem:

1. Reproduce it against the current working tree before acting.
2. Mark it `retained`, `downgraded`, or `rejected`. Reject only with evidence (a test, a trace, or a contract clause) and say what the evidence shows.
3. Fix every retained problem. A behavior defect gets a regression test that fails without the fix and passes with it, covering every site with the same defect.

The unattended overrides from the first round still apply: commit locally, never push, never call GitHub, leave the working tree clean, and finish with a green `mvn -o test`. Follow `verification/pit.md` to re-evaluate PIT scope and freshness after production, test, helper, or configuration changes.

Return the full structured report again, describing the whole branch rather than only this round, and record one `dispositions` entry per problem above.
