# AGENTS.md

Governs `blocking-queues/src/main/java/io/github/monadrome/queues/` and
`blocking-queues/src/test/java/io/github/monadrome/queues/`.

## Package scope

This module is a standalone general-purpose queue library. It has no dependency
on `parallel-in-scope`, structured concurrency, cancellation, task context, or
executor ownership. Users instantiate these queues and install them on their own
`ThreadPoolExecutor` or other `BlockingQueue` consumer.

- `DrainingBlockingQueue` implements the lifecycle contract in
  `design/draining-queue-contract.md`.
- `ResizableBlockingQueue` provides a dynamically adjustable bounded FIFO queue.
- Dependencies are limited to the JDK, Guava `Monitor`, and JSpecify.

## Change rules

- Read `design/draining-queue-contract.md` before changing observable behavior of
  `DrainingBlockingQueue`.
- Keep the JDK `LinkedBlockingQueue` parity comments and Java 8 API baseline.
- Do not add a dependency on the `parallel-in-scope` core module.
- Queue behavior changes require targeted tests, the full reactor test suite, and
  the concurrency/race checks required by the repository root instructions.
