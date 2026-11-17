# Task identity contract

Attribution identity for observations and the executor wait graph: two disjoint identity spaces,
each a label the library mints and records, never a handle for reading results. Landed from
`proposal:P0039`; the rejected-alternatives section keeps the superseded shapes.

## Spaces and minting

- A unit id (`TaskId.Unit`) names one admitted execution unit: a single task, a `Par.map` batch,
  a group member, or a terminal combine. `MultiTaskContext` MUST mint exactly one per unit at
  resolution.
- A group-run id (`TaskId.GroupRun`) names one run of a task group: a coordination scope with no
  execution of its own. `TaskGroup` MUST mint one per run; two runs of one declaration MUST NOT
  share an id.
- Ids MUST remain process-local diagnostics: an id MUST NOT be persisted, parsed, or used as a
  wire format, and `toString()` is a rendering, not an exchange format.

## Disjointness

- The two spaces MUST be disjoint in typing and in equality. The mechanism is the closed `TaskId`
  hierarchy: the base constructor is private, `Unit` and `GroupRun` are the only kinds, and the
  runtime class carries the kind into `equals`/`hashCode` together with the label. A third kind
  MUST NOT be added without amending this contract.
- A slot typed for one kind MUST NOT accept the other. `TaskNode.id` is a unit id because only
  units enter the executor wait graph; a group run holds no thread and MUST NOT become a graph
  node.
- One observation record MUST name at most one identity space: a member or batch record carries
  a unit id, a group's own summary carries only a group-run id, and a derived view carries
  neither.

## Equality as reconciliation

- Equality MUST cover kind and label, so a user-minted id compares equal to a library-recorded id
  of the same kind and label. Fabrication for comparison is the documented use of the factories;
  reference equality would make the observation record unfalsifiable.
- Ids are attribution labels only: an id MUST NOT be used to read results, drive behavior, or
  prove that a unit ever ran.

## Rejected alternatives

- Phantom kind tags plus a private `Kind` enum (the shape before `proposal:P0039`): one dichotomy
  stated by two mechanisms that can drift — a factory returning `TaskId<TaskUnit>` while minting
  `Kind.TASK_GROUP_RUN` compiled cleanly — and an ongoing cost of two empty public interfaces
  plus generic noise in every identity signature.
- Kind-enum-only single class: value-level separation intercepts a wrong kind only when `equals`
  runs; a wrong-kind id can flow silently into a slot that never compares it and surface far
  from the mistake, for example a group-run id filed as a graph node id.
- Two independent top-level classes (`UnitId`/`GroupRunId`): loses the common supertype that
  heterogeneous id collections, uniform rendering, and records carrying either space rely on,
  and duplicates label validation and equality logic.
