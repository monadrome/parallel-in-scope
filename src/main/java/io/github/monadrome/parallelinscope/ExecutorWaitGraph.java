package io.github.monadrome.parallelinscope;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verifyNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.ImmutableGraph;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The executor waits-for graph one {@link ParRuntime} accumulates over its lifetime.
 *
 * <p>Nodes are the executors registered with the runtime, keyed by {@link ExecutorIdentity}. An
 * admission records an edge from the executor of the waiting unit to the executor of each unit it
 * synchronously waits for, when both are registered here and the awaited one is starvation-prone.
 * Edges only accumulate, so the graph is bounded by the square of the registered executors.
 *
 * <p>All state is one immutable {@link State} behind an {@link AtomicReference}. A known wait costs
 * one volatile read and a table lookup. New waits of one admission are installed together by a
 * compare-and-set retry loop; the thread whose installation succeeds compares the cyclic components
 * of the state it installed with those of the state it replaced, so every newly formed component is
 * announced by exactly the installation that formed it. Announcements are handed to the runtime's
 * own executor: the admitting thread never calls a logging handler, which may block or re-enter.
 * See {@code design/task-graph.md}.
 */
final class ExecutorWaitGraph {
    private static final Logger LOGGER = Logger.getLogger(ExecutorWaitGraph.class.getName());

    private final ImmutableSetMultimap<ExecutorIdentity, ParId> aliases;
    private final ImmutableMap<ExecutorIdentity, Integer> registrationOrder;
    private final Executor announcements;
    private final AtomicReference<State> state;

    /**
     * Creates an empty graph over the runtime's registrations.
     *
     * @param aliases every id each registered executor is registered under, in registration order
     * @param announcements the runtime-owned executor that delivers cycle warnings
     */
    ExecutorWaitGraph(ImmutableSetMultimap<ExecutorIdentity, ParId> aliases, Executor announcements) {
        this.aliases = checkNotNull(aliases, "aliases cannot be null");
        this.announcements = checkNotNull(announcements, "announcements cannot be null");
        ImmutableMap.Builder<ExecutorIdentity, Integer> order = ImmutableMap.builder();
        int position = 0;
        for (ExecutorIdentity identity : aliases.keySet()) {
            order.put(identity, position++);
        }
        this.registrationOrder = order.build();
        this.state = new AtomicReference<>(State.empty());
    }

    /** Records that {@code waiter}'s body synchronously waits for {@code awaited}. */
    void record(@Nullable TaskNode waiter, TaskNode awaited) {
        if (waiter == null) {
            return;
        }
        ExecutorIdentity from = waiter.executor();
        if (from == null || !aliases.containsKey(from)) {
            return;
        }
        ExecutorIdentity to = newWaitTarget(from, awaited, current().waits);
        if (to == null) {
            return;
        }
        installOrAnnounce(ImmutableList.of(new Wait(from, to, waiter + " -> " + awaited)));
    }

    /**
     * Records that {@code waiter}'s body synchronously waits for both {@code firstAwaited} and {@code
     * secondAwaited}: the two-target form of {@link #record(TaskNode, List)}, so both new waits become
     * visible together.
     */
    void record(@Nullable TaskNode waiter, TaskNode firstAwaited, TaskNode secondAwaited) {
        if (waiter == null) {
            return;
        }
        ExecutorIdentity from = waiter.executor();
        if (from == null || !aliases.containsKey(from)) {
            return;
        }
        Table<ExecutorIdentity, ExecutorIdentity, Wait> known = current().waits;
        ExecutorIdentity first = newWaitTarget(from, firstAwaited, known);
        ExecutorIdentity second = newWaitTarget(from, secondAwaited, known);
        if (first == null && second == null) {
            return;
        }
        ImmutableList.Builder<Wait> missing = ImmutableList.builder();
        if (first != null) {
            missing.add(new Wait(from, first, waiter + " -> " + firstAwaited));
        }
        if (second != null) {
            missing.add(new Wait(from, second, waiter + " -> " + secondAwaited));
        }
        installOrAnnounce(missing.build());
    }

    /**
     * Records one admission: {@code waiter}'s body synchronously waits for every unit in {@code
     * awaited}. A top-level admission, without a waiter, records nothing. The admission's new waits
     * become visible together. A failure of the recording itself is reported asynchronously and never
     * fails the admission.
     */
    void record(@Nullable TaskNode waiter, List<TaskNode> awaited) {
        if (waiter == null) {
            return;
        }
        ExecutorIdentity from = waiter.executor();
        if (from == null || !aliases.containsKey(from)) {
            return;
        }
        Table<ExecutorIdentity, ExecutorIdentity, Wait> known = current().waits;
        List<Wait> missing = null;
        for (TaskNode target : awaited) {
            ExecutorIdentity to = newWaitTarget(from, target, known);
            if (to == null) {
                continue;
            }
            if (missing == null) {
                missing = new ArrayList<>(awaited.size());
            }
            missing.add(new Wait(from, to, waiter + " -> " + target));
        }
        if (missing != null) {
            installOrAnnounce(missing);
        }
    }

