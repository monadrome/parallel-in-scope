package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Locks the one execution site the terminal combine is allowed to use: a worker of its own {@code
 * Par}.
 *
 * <p>The combine is submitted at join time by the convergence callback, so the thread handing it to
 * the executor is a framework thread whose contract forbids user code; the combine therefore
 * declares {@code forbidInlineExecution}. A submission-time refusal alone cannot enforce that
 * contract: a pool whose {@code RejectedExecutionHandler} runs the task inside {@code execute()} —
 * the JDK's {@code CallerRunsPolicy} — reaches the body without ever raising {@code
 * RejectedExecutionException}. Without the execution-time guard the group then reported {@code
 * SUCCESS} while the user function had run on the convergence callback thread, which is the first
 * candidate the design rejects because which member finishes last is a race.
 *
 * <p>The guard is deliberately not a refusal to accept such a pool. {@code CallerRunsPolicy} only
 * runs inline under genuine saturation, so a pool that never saturates never violates anything and
 * keeps working; the check fires on the execution that actually breaks the guarantee.
 */
class TaskGroupCombineInlineGuardTest {

    private static ThreadPoolExecutor saturatingPool(String threadName) {
        return new ThreadPoolExecutor(
                2,
                2,
                0,
                TimeUnit.SECONDS,
                SmartBlockingQueue.create(100),
                r -> new Thread(r, threadName),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Test
    void combineFailsAsSubmissionFailureWhenItsPoolWouldRunItOnTheConvergenceThread() throws Exception {
        ExecutorService memberPool = Executors.newFixedThreadPool(2, r -> new Thread(r, "member"));
        ThreadPoolExecutor combinePool = saturatingPool("combine-worker");
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("m"), memberPool)
                .register(ParId.of("c"), combinePool)
                .build();
        // The combine's Par is shared, as a registered Par is meant to be, and other work occupies
        // both of its threads. Nothing here forces a rejection: the pool is simply busy, which is the
        // state any loaded pool is in.
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            combinePool.execute(() -> {
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        AtomicReference<String> combineThread = new AtomicReference<>();
        try {
            TaskGroup<?, String> group = runtime.groupDraft("g", Duration.ofSeconds(10))
                    .par(
                            "a",
                            runtime.par(ParId.of("m")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            () -> "a")
                    .par(
                            "b",
                            runtime.par(ParId.of("m")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            () -> "b")
                    .combine(
                            "sum",
                            runtime.par(ParId.of("c")),
                            // rejectEnqueue is requested so the queue refuses the combine and the
                            // handler decides its fate. Without it the default enqueues onto the
                            // 100-deep queue and the combine simply waits for a worker, which is the
                            // correct behavior but not the one under test here.
                            TaskOptions.inheritTimeout().rejectEnqueue(true),
                            TypeToken.of(String.class),
                            values -> {
                                combineThread.set(Thread.currentThread().getName());
                                return "combined";
                            })
                    .submitAll();

            TaskGroupReport result = group.completionFuture().get(10, TimeUnit.SECONDS);

            // The violation is now visible instead of silent, and the body never ran at all.
            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            assertThat(combineThread.get()).isNull();
            assertThat(result.failedTaskName()).isEqualTo("sum");
            // Safe in this direction where an equality on a positive count would not be: the counter
            // only ever lags what has actually finished, so it cannot report work that never ran. The
            // two occupying tasks are still parked on the latch, which is released in the finally.
            assertThat(combinePool.getCompletedTaskCount()).isEqualTo(0);
        } finally {
            release.countDown();
            runtime.close();
            memberPool.shutdownNow();
            combinePool.shutdownNow();
        }
    }

    @Test
    void aRefusedCombineStillReleasesItsBodyCompletionSlot() throws Exception {
        // The guard fires after the phase claim has already moved the body slot to RUNNING, so the
        // slot must be released by the exit publish rather than the skip transition. Getting that
        // wrong strands the body-completion barrier: close() and awaitBodyCompletion() would wait for
        // a body that is never going to run.
        ExecutorService memberPool = Executors.newFixedThreadPool(2, r -> new Thread(r, "member"));
        ThreadPoolExecutor combinePool = saturatingPool("combine-worker");
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("m"), memberPool)
                .register(ParId.of("c"), combinePool)
                .build();
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            combinePool.execute(() -> {
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        try {
            TaskGroup<?, String> group = runtime.groupDraft("g", Duration.ofSeconds(10))
                    .par(
                            "a",
                            runtime.par(ParId.of("m")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            () -> "a")
                    .combine(
                            "sum",
                            runtime.par(ParId.of("c")),
                            TaskOptions.inheritTimeout().rejectEnqueue(true),
                            TypeToken.of(String.class),
                            values -> "combined")
                    .submitAll();

            assertThat(group.completionFuture().get(10, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUBMISSION_FAILURE);
            // Zero wait: every body slot must already be released, not merely releasable.
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isTrue();
        } finally {
            release.countDown();
            runtime.close();
            memberPool.shutdownNow();
            combinePool.shutdownNow();
        }
    }

    @Test
    void combineOnTheSamePolicyPoolStillSucceedsWhenThatPoolIsNotSaturated() throws Exception {
        ExecutorService memberPool = Executors.newFixedThreadPool(2, r -> new Thread(r, "member"));
        ThreadPoolExecutor combinePool = saturatingPool("combine-worker");
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("m"), memberPool)
                .register(ParId.of("c"), combinePool)
                .build();
        AtomicReference<String> combineThread = new AtomicReference<>();
        try {
            // Same pool, same CallerRunsPolicy, nothing occupying it. This is the case a
            // detect-the-policy-and-refuse design would have broken: the guarantee is never actually
            // violated here, so the combine must run normally.
            TaskGroup<?, String> group = runtime.groupDraft("g", Duration.ofSeconds(10))
                    .par(
                            "a",
                            runtime.par(ParId.of("m")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            () -> "a")
                    .combine(
                            "sum",
                            runtime.par(ParId.of("c")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            values -> {
                                combineThread.set(Thread.currentThread().getName());
                                return "combined";
                            })
                    .submitAll();

            TaskGroupReport result = group.completionFuture().get(10, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(combineThread.get()).isEqualTo("combine-worker");
        } finally {
            runtime.close();
            memberPool.shutdownNow();
            combinePool.shutdownNow();
        }
    }

    @Test
    void combineOnAnAlwaysInlineExecutorKeepsItsDocumentedAllowance() throws Exception {
        ExecutorService memberPool = Executors.newFixedThreadPool(2, r -> new Thread(r, "member"));
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("m"), memberPool)
                .register(ParId.of("direct"), MoreExecutors.newDirectExecutorService())
                .build();
        AtomicReference<String> combineThread = new AtomicReference<>();
        try {
            // A direct executor runs every task on whoever submits it. That is not a saturation
            // symptom but the executor's whole contract, chosen explicitly by whoever registered it,
            // and CombineBody documents it as an accepted exception. The guard must leave it alone:
            // it is scoped to ThreadPoolExecutor, where inline execution can only come from a
            // rejection handler.
            TaskGroup<?, String> group = runtime.groupDraft("g", Duration.ofSeconds(10))
                    .par(
                            "a",
                            runtime.par(ParId.of("m")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            () -> "a")
                    .combine(
                            "sum",
                            runtime.par(ParId.of("direct")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            values -> {
                                combineThread.set(Thread.currentThread().getName());
                                return "combined";
                            })
                    .submitAll();

            TaskGroupReport result = group.completionFuture().get(10, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(combineThread.get()).startsWith("member");
        } finally {
            runtime.close();
            memberPool.shutdownNow();
        }
    }

    @Test
    void aSharedPoolWorkerMayStillPickTheCombineOffItsOwnQueue() throws Exception {
        // The guard compares thread identity only while execute() has not returned. Without that
        // window a shared pool gives a false positive: the convergence callback runs on worker N,
        // enqueues the combine, that worker finishes its member and returns to the pool, then picks
        // the combine off the queue — legitimately, on worker N. A queued task can only start after
        // execute() returned, which is what separates it from an inline run.
        ExecutorService shared = Executors.newFixedThreadPool(2, r -> new Thread(r, "shared"));
        ParRuntime runtime =
                ParRuntime.builder().register(ParId.of("s"), shared).build();
        AtomicReference<String> combineThread = new AtomicReference<>();
        try {
            TaskGroup<?, String> group = runtime.groupDraft("g", Duration.ofSeconds(10))
                    .par(
                            "a",
                            runtime.par(ParId.of("s")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            () -> "a")
                    .par(
                            "b",
                            runtime.par(ParId.of("s")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            () -> "b")
                    .combine(
                            "sum",
                            runtime.par(ParId.of("s")),
                            TaskOptions.inheritTimeout(),
                            TypeToken.of(String.class),
                            values -> {
                                combineThread.set(Thread.currentThread().getName());
                                return "combined";
                            })
                    .submitAll();

            TaskGroupReport result = group.completionFuture().get(10, TimeUnit.SECONDS);

            assertThat(result.outcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(combineThread.get()).startsWith("shared");
        } finally {
            runtime.close();
            shared.shutdownNow();
        }
    }
}
