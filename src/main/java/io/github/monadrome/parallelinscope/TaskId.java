package io.github.monadrome.parallelinscope;

import java.util.Objects;

/**
 * Immutable identity of one admitted work unit, typed by the kind of unit it names.
 *
 * <p>The type parameter {@code K} is a phantom tag: it carries no data and appears in no
 * signature, but it makes the identity spaces disjoint at compile time. A {@code
 * TaskId<TaskUnit>} names a task unit — a single task, a {@code Par.map} batch, a group member,
 * or a terminal combine — and a {@code TaskId<TaskGroupRun>} names one run of a task group.
 * Neither converts to the other, so a group id can no longer be filed among unit ids in a map
 * that was meant to hold units.
 *
 * <p>Equality is on the kind <em>and</em> the label, so {@code TaskId.unit("1")} and {@code
 * TaskId.group("1")} are never equal even though they share a label. Instances are therefore
 * usable as map keys and as {@link TaskNode} identity. Both parts are process-local and for
 * diagnostics only: an id must never be persisted, parsed, or used as a wire format, and {@link
 * #toString()} is a rendering rather than an exchange format.
 *
 * <p>The library mints ids from one sequence per kind. The factories build an id with a given
 * label, for example to compare against an id the library recorded; an id minted that way carries
 * no claim that any unit ever ran, and the factory is the single validation boundary — a label is
 * never null and never blank, so no consumer repeats that check.
 *
 * @param <K> the kind of unit this id names: {@link TaskUnit} or {@link TaskGroupRun}
 * @since 0.3.0
 */
public final class TaskId<K> {

    /** The identity spaces this library mints; part of the value so cross-kind ids never compare equal. */
    private enum Kind {
        TASK_UNIT("unit-"),
        TASK_GROUP_RUN("group-");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }
    }

    private final Kind kind;
    private final String label;

    private TaskId(Kind kind, String label) {
        this.kind = kind;
        this.label = label;
    }

    /**
     * Creates the identity of a task unit.
     *
     * @param label the process-local label distinguishing this unit from other units
     * @return the unit id, equal only to another {@code TaskId} of this kind and label
     * @throws NullPointerException if {@code label} is null
     * @throws IllegalArgumentException if {@code label} is blank
     */
    public static TaskId<TaskUnit> unit(String label) {
        return new TaskId<>(Kind.TASK_UNIT, Validation.requireName(label, "Task unit id"));
    }

    /**
     * Creates the identity of a task-group run.
     *
     * @param label the process-local label distinguishing this run from other runs
     * @return the group id, equal only to another {@code TaskId} of this kind and label
     * @throws NullPointerException if {@code label} is null
     * @throws IllegalArgumentException if {@code label} is blank
     */
    public static TaskId<TaskGroupRun> group(String label) {
        return new TaskId<>(Kind.TASK_GROUP_RUN, Validation.requireName(label, "Task group id"));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TaskId)) {
            return false;
        }
        TaskId<?> id = (TaskId<?>) other;
        return kind == id.kind && label.equals(id.label);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(kind) + label.hashCode();
    }

    /** Diagnostics only; a rendering, never a persisted or exchanged format. */
    @Override
    public String toString() {
        return kind.prefix + label;
    }
}
