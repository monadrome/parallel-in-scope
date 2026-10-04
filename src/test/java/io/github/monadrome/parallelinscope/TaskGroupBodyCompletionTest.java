package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for {@link TaskGroup#close()} and {@link TaskGroup#awaitBodyCompletion(Duration)}:
 * cancel plus bounded wait on task-body exit, driven by the shared body-completion signal.
 */
class TaskGroupBodyCompletionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Awaits the latch, ignoring interrupts, so cancellation cannot force the body out early. */
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        for (; ; ) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void closeCancelsAndWaitsForBodyExitWithinRemainingBudget() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("close-waits", TIMEOUT)
                    .par("blocked", global.par(ParId.of("worker")), Integer.class, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            Thread closing = new Thread(() -> {
                group.close();
                closeReturned.countDown();
            });
            closing.start();

            // The cancel lands and the future converges while the body is still parked: close must
            // keep waiting for the body to exit rather than returning with the future.
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.GROUP_CANCELLED);
            assertThat(closeReturned.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(closeReturned.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isTrue();
            closing.join(2000);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeWithDefaultGraceDerivesTheBudgetFromTheRemainingDeadline() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch bodyExited = new CountDownLatch(1);
        try {
            // No closeGrace configured: the wait budget is the remaining deadline at close time.
            TaskGroup<Integer, Void> group = global.groupDraft("derived", Duration.ofMillis(300))
                    .par(
                            "ignoring",
                            global.par(ParId.of("worker")),
                            TaskOptions.timeout(Duration.ofMillis(300)),
                            TypeToken.of(Integer.class),
                            () -> {
                                entered.countDown();
                                try {
                                    awaitIgnoringInterrupt(release);
                                } finally {
                                    bodyExited.countDown();
                                }
                                return 1;
                            })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // The deadline lapses before close: the derived budget is exhausted, so close returns
            // right after cancelling, with the body still parked.
            Thread.sleep(400);
            long closeStart = System.nanoTime();
            group.close();
            long closeElapsedMillis = (System.nanoTime() - closeStart) / 1_000_000;
            assertThat(closeElapsedMillis).isLessThan(1000);
            assertThat(bodyExited.getCount()).isEqualTo(1);
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeWithAstronomicGraceWaitsForTheBodyToExit() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        try {
            // Duration.ofSeconds(Long.MAX_VALUE) overflows toNanos(); close() must saturate the
            // grace and keep waiting for the body to exit instead of failing or skipping the wait.
            TaskGroup<Integer, Void> group = global.groupDraft("astronomic-grace", TIMEOUT)
                    .closeGrace(Duration.ofSeconds(Long.MAX_VALUE))
                    .par("ignoring", global.par(ParId.of("worker")), Integer.class, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            Thread closing = new Thread(() -> {
                group.close();
                closeReturned.countDown();
            });
            closing.start();

            // The saturated budget means close keeps waiting while the body is parked.
            assertThat(closeReturned.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(closeReturned.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isTrue();
            closing.join(2000);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeWaitsTheCloseGraceEvenAfterTheDeadlineIsExhausted() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch bodyExited = new CountDownLatch(1);
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("exhausted", Duration.ofMillis(300))
                    .closeGrace(Duration.ofMillis(400))
                    .par(
                            "ignoring",
                            global.par(ParId.of("worker")),
                            TaskOptions.timeout(Duration.ofMillis(300)),
                            TypeToken.of(Integer.class),
                            () -> {
                                entered.countDown();
                                try {
                                    awaitIgnoringInterrupt(release);
                                } finally {
                                    bodyExited.countDown();
                                }
                                return 1;
                            })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // Let the execution deadline lapse: the close grace is a cleanup budget that starts
            // at close time, so close still waits it rather than returning immediately.
            Thread.sleep(400);
            long closeStart = System.nanoTime();
            group.close();
            long closeElapsedMillis = (System.nanoTime() - closeStart) / 1_000_000;
            assertThat(closeElapsedMillis).isGreaterThanOrEqualTo(300);
            // close returned when the grace elapsed, while the interrupt-ignoring body is still
            // inside user code.
            assertThat(bodyExited.getCount()).isEqualTo(1);
            // An explicit bounded wait reports the still-running body, then succeeds after exit.
            assertThat(group.awaitBodyCompletion(Duration.ofMillis(100))).isFalse();

            release.countDown();
            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeWithZeroGraceIsCancelOnly() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch bodyExited = new CountDownLatch(1);
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("zero-grace", TIMEOUT)
                    .closeGrace(Duration.ZERO)
                    .par("ignoring", global.par(ParId.of("worker")), Integer.class, () -> {
                        entered.countDown();
                        try {
                            awaitIgnoringInterrupt(release);
                        } finally {
                            bodyExited.countDown();
                        }
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            long closeStart = System.nanoTime();
            group.close();
            long closeElapsedMillis = (System.nanoTime() - closeStart) / 1_000_000;
            // No wait at all: cancellation took effect, the body is still parked, and close
            // returned far below any grace.
            assertThat(closeElapsedMillis).isLessThan(1000);
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.GROUP_CANCELLED);
            assertThat(bodyExited.getCount()).isEqualTo(1);
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeGraceElapseLogsTheOutstandingMemberNames() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Logger groupLogger = Logger.getLogger(TaskGroup.class.getName());
        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        groupLogger.addHandler(capture);
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("warn-visible", TIMEOUT)
                    .closeGrace(Duration.ofMillis(200))
                    .par("blocked", global.par(ParId.of("worker")), Integer.class, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            group.close();

            // The grace elapsed with the body still running: the leak is visible data, naming the
            // group and the outstanding member.
            assertThat(records).anySatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                assertThat(record.getMessage()).contains("warn-visible").contains("blocked");
            });

            release.countDown();
            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
        } finally {
            groupLogger.removeHandler(capture);
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeOnEntryWithInterruptFlagCancelsButSkipsWaitAndPreservesFlag() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("interrupted-entry", TIMEOUT)
                    .par("blocked", global.par(ParId.of("worker")), Integer.class, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            Thread.currentThread().interrupt();
            try {
                group.close();
                // The interrupt flag survives close, and cancellation still took effect.
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.GROUP_CANCELLED);

            release.countDown();
            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
    @Test
    void awaitBodyCompletionValidatesArgumentsAndHonorsInterruption() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("await-contract", TIMEOUT)
                    .par("blocked", global.par(ParId.of("worker")), Integer.class, () -> {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> group.awaitBodyCompletion(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> group.awaitBodyCompletion(Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
            // A zero budget performs a single check against the still-running body.
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isFalse();
            // An oversized duration saturates instead of overflowing and still waits.
            AtomicReference<Boolean> saturated = new AtomicReference<>();
            Thread waiting = new Thread(() -> {
                try {
                    saturated.set(group.awaitBodyCompletion(Duration.ofSeconds(Long.MAX_VALUE)));
                } catch (InterruptedException interrupted) {
                    saturated.set(null);
                }
            });
            waiting.start();

            // Entry interrupt: the flag is cleared per Java convention and reported.
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> group.awaitBodyCompletion(Duration.ofSeconds(1)))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();

            release.countDown();
            waiting.join(2000);
            assertThat(saturated.get()).isEqualTo(Boolean.TRUE);
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void awaitBodyCompletionInterruptedDuringWaitClearsFlagAndThrows() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("mid-wait", TIMEOUT)
                    .par("blocked", global.par(ParId.of("worker")), Integer.class, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            Thread waiter = new Thread(() -> {
                waiting.countDown();
                try {
                    group.awaitBodyCompletion(Duration.ofSeconds(30));
                } catch (Throwable failure) {
                    outcome.set(failure);
                }
            });
            waiter.start();
            assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue();
            waiter.interrupt();
            waiter.join(2000);
            assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
            assertThat(waiter.isInterrupted()).isFalse();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeAndAwaitFromWithinMemberBodyAreRejectedAsSelfAwait() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        AtomicReference<TaskGroup<Integer, ?>> groupRef = new AtomicReference<>();
        CountDownLatch groupReady = new CountDownLatch(1);
        AtomicReference<Throwable> awaitFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("self-await", TIMEOUT)
                    .par("self", global.par(ParId.of("worker")), Integer.class, () -> {
                        groupReady.await(5, TimeUnit.SECONDS);
                        try {
                            Objects.requireNonNull(groupRef.get()).awaitBodyCompletion(Duration.ofMillis(10));
                        } catch (Throwable failure) {
                            awaitFailure.set(failure);
                        }
                        try {
                            Objects.requireNonNull(groupRef.get()).close();
                        } catch (Throwable failure) {
                            closeFailure.set(failure);
                        }
                        return 1;
                    })
                    .submitAll();
            groupRef.set(group);
            groupReady.countDown();

            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
            assertThat(awaitFailure.get()).isInstanceOf(IllegalStateException.class);
            assertThat(closeFailure.get()).isInstanceOf(IllegalStateException.class);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void nestedInlineCallOnMemberThreadIsCoveredByTheSelfAwaitGuard() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService direct = MoreExecutors.newDirectExecutorService();
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .register(ParId.of("direct"), direct)
                .build();
        AtomicReference<TaskGroup<String, ?>> groupRef = new AtomicReference<>();
        CountDownLatch groupReady = new CountDownLatch(1);
        try {
            TaskGroup<String, Void> group = global.groupDraft("nested-guard", TIMEOUT)
                    .par("outer", global.par(ParId.of("worker")), String.class, () -> {
                        groupReady.await(5, TimeUnit.SECONDS);
                        // The inner task's executor is a direct executor service, so it runs inline
                        // on the member thread and the inner body nests under the member body on the
                        // same call stack.
                        return global.par(ParId.of("direct"))
                                .submit(
                                        "inner",
                                        () -> {
                                            try {
                                                Objects.requireNonNull(groupRef.get())
                                                        .awaitBodyCompletion(Duration.ofMillis(10));
                                                return "unguarded";
                                            } catch (IllegalStateException guarded) {
                                                return "guarded";
                                            }
                                        },
                                        TaskOptions.inheritTimeout())
                                .get(5, TimeUnit.SECONDS);
                    })
                    .submitAll();
            groupRef.set(group);
            groupReady.countDown();

            assertThat(group.futureOf("outer", TypeToken.of(String.class)).get(2, TimeUnit.SECONDS))
                    .isEqualTo("guarded");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void blockedObservationCallbackDoesNotBlockBodyCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch callbackRelease = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("observation", TIMEOUT)
                    .par("quick", global.par(ParId.of("worker")), Integer.class, () -> 1)
                    .submitAll();

            Futures.addCallback(
                    group.futureOf("quick", TypeToken.of(Integer.class)).completionFuture(),
                    new FutureCallback<TaskCompletion<Integer>>() {
                        @Override
                        public void onSuccess(@Nullable TaskCompletion<Integer> completion) {
                            callbackEntered.countDown();
                            awaitIgnoringInterrupt(callbackRelease);
                        }

                        @Override
                        public void onFailure(Throwable failure) {}
                    },
                    callbackExecutor);

            assertThat(callbackEntered.await(2, TimeUnit.SECONDS)).isTrue();
            // The observation callback is still blocked, but the task body has already exited and
            // the observation snapshot is published: callbacks run on the consumer's executor,
            // never on the execution path.
            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();

            callbackRelease.countDown();
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            callbackRelease.countDown();
            callbackExecutor.shutdownNow();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void unstartedCombineReleasesItsSlotOnCancellation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger combineRuns = new AtomicInteger();
        try {
            TaskGroup<Integer, String> group = global.groupDraft("combine-cancel", TIMEOUT)
                    .par("blocked", global.par(ParId.of("worker")), Integer.class, () -> {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    })
                    .combine("assemble", global.par(ParId.of("worker")), String.class, values -> {
                        combineRuns.incrementAndGet();
                        return "combined";
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // Cancelling before the join leaves the combine unsubmitted; its slot is released as
            // skipped, so body completion still converges.
            group.close();
            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(combineRuns).hasValue(0);
            assertThat(group.callableReleased("assemble")).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void successfulGroupWithCombineReportsBodyCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroup<Integer, String> group = global.groupDraft("combine-ok", TIMEOUT)
                    .par("value", global.par(ParId.of("worker")), Integer.class, () -> 40)
                    .combine("assemble", global.par(ParId.of("worker")), String.class, values -> "combined")
                    .submitAll();

            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isTrue();
            // The future's own holder release sits in run()'s finally, just after completion:
            // await the probe instead of racing it.
            Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(group.callableReleasedAt(0)).isTrue();
                assertThat(group.callableReleased("assemble")).isTrue();
            });
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void emptyGroupReportsImmediateBodyCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroup<Void, Void> group = global.groupDraft("empty", TIMEOUT).submitAll();
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isTrue();
            group.close();
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedCloseAndConcurrentWaitersObserveTheSameCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger trueResults = new AtomicInteger();
        try {
            TaskGroup<Integer, Void> group = global.groupDraft("multi-waiter", TIMEOUT)
                    .par("blocked", global.par(ParId.of("worker")), Integer.class, () -> {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            Thread[] waiters = new Thread[2];
            for (int i = 0; i < waiters.length; i++) {
                waiters[i] = new Thread(() -> {
                    try {
                        if (group.awaitBodyCompletion(Duration.ofSeconds(10))) {
                            trueResults.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
                waiters[i].start();
            }
            group.close();
            group.close();
            release.countDown();
            for (Thread waiter : waiters) {
                waiter.join(2000);
            }
            assertThat(trueResults).hasValue(2);
            group.close();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void successfulAwaitEstablishesVisibilityOfBodyWrites() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        int[] writes = new int[2];
        try {
            TaskGroup<Tuple2<Integer, Integer>, Void> group = global.groupDraft("visibility", TIMEOUT)
                    .par("first", global.par(ParId.of("worker")), Integer.class, () -> {
                        writes[0] = 1;
                        return 1;
                    })
                    .par("second", global.par(ParId.of("worker")), Integer.class, () -> {
                        writes[1] = 2;
                        return 2;
                    })
                    .submitAll();

            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            // Plain field writes by the task bodies are visible after a successful wait.
            assertThat(writes).containsExactly(1, 2);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }
}
