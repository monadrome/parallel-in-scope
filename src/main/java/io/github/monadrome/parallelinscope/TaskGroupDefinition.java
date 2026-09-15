package io.github.monadrome.parallelinscope;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Immutable, reusable, structure-only description of one heterogeneous task group.
 *
 * <p>A definition captures nothing but structure: the owning {@link ParRuntime}'s identity, the
 * group name, the forced choice between an explicit timeout and an inherited one, the optional
 * close grace, and the ordered member declarations — each carrying its name, declaration order,
 * internal kind, the owner-bound {@link Par} resolved at configuration time, and immutable {@link
 * TaskOptions}. It binds no thread context, executor, deadline, {@code Callable}, combine body, or
 * completion callback: those belong to a single run and are supplied per submission through {@link
 * TaskGroup.Bindings}. The same definition may be submitted repeatedly and concurrently for as long
 * as its owner lives.
 */
public final class TaskGroupDefinition {
    private final ParRuntime owner;
    private final String name;
    private final @Nullable Duration timeout;
    private final @Nullable Duration closeGrace;
    private final ImmutableList<Slot> slots;
    private final ImmutableList<Slot> members;
    private final @Nullable Slot combine;

    private TaskGroupDefinition(Builder builder) {
        this.owner = builder.owner;
        this.name = builder.name;
        this.timeout = builder.timeout;
        this.closeGrace = builder.closeGrace;
        this.slots = ImmutableList.copyOf(builder.slots);
        this.members =
                builder.slots.stream().filter(slot -> slot.kind == Kind.MEMBER).collect(toImmutableList());
        this.combine = builder.combine;
    }

    /** The group name declared at {@code defineGroup*}. */
    public String name() {
        return name;
    }

    /** The owner this definition is bound to; only it may submit this definition. */
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

    /** One immutable declared slot: name, owner-bound Par, options, kind, and its Member handle. */
    static final class Slot {
        final String name;
        final Par par;
        final TaskOptions options;
        final Kind kind;
        final int memberIndex;
        final Member<?> handle;

        Slot(String name, Par par, TaskOptions options, Kind kind, int memberIndex) {
            this.name = name;
            this.par = par;
            this.options = options;
            this.kind = kind;
            this.memberIndex = memberIndex;
            this.handle = new Member<>(name);
        }
    }

    /**
     * Library-created, immutable, identity-based structural handle of one declared slot.
     *
     * <p>A {@code Member<T>} is produced by {@link Builder#task(String, Par)} or {@link
     * Builder#combine(String, Par)} and identifies that slot within its definition by object
     * identity — handles are neither equal to each other nor matched by name across definitions.
     * The type parameter {@code T} constrains the {@code Callable} bound through {@link
     * TaskGroup.Bindings#task} and the {@link TaskFuture} returned by {@link
     * TaskGroup#future(Member)}. The name is diagnostic only. A handle carries no execution state
     * and no user executable object.
     *
     * @param <T> the slot's result type
     */
    public static final class Member<T> {
        private final String name;

        private Member(String name) {
            this.name = name;
        }

        /** The declared slot name; diagnostics and observation only, never a matching key. */
        public String name() {
            return name;
        }

        /** Identity semantics are deliberate: a handle names exactly the slot it was created for. */
        @Override
        public String toString() {
            return "Member{" + name + '}';
        }
    }

    /**
     * Configuration builder; each {@link #task}/{@link #combine} call is validated immediately.
     *
     * <p>Created only by the owner through {@link ParRuntime#defineGroup(String, Duration)} or
     * {@link ParRuntime#defineGroupInheriting(String)}. Not thread-safe: use it from one
     * synchronous configuration flow. The first {@link #build()} seals the builder and returns the
     * definition; later {@code build()} calls return that same instance, and any mutating call
     * after sealing throws {@link IllegalStateException}.
     */
    public static final class Builder {
        private final ParRuntime owner;
        private final String name;
        private final @Nullable Duration timeout;
        private final List<Slot> slots = new ArrayList<>();
        private final Set<String> seenNames = new HashSet<>();
        private int memberCount;
        private @Nullable Slot combine;
        private @Nullable Duration closeGrace;
        private @Nullable TaskGroupDefinition built;

        Builder(ParRuntime owner, String name, @Nullable Duration timeout) {
            this.owner = owner;
            this.name = name;
            this.timeout = timeout;
        }

