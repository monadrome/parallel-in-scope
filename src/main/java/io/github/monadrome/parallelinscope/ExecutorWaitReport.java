package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of the executor waits-for structure a {@link ParRuntime} has observed.
 *
 * <p>An executor waits for another when a task body running on the first synchronously waits — in
 * {@code Par.map}, {@code Par.mapAndCombine}, or a task group's {@code runAll()} — for work on the
 * second, and the second is starvation-prone: it parks a submission in its queue behind busy
 * workers instead of growing or rejecting. A cycle of such waits, an executor waiting on itself
 * included, is the structural shape of a thread-pool deadlock: under enough load every worker of
 * the cycle can end up waiting for work queued behind itself.
 *
 * <p>The report is a structural risk signal accumulated over the runtime's lifetime, not a
 * deadlock proof. A wait recorded once stays recorded, so a cycle may join waits that never
 * happened at the same time, and a pool with headroom may never actually starve; an empty report
 * says nothing about waits the library cannot see — external futures, direct executor submissions,
 * locks, unregistered or opaque executors, or other runtimes. A wait is attributed to the executor
 * its unit was submitted to; when a body runs inline on a borrowed thread, the blocked thread may
 * belong to another pool.
 *
 * <p>Reports are created only by the library; read the current one with {@link
 * ParRuntime#executorWaitReport()}.
 *
 * @since 0.4.0
 */
public final class ExecutorWaitReport {
    private static final ExecutorWaitReport EMPTY = new ExecutorWaitReport(ImmutableList.<Component>of());

    private final ImmutableList<Component> cyclicComponents;

    private ExecutorWaitReport(ImmutableList<Component> cyclicComponents) {
        this.cyclicComponents = cyclicComponents;
    }

    static ExecutorWaitReport empty() {
        return EMPTY;
    }

    static ExecutorWaitReport of(ImmutableList<Component> cyclicComponents) {
        return cyclicComponents.isEmpty() ? EMPTY : new ExecutorWaitReport(cyclicComponents);
    }

    /**
     * Returns the cyclic strongly connected components of the observed executor waits-for graph: each
     * is a maximal set of executors that all reach one another through waits, or a single executor
     * that waits on itself. Components are listed in the registration order of their first member.
     *
     * @return the cyclic components; empty when no wait cycle has been observed
     */
    public List<Component> cyclicComponents() {
        return cyclicComponents;
    }

    @Override
    public String toString() {
        if (cyclicComponents.isEmpty()) {
            return "no executor wait cycle observed";
        }
        List<String> rendered = new ArrayList<>(cyclicComponents.size());
        for (Component component : cyclicComponents) {
            rendered.add(component.toString());
        }
        return String.join("; ", rendered);
    }

    /**
     * One cyclic strongly connected component of the executor waits-for graph, with one concrete
     * cycle through it. Each executor is given as the set of every {@link ParId} it is registered
     * under: several ids may share one executor, which is still one node.
     *
     * @since 0.4.0
     */
    public static final class Component {
        private final ImmutableSet<ExecutorIdentity> identities;
        private final ImmutableList<Set<ParId>> members;
        private final ImmutableList<Set<ParId>> cycle;
        private final ImmutableList<String> witnesses;

        Component(
                ImmutableSet<ExecutorIdentity> identities,
                ImmutableList<Set<ParId>> members,
                ImmutableList<Set<ParId>> cycle,
                ImmutableList<String> witnesses) {
            checkArgument(!cycle.isEmpty(), "a cyclic component needs a cycle");
            checkArgument(
                    witnesses.size() == cycle.size(),
                    "cycle has %s waits but %s witnesses",
                    cycle.size(),
                    witnesses.size());
            this.identities = checkNotNull(identities, "identities cannot be null");
            this.members = checkNotNull(members, "members cannot be null");
            this.cycle = cycle;
            this.witnesses = witnesses;
        }

        ImmutableSet<ExecutorIdentity> identities() {
            return identities;
        }

        /**
         * Returns every executor of the component, in registration order.
         *
         * @return each member executor as its registered ids
         */
        public List<Set<ParId>> members() {
            return members;
        }

        /**
         * Returns one concrete wait cycle through the component, in wait order: each executor waits
         * on the next, and the last waits on the first. A single entry is an executor waiting on
         * itself.
         *
         * @return the executors of the cycle as their registered ids
         */
        public List<Set<ParId>> cycle() {
            return cycle;
        }

        /**
         * Renders the cycle with, for each wait, the first observed pair of task units behind it: the
         * waiting unit and the unit it waited for.
         */
        @Override
        public String toString() {
            List<String> waits = new ArrayList<>(cycle.size());
            for (int index = 0; index < cycle.size(); index++) {
                waits.add(render(cycle.get(index)) + " -> " + render(cycle.get((index + 1) % cycle.size())) + " ("
                        + witnesses.get(index) + ")");
            }
            return String.join(", ", waits);
        }

        private static String render(Set<ParId> executor) {
            List<String> ids = new ArrayList<>(executor.size());
            for (ParId id : executor) {
                ids.add(id.value());
            }
            return "[" + String.join("/", ids) + "]";
        }
    }
}
