# SpotBugs audit

SpotBugs is an opt-in static audit, not part of the default build. Error
Prone/NullAway already gate the default compile; SpotBugs is retained for the
detector families Error Prone does not implement:

- cross-thread access to ordinary fields (`AT_STALE_THREAD_WRITE_OF_PRIMITIVE`,
  `AT_NONATOMIC_64BIT_PRIMITIVE`)
- annotation versus runtime-contract mismatches
  (`NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE`)

The default CI build does not run the profile. A full scan of the current tree
produces 29 findings: 2 high, 20 medium, 7 low. The high findings are both
`NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE` on defensive null checks;
they are reviewed false positives and listed in
[`spotbugs-exclude.xml`](spotbugs-exclude.xml). The medium findings are
`CT_CONSTRUCTOR_THROW` (finalizer-attack heuristic; this library has no
finalizers), `EI_EXPOSE_REP` (intentional result/monitor views),
`AT_*` on the weak-consistency `QueueSpliterator`, and one benign
`TaskBatch.reportString()` `getMessage()` null. None is a confirmed defect.

## Run

High-priority gate, using the reviewed baseline:

```bash
mvn -Pspotbugs -Dmaven.test.skip=true verify
```

Full report without failing the build:

```bash
mvn -Pspotbugs -Dspotbugs.threshold=Low -Dmaven.test.skip=true spotbugs:spotbugs
```

The HTML/XML report is under `target/spotbugs.html` and
`target/spotbugsXml.xml`. Triage a new finding before adding an exclusion; the
baseline file is for reviewed false positives only, not for silencing whole
detector families.

## Why it is not a required gate

Error Prone covers the compile-time bug patterns this library relies on. The
SpotBugs high-priority findings on the current tree are the two reviewed
false positives above, so making the profile mandatory would add a build step
without a confirmed incremental defect. Keep the profile for periodic audits and
promote it to CI only after a scan finds a real high-priority defect that Error
Prone missed.