        /**
         * Sets the close grace: the bounded wait {@link TaskGroup#close()} performs for member
         * bodies to exit after requesting cancellation.
         *
         * @throws NullPointerException if {@code closeGrace} is null
         * @throws IllegalArgumentException if {@code closeGrace} is negative
         * @throws IllegalStateException if the builder is already sealed by {@link #build()}
         */
        public Builder closeGrace(Duration closeGrace) {
            checkMutable();
            Objects.requireNonNull(closeGrace, "closeGrace cannot be null");
            if (closeGrace.isNegative()) {
                throw new IllegalArgumentException("closeGrace must not be negative: " + closeGrace);
            }
            this.closeGrace = closeGrace;
            return this;
        }

        /**
         * Declares one plain member on the given {@code Par}, inheriting the group deadline.
         *
         * <p>Shorthand for {@link #task(String, Par, TaskOptions)} with {@link
         * TaskOptions#inheritTimeout()}.
         */
        public <T> Member<T> task(String memberName, Par par) {
            return task(memberName, par, TaskOptions.inheritTimeout());
        }

        /**
         * Declares one plain member on the given {@code Par}.
         *
         * <p>The name must be non-null, non-blank, and unique across every member and the combine;
         * the {@code Par} must belong to the same {@link ParRuntime} that created this builder —
         * both are rejected here, at configuration time, before any run state exists. Omitting
         * {@code TaskOptions} is equivalent to {@link TaskOptions#inheritTimeout()}.
         *
         * @throws NullPointerException if any argument is null
         * @throws IllegalArgumentException if the name is blank or duplicated, or the {@code Par}
         *     belongs to a different {@code ParRuntime}
         * @throws IllegalStateException if the builder is already sealed by {@link #build()}
         */
        public <T> Member<T> task(String memberName, Par par, TaskOptions options) {
            checkMutable();
            return add(memberName, par, options, Kind.MEMBER);
        }

        /**
         * Declares the terminal combine on the given {@code Par}, inheriting the group deadline.
         *
         * <p>Shorthand for {@link #combine(String, Par, TaskOptions)} with {@link
         * TaskOptions#inheritTimeout()}.
         */
        public <R> Member<R> combine(String combineName, Par par) {
            return combine(combineName, par, TaskOptions.inheritTimeout());
        }

        /**
         * Declares the terminal combine on the given {@code Par}.
         *
         * <p>The combine depends on every plain member and is submitted to its {@code Par} only
         * after all of them succeed. A group accepts at most one combine: a second {@code
         * combine()} call throws {@link IllegalStateException}. Its name must be unique across
         * every member and the combine, and the {@code Par} must belong to the owning {@code
         * ParRuntime}.
         *
         * @throws NullPointerException if any argument is null
         * @throws IllegalArgumentException if the name is blank or duplicated, or the {@code Par}
         *     belongs to a different {@code ParRuntime}
         * @throws IllegalStateException if a combine is already declared or the builder is sealed
         */
        public <R> Member<R> combine(String combineName, Par par, TaskOptions options) {
            checkMutable();
            Objects.requireNonNull(combineName, "combineName cannot be null");
            Objects.requireNonNull(par, "par cannot be null");
            Objects.requireNonNull(options, "options cannot be null");
            if (combine != null) {
                throw new IllegalStateException("A group accepts at most one combine");
            }
            Member<R> handle = add(combineName, par, options, Kind.COMBINE);
            combine = slots.get(slots.size() - 1);
            return handle;
        }

        /**
         * Seals this builder and returns the immutable definition. The first call creates the
         * instance; every later call returns that same instance.
         */
        public TaskGroupDefinition build() {
            if (built == null) {
                built = new TaskGroupDefinition(this);
            }
            return built;
        }

        private <T> Member<T> add(String memberName, Par par, TaskOptions options, Kind kind) {
            Objects.requireNonNull(memberName, "memberName cannot be null");
            Objects.requireNonNull(par, "par cannot be null");
            Objects.requireNonNull(options, "options cannot be null");
            if (memberName.trim().isEmpty()) {
                throw new IllegalArgumentException("member name cannot be blank");
            }
            if (par.runtime() != owner) {
                throw new IllegalArgumentException(
                        "Par '" + par.id() + "' does not belong to the ParRuntime that created this builder");
            }
            if (!seenNames.add(memberName)) {
                throw new IllegalArgumentException("Duplicate name '" + memberName + "'");
            }
            int memberIndex = kind == Kind.MEMBER ? memberCount++ : -1;
            Slot slot = new Slot(memberName, par, options, kind, memberIndex);
            slots.add(slot);
            @SuppressWarnings("unchecked")
            Member<T> handle = (Member<T>) slot.handle;
            return handle;
        }

        private void checkMutable() {
            if (built != null) {
                throw new IllegalStateException("Builder is sealed: the definition has been built");
            }
        }
    }
}
