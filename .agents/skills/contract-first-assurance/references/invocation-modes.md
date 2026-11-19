# Invocation modes

`contract-first-assurance` is the workflow entry. Autonomous invocation is allowed by default;
its sub-flows allow only explicit invocation by default.

## The autonomously invocable entry

`SKILL.md` does not set `disable-model-invocation: true`, and keeps this in
`agents/openai.yaml`:

```yaml
policy:
  allow_implicit_invocation: true
```

This lets the model decide from the description whether to enter the workflow, while the user
can still use `/contract-first-assurance`.

## The manual-only entry

If a repository wants the entry to be user-manual, change both places together:

1. add `disable-model-invocation: true` to the entry `SKILL.md`'s frontmatter;
2. change the entry `agents/openai.yaml` to:

```yaml
policy:
  allow_implicit_invocation: false
```

The two configurations must stay consistent. Changing only one makes Claude Code and Codex
behave differently on invocation.

## Sub-flows

`contract-extract`, `isolated-test-generation`, `contract-diff`, and `assurance-report` have
autonomous invocation off on both platforms. They are read explicitly by the entry per phase,
and can also be debugged by the user typing the corresponding slash or `$skill` commands
directly.
