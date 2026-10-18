package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The runtime-level executor waits-for graph in isolation: which waits become edges, how cyclic
 * strongly connected components are reported and announced, and that concurrent installations
 * neither lose a wait nor announce a component twice.
 */
class ExecutorWaitGraphTest {
    private static final AtomicInteger UNIT_IDS = new AtomicInteger();

    private final List<ExecutorService> executors = new ArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> announcements = new ConcurrentLinkedQueue<>();

    @AfterEach
    void shutDown() {
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
    }

    private ExecutorIdentity executor() {
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        executors.add(executor);
        return new ExecutorIdentity(executor);
    }

    private ExecutorWaitGraph graph(Object... identitiesAndIds) {
        ImmutableSetMultimap.Builder<ExecutorIdentity, ParId> aliases = ImmutableSetMultimap.builder();
        for (int index = 0; index < identitiesAndIds.length; index += 2) {
            aliases.put((ExecutorIdentity) identitiesAndIds[index], ParId.of((String) identitiesAndIds[index + 1]));
        }
        return new ExecutorWaitGraph(aliases.build(), announcements::add);
    }

    private static TaskNode unit(String name, ExecutorIdentity executor) {
        return unit(name, executor, true);
    }

    private static TaskNode unit(String name, ExecutorIdentity executor, boolean starvationProne) {
        return new TaskNode(
                TaskId.unit(String.valueOf(UNIT_IDS.incrementAndGet())),
                name,
                executor,
                null,
                starvationProne,
                TaskType.IO_BOUND,
                1,
                1,
                Long.MAX_VALUE);
    }

    private static List<Set<ParId>> ids(String... names) {
        List<Set<ParId>> ids = new ArrayList<>();
        for (String name : names) {
            ids.add(ImmutableSet.of(ParId.of(name)));
        }
        return ids;
    }

    @Test
    void aTopLevelAdmissionRecordsNothing() {
        ExecutorIdentity a = executor();
        ExecutorWaitGraph graph = graph(a, "a");
        ExecutorWaitReport before = graph.report();

        graph.record(null, unit("child", a));

        assertThat(graph.report()).isSameAs(before);
        assertThat(graph.report().cyclicComponents()).isEmpty();
        assertThat(announcements).isEmpty();
    }

    @Test
    void aNestedWaitOnTheSameStarvationPronePoolIsASelfLoopAnnouncedOnce() {
        ExecutorIdentity a = executor();
        ExecutorWaitGraph graph = graph(a, "a");
        TaskNode outer = unit("outer", a);

        graph.record(outer, unit("inner", a));
        ExecutorWaitReport first = graph.report();
        graph.record(outer, unit("inner-again", a));

        assertThat(graph.report()).isSameAs(first);
        assertThat(first.cyclicComponents()).hasSize(1);
        ExecutorWaitReport.Component component = first.cyclicComponents().get(0);
        assertThat(component.members()).containsExactly(ImmutableSet.of(ParId.of("a")));
        assertThat(component.cycle()).containsExactly(ImmutableSet.of(ParId.of("a")));
        assertThat(component.toString())
                .contains("[a] -> [a]")
                .contains("outer[")
                .contains("inner[");
        assertThat(announcements).hasSize(1);
    }

    @Test
    void waitsOnAPoolThatCannotStarveTheWaiterAreNotEdges() {
        ExecutorIdentity a = executor();
        ExecutorWaitGraph graph = graph(a, "a");

        graph.record(unit("outer", a), unit("inner", a, false));

        assertThat(graph.report().cyclicComponents()).isEmpty();
    }

    @Test
    void waitsWithAnUnregisteredEndpointAreNotEdges() {
        ExecutorIdentity registered = executor();
        ExecutorIdentity foreign = executor();
        ExecutorWaitGraph graph = graph(registered, "registered");

        graph.record(unit("foreign-caller", foreign), unit("child", registered));
        graph.record(unit("caller", registered), unit("foreign-child", foreign));
        graph.record(unit("caller", registered), unit("child", foreign));

        assertThat(graph.report().cyclicComponents()).isEmpty();
    }

    @Test
    void waitsFromSeparateAdmissionsCloseOneCycleAcrossRequests() {
        ExecutorIdentity a = executor();
        ExecutorIdentity b = executor();
        ExecutorWaitGraph graph = graph(a, "a", b, "b");

        graph.record(unit("request-1", a), unit("load", b));
        assertThat(graph.report().cyclicComponents()).isEmpty();
        assertThat(announcements).isEmpty();
        graph.record(unit("request-2", b), unit("save", a));

        assertThat(graph.report().cyclicComponents()).hasSize(1);
        ExecutorWaitReport.Component component =
                graph.report().cyclicComponents().get(0);
        assertThat(component.members()).containsExactlyElementsOf(ids("a", "b"));
        assertThat(component.cycle()).containsExactlyElementsOf(ids("a", "b"));
        assertThat(component.toString()).contains("[a] -> [b] (request-1[").contains("[b] -> [a] (request-2[");
        assertThat(announcements).hasSize(1);
    }

