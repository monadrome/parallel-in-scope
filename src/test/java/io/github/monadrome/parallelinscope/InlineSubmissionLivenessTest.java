package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A body running on a borrowed thread stays under the deadline, and gives the thread back clean.
 *
 * <p>When an element's body runs inline it occupies the very thread that submits the rest of the
 * batch. A body that waits for a later element of its own batch therefore holds the only resource
 * that could ever satisfy it. Nothing in the batch can break that on its own, so the deadline is the
 * only source of liveness on this path — which is why the token binds before submission starts
 * rather than after every element is handed off. The timer cancels the element, the cancellation
 * interrupts the thread running its body, and submission resumes.
 *
 * <p>That rescue leaves an interrupt on a thread the library borrowed rather than owns, so {@code
 * run()} restores the thread's entry state on the way out. Without it the caller's next blocking
 * call throws instead of returning the batch's verdict, and the submitter thread's own {@code
 * take()} throws and abandons every element still unsubmitted.
 */
class InlineSubmissionLivenessTest {

    private static final Duration DEADLINE = Duration.ofSeconds(2);
    private static final Duration WATCHDOG = Duration.ofSeconds(20);

    @Test
    void aBatchWhoseInlineBodyWaitsForALaterElementStillConvergesOnItsDeadline() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), direct).build();
        CountDownLatch lastElementStarted = new CountDownLatch(1);
        AtomicBoolean returned = new AtomicBoolean();
        AtomicBoolean flagLeaked = new AtomicBoolean();
        AtomicInteger bodiesStarted = new AtomicInteger();

        Thread caller = new Thread(
                () -> {
                    TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                            .submitBatch(
                                    Arrays.asList(0, 1, 2, 3, 4, 5),
                                    value -> {
                                        bodiesStarted.incrementAndGet();
                                        if (value == 5) {
                                            lastElementStarted.countDown();
                                        }
                                        if (value == 0) {
                                            try {
                                                // Unbounded on purpose: element 5 cannot start until
                                                // this body releases the thread, so only the deadline
                                                // can end this wait.
                                                lastElementStarted.await();
                                            } catch (InterruptedException e) {
                                                // The textbook restore, which is exactly what would
                                                // leak without the isolation in run().
                                                Thread.currentThread().interrupt();
                                                throw new IllegalStateException(e);
                                            }
                                        }
                                        return value;
                                    },
                                    BatchOptions.timeout("inline-liveness", DEADLINE)
                                            .parallelism(6)
                                            .taskType(TaskType.CPU_BOUND));
                    returned.set(true);
                    flagLeaked.set(Thread.currentThread().isInterrupted());
                    assertThat(batch.report().stateCounts()).containsOnlyKeys(TaskOutcome.TIMEOUT);
                },
                "inline-liveness-caller");
        caller.setDaemon(true);
        caller.start();
        caller.join(WATCHDOG.toMillis());

        try {
            assertThat(returned)
                    .as("map() must return on its deadline; a wedged caller thread never would")
                    .isTrue();
            assertThat(flagLeaked)
                    .as("the rescue interrupt must not outlive the body that answered it")
                    .isFalse();
            assertThat(bodiesStarted.get())
                    .as("elements the expired deadline cancelled must never enter user code")
                    .isEqualTo(1);
        } finally {
            lastElementStarted.countDown();
            global.close();
            direct.shutdownNow();
        }
    }

    @Test
    void aCallerFreedByTheDeadlineCanStillReadTheBatchVerdict() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), direct).build();
        CountDownLatch lastElementStarted = new CountDownLatch(1);
        AtomicBoolean awaitAnswered = new AtomicBoolean();
        AtomicBoolean awaitThrew = new AtomicBoolean();

        Thread caller = new Thread(
                () -> {
                    TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                            .submitBatch(
                                    Arrays.asList(0, 1, 2),
                                    value -> {
                                        if (value == 2) {
                                            lastElementStarted.countDown();
                                        }
                                        if (value == 0) {
                                            try {
                                                lastElementStarted.await();
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                throw new IllegalStateException(e);
                                            }
                                        }
                                        return value;
                                    },
                                    BatchOptions.timeout("inline-verdict", DEADLINE)
                                            .parallelism(3)
                                            .taskType(TaskType.CPU_BOUND));
                    // The first thing any real caller does after map() returns. A leaked flag makes
                    // this throw, so the caller learns nothing about why the batch ended.
                    try {
                        awaitAnswered.set(batch.awaitBodyCompletion(Duration.ofSeconds(5)));
                    } catch (InterruptedException e) {
                        awaitThrew.set(true);
                        Thread.currentThread().interrupt();
                    }
                },
                "inline-verdict-caller");
        caller.setDaemon(true);
        caller.start();
        caller.join(WATCHDOG.toMillis());

        try {
            assertThat(awaitThrew)
                    .as("a blocking call after map() must not inherit the rescue interrupt")
                    .isFalse();
            assertThat(awaitAnswered).isTrue();
        } finally {
            lastElementStarted.countDown();
            global.close();
            direct.shutdownNow();
        }
    }

    /**
     * The group control. It already bound before submitting, so it never had the deadlock — this
     * pins that, and pins that the interrupt isolation reaches its result API too: before the
     * isolation, the leaked flag made {@code completionFuture().get()} throw instead of reporting
     * the group's own TIMEOUT verdict.
     */
    @Test
    void aGroupWhoseInlineMemberWaitsForAnotherConvergesAndReportsItsOutcome() throws Exception {
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), direct).build();
        CountDownLatch secondStarted = new CountDownLatch(1);
        TaskOptions memberOptions = TaskOptions.inheritTimeout();

        try {
            TaskGroup<?, Void> group = global.groupDraft("inline-group", DEADLINE)
                    .par("a", global.par(ParId.of("worker")), memberOptions, TypeToken.of(Integer.class), () -> {
                        try {
                            secondStarted.await();
                        } catch (InterruptedException e) {
                            // The textbook restore. It matters here: await() on its own clears the
                            // flag when it throws, so without this the leak never appears and this
                            // test would pass for the wrong reason.
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                        return 1;
                    })
                    .par("b", global.par(ParId.of("worker")), memberOptions, TypeToken.of(Integer.class), () -> {
                        secondStarted.countDown();
                        return 2;
                    })
                    .submitAll();

            // submitAll() ran the inline member on this thread, so the flag is read before any
            // blocking call: a leak here is what made get() below throw instead of reporting the
            // group's verdict.
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the submitting thread must be handed back clean")
                    .isFalse();

            TaskGroupReport result = group.completionFuture().get(WATCHDOG.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
        } finally {
            secondStarted.countDown();
            global.close();
            direct.shutdownNow();
        }
    }

    /**
     * The pooled-path control: a body that restores the interrupt flag must not poison the next task
     * on the same worker. {@code ThreadPoolExecutor.runWorker} already clears it, so this pins that
     * the isolation in {@code run()} neither relies on nor disturbs that immunity.
     */
    @Test
    void aRestoredInterruptOnAPooledWorkerDoesNotReachTheNextTaskOnThatWorker() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), single).build();
        try {
            TaskBatch<Boolean> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(0, 1),
                            value -> {
                                boolean flagOnEntry = Thread.currentThread().isInterrupted();
                                if (value == 0) {
                                    Thread.currentThread().interrupt();
                                }
                                return flagOnEntry;
                            },
                            BatchOptions.timeout("pooled-control", Duration.ofSeconds(10))
                                    .parallelism(1)
                                    .taskType(TaskType.IO_BOUND));

            assertThat(batch.valuesOrThrow()).containsExactly(false, false);
        } finally {
            global.close();
            single.shutdownNow();
        }
    }

    /**
     * The submitter-thread case, which is the one that silently lost work: the sliding-window refill
     * calls the handoff on the library's own submitter thread, so a body running inline there left a
     * dirty flag on a thread the library owns. The refill loop's {@code take()} then threw at once
     * and abandoned every element still unsubmitted.
     */
    @Test
    void aBodyRunningInlineOnTheSubmitterThreadDoesNotAbandonTheRestOfTheBatch() throws Exception {
        // Core pool of 1 with a zero-capacity queue and CallerRunsPolicy: the first element
        // occupies the worker and every later handoff runs inline on the submitting thread, so the
        // window-external elements run on the library's submitter thread rather than the caller's.
        ThreadPoolExecutor saturated = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new java.util.concurrent.SynchronousQueue<Runnable>(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), saturated).build();
        AtomicInteger executed = new AtomicInteger();
        try {
            TaskBatch<Integer> batch = global.par(ParId.of("worker"))
                    .submitBatch(
                            Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
                            value -> {
                                executed.incrementAndGet();
                                // Every body restores the flag, the textbook way.
                                Thread.currentThread().interrupt();
                                return value;
                            },
                            BatchOptions.timeout("submitter-thread", Duration.ofSeconds(15))
                                    .parallelism(1)
                                    .taskType(TaskType.CPU_BOUND));

            assertThat(batch.awaitBodyCompletion(Duration.ofSeconds(15))).isTrue();
            assertThat(executed.get())
                    .as("a dirty flag on the submitter thread must not abandon unsubmitted elements")
                    .isEqualTo(12);
            assertThat(batch.report().stateCounts()).doesNotContainKey(TaskOutcome.SUBMISSION_FAILURE);
        } finally {
            global.close();
            saturated.shutdownNow();
        }
    }
}
