<!--
Thanks for contributing! First time here? Please read CONTRIBUTING.md first:
external contributions beyond the trivial list need an issue before a pull
request.
-->

## Summary

<!-- What changed and why — explain the motivation, not the file list.

If this PR adds or changes a public API or a documented contract, the summary
must carry the concrete rationale: the best code a user can write today, the
same code with the change applied, and the failure mode the change removes.
When no issue is linked, this description is the only record of the decision —
make it self-contained. -->

## Related issue

<!--
`Closes #NN` when this PR completes the issue, `Refs #NN` when it is one step
of it. Issues are opt-in (see AGENTS.md, "Issue Tracking"): maintainer-driven
work may omit this, but then the Summary above must stand alone as the record.
-->

Closes #

## Verification

<!-- List the commands you actually ran and their results. Documentation-only
changes don't need Java tests — delete the rows that don't apply and say so. -->

| Command | Result |
|---|---|
| `mvn test` | |
| `mvn spotless:apply` | |

## Breaking changes

<!--
The 0.x line accepts breaking changes with a documented rationale. If this PR
renames or alters a public API or a documented contract, update
docs/en/migration-v0.3.md and docs/zh/migration-v0.3.md in the same PR.
-->

- [ ] No breaking changes
- [ ] Breaking changes, rationale in the summary; migration notes updated
