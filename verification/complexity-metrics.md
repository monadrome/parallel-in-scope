# Complexity metrics gray evaluation

The complexity collector is an evaluation input to `refactoring-review`, not a build or quality
gate. A plan records the stage as `gray-evaluation` and writes the evidence under
`evaluation.complexity`.

The current scope is core production Java under `src/main/java/io/github/monadrome/parallelinscope/`;
the `queue` package is excluded. The collector compares the merge-base and the head, and emits
method candidates when absolute or base/head delta thresholds are met. Candidates are injected
into the structure lens only.

Reviewers must treat the evidence as an attention hint. A high value can be intrinsic to an
algorithm, exhaustive dispatch, or a concurrency protocol. The detector may reject the candidate;
an accepted finding still needs a named behavior-preserving move and guarding tests.

The gray stage is complete only when the evidence is present and interpretable. It does not mean
that a change passed because no candidate was emitted. Future AST collection or additional risk
signals require a new evaluation round and must preserve the non-gating boundary.
