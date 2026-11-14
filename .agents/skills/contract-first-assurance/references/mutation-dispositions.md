# Mutation dispositions

Mutation verification has two independent outputs:

1. the raw status emitted by the mutation runner; and
2. the assurance conclusion after evidence review.

Never change the raw status in XML, copied reports, or metrics. A raw
`TIMED_OUT` may be accepted as an exception only when the host adapter allows
the disposition and the run records all of the following:

- the same mutant reproduces the timeout in a focused rerun;
- the unmutated production tests pass;
- XML, command logs, and process-heartbeat evidence are complete;
- the source-level invariant or interleaving that the mutant breaks is stated;
- no worker, memory, or environment failure explains the timeout instead; and
- a maintainer or independent reviewer approves the classification.

Use this representation in `run-manifest.json` and `report.md`:

```json
{
  "rawStatus": "TIMED_OUT",
  "disposition": "EXPECTED_MUTATION_LIVENESS",
  "accepted": true,
  "evidence": ["logs/pit.log", "inputs/pit-audit.md"],
  "approvedBy": "maintainer"
}
```

The final assurance gate may be `pass` when every exception is accepted and every other
required check passes. This does not rewrite a repository-specific PIT report
that separately retains an `inconclusive` raw-run label; it adds the assurance
layer needed to express that the remaining raw timeouts are understood and
accepted. Reports should show this as `PASS WITH ACCEPTED
EXCEPTIONS` for readers while storing the normalized gate value `pass` for
machine checks. An unresolved or weakly evidenced timeout remains
`inconclusive`; increasing the timeout or relying on Maven's exit code is not
an acceptance disposition.
