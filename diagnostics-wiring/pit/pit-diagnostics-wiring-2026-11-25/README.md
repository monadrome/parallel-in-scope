# Focused PIT: diagnostics-wiring

Scope: changed classes Announcements*, ExecutorWaitGraph*, ParRuntime(+nested), Par(+nested),
TaskGroup(+nested), WorkloadFit*; tests for announcements, executor waits, inert enqueue
refusal, ParRuntime*, ParSubmit, ParMap*, TaskGroup*, TaskType*, WorkloadFit*, GroupDraftLifecycle.
Mutators: core set including VOID_METHOD_CALLS. JDK 25. No history input.

head 27ff7b56: 572 generated, KILLED 517, SURVIVED 29, NO_COVERAGE 19, TIMED_OUT 7.
base f093bb12: 576 generated, same scope and command, non-killed set identical except
timing variance on unchanged methods (TaskGroup#start negated conditional TIMED_OUT at head,
KILLED at base; ParRuntime#enterAdmission one mutant TIMED_OUT at base, KILLED at head;
TaskGroup#classifyMemberTermination null return NO_COVERAGE at base, KILLED at head).

All 12 mutants on lines changed by this delivery are KILLED: every trackAdmission call removal
(Par x3, TaskGroup), both halves inside both trackAdmission overloads, the empty-list guard,
the announcements executor default, ParRuntime#announce delivery, Announcements#deliver execute.
Every SURVIVED, NO_COVERAGE, and TIMED_OUT entry is on an unchanged line and was present at base
with the same class, method, mutator, and description; they are pre-existing and not dispositioned
by this delivery.
