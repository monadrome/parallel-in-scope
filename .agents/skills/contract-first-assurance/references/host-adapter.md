# Host adapter contract

`contract-first-assurance` is portable only when the repository supplies a host
adapter. The skill owns the assurance protocol and artifact schema; the adapter
owns repository-specific commands and policies.

## Required inputs

Before phase one, resolve these values from the repository's configuration or
maintenance instructions:

| Key | Meaning |
| --- | --- |
| `workspace_root` | Root of the repository being verified. |
| `adapter_version` | Version of the repository adapter used for the run. |
| `change_scope` | PR, commit range, or working-tree paths under review. |
| `base_ref` | Comparison base when the change is not supplied explicitly. |
| `build_commands` | Commands for compile checks, target tests, and the full suite. |
| `format_commands` | Required formatting or static checks. |
| `mutation_policy` | Whether mutation testing applies and which command owns its scope. |
| `artifact_root` | Run directory; defaults to `.contract-first-assurance`. |

The adapter may also provide `contract_paths`, `public_api_paths`,
`design_paths`, `source_paths`, and `test_paths` to narrow reads. If a required
value is missing, record the affected phase as `BLOCKED` or `INCONCLUSIVE` and
explain the missing input. Do not infer a Maven, Java, Gradle, npm, or PIT
workflow from the repository name.

## Resolution order

Use the first source that explicitly defines a value:

1. A run-specific adapter passed by the caller;
2. Repository-local assurance configuration;
3. The applicable `AGENTS.md` and project verification documents;
4. A safe generic default only for `artifact_root`.

The host adapter must be read before the first build or test command. Keep its
resolved values in the run manifest and `verification-summary.md` so another
worktree can reproduce the run without relying on the parent checkout. The
manifest records the adapter version, resolved base and head commits, dirty
state, command argv, tool versions, and hashes of copied inputs and outputs.

## Isolation boundary

The adapter may expose command names and public type signatures to the isolated
test generator. It must not expose production method bodies, private fields,
existing tests, prior reports, parent worktrees, or agent conversation history.

## Compatibility

The package version and artifact schema are independent of the host adapter.
Changing a repository command or path is an adapter change; changing candidate
status values, required artifacts, or phase hand-off rules is a package change.

An adapter may also provide `route_gates`, `status_policy`, and
`execution_policy` for a host orchestrator. These values configure the
repository workflow; they do not create a second run manifest or replace the
portable `contract-assurance-run/v1` schema.

For mutation-enabled repositories, `mutation_policy` may define
`accepted_dispositions`. Each disposition names the evidence required before a
raw mutation status may be accepted as an exception. The orchestrator must
retain the raw status and record the disposition, evidence references, and
approver in the run manifest. An accepted exception affects the gate result;
it never changes the mutation XML or raw status. An unresolved timeout,
memory error, worker failure, missing XML, or incomplete log remains
`INCONCLUSIVE`.

The current gate names are `narrow-checks`, `docs`, `regression-tests`,
`related-tests`, `full-suite`, `consumer`, `pit`, `independent-review`,
`race-claims`, `refactoring-review`, `dependency-audit`, and `maven`.
`workspace.head` and `finalHead` identify the same frozen final commit for one
run; a later fix starts a new run instead of mutating an existing manifest.
