# Reviewer adapters

Every adapter implements one interface: build an argv plan, run it under the shared process supervisor,
and parse the final text into findings. The supervisor provides detached process groups, streamed
stdout/stderr logs, hard timeouts, SIGTERM then SIGKILL, and immutable per-attempt records.

## Command matrix

| Agent | Command (production default) | Model | Effort | Notes |
|---|---|---|---|---|
| `codex` | `codex exec -C <snapshot> --sandbox read-only --add-dir <agent-output> --output-last-message <file> [--model M] [-c model_reasoning_effort=E] [extraArgs] <prompt>` | `--model` | `-c model_reasoning_effort=` | Read-only sandbox. Last-message file is preferred; stdout is a fallback. An explicit `tokens used` footer supplies `totalTokens` only. |
| `claude` | `claude [--model M] [extraArgs] -p <prompt>` | `--model` | recorded only | No `--dangerously-skip-permissions` or equivalent. |
| `kimi` | `kimi --output-format stream-json [--model M] [extraArgs] -p <prompt>` | `--model` | recorded only | Never passes `--auto`; it conflicts with non-interactive `-p`. All flags precede `-p <prompt>` so `-p` consumes the prompt, not the next option. The stream is parsed for text events. |
| `ocr` | `ocr review --commit <head> --format json --audience agent --background-file <context> [--effort E] [--model M] [extraArgs]` | `--model` if set | `--effort` | OCR reviews the commit's parent range. Live runs with a custom `--base` are approximate for this adapter. Its `summary.input_tokens/output_tokens/total_tokens` are recorded. |
| `kiro` | `kiro-cli chat --v3 --no-interactive --trust-all-tools [--model M] [extraArgs] <prompt>` | `--model` | recorded only | Non-interactive mode requires `--trust-all-tools`; the flag is confined to the adapter and documented as a risk. |
| `fixture` | `node <fixture-worker> <fixture.json>` | provenance only | provenance only | Tests and local end-to-end only. Never selected automatically. |

All commands are spawned without a shell, with the snapshot as cwd and a per-attempt writable
`agent-output/<agent>/attempt-N/` directory. The snapshot is checked with `git status` before and after
the process; a modified snapshot fails that attempt.

Reviewer isolation is a benchmark trust boundary, not an OS sandbox. Gold lives outside the run
directory, the historical context withholds the fix commit, and agents are instructed not to read
adjudication or other reviewers' output. A deliberately malicious CLI can still traverse the filesystem
or inspect the shared git object database; do not treat this tool as a hostile-code sandbox.

User-supplied `extraArgs` are appended as separate argv elements. The following are rejected because
they bypass the benchmark's safety posture:

- any `--dangerously*` flag
- `--yolo`
- `--allow-all-tools`
- `--trust-all-tools` (the Kiro adapter owns this flag)
- `--auto`
- `--full-auto`

`system.command` may point to an absolute binary or a command on `PATH`. `system.version` pins the
recorded version; otherwise the adapter runs `<command> --version` and stores the first line. A missing
command is a setup error for an explicitly selected system. Auto-selection silently skips missing real
CLIs but errors if none are found.

## Output parsing

Adapters produce text, then a common parser extracts the last review-shaped JSON object. Accepted
shapes:

- the exact prompt schema: `{"summary": "...", "findings": [...]}`
- a bare findings array
- a fenced `json` block
- a nested `review`, `result`, `data`, or `output` wrapper
- Kimi `stream-json` lines, whose `result`/`text`/`content`/message text is concatenated before parsing

An explicit empty `findings: []` is a successful empty review. Empty stdout, prose-only output, invalid
JSON, an object with no findings array, or an invalid finding is a failed attempt. Usage is recorded
only from explicit output: OCR's manifest/summary token fields, Codex's `tokens used` footer, or an
explicit `usage`/`summary` object in a review response. Undefined input/output/total/cost/currency
values stay null; the tool never estimates usage or derives a total from partial counts. The raw stdout and
stderr logs remain on disk; failure records have `findings: null`, never `[]`.

Confidence and severity are normalized deterministically (see `docs/protocol.md`). Findings beyond
`config.budget` are dropped from the normalized record and counted in `budgetDropped`; the raw response
is unaffected.

## Fixture adapter

The fixture adapter runs a real detached Node process, so it exercises the same timeout/process-group
logic as production adapters. Its fixture JSON is:

```json
{
  "summary": "fixture review",
  "findings": [
    {
      "file": "src/math.ts",
      "line": 1,
      "title": "add subtracts",
      "trigger": "add(2,3)",
      "consequence": "returns -1",
      "evidence": "src/math.ts:1",
      "severity": "P1",
      "confidence": 0.9,
      "suggestedFix": "use a+b"
    }
  ]
}
```

Additional fields for local failure testing:

| Field | Effect |
|---|---|
| `delayMs` | Sleep before producing output; used with a short review timeout. |
| `spawnChild` | Spawn a long-lived grandchild and write its PID to `childPidFile`. Verifies group kill. |
| `childPidFile` | Absolute PID file path for the grandchild. |
| `malformed` | Print invalid JSON; the step fails as a parse error. |
| `exitCode` | Exit with a nonzero code; the step fails even if stdout looks valid. |
| `modifyFile` / `modifyContent` | Write a file in the snapshot to verify the dirty-worktree guard fails the attempt. |
| `usage` | Object with `input_tokens`, `output_tokens`, `total_tokens`, `cost`, `currency`; verifies usage propagation. |

A systems entry:

```json
{
  "agent": "fixture",
  "fixture": "/absolute/or/relative/fixture.json",
  "provider": "fixture",
  "model": "fixture-model",
  "effort": "none"
}
```

Relative fixture paths are resolved against the current working directory when the run is created and
stored as absolute in `run.json`, so resume works from another directory.

## Adding an adapter

1. Implement `ReviewAdapter`: `identity`, `buildPlan`, `parse` in `src/adapters/`.
2. Register it in `src/adapters/registry.ts` and allow its agent name in configuration.
3. Keep production defaults free of dangerous bypass flags. If a CLI requires a trust flag for
   non-interactive use, document it here and confine it to the adapter.
4. Add a fixture-based or parser-level test; do not make the test suite depend on a real model.
5. Record model/effort in `SystemIdentity` even when the CLI has no flag for it. Provenance is part of
   the measurement; silently omitting it makes results unattributable.

## Timeout behavior

The supervisor starts the command in a new process group, opens per-attempt stdout/stderr files in
exclusive-create mode, and writes the process output straight to those files. On timeout it sends
`SIGTERM` to the group, waits `killGraceSeconds`, then sends `SIGKILL`. If the group ignores signals, a
final settle timer reports `timed_out` rather than hanging the run. A timeout is a terminal attempt
status, not an empty review; resume may retry with a new attempt while the old logs and record remain.