    /**
     * The executor of {@code target} when this admission records a new edge from {@code from} to it, or
     * null when the target is ineligible for an edge or the edge is already known.
     */
    private @Nullable ExecutorIdentity newWaitTarget(
            ExecutorIdentity from, TaskNode target, Table<ExecutorIdentity, ExecutorIdentity, Wait> known) {
        ExecutorIdentity to = target.executor();
        if (to == null || !target.starvationProne() || !aliases.containsKey(to) || known.contains(from, to)) {
            return null;
        }
        return to;
    }

    private void installOrAnnounce(List<Wait> missing) {
        try {
            install(missing);
        } catch (RuntimeException failure) {
            announce(() -> LOGGER.log(Level.WARNING, "Recording an executor wait failed", failure));
        }
    }

    /** The current snapshot. */
    ExecutorWaitReport report() {
        return current().report;
    }

    private State current() {
        return verifyNotNull(state.get());
    }

    private void install(List<Wait> missing) {
        while (true) {
            State current = current();
            State next = current.with(missing, this);
            if (next == current) {
                return;
            }
            if (state.compareAndSet(current, next)) {
                for (ExecutorWaitReport.Component component : next.report.cyclicComponents()) {
                    if (!current.componentKeys.contains(component.identities())) {
                        announce(() -> LOGGER.warning(warning(component)));
                    }
                }
                return;
            }
        }
    }

    private static String warning(ExecutorWaitReport.Component component) {
        return "Potential executor deadlock: " + component
                + ". A task body on each of these starvation-prone pools synchronously waits for work"
                + " queued on the next, so under load every worker of the cycle can wait for work queued"
                + " behind itself. This is a wait structure the ParRuntime has observed at some point,"
                + " not proof that the waits overlap or that threads are deadlocked now; see"
                + " ParRuntime.executorWaitReport().";
    }

    private void announce(Runnable message) {
        try {
            announcements.execute(() -> {
                try {
                    message.run();
                } catch (RuntimeException handlerFailure) {
                    return;
                }
            });
        } catch (RejectedExecutionException servicesShutDown) {
            return;
        }
    }

    private ExecutorWaitReport.Component component(
            List<ExecutorIdentity> members,
            Graph<ExecutorIdentity> graph,
            Table<ExecutorIdentity, ExecutorIdentity, Wait> waits) {
        List<ExecutorIdentity> cycle = cycleThrough(members, graph);
        ImmutableList.Builder<Set<ParId>> cycleIds = ImmutableList.builder();
        ImmutableList.Builder<String> witnesses = ImmutableList.builder();
        for (int index = 0; index < cycle.size(); index++) {
            ExecutorIdentity source = cycle.get(index);
            ExecutorIdentity target = cycle.get((index + 1) % cycle.size());
            cycleIds.add(aliases.get(source));
            Wait wait = waits.get(source, target);
            witnesses.add(wait == null ? "" : wait.witness);
        }
        ImmutableList.Builder<Set<ParId>> memberIds = ImmutableList.builder();
        for (ExecutorIdentity member : members) {
            memberIds.add(aliases.get(member));
        }
        return new ExecutorWaitReport.Component(
                ImmutableSet.copyOf(members), memberIds.build(), cycleIds.build(), witnesses.build());
    }

    /**
     * The shortest cycle through the first member, restricted to the component. A lone member is its
     * own self-loop; a larger component skips the first member's self-loop so the cycle shows how the
     * members wait on one another.
     */
    private static List<ExecutorIdentity> cycleThrough(List<ExecutorIdentity> members, Graph<ExecutorIdentity> graph) {
        ExecutorIdentity start = members.get(0);
        if (members.size() == 1) {
            return ImmutableList.of(start);
        }
        Set<ExecutorIdentity> inside = ImmutableSet.copyOf(members);
        Map<ExecutorIdentity, ExecutorIdentity> parents = new HashMap<>();
        ArrayDeque<ExecutorIdentity> frontier = new ArrayDeque<>();
        frontier.add(start);
        while (!frontier.isEmpty()) {
            ExecutorIdentity node = frontier.remove();
            for (ExecutorIdentity next : graph.successors(node)) {
                if (!inside.contains(next) || (node.equals(start) && next.equals(start))) {
                    continue;
                }
                if (next.equals(start)) {
                    List<ExecutorIdentity> path = new ArrayList<>();
                    for (ExecutorIdentity step = node; step != null; step = parents.get(step)) {
                        path.add(0, step);
                    }
                    return ImmutableList.copyOf(path);
                }
                if (!parents.containsKey(next)) {
                    parents.put(next, node);
                    frontier.add(next);
                }
            }
        }
        throw new AssertionError("strongly connected component without a cycle through " + start);
    }

