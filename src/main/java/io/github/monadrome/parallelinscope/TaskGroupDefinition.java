package io.github.monadrome.parallelinscope;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Frozen, package-private structure of one group run, produced by {@code GroupDraft#freeze()} at the
 * {@code submitAll()} boundary.
 *
 * <p>It is not a public type any more: a group is declared and submitted in one fluent chain, so
 * there is nothing for a caller to reuse or hold. What remains is the shape the preparation kernel
 * needs — the group name, the forced choice between an explicit timeout and an inherited one, the
 * optional close grace, the plain member slots in declaration order, and the optional terminal
 * combine slot. Each slot carries its name, the {@link Par} resolved at declaration time, immutable
 * {@link TaskOptions}, and the {@link TypeToken} that the member's runtime type check and every
 * later typed lookup compare against.
 *
 * <p>Members and the combine arrive as two separate arguments rather than one list tagged by kind,
 * so "at most one combine" is a property of the structure instead of a rule this constructor has to
 * enforce. The declaration layer holds them separately already, and nothing reads them as a single
 * sequence: a tagged list would only be flattened on the way in and split apart again on arrival.
 *
 * <p>The owning runtime is not carried here: the kernel already receives it as an explicit
 * argument, and the draft that builds this structure is bound to that same runtime at creation, so
 * a stored copy could only ever agree with the argument.
 *
 * <p>The instance is created only after the declaration is complete and is never mutated, so the
 * kernel can read it without synchronization.
 */
final class TaskGroupDefinition {
    private final String name;
    private final @Nullable Duration timeout;
    private final @Nullable Duration closeGrace;
    private final ImmutableList<Slot> members;
    private final @Nullable Slot combine;

    TaskGroupDefinition(
            String name,
            @Nullable Duration timeout,
            @Nullable Duration closeGrace,
            List<Slot> members,
            @Nullable Slot combine) {
        this.name = name;
        this.timeout = timeout;
        this.closeGrace = closeGrace;
        this.members = ImmutableList.copyOf(members);
        this.combine = combine;
    }

    String name() {
        return name;
    }

    /** The explicit group timeout; null means the enclosing scope's deadline is inherited. */
    @Nullable
    Duration timeout() {
        return timeout;
    }

    /** The explicit close grace used by {@link TaskGroup#close()}; null means it is derived. */
    @Nullable
    Duration closeGrace() {
        return closeGrace;
    }

    /** The plain members in declaration order. */
    List<Slot> members() {
        return members;
    }

    /** The terminal combine slot, or null when the group declares none. */
    @Nullable
    Slot combineSlot() {
        return combine;
    }

    /**
     * One declared slot: name, the {@link Par} it runs on, options, and declared result type. A slot
     * carries no kind tag: it is a member or the combine by virtue of which argument it arrived in.
     */
    static final class Slot {
        final String name;
        final Par par;
        final TaskOptions options;
        final TypeToken<?> type;

        Slot(String name, Par par, TaskOptions options, TypeToken<?> type) {
            this.name = name;
            this.par = par;
            this.options = options;
            this.type = type;
        }
    }
}
