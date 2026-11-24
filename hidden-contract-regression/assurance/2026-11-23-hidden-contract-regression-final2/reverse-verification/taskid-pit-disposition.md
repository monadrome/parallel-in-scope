# Final-head TaskId PIT disposition

- Source revision: `4a16413aa43eda10a4e0b00e0b91fa23e8896574`; no history input.
- Build JDK: OpenJDK 25.0.4.1.
- Production scope: `io.github.monadrome.parallelinscope.TaskId*`.
- Test scope: `TaskIdTest*` and `TaskIdentityTest*`.
- Mutators: `CONDITIONALS_BOUNDARY`, `NEGATE_CONDITIONALS`, `INCREMENTS`, `INVERT_NEGS`, `NULL_RETURNS`, `FALSE_RETURNS`, `TRUE_RETURNS`, `PRIMITIVE_RETURNS`, `EMPTY_RETURNS`, `VOID_METHOD_CALLS`.
- Raw result: 8 mutations, 7 `KILLED`, 1 `SURVIVED`; no other raw statuses. Complete XML is `taskid-pit-mutations.xml`; command output is `logs/006-pit.stdout.log`.
- Survivor disposition: equivalent. `TaskId.hashCode()` was changed to constant zero by `PRIMITIVE_RETURNS`. Equal objects still have equal hashes; unequal objects may collide. The public contract does not promise hash distribution or performance.
- Overall PIT gate: pass, with the equivalent-survivor disposition. This is not an accepted timeout exception and `acceptedExceptions` remains empty.
