package io.github.monadrome.parallelinscope;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Frozen, package-private structure of one group run, produced by {@link GroupDraft#freeze()} at the
 * {@code submitAll()} boundary.
 *
 * <p>It is not a public type any more: a group is declared and submitted in one fluent chain, so
 * there is nothing for a caller to reuse or hold. What remains is the shape the preparation kernel
 * needs — the owning runtime's identity, the group name, the forced choice between an explicit
 * timeout and an inherited one, the optional close grace, and the ordered slots — each carrying its
 * name, the owner-bound {@link Par} resolved at declaration time, immutable {@link TaskOptions}, and
 * the {@link TypeToken} that the member's runtime type check and every later typed lookup compare
 * against.
 *
 * <p>The instance is created only after the declaration is complete and is never mutated, so the
 * kernel can read it without synchronization.
 */
final class TaskGroupDefinition {
    private final ParRuntime owner;
    private final String name;
    private final @Nullable Duration timeout;
    private final @Nullable Duration closeGrace;
    private final ImmutableList<Slot> slots;
    private final ImmutableList<Slot> members;
    private final @Nullable Slot combine;

    TaskGroupDefinition(
            ParRuntime owner,
            String name,
            @Nullable Duration timeout,
            @Nullable Duration closeGrace,
            List<Slot> slots) {
        this.owner = owner;
        this.name = name;
        this.timeout = timeout;
        this.closeGrace = closeGrace;
        this.slots = ImmutableList.copyOf(slots);
        ImmutableList.Builder<Slot> memberBuilder = ImmutableList.builder();
        Slot combineSlot = null;
        for (Slot slot : this.slots) {
            if (slot.kind == Kind.MEMBER) {
                memberBuilder.add(slot);
            } else {
                combineSlot = slot;
            }
        }
        this.members = memberBuilder.build();
        this.combine = combineSlot;
    }

    String name() {
        return name;
    }

    ParRuntime owner() {
        return owner;
    }

    /** The explicit group timeout; empty means the enclosing scope's deadline is inherited. */
    Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    /** The explicit close grace used by {@link TaskGroup#close()}; empty means it is derived. */
    Optional<Duration> closeGrace() {
        return Optional.ofNullable(closeGrace);
    }

    /** Every declared slot in declaration order, including the terminal combine when present. */
    List<Slot> slots() {
        return slots;
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

    /** Internal kind of one declared slot: a plain member or the terminal combine. */
    enum Kind {
        MEMBER,
        COMBINE
    }

    /** One declared slot: name, owner-bound Par, options, kind, and declared result type. */
    static final class Slot {
        final String name;
        final Par par;
        final TaskOptions options;
        final Kind kind;
        final TypeToken<?> type;

        Slot(String name, Par par, TaskOptions options, Kind kind, TypeToken<?> type) {
            this.name = name;
            this.par = par;
            this.options = options;
            this.kind = kind;
            this.type = type;
        }
    }
}