    @Test
    void componentsAndTheirMembersFollowRegistrationOrderNotObservationOrder() {
        ExecutorIdentity a = executor();
        ExecutorIdentity b = executor();
        ExecutorIdentity c = executor();
        ExecutorWaitGraph graph = graph(a, "a", b, "b", c, "c");

        graph.record(unit("c-self", c), unit("c-child", c));
        graph.record(unit("b-to-a", b), unit("on-a", a));
        graph.record(unit("a-to-b", a), unit("on-b", b));

        List<ExecutorWaitReport.Component> components = graph.report().cyclicComponents();
        assertThat(components).hasSize(2);
        assertThat(components.get(0).members()).containsExactlyElementsOf(ids("a", "b"));
        assertThat(components.get(0).cycle()).containsExactlyElementsOf(ids("a", "b"));
        assertThat(components.get(1).members()).containsExactlyElementsOf(ids("c"));
    }

    @Test
    void theReportRendersEveryComponentOrSaysNoneWasObserved() {
        ExecutorIdentity a = executor();
        ExecutorIdentity b = executor();
        ExecutorWaitGraph graph = graph(a, "a", b, "b");
        assertThat(graph.report()).hasToString("no executor wait cycle observed");

        graph.record(unit("a-self", a), unit("a-child", a));
        graph.record(unit("b-self", b), unit("b-child", b));

        assertThat(graph.report().toString()).startsWith("[a] -> [a] (a-self[").contains("; [b] -> [b] (b-self[");
    }

