package io.github.monadrome.parallelinscope;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Frozen, package-private structure of one group run, produced by {@code GroupDraft#freeze()} at
 * the {@code submitAll()} boundary.
 *
 * <p>The preparation kernel receives the group name, the forced choice between an explicit timeout
 * and an inherited one, the optional close grace and request-cancellation input, the plain member
 * slots in declaration order, and the optional terminal combine slot. Each slot carries its name,
 * the {@link Par} resolved at declaration time, immutable {@link TaskOptions}, and the {@link
 * TaskKey} used for typed result lookup. No runtime type information is retained.
 *
 * <p>The instance is created only after the declaration is complete and is never mutated, so the
 * kernel can read it without synchronization.
 */
final class TaskGroupDefinition {
    /*
     * Members and the combine arrive as two separate arguments rather than one list tagged by kind,
     * so "at most one combine" is a property of the structure instead of a rule this constructor
     * has to enforce. The declaration layer holds them separately already, and nothing reads them
     * as a single sequence: a tagged list would only be flattened on the way in and split apart
     * again on arrival.
     *
     * The owning runtime is not carried here: the kernel already receives it as an explicit
     * argument, and the draft that builds this structure is bound to that same runtime at creation,
     * so a stored copy could only ever agree with the argument.
     */

    private final String name;
    private final @Nullable Duration timeout;
    private final @Nullable Duration closeGrace;
    private final @Nullable ListenableFuture<?> cancellationSignal;
    private final boolean cancellationInterrupt;
    private final ImmutableList<Slot> members;
    private final @Nullable Slot combine;

    TaskGroupDefinition(
            String name,
            @Nullable Duration timeout,
            @Nullable Duration closeGrace,
            @Nullable ListenableFuture<?> cancellationSignal,
            boolean cancellationInterrupt,
            List<Slot> members,
            @Nullable Slot combine) {
        this.name = name;
        this.timeout = timeout;
        this.closeGrace = closeGrace;
        this.cancellationSignal = cancellationSignal;
        this.cancellationInterrupt = cancellationInterrupt;
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

    CancellationToken.@Nullable RequestCancellationLink admitCancellation(CancellationToken token) {
        if (cancellationSignal == null) {
            return null;
        }
        return token.cancelOn(cancellationSignal, cancellationInterrupt);
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
     * One declared slot: key, the {@link Par} it runs on, and options. A slot carries no kind tag:
     * it is a member or the combine by virtue of which argument it arrived in.
     */
    static final class Slot {
        final String name;
        final Par par;
        final TaskOptions options;
        final TaskKey<?> key;
        final boolean valueView;

        Slot(TaskKey<?> key, Par par, TaskOptions options, boolean valueView) {
            this.key = key;
            this.valueView = valueView;
            this.name = key.name();
            this.par = par;
            this.options = options;
        }
    }
}
