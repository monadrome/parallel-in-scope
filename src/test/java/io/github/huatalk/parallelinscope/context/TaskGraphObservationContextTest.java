package io.github.huatalk.parallelinscope.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.huatalk.parallelinscope.context.graph.TaskGraph;
import io.github.huatalk.parallelinscope.scope.GlobalPar;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Lifecycle semantics of {@link TaskGraphObservationContext}: ownership, polarity, idempotence. */
class TaskGraphObservationContextTest {

    private GlobalPar global;

    @AfterEach
    void cleanUp() {
        TaskGraph.restore(null);
        if (global != null) {
            global.close();
        }
    }

    @Test
    void openTaskGraphObservationExposesOwnerPreviousDataAndCurrentPolarity() {
        global = GlobalPar.builder().build();
        TaskGraphObservationContext context = global.openTaskGraphObservation();
        try {
            assertThat(context.owner()).isSameAs(global);
            assertThat(context.isClosed()).isFalse();
            assertThat(TaskGraphObservationContext.current()).isSameAs(context);
            assertThat(context.previousData()).isNull(); // No outer graph was active.
        } finally {
            context.close();
        }

        assertThat(context.isClosed()).isTrue();
        assertThat(TaskGraphObservationContext.current()).isNull();

        // current() stays null after closing even without a fresh thread-local reset.
        assertThat(TaskGraphObservationContext.current()).isNull();
    }

    @Test
    void nestedObservationsRestoreTheOuterGraphData() throws Exception {
        global = GlobalPar.builder()
                .register("io", Executors.newSingleThreadExecutor())
                .build();
        try (TaskGraphObservationContext outer = global.openTaskGraphObservation()) {
            outer.previousData(); // outer has no earlier data
            TaskGraph.Data outerData = TaskGraph.data();
            assertThat(outerData).isNotNull();

            TaskGraphObservationContext inner = global.openTaskGraphObservation();
            assertThat(inner.previousData()).isSameAs(outerData);
            inner.close();

            assertThat(TaskGraphObservationContext.current()).isSameAs(outer);
            assertThat(TaskGraph.data()).isSameAs(outerData);
        }
        assertThat(TaskGraphObservationContext.current()).isNull();
    }

    @Test
    void doubleCloseDestroysTheGraphOnlyOnce() throws Exception {
        java.util.concurrent.atomic.AtomicInteger detections =
                new java.util.concurrent.atomic.AtomicInteger();
        global = GlobalPar.builder()
                .livelockPolicy(io.github.huatalk.parallelinscope.scope.GlobalParLivelockPolicy.builder()
                        .enabled(true)
                        .listener(event -> detections.incrementAndGet())
                        .build())
                .build();

        TaskGraphObservationContext context = global.openTaskGraphObservation();
        TaskGraph.logTaskPair("a", "a", "b", "b", new io.github.huatalk.parallelinscope.context.graph.TaskEdge(
                1, io.github.huatalk.parallelinscope.scope.TaskType.IO_BOUND, "e1", "e2", 1, 10L));
        TaskGraph.logTaskPair("b", "b", "a", "a", new io.github.huatalk.parallelinscope.context.graph.TaskEdge(
                1, io.github.huatalk.parallelinscope.scope.TaskType.IO_BOUND, "e2", "e1", 1, 10L));

        context.close();
        int afterFirstClose = detections.get();
        context.close(); // Idempotent: no second detection pass.
        assertThat(detections.get()).isEqualTo(afterFirstClose);
        assertThat(afterFirstClose).isEqualTo(1);
    }

    @Test
    void initForObservationRejectsClosedObservationContexts() throws Exception {
        global = GlobalPar.builder().build();
        TaskGraphObservationContext context = global.openTaskGraphObservation();
        context.close();
        assertThatThrownBy(() -> TaskGraph.initForObservation(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already closed");
        assertThatThrownBy(() -> new TaskGraphObservationContext(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void completeMarksClosedWithoutListenerSideEffects() throws Exception {
        java.util.concurrent.atomic.AtomicInteger detections =
                new java.util.concurrent.atomic.AtomicInteger();
        global = GlobalPar.builder()
                .livelockPolicy(io.github.huatalk.parallelinscope.scope.GlobalParLivelockPolicy.builder()
                        .enabled(true)
                        .listener(event -> detections.incrementAndGet())
                        .build())
                .build();
        TaskGraphObservationContext context = global.openTaskGraphObservation();
        context.complete();
        assertThat(context.isClosed()).isTrue();
        assertThat(detections.get()).isZero();
        context.close();
        assertThat(detections.get()).isZero();
    }
}
