# English Documentation

> This repository page follows `0.4.0-SNAPSHOT`. For the released `0.3.0` line, use the
> [stable online documentation](https://monadrome.github.io/parallel-in-scope/).

Start with the full user guide. Use the reference pages for exact contracts and the migration guides when upgrading.

## User Documentation

| Document | Role |
|---|---|
| [5-minute quickstart](quickstart.md) | First successful batch in a new Maven project |
| [Full user guide](user-guide.md) | Configuration, public APIs, runtime behavior, and advanced features |
| [v0.4 migration guide](migration-v0.4.md) | Typed task keys, aggregate result reads, and runtime diagnostics on the development line |
| [v0.3 migration guide](migration-v0.3.md) | The one-shot task-group chain and runtime contract changes from `0.2.x` |
| [v0.2 migration guide](migration-v0.2.md) | Breaking changes from the `0.1.x` API |
| [Demo project](https://github.com/monadrome/parallel-in-scope/blob/dev/v0.4.0/demo/README.en.md) | Runnable examples and build commands |
| [Demo documentation map](https://github.com/monadrome/parallel-in-scope/blob/dev/v0.4.0/demo/docs/en/README.md) | Documentation map for the example catalog (the articles themselves are in Chinese) |

## API and Contracts

| Document | Role |
|---|---|
| [Cooperative cancellation](reference/cooperative-cancellation.md) | Checkpoints, exceptions, and cancellation propagation |
| [Nullability annotations](reference/nullability-annotations.md) | Public API and internal annotation rules |

## Implementation and Design

| Document | Role |
|---|---|
| [Design philosophy](design/philosophy.md) | Design decisions and boundaries |
| [Idea Graveyard](design/idea-graveyard.md) | Intentionally unsupported features |

## Tests and Cases

| Document | Role |
|---|---|
| [Testing code that uses the library](testing/testing-your-code.md) | Deterministic timeout, cleanup, budget, and result tests |
| [TaskBatchResult test design](testing/task-batch-result-report-test-design.md) | Stable report contracts and concurrency assertions |
| [AI concurrency review case study](case-studies/ai-concurrency-review-false-positive.md) | A false-positive review and how to verify it |

## Documentation Roles

- The primary guide describes supported user workflows.
- API/contract references define current contracts.
- Demo articles explain problems and examples; they do not override the API contract.
- Internal documents may evolve with the implementation.