    /** One wait between two executors with the first pair of task units observed behind it. */
    private static final class Wait {
        final ExecutorIdentity from;
        final ExecutorIdentity to;
        final String witness;

        Wait(ExecutorIdentity from, ExecutorIdentity to, String witness) {
            this.from = from;
            this.to = to;
            this.witness = witness;
        }
    }

    /** One immutable version of the graph and the report derived from it. */
    private static final class State {
        private static final State EMPTY = new State(
                ImmutableTable.<ExecutorIdentity, ExecutorIdentity, Wait>of(),
                ExecutorWaitReport.empty(),
                ImmutableSet.<ImmutableSet<ExecutorIdentity>>of());

        final ImmutableTable<ExecutorIdentity, ExecutorIdentity, Wait> waits;
        final ExecutorWaitReport report;
        final ImmutableSet<ImmutableSet<ExecutorIdentity>> componentKeys;

        private State(
                ImmutableTable<ExecutorIdentity, ExecutorIdentity, Wait> waits,
                ExecutorWaitReport report,
                ImmutableSet<ImmutableSet<ExecutorIdentity>> componentKeys) {
            this.waits = waits;
            this.report = report;
            this.componentKeys = componentKeys;
        }

        static State empty() {
            return EMPTY;
        }

        /** This state plus every wait in {@code added} it lacks; this same instance when it lacks none. */
        State with(List<Wait> added, ExecutorWaitGraph owner) {
            ImmutableTable.Builder<ExecutorIdentity, ExecutorIdentity, Wait> builder = ImmutableTable.builder();
            builder.putAll(waits);
            Map<ExecutorIdentity, Set<ExecutorIdentity>> fresh = new HashMap<>();
            boolean grew = false;
            for (Wait wait : added) {
                if (waits.contains(wait.from, wait.to)) {
                    continue;
                }
                Set<ExecutorIdentity> targets = fresh.get(wait.from);
                if (targets == null) {
                    targets = new HashSet<>();
                    fresh.put(wait.from, targets);
                }
                if (targets.add(wait.to)) {
                    builder.put(wait.from, wait.to, wait);
                    grew = true;
                }
            }
            if (!grew) {
                return this;
            }
            ImmutableTable<ExecutorIdentity, ExecutorIdentity, Wait> next = builder.build();
            ImmutableList<ExecutorWaitReport.Component> components = owner.cyclicComponents(next);
            ImmutableSet.Builder<ImmutableSet<ExecutorIdentity>> keys = ImmutableSet.builder();
            for (ExecutorWaitReport.Component component : components) {
                keys.add(component.identities());
            }
            return new State(next, ExecutorWaitReport.of(components), keys.build());
        }
    }

    /**
     * The cyclic strongly connected components of {@code waits}, ordered by the registration order of
     * their first member. Two executors share a component exactly when each reaches the other; a lone
     * executor is cyclic only through a self-loop. Reachability is computed once per node, so the
     * cost is polynomial in the registered executors.
     */
    private ImmutableList<ExecutorWaitReport.Component> cyclicComponents(
            ImmutableTable<ExecutorIdentity, ExecutorIdentity, Wait> waits) {
        ImmutableGraph.Builder<ExecutorIdentity> builder =
                GraphBuilder.directed().allowsSelfLoops(true).<ExecutorIdentity>immutable();
        for (Table.Cell<ExecutorIdentity, ExecutorIdentity, Wait> cell : waits.cellSet()) {
            builder.putEdge(checkNotNull(cell.getRowKey()), checkNotNull(cell.getColumnKey()));
        }
        ImmutableGraph<ExecutorIdentity> graph = builder.build();
        List<ExecutorIdentity> nodes = new ArrayList<>(graph.nodes());
        nodes.sort((left, right) -> Integer.compare(position(left), position(right)));
        Map<ExecutorIdentity, Set<ExecutorIdentity>> reach = new HashMap<>();
        for (ExecutorIdentity node : nodes) {
            reach.put(node, Graphs.reachableNodes(graph, node));
        }
        Set<ExecutorIdentity> assigned = new HashSet<>();
        ImmutableList.Builder<ExecutorWaitReport.Component> components = ImmutableList.builder();
        for (ExecutorIdentity node : nodes) {
            if (assigned.contains(node)) {
                continue;
            }
            List<ExecutorIdentity> members = new ArrayList<>();
            for (ExecutorIdentity other : nodes) {
                if (verifyNotNull(reach.get(node)).contains(other)
                        && verifyNotNull(reach.get(other)).contains(node)) {
                    members.add(other);
                }
            }
            assigned.addAll(members);
            if (members.size() > 1 || graph.hasEdgeConnecting(node, node)) {
                components.add(component(members, graph, waits));
            }
        }
        return components.build();
    }

    private int position(ExecutorIdentity identity) {
        Integer position = registrationOrder.get(identity);
        return position == null ? Integer.MAX_VALUE : position;
    }
}
