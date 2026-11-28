package io.github.monadrome.parallelinscope.assurance;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import io.github.monadrome.parallelinscope.CancellationReason;
import io.github.monadrome.parallelinscope.ExecutorWaitReport;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.ScopeTermination;
import io.github.monadrome.parallelinscope.TaskGroupResult;
import io.github.monadrome.parallelinscope.TaskKey;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Candidate CONTRACT-TDD-012, contract C-7.
 *
 * <p>Precondition: a built {@link ParRuntime} registering one single-worker {@code ThreadPoolExecutor}
 * {@code io} with an unbounded buffering queue.
 *
 * <p>Operation sequence: inside a task execution running on {@code io}, submit a source future
 * directly to the same pool (it queues behind the busy worker), then run a group with an explicit
 * timeout that adopts that source with producer {@code io}, and wait for its {@code runAll()}.
 *
 * <p>Observation points: the group's scope termination, the source's cancellation state, and the
 * runtime's {@code executorWaitReport()}.
 *
 * <p>Assertions: the group ends cancelled with reason kind {@code DEADLINE_EXCEEDED}, the adopted
 * source is cancelled, and the report has one cyclic component whose cycle is the single executor
 * {@code io} (a self-loop).
 *
 * <p>Uncertainty: the "no wrap/submit/cancel/reorder on the source's behalf" obligations are not
 * directly observable through the public API and are covered only by the three observable outcomes;
 * the registered executor is the raw pool, while a Guava listening view is used solely to submit
 * the source as a {@code ListenableFuture}.
 */
public class ContractTdd012AdoptedSourceQueuedBehindItsWaiterTest {

    @Test
    void adoptedSourceQueuedBehindItsOwnWaiterEndsAtDeadlineAndRecordsSelfLoop() throws Exception {
        ThreadPoolExecutor ioPool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        ListeningExecutorService ioSubmit = MoreExecutors.listeningDecorator(ioPool);
        ParId ioId = ParId.of("io");
        ParRuntime runtime = ParRuntime.builder().register(ioId, ioPool).defaultPar(ioId).build();
        Par ioPar = runtime.defaultPar();
        AtomicReference<ListenableFuture<String>> sourceRef = new AtomicReference<>();
        AtomicReference<TaskGroupResult<Void>> innerRef = new AtomicReference<>();
        try {
            TaskKey<Object> outerKey = TaskKey.named("outer");
            TaskGroupResult<Void> outer = runtime.group("outer", Duration.ofSeconds(10))
                    .par(outerKey, ioPar, () -> {
                        ListenableFuture<String> source = ioSubmit.submit(() -> "queued");
                        sourceRef.set(source);
                        TaskKey<String> srcKey = TaskKey.named("src");
                        TaskGroupResult<Void> inner = runtime.group("inner", Duration.ofMillis(300))
                                .adopt(srcKey, ioPar, source)
                                .runAll();
                        innerRef.set(inner);
                        return null;
                    })
                    .runAll();

            assertThat(outer.scopeTermination().successful()).isTrue();

            TaskGroupResult<Void> inner = innerRef.get();
            assertThat(inner.scopeTermination().kind()).isEqualTo(ScopeTermination.Kind.CANCELLED);
            assertThat(inner.scopeTermination().cancellationReason()).isNotNull();
            assertThat(inner.scopeTermination().cancellationReason().kind())
                    .isEqualTo(CancellationReason.Kind.DEADLINE_EXCEEDED);
            assertThat(sourceRef.get().isCancelled()).isTrue();

            ExecutorWaitReport report = runtime.executorWaitReport();
            assertThat(report.cyclicComponents()).hasSize(1);
            ExecutorWaitReport.Component component = report.cyclicComponents().get(0);
            assertThat(component.cycle()).hasSize(1);
            assertThat(component.cycle().get(0)).contains(ioId);
        } finally {
            runtime.close();
            ioPool.shutdownNow();
        }
    }
}
