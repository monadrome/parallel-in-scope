package io.github.monadrome.parallelinscope;

import com.google.errorprone.annotations.Immutable;

/**
 * Immutable identity of one admitted work unit or one task-group run.
 *
 * <p>The hierarchy is closed: the base constructor is private, so {@link Unit} and {@link
 * GroupRun} are the only kinds, and the two identity spaces are disjoint at compile time. A {@code
 * Unit} id names a task unit — a single task, a {@code Par.map} batch, a group member, or a
 * terminal combine — and a {@code GroupRun} id names one run of a task group. Neither converts to
 * the other, so maps typed for one kind reject ids of the other kind at compile time.
 *
 * <p>Equality is on the kind and the label: the runtime class carries the kind, so a unit id and
 * a group-run id are never equal even when they share a label. Instances are therefore usable as
 * map keys. Both parts are process-local and for diagnostics only: an id must never be persisted,
 * parsed, or used as a wire format, and {@link #toString()} is a rendering rather than an exchange
 * format.
 *
 * <p>The factories build an id with a given label, for example to compare against an id the
 * library recorded; an id minted that way carries no claim that any unit ever ran, and a label is
 * never null or blank.
 */
@Immutable
public abstract class TaskId {

    private final String prefix;
    private final String label;

    private TaskId(String prefix, String label) {
        this.prefix = prefix;
        this.label = label;
    }

    /**
     * Creates the identity of a task unit.
     *
     * @param label the process-local label distinguishing this unit from other units
     * @return the unit id, equal only to another unit id with the same label
     * @throws NullPointerException if {@code label} is null
     * @throws IllegalArgumentException if {@code label} is blank
     */
    public static Unit unit(String label) {
        return new Unit(Validation.requireName(label, "Task unit id"));
    }

    /**
     * Creates the identity of a task-group run.
     *
     * @param label the process-local label distinguishing this run from other runs
     * @return the group-run id, equal only to another group-run id with the same label
     * @throws NullPointerException if {@code label} is null
     * @throws IllegalArgumentException if {@code label} is blank
     */
    public static GroupRun group(String label) {
        return new GroupRun(Validation.requireName(label, "Task group id"));
    }

    @Override
    public final boolean equals(Object other) {
        return other instanceof TaskId && getClass() == other.getClass() && label.equals(((TaskId) other).label);
    }

    @Override
    public final int hashCode() {
        return 31 * getClass().hashCode() + label.hashCode();
    }

    /** Diagnostics only; a rendering, never a persisted or exchanged format. */
    @Override
    public final String toString() {
        return prefix + label;
    }

    /**
     * The identity of a task unit: a single task, a {@code Par.map} batch, a group member, or a
     * terminal combine — anything with its own execution.
     */
    @Immutable
    public static final class Unit extends TaskId {
        private Unit(String label) {
            super("unit-", label);
        }
    }

    /**
     * The identity of one task-group run: a coordination scope with no execution of its own, so
     * its identity is never filed among unit ids.
     */
    @Immutable
    public static final class GroupRun extends TaskId {
        private GroupRun(String label) {
            super("group-", label);
        }
    }
}
