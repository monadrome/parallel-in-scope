# Lens M: Modules And Seams

Smells that only show across files: where behavior lives, which data travels together, and whether
the change's modules are deep or shallow. This lens always receives the whole diff and is never
sharded. Default tier `T1`; a move that changes a public signature or adds a type is `T2`.

Repository structure fixed by higher sources is not a finding (R3): the root package co-locates the
public API with the package-private kernel on purpose; do not propose subpackages, public bridge
types, or splitting the `queue` package out.

### M01 Feature envy
- Smell: a method the change added that reads another type's data more than its own.
- Move: Move Function onto the data it envies.
- mechanizable: no

### M02 Data clump
- Smell: the same few fields or parameters travel together through several signatures the change
  touches.
- Move: Introduce Parameter Object (`T2` when it adds a public type).
- mechanizable: no

### M03 Shotgun surgery
- Smell: one logical change forced scattered, similar edits across many files of the diff.
- Move: Move Function or Combine Functions into Class so what changes together lives together.
- mechanizable: no

### M04 Divergent change
- Smell: one file in the diff is edited for several unrelated reasons.
- Move: Split Phase or Extract Class along the reasons.
- Suppress: an AGENTS.md or contract update that accompanies its implementation by rule.
- mechanizable: no

### M05 Duplication across files
- Smell: the same shape appears in two or more files of the change, or the change adds another copy
  of a helper that already exists elsewhere in the repository.
- Move: reuse the existing helper or Extract Function into the type that owns the concept.
- mechanizable: no

### M06 Shallow module
- Smell: a type or method the change added whose interface is nearly as complex as its body.
  Apply the deletion test: would deleting it concentrate complexity (keep it) or merely move it
  (shallow)?
- Move: Inline Class or Inline Function into its single caller.
- mechanizable: no

### M07 Leaking seam
- Smell: a caller in the change depends on another type's internal state or ordering that its
  declaration does not promise.
- Move: Encapsulate the state behind a method whose Javadoc states the promise.
- mechanizable: no

### M08 Refused bequest
- Smell: a subclass or implementation the change added overrides or ignores most of what it
  inherits.
- Move: Replace Superclass with Delegate.
- Suppress: overrides that a JDK or Guava base class requires (`AbstractFuture` hooks).
- mechanizable: no
