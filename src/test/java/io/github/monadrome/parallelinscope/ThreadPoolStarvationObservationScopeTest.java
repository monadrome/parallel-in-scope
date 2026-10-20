package io.github.monadrome.parallelinscope;

import static com.google.common.base.Verify.verifyNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.ttl.threadpool.TtlExecutors;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("NullAway.Init")
class ThreadPoolStarvationObservationScopeTest {

    private ParRuntime runtime;
    private ExecutorService executor;
    private ExecutorService secondExecutor;
    private final List<ThreadPoolStarvationObservationScope> observations = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (ThreadPoolStarvationObservationScope observation : observations) {
            observation.close();
        }
        if (runtime != null) {
            runtime.close();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (secondExecutor != null) {
            secondExecutor.shutdownNow();
        }
    }

    @Test
    void detectsSustainedBacklogFromDirectExecutorSubmissions() throws Exception {
        CountingSizeQueue queue = new CountingSizeQueue();
        ThreadPoolExecutor pool = registerPool(queue);
        CountDownLatch release = saturate(pool);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(20));
        awaitCalls(queue, 3);
        observation.close();

        ThreadPoolStarvationReport report = report(observation);
        assertThat(report.status()).isEqualTo(ThreadPoolStarvationReport.Status.POTENTIAL_STARVATION);
        assertThat(report.anyIssue()).isTrue();
        assertThat(report.samplingInterval()).isEqualTo(Duration.ofMillis(20));
        assertThat(report.failureMessage()).isNull();
        assertThat(report.samples()).isGreaterThanOrEqualTo(3);
        assertThat(report.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.executor()).isEqualTo("worker");
            assertThat(evidence.poolSize()).isEqualTo(1);
            assertThat(evidence.activeCount()).isEqualTo(1);
            assertThat(evidence.queueSize()).isEqualTo(1);
            assertThat(evidence.completedTaskCount()).isZero();
            assertThat(evidence.stagnantIntervals()).isGreaterThanOrEqualTo(2);
            assertThat(evidence.toString()).contains("stagnantIntervals=");
        });
        assertThat(report.toString()).contains("samplingInterval=PT0.02S", "failureMessage=null");
        assertThat(runtime.snapshot().activeObservations()).isZero();
        release.countDown();
    }

    @Test
    void doesNotReportBacklogThatMakesProgress() throws Exception {
        GatedSizeQueue queue = new GatedSizeQueue();
        ThreadPoolExecutor pool = registerPool(queue);
        CountDownLatch release = saturate(pool);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(10));
        try {
            assertThat(queue.entered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> pool.getCompletedTaskCount() == 2);
        } finally {
            queue.release.countDown();
        }
        observation.close();

        ThreadPoolStarvationReport report = report(observation);
        assertThat(report.status()).isEqualTo(ThreadPoolStarvationReport.Status.NO_ISSUE);
        assertThat(report.anyIssue()).isFalse();
        assertThat(report.evidence()).isEmpty();
    }

    @Test
    void immediateCloseDoesNotPairSamplesBeforeTheInterval() throws Exception {
        ThreadPoolExecutor pool = registerPool(new LinkedBlockingQueue<>());
        CountDownLatch release = saturate(pool);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofSeconds(10));
        observation.close();

        ThreadPoolStarvationReport report = report(observation);
        assertThat(report.status()).isEqualTo(ThreadPoolStarvationReport.Status.NO_ISSUE);
        assertThat(report.samples()).isEqualTo(2);
        assertThat(report.evidence()).isEmpty();
        release.countDown();
    }

    @Test
    void mergesPhysicalExecutorLabelsThroughTtlWrapper() throws Exception {
        CountingSizeQueue queue = new CountingSizeQueue();
        ThreadPoolExecutor pool = newPool(queue);
        executor = pool;
        runtime = ParRuntime.builder()
                .register(ParId.of("a"), pool)
                .register(ParId.of("b"), TtlExecutors.getTtlExecutorService(pool))
                .register(ParId.of("c"), pool)
                .build();
        queue.observing.set(true);
        CountDownLatch release = saturate(pool);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(20));
        awaitCalls(queue, 3);
        observation.close();

        ThreadPoolStarvationReport report = report(observation);
        assertThat(report.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.executor()).isEqualTo("a,b,c");
            assertThat(evidence.stagnantIntervals()).isPositive();
        });
        assertThat(report.unsupportedExecutors()).isEmpty();
        release.countDown();
    }

    @Test
    void reportsScheduledSamplingFailureWithoutFailingClose() throws Exception {
        FailingSizeQueue queue = new FailingSizeQueue(2, new IllegalStateException("queue size failed"));
        registerPool(queue);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(10));
        ScheduledFuture<?> scheduled = sampler(observation);
        awaitCalls(queue, 2);
        awaitSchedulerTicks(3);
        assertThat(queue.sizeCalls.get()).isEqualTo(2);
        assertThat(scheduled.isCancelled()).isTrue();
        observation.close();

        ThreadPoolStarvationReport report = report(observation);
        assertThat(report.status()).isEqualTo(ThreadPoolStarvationReport.Status.SAMPLING_FAILED);
        assertThat(report.failureMessage()).isEqualTo("queue size failed");
        assertThat(report.samples()).isEqualTo(2);
        assertThat(report.anyIssue()).isFalse();
    }

    @Test
    void reportsFinalSamplingFailureWithoutFailingClose() throws Exception {
        FailingSizeQueue queue = new FailingSizeQueue(2, new IllegalStateException("final sample failed"));
        registerPool(queue);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofSeconds(10));
        observation.close();

        assertThat(report(observation).status()).isEqualTo(ThreadPoolStarvationReport.Status.SAMPLING_FAILED);
        assertThat(report(observation).failureMessage()).isEqualTo("final sample failed");
        assertThat(report(observation).samples()).isEqualTo(1);
    }

    @Test
    void reportsInitialSamplingFailureWithNonemptyFallbackMessage() throws Exception {
        FailingSizeQueue queue = new FailingSizeQueue(1, new IllegalStateException());
        registerPool(queue);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(10));
        awaitSchedulerTicks(3);
        assertThat(queue.sizeCalls.get()).isEqualTo(1);
        observation.close();

        assertThat(report(observation).status()).isEqualTo(ThreadPoolStarvationReport.Status.SAMPLING_FAILED);
        assertThat(report(observation).failureMessage()).isEqualTo(IllegalStateException.class.getName());
        assertThat(report(observation).samples()).isEqualTo(1);
    }

    @Test
    void publishesReportBeforeRethrowingScheduledSamplingError() throws Exception {
        AssertionError failure = new AssertionError("scheduled sample failed");
        FailingSizeQueue queue = new FailingSizeQueue(2, failure);
        registerPool(queue);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(10));
        awaitCalls(queue, 2);

        assertThatThrownBy(observation::close).isSameAs(failure);
        assertThat(report(observation).status()).isEqualTo(ThreadPoolStarvationReport.Status.SAMPLING_FAILED);
        assertThat(runtime.snapshot().activeObservations()).isZero();
    }

    @Test
    void publishesReportBeforeRethrowingFinalSamplingError() throws Exception {
        AssertionError failure = new AssertionError("final sample failed");
        registerPool(new FailingSizeQueue(2, failure));
        ThreadPoolStarvationObservationScope observation = open(Duration.ofSeconds(10));

        assertThatThrownBy(observation::close).isSameAs(failure);
        assertThat(report(observation).failureMessage()).isEqualTo("final sample failed");
        assertThat(runtime.snapshot().activeObservations()).isZero();
    }

    @Test
    void firstFailureMessageWinsWhileALaterFinalErrorStillPropagates() throws Exception {
        AssertionError finalError = new AssertionError("second failure");
        CountingSizeQueue queue = new CountingSizeQueue() {
            @Override
            public int size() {
                int size = super.size();
                if (sizeCalls.get() == 2) {
                    throw new IllegalStateException("first failure");
                }
                if (sizeCalls.get() == 3) {
                    throw finalError;
                }
                return size;
            }
        };
        registerPool(queue);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(10));
        awaitCalls(queue, 2);

        assertThatThrownBy(observation::close).isSameAs(finalError);
        assertThat(report(observation).status()).isEqualTo(ThreadPoolStarvationReport.Status.SAMPLING_FAILED);
        assertThat(report(observation).failureMessage()).isEqualTo("first failure");
        assertThat(report(observation).samples()).isEqualTo(1);
        assertThat(runtime.snapshot().activeObservations()).isZero();
    }

    @Test
    void concurrentCloseCallsAllReturnAndReleaseObservation() throws Exception {
        GatedSizeQueue queue = new GatedSizeQueue();
        registerPool(queue);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(10));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger publications = new AtomicInteger();
        observation.reportFuture().addListener(publications::incrementAndGet, MoreExecutors.directExecutor());
        try {
            assertThat(queue.entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 4; i++) {
                Thread closer = new Thread(
                        () -> {
                            try {
                                Uninterruptibles.awaitUninterruptibly(start);
                                observation.close();
                            } catch (Throwable error) {
                                failure.compareAndSet(null, error);
                            } finally {
                                done.countDown();
                            }
                        },
                        "starvation-closer-" + i);
                closer.setDaemon(true);
                closer.start();
            }
            start.countDown();
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(observation::closed);
            assertThat(observation.reportFuture().isDone()).isFalse();
        } finally {
            queue.release.countDown();
        }
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(observation.reportFuture().isDone()).isTrue();
        assertThat(publications.get()).isEqualTo(1);
        assertThat(runtime.snapshot().activeObservations()).isZero();
    }

    @Test
    void concurrentClosersPreserveInterruptionWhileWaitingForSamplingAndPublication() throws Exception {
        GatedSizeQueue queue = new GatedSizeQueue();
        registerPool(queue);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(10));
        AtomicBoolean winnerInterrupted = new AtomicBoolean();
        AtomicBoolean loserInterrupted = new AtomicBoolean();
        Thread winner = new Thread(
                () -> {
                    Thread.currentThread().interrupt();
                    observation.close();
                    winnerInterrupted.set(Thread.currentThread().isInterrupted());
                },
                "starvation-close-winner");
        Thread loser = new Thread(
                () -> {
                    Thread.currentThread().interrupt();
                    observation.close();
                    loserInterrupted.set(Thread.currentThread().isInterrupted());
                },
                "starvation-close-loser");
        winner.setDaemon(true);
        loser.setDaemon(true);
        try {
            assertThat(queue.entered.await(5, TimeUnit.SECONDS)).isTrue();
            winner.start();
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(observation::closed);
            loser.start();
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .until(() -> winner.getState() == Thread.State.WAITING && loser.getState() == Thread.State.WAITING);
        } finally {
            queue.release.countDown();
        }
        winner.join(5000);
        loser.join(5000);
        assertThat(winner.isAlive()).isFalse();
        assertThat(loser.isAlive()).isFalse();
        assertThat(winnerInterrupted.get()).isTrue();
        assertThat(loserInterrupted.get()).isTrue();
        assertThat(runtime.snapshot().activeObservations()).isZero();
    }

    @Test
    @SuppressWarnings("NullAway")
    void validatesIntervalAndObservationLifecycle() {
        runtime = ParRuntime.builder().build();
        assertThatThrownBy(() -> runtime.openThreadPoolStarvationObservation(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> runtime.openThreadPoolStarvationObservation(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.openThreadPoolStarvationObservation(Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.openThreadPoolStarvationObservation(Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.openThreadPoolStarvationObservation(Duration.ofMillis(9)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10 ms")
                .hasMessageContaining("PT0.009S");
        assertThat(runtime.snapshot().activeObservations()).isZero();
        runtime.close();
        assertThatThrownBy(() -> runtime.openThreadPoolStarvationObservation(Duration.ofMillis(10)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runtime.snapshot().activeObservations()).isZero();
    }

    @Test
    void multipleObservationsTrackAndPublishIndependently() throws Exception {
        runtime = ParRuntime.builder().build();
        ThreadPoolStarvationObservationScope first = open(Duration.ofMillis(10));
        ThreadPoolStarvationObservationScope second = open(Duration.ofSeconds(10));
        assertThat(runtime.inFlight()).isEqualTo(2);
        assertThat(runtime.snapshot().activeObservations()).isEqualTo(2);
        assertThat(first.reportFuture()).isNotSameAs(second.reportFuture());
        first.close();
        assertThat(runtime.inFlight()).isEqualTo(1);
        assertThat(runtime.snapshot().activeObservations()).isEqualTo(1);
        assertThat(second.reportFuture().isDone()).isFalse();
        second.close();
        assertThat(report(first)).isNotSameAs(report(second));
        assertThat(report(first).samplingInterval()).isEqualTo(Duration.ofMillis(10));
        assertThat(report(second).samplingInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(runtime.snapshot().activeObservations()).isZero();
    }

    @Test
    void evidenceTakesPriorityOverUnsupportedExecutorsAndSamplingFailure() throws Exception {
        FailingSizeQueue queue = new FailingSizeQueue(4, new IllegalStateException("later sampling failure"));
        ThreadPoolExecutor pool = newPool(queue);
        executor = pool;
        secondExecutor = Executors.newSingleThreadExecutor();
        runtime = ParRuntime.builder()
                .register(ParId.of("visible"), pool)
                .register(ParId.of("unsupported"), secondExecutor)
                .build();
        queue.observing.set(true);
        CountDownLatch release = saturate(pool);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(20));
        awaitCalls(queue, 4);
        observation.close();

        ThreadPoolStarvationReport report = report(observation);
        assertThat(report.status()).isEqualTo(ThreadPoolStarvationReport.Status.POTENTIAL_STARVATION);
        assertThat(report.unsupportedExecutors()).containsExactly("unsupported");
        assertThat(report.failureMessage()).isEqualTo("later sampling failure");
        assertThat(report.evidence()).hasSize(1);
        release.countDown();
    }

    @Test
    void samplingFailureTakesPriorityOverUnsupportedExecutors() throws Exception {
        FailingSizeQueue queue = new FailingSizeQueue(1, new IllegalStateException("initial failure"));
        executor = newPool(queue);
        secondExecutor = Executors.newSingleThreadExecutor();
        runtime = ParRuntime.builder()
                .register(ParId.of("visible"), executor)
                .register(ParId.of("unsupported"), secondExecutor)
                .build();
        queue.observing.set(true);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofSeconds(10));
        observation.close();

        assertThat(report(observation).status()).isEqualTo(ThreadPoolStarvationReport.Status.SAMPLING_FAILED);
        assertThat(report(observation).unsupportedExecutors()).containsExactly("unsupported");
    }

    @Test
    void recoveryDoesNotClearWindowEvidence() throws Exception {
        CountingSizeQueue queue = new CountingSizeQueue();
        ThreadPoolExecutor pool = registerPool(queue);
        pool.submit(() -> {}).get(5, TimeUnit.SECONDS);
        CountDownLatch release = saturate(pool);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(10));
        awaitCalls(queue, 3);
        release.countDown();
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> pool.getCompletedTaskCount() == 3);
        observation.close();

        assertThat(report(observation).status()).isEqualTo(ThreadPoolStarvationReport.Status.POTENTIAL_STARVATION);
        assertThat(report(observation).evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.completedTaskCount()).isEqualTo(1);
            assertThat(evidence.queueSize()).isEqualTo(1);
        });
    }

    @Test
    void closeStopsPeriodicSampling() throws Exception {
        CountingSizeQueue queue = new CountingSizeQueue();
        registerPool(queue);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(10));
        ScheduledFuture<?> scheduled = sampler(observation);
        assertThat(observation.closed()).isFalse();
        awaitCalls(queue, 2);
        observation.close();
        assertThat(scheduled.isCancelled()).isTrue();
        int callsAfterClose = queue.sizeCalls.get();
        awaitSchedulerTicks(3);
        assertThat(queue.sizeCalls.get()).isEqualTo(callsAfterClose);
    }

    @Test
    void blockedSamplingDoesNotDelayDeadlineDetection() throws Exception {
        GatedSizeQueue queue = new GatedSizeQueue();
        registerPool(queue);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(10));
        try {
            assertThat(queue.entered.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch deadline = new CountDownLatch(1);
            runtime.timeoutScheduler().schedule(deadline::countDown, 0, TimeUnit.NANOSECONDS);
            assertThat(deadline.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(queue.samplingThread.get()).startsWith("ParRuntime-starvation-");
            assertThat(queue.samplingDaemon.get()).isTrue();
        } finally {
            queue.release.countDown();
            observation.close();
        }
    }

    @Test
    void delayedSamplingWaitsAFullIntervalBeforeTheNextInvocation() throws Exception {
        runtime = ParRuntime.builder().build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondInvocation = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicLong firstCompletedAt = new AtomicLong();
        AtomicLong delayBetweenInvocations = new AtomicLong();
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(50);
        ScheduledFuture<?> sampler = runtime.scheduleStarvationSampling(
                () -> {
                    if (invocations.incrementAndGet() == 1) {
                        entered.countDown();
                        assertThat(Uninterruptibles.awaitUninterruptibly(release, 5, TimeUnit.SECONDS))
                                .isTrue();
                        firstCompletedAt.set(System.nanoTime());
                    } else {
                        delayBetweenInvocations.compareAndSet(0, System.nanoTime() - firstCompletedAt.get());
                        secondInvocation.countDown();
                    }
                },
                intervalNanos);
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            runtime.timeoutScheduler().schedule(release::countDown, 150, TimeUnit.MILLISECONDS);
            assertThat(secondInvocation.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(delayBetweenInvocations.get()).isGreaterThanOrEqualTo(intervalNanos);
        } finally {
            release.countDown();
            sampler.cancel(false);
        }
    }

    @Test
    void identifiesExecutorsThatCannotBeInspected() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        runtime = ParRuntime.builder().register(ParId.of("wrapped"), executor).build();
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(20));
        observation.close();

        assertThat(report(observation).status()).isEqualTo(ThreadPoolStarvationReport.Status.INSUFFICIENT_VISIBILITY);
        assertThat(report(observation).unsupportedExecutors()).containsExactly("wrapped");
    }

    @Test
    void closingAnObservationIsIdempotentAndItsReportCannotBeCancelled() {
        runtime = ParRuntime.builder().build();
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(20));
        assertThat(observation.reportFuture().cancel(true)).isFalse();
        assertThat(observation.reportFuture().isDone()).isFalse();
        observation.close();
        observation.close();

        assertThat(observation.reportFuture().cancel(false)).isFalse();
        assertThat(observation.closed()).isTrue();
    }

    @Test
    void runtimeCloseWaitsForAnOpenObservationBeforeRetiringServices() throws Exception {
        CountingSizeQueue queue = new CountingSizeQueue();
        registerPool(queue);
        ThreadPoolStarvationObservationScope observation = open(Duration.ofMillis(10));
        runtime.close();
        awaitCalls(queue, 3);

        assertThat(runtime.inFlight()).isEqualTo(1);
        assertThat(runtime.snapshot().activeObservations()).isEqualTo(1);
        assertThat(runtime.awaitQuiescence(Duration.ofMillis(20))).isFalse();
        observation.close();
        assertThat(runtime.awaitQuiescence(Duration.ofSeconds(2))).isTrue();
        assertThatThrownBy(() -> runtime.scheduleStarvationSampling(() -> {}, 10000000))
                .isInstanceOf(RejectedExecutionException.class);
    }

    private ThreadPoolExecutor registerPool(LinkedBlockingQueue<Runnable> queue) {
        ThreadPoolExecutor pool = newPool(queue);
        executor = pool;
        runtime = ParRuntime.builder().register(ParId.of("worker"), pool).build();
        if (queue instanceof CountingSizeQueue) {
            ((CountingSizeQueue) queue).observing.set(true);
        }
        return pool;
    }

    private ThreadPoolStarvationObservationScope open(Duration interval) {
        ThreadPoolStarvationObservationScope observation = runtime.openThreadPoolStarvationObservation(interval);
        observations.add(observation);
        return observation;
    }

    private void awaitSchedulerTicks(int ticks) throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(ticks);
        ScheduledFuture<?> barrier = runtime.scheduleStarvationSampling(completed::countDown, 10000000);
        try {
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            barrier.cancel(false);
        }
    }

    private static ThreadPoolStarvationReport report(ThreadPoolStarvationObservationScope observation)
            throws Exception {
        return verifyNotNull(Futures.getDone(observation.reportFuture()));
    }

    private static ScheduledFuture<?> sampler(ThreadPoolStarvationObservationScope observation) throws Exception {
        Field field = ThreadPoolStarvationObservationScope.class.getDeclaredField("sampler");
        field.setAccessible(true);
        AtomicReference<?> reference = (AtomicReference<?>) verifyNotNull(field.get(observation));
        return (ScheduledFuture<?>) verifyNotNull(reference.get());
    }

    private static void awaitCalls(CountingSizeQueue queue, int count) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> queue.sizeCalls.get() >= count);
    }

    private static CountDownLatch saturate(ThreadPoolExecutor pool) throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        pool.execute(() -> {});
        return release;
    }

    private static ThreadPoolExecutor newPool(LinkedBlockingQueue<Runnable> queue) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue);
    }

    private static class CountingSizeQueue extends LinkedBlockingQueue<Runnable> {
        final AtomicInteger sizeCalls = new AtomicInteger();
        final AtomicBoolean observing = new AtomicBoolean();

        @Override
        public int size() {
            if (observing.get()) {
                sizeCalls.incrementAndGet();
            }
            return super.size();
        }
    }

    private static final class GatedSizeQueue extends LinkedBlockingQueue<Runnable> {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<String> samplingThread = new AtomicReference<>("");
        private final AtomicBoolean samplingDaemon = new AtomicBoolean();

        @Override
        public int size() {
            if (Thread.currentThread().getName().startsWith("ParRuntime-")) {
                samplingThread.set(Thread.currentThread().getName());
                samplingDaemon.set(Thread.currentThread().isDaemon());
                entered.countDown();
                assertThat(Uninterruptibles.awaitUninterruptibly(release, 10, TimeUnit.SECONDS))
                        .isTrue();
            }
            return super.size();
        }
    }

    private static final class FailingSizeQueue extends CountingSizeQueue {
        private final int failingCall;
        private final Throwable failure;

        private FailingSizeQueue(int failingCall, Throwable failure) {
            this.failingCall = failingCall;
            this.failure = failure;
        }

        @Override
        public int size() {
            int size = super.size();
            if (sizeCalls.get() == failingCall) {
                if (failure instanceof Error) {
                    throw (Error) failure;
                }
                throw (RuntimeException) failure;
            }
            return size;
        }
    }
}