    @Test
    void aComponentRequiresACycleWithOneWitnessPerWait() {
        ImmutableList<Set<ParId>> cycle = ImmutableList.<Set<ParId>>of(ImmutableSet.of(ParId.of("a")));
        assertThatThrownBy(() -> new ExecutorWaitReport.Component(
                        ImmutableSet.of(), cycle, ImmutableList.<Set<ParId>>of(), ImmutableList.<String>of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a cycle");
        assertThatThrownBy(() -> new ExecutorWaitReport.Component(
                        ImmutableSet.of(), cycle, cycle, ImmutableList.of("one", "two")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle has 1 waits but 2 witnesses");
    }

    @Test
    void anExecutorRegisteredUnderSeveralIdsIsOneMemberCarryingEveryId() {
        ExecutorIdentity shared = executor();
        ExecutorWaitGraph graph = graph(shared, "orders", shared, "billing");

        graph.record(unit("outer", shared), unit("inner", shared));

        ExecutorWaitReport.Component component =
                graph.report().cyclicComponents().get(0);
        assertThat(component.members()).containsExactly(ImmutableSet.of(ParId.of("orders"), ParId.of("billing")));
        assertThat(component.toString()).contains("[orders/billing] -> [orders/billing]");
    }

    @Test
    void oneAdmissionInstallsAllOfItsWaitsTogether() {
        ExecutorIdentity a = executor();
        ExecutorIdentity b = executor();
        ExecutorIdentity c = executor();
        ExecutorWaitGraph graph = graph(a, "a", b, "b", c, "c");

        List<TaskNode> awaited = Arrays.asList(unit("member-b", b), unit("member-c", c));
        graph.record(unit("group-caller", a), awaited.size(), awaited::get);
        graph.record(unit("back-from-b", b), unit("to-a", a));
        graph.record(unit("back-from-c", c), unit("to-a", a));

        assertThat(graph.report().cyclicComponents()).hasSize(1);
        assertThat(graph.report().cyclicComponents().get(0).members()).containsExactlyElementsOf(ids("a", "b", "c"));
    }

    @Test
    void aTwoTargetWaitWithNoRecordableTargetAddsNoEdge() {
        ExecutorIdentity a = executor();
        ExecutorIdentity foreign = executor();
        ExecutorWaitGraph graph = graph(a, "a");
        ExecutorWaitReport before = graph.report();

        graph.record(unit("caller", a), unit("foreign-child", foreign), unit("not-starvation-prone", a, false));

        assertThat(graph.report()).isSameAs(before);
        assertThat(announcements).isEmpty();
    }

    @Test
    void aTwoTargetWaitInstallsOnlyTheSecondTargetWhenTheFirstIsAlreadyKnown() {
        ExecutorIdentity a = executor();
        ExecutorIdentity b = executor();
        ExecutorIdentity c = executor();
        ExecutorWaitGraph graph = graph(a, "a", b, "b", c, "c");
        graph.record(unit("known-waiter", a), unit("known-child", b));

        graph.record(unit("two-target", a), unit("known-child-again", b), unit("new-child", c));
        graph.record(unit("back-from-c", c), unit("to-a", a));

        assertThat(graph.report().cyclicComponents()).hasSize(1);
        ExecutorWaitReport.Component component =
                graph.report().cyclicComponents().get(0);
        assertThat(component.members()).containsExactlyElementsOf(ids("a", "c"));
        assertThat(component.cycle()).containsExactlyElementsOf(ids("a", "c"));
        assertThat(component.toString()).contains("new-child[").contains("back-from-c[");
        assertThat(announcements).hasSize(1);
    }

    @Test
    void aTwoTargetWaitInstallsOnlyTheFirstTargetWhenTheSecondIsAlreadyKnown() {
        ExecutorIdentity a = executor();
        ExecutorIdentity b = executor();
        ExecutorIdentity c = executor();
        ExecutorWaitGraph graph = graph(a, "a", b, "b", c, "c");
        graph.record(unit("known-waiter", a), unit("known-child", c));

        graph.record(unit("two-target", a), unit("new-child", b), unit("known-child-again", c));
        graph.record(unit("back-from-b", b), unit("to-a", a));

        assertThat(graph.report().cyclicComponents()).hasSize(1);
        ExecutorWaitReport.Component component =
                graph.report().cyclicComponents().get(0);
        assertThat(component.members()).containsExactlyElementsOf(ids("a", "b"));
        assertThat(component.cycle()).containsExactlyElementsOf(ids("a", "b"));
        assertThat(component.toString()).contains("new-child[").contains("back-from-b[");
        assertThat(announcements).hasSize(1);
    }

    @Test
    void aTwoTargetWaitWithTwoNewTargetsInstallsBothEdges() {
        ExecutorIdentity a = executor();
        ExecutorIdentity b = executor();
        ExecutorIdentity c = executor();
        ExecutorWaitGraph graph = graph(a, "a", b, "b", c, "c");

        graph.record(unit("two-target", a), unit("on-b", b), unit("on-c", c));
        graph.record(unit("back-from-b", b), unit("to-a", a));
        graph.record(unit("back-from-c", c), unit("to-a", a));

        assertThat(graph.report().cyclicComponents()).hasSize(1);
        ExecutorWaitReport.Component component =
                graph.report().cyclicComponents().get(0);
        assertThat(component.members()).containsExactlyElementsOf(ids("a", "b", "c"));
        assertThat(component.cycle()).containsExactlyElementsOf(ids("a", "b"));
        assertThat(announcements).hasSize(2);
    }

    @Test
    void aTwoTargetWaitOnTheSameExecutorTwiceKeepsTheFirstWitness() {
        ExecutorIdentity a = executor();
        ExecutorWaitGraph graph = graph(a, "a");

        graph.record(unit("caller", a), unit("first-child", a), unit("second-child", a));

        assertThat(graph.report().cyclicComponents()).hasSize(1);
        ExecutorWaitReport.Component component =
                graph.report().cyclicComponents().get(0);
        assertThat(component.members()).containsExactlyElementsOf(ids("a"));
        assertThat(component.cycle()).containsExactlyElementsOf(ids("a"));
        assertThat(component.toString()).contains("first-child[").doesNotContain("second-child[");
        assertThat(announcements).hasSize(1);
    }

    @Test
    void componentsThatMergeAreAnnouncedAgainAsTheMergedComponent() {
        ExecutorIdentity a = executor();
        ExecutorIdentity b = executor();
        ExecutorWaitGraph graph = graph(a, "a", b, "b");

        graph.record(unit("a-self", a), unit("a-child", a));
        graph.record(unit("b-self", b), unit("b-child", b));
        assertThat(graph.report().cyclicComponents()).hasSize(2);
        assertThat(announcements).hasSize(2);

        graph.record(unit("a-to-b", a), unit("on-b", b));
        assertThat(announcements).hasSize(2);
        graph.record(unit("b-to-a", b), unit("on-a", a));

        assertThat(graph.report().cyclicComponents()).hasSize(1);
        ExecutorWaitReport.Component merged = graph.report().cyclicComponents().get(0);
        assertThat(merged.members()).containsExactlyElementsOf(ids("a", "b"));
        assertThat(merged.cycle()).containsExactlyElementsOf(ids("a", "b"));
        assertThat(announcements).hasSize(3);
    }

    @Test
    void aCompleteGraphReportsOneComponentInsteadOfEnumeratingItsCycles() {
        List<ExecutorIdentity> pools = new ArrayList<>();
        List<Object> registrations = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            ExecutorIdentity pool = executor();
            pools.add(pool);
            registrations.add(pool);
            registrations.add("p" + index);
        }
        ExecutorWaitGraph graph = graph(registrations.toArray());

        for (ExecutorIdentity from : pools) {
            List<TaskNode> awaited = new ArrayList<>();
            for (ExecutorIdentity to : pools) {
                awaited.add(unit("child", to));
            }
            graph.record(unit("caller", from), awaited.size(), awaited::get);
        }

        assertThat(graph.report().cyclicComponents()).hasSize(1);
        assertThat(graph.report().cyclicComponents().get(0).members()).hasSize(12);
        assertThat(graph.report().cyclicComponents().get(0).cycle()).hasSize(2);
    }

    @Test
    void anAnnouncementRejectedAfterShutdownDoesNotFailTheAdmission() {
        ExecutorIdentity a = executor();
        ExecutorWaitGraph graph = new ExecutorWaitGraph(ImmutableSetMultimap.of(a, ParId.of("a")), command -> {
            throw new RejectedExecutionException("shut down");
        });

        graph.record(unit("outer", a), unit("inner", a));

        assertThat(graph.report().cyclicComponents()).hasSize(1);
    }

    @Test
    void aFailingLogHandlerIsContainedInsideTheAnnouncement() {
        ExecutorIdentity a = executor();
        ExecutorWaitGraph graph = graph(a, "a");
        Logger logger = Logger.getLogger(ExecutorWaitGraph.class.getName());
        Handler failing = new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw new IllegalStateException("handler failure");
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        boolean parents = logger.getUseParentHandlers();
        logger.addHandler(failing);
        logger.setUseParentHandlers(false);
        try {
            graph.record(unit("outer", a), unit("inner", a));
            assertThat(announcements).hasSize(1);
            announcements.remove().run();
        } finally {
            logger.removeHandler(failing);
            logger.setUseParentHandlers(parents);
        }
    }

    @Test
    void concurrentAdmissionsClosingOneRingNeitherLoseAWaitNorAnnounceTwice() throws Exception {
        int ring = 6;
        for (int round = 0; round < 200; round++) {
            announcements.clear();
            List<ExecutorIdentity> pools = new ArrayList<>();
            List<Object> registrations = new ArrayList<>();
            for (int index = 0; index < ring; index++) {
                ExecutorIdentity pool = executor();
                pools.add(pool);
                registrations.add(pool);
                registrations.add("r" + index);
            }
            ExecutorWaitGraph graph = graph(registrations.toArray());
            CyclicBarrier start = new CyclicBarrier(ring);
            List<Thread> threads = new ArrayList<>();
            for (int index = 0; index < ring; index++) {
                ExecutorIdentity from = pools.get(index);
                ExecutorIdentity to = pools.get((index + 1) % ring);
                Thread thread = new Thread(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    graph.record(unit("caller", from), unit("child", to));
                });
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join(TimeUnit.SECONDS.toMillis(5));
                assertThat(thread.isAlive()).isFalse();
            }
            assertThat(graph.report().cyclicComponents()).hasSize(1);
            assertThat(graph.report().cyclicComponents().get(0).cycle()).hasSize(ring);
            assertThat(announcements).hasSize(1);
            shutDown();
            executors.clear();
        }
    }

    @Test
    void concurrentAdmissionsOfOneWaitInstallItOnce() throws Exception {
        ExecutorIdentity a = executor();
        ExecutorWaitGraph graph = graph(a, "a");
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Thread> started = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                graph.record(unit("outer", a), 1, ignored -> unit("inner", a));
            });
            started.add(thread);
            thread.start();
        }
        for (Thread thread : started) {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(graph.report().cyclicComponents()).hasSize(1);
        assertThat(announcements).hasSize(1);
        assertThat(ImmutableList.copyOf(graph.report().cyclicComponents().get(0).cycle()))
                .containsExactly(ImmutableSet.of(ParId.of("a")));
    }

    @Test
    void aFailureWhileReadingOneAdmissionDoesNotEscapeRecording() {
        ExecutorIdentity a = executor();
        ExecutorWaitGraph graph = graph(a, "a");
        ExecutorWaitReport before = graph.report();

        graph.record(unit("caller", a), 2, index -> {
            if (index == 0) {
                return unit("self", a);
            }
            throw new IllegalStateException("broken source");
        });

        assertThat(graph.report()).isSameAs(before);
        assertThat(announcements).hasSize(1);
    }
}
