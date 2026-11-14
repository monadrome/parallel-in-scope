package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for {@link TaskGroup#close()} and {@link TaskGroup#awaitExecutionExit(Duration)}:
 * cancel plus bounded wait on task-execution exit, driven by the shared execution exit signal.
 */
class TaskGroupExecutionExitTest {
    private static final TaskKey<String> KEY_OUTER_STRING = TaskKey.named("outer");
    private static final TaskKey<Integer> KEY_QUICK_INTEGER = TaskKey.named("quick");

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Awaits the latch, ignoring interrupts, so cancellation cannot force the execution out early. */
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
    void closeCancelsAndWaitsForExecutionExitWithinRemainingBudget() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        try {
            TaskGroup<Void> group = global.groupDraft("close-waits", TIMEOUT)
                    .par(TaskKey.<Integer>named("blocked"), global.par(ParId.of("worker")), () -> {
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

            // The cancel lands and the future converges while the execution is still parked: close must
            // keep waiting for the execution to exit rather than returning with the future.
            assertThat(group.convergenceFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskClassification.GROUP_CANCELLED);
            assertThat(closeReturned.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(group.awaitExecutionExit(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(closeReturned.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(group.awaitExecutionExit(Duration.ZERO)).isTrue();
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
        CountDownLatch executionExited = new CountDownLatch(1);
        try {
            // No closeGrace configured: the wait budget is the remaining deadline at close time.
            TaskGroup<Void> group = global.groupDraft("derived", Duration.ofMillis(300))
                    .par(
                            TaskKey.<Integer>named("ignoring"),
                            global.par(ParId.of("worker")),
                            TaskOptions.timeout(Duration.ofMillis(300)),
                            () -> {
                                entered.countDown();
                                try {
                                    awaitIgnoringInterrupt(release);
                                } finally {
                                    executionExited.countDown();
                                }
                                return 1;
                            })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // The deadline lapses before close: the derived budget is exhausted, so close returns
            // right after cancelling, with the execution still parked.
            Thread.sleep(400);
            long closeStart = System.nanoTime();
            group.close();
            long closeElapsedMillis = (System.nanoTime() - closeStart) / 1_000_000;
            assertThat(closeElapsedMillis).isLessThan(1000);
            assertThat(executionExited.getCount()).isEqualTo(1);
            assertThat(group.awaitExecutionExit(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(group.awaitExecutionExit(Duration.ofSeconds(2))).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeWithAstronomicGraceWaitsForTheExecutionToExit() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        try {
            // Duration.ofSeconds(Long.MAX_VALUE) overflows toNanos(); close() must saturate the
            // grace and keep waiting for the execution to exit instead of failing or skipping the wait.
            TaskGroup<Void> group = global.groupDraft("astronomic-grace", TIMEOUT)
                    .closeGrace(Duration.ofSeconds(Long.MAX_VALUE))
                    .par(TaskKey.<Integer>named("ignoring"), global.par(ParId.of("worker")), () -> {
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

            // The saturated budget means close keeps waiting while the execution is parked.
            assertThat(closeReturned.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(group.awaitExecutionExit(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(closeReturned.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(group.awaitExecutionExit(Duration.ZERO)).isTrue();
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
        CountDownLatch executionExited = new CountDownLatch(1);
        try {
            TaskGroup<Void> group = global.groupDraft("exhausted", Duration.ofMillis(300))
                    .closeGrace(Duration.ofMillis(400))
                    .par(
                            TaskKey.<Integer>named("ignoring"),
                            global.par(ParId.of("worker")),
                            TaskOptions.timeout(Duration.ofMillis(300)),
                            () -> {
                                entered.countDown();
                                try {
                                    awaitIgnoringInterrupt(release);
                                } finally {
                                    executionExited.countDown();
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
            // close returned when the grace elapsed, while the interrupt-ignoring execution is still
            // inside user code.
            assertThat(executionExited.getCount()).isEqualTo(1);
            // An explicit bounded wait reports the still-running execution, then succeeds after exit.
            assertThat(group.awaitExecutionExit(Duration.ofMillis(100))).isFalse();

            release.countDown();
            assertThat(group.awaitExecutionExit(Duration.ofSeconds(2))).isTrue();
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
        CountDownLatch executionExited = new CountDownLatch(1);
        try {
            TaskGroup<Void> group = global.groupDraft("zero-grace", TIMEOUT)
                    .closeGrace(Duration.ZERO)
                    .par(TaskKey.<Integer>named("ignoring"), global.par(ParId.of("worker")), () -> {
                        entered.countDown();
                        try {
                            awaitIgnoringInterrupt(release);
                        } finally {
                            executionExited.countDown();
                        }
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            long closeStart = System.nanoTime();
            group.close();
            long closeElapsedMillis = (System.nanoTime() - closeStart) / 1_000_000;
            // No wait at all: cancellation took effect, the execution is still parked, and close
            // returned far below any grace.
            assertThat(closeElapsedMillis).isLessThan(1000);
            assertThat(group.convergenceFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskClassification.GROUP_CANCELLED);
            assertThat(executionExited.getCount()).isEqualTo(1);
            assertThat(group.awaitExecutionExit(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(group.awaitExecutionExit(Duration.ofSeconds(2))).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeGraceElapseLeavesExecutionUnconfirmed() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<Void> group = global.groupDraft("close-grace", TIMEOUT)
                    .closeGrace(Duration.ofMillis(200))
                    .par(TaskKey.<Integer>named("blocked"), global.par(ParId.of("worker")), () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            group.close();

            // The grace elapsed with the execution still running; the exit query remains authoritative.
            assertThat(group.awaitExecutionExit(Duration.ZERO)).isFalse();

            release.countDown();
            assertThat(group.awaitExecutionExit(Duration.ofSeconds(2))).isTrue();
        } finally {
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
            TaskGroup<Void> group = global.groupDraft("interrupted-entry", TIMEOUT)
                    .par(TaskKey.<Integer>named("blocked"), global.par(ParId.of("worker")), () -> {
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
            assertThat(group.convergenceFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskClassification.GROUP_CANCELLED);

            release.countDown();
            assertThat(group.awaitExecutionExit(Duration.ofSeconds(2))).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @SuppressWarnings("NullAway")
    @Test
    void awaitExecutionExitValidatesArgumentsAndHonorsInterruption() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<Void> group = global.groupDraft("await-contract", TIMEOUT)
                    .par(TaskKey.<Integer>named("blocked"), global.par(ParId.of("worker")), () -> {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> group.awaitExecutionExit(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> group.awaitExecutionExit(Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
            // A zero budget performs a single check against the still-running execution.
            assertThat(group.awaitExecutionExit(Duration.ZERO)).isFalse();
            // An oversized duration saturates instead of overflowing and still waits.
            AtomicReference<Boolean> saturated = new AtomicReference<>();
            Thread waiting = new Thread(() -> {
                try {
                    saturated.set(group.awaitExecutionExit(Duration.ofSeconds(Long.MAX_VALUE)));
                } catch (InterruptedException interrupted) {
                    saturated.set(null);
                }
            });
            waiting.start();

            // Entry interrupt: the flag is cleared per Java convention and reported.
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> group.awaitExecutionExit(Duration.ofSeconds(1)))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();

            release.countDown();
            waiting.join(2000);
            assertThat(saturated.get()).isTrue();
            assertThat(group.awaitExecutionExit(Duration.ZERO)).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void awaitExecutionExitInterruptedDuringWaitClearsFlagAndThrows() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        try {
            TaskGroup<Void> group = global.groupDraft("mid-wait", TIMEOUT)
                    .par(TaskKey.<Integer>named("blocked"), global.par(ParId.of("worker")), () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            Thread waiter = new Thread(() -> {
                waiting.countDown();
                try {
                    group.awaitExecutionExit(Duration.ofSeconds(30));
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
    void closeAndAwaitFromWithinMemberExecutionAreRejectedAsSelfAwait() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        AtomicReference<TaskGroup<?>> groupRef = new AtomicReference<>();
        CountDownLatch groupReady = new CountDownLatch(1);
        AtomicReference<Throwable> awaitFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        try {
            TaskGroup<Void> group = global.groupDraft("self-await", TIMEOUT)
                    .par(TaskKey.<Integer>named("self"), global.par(ParId.of("worker")), () -> {
                        groupReady.await(5, TimeUnit.SECONDS);
                        try {
                            Objects.requireNonNull(groupRef.get()).awaitExecutionExit(Duration.ofMillis(10));
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

            assertThat(group.convergenceFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskClassification.SUCCESS);
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
        AtomicReference<TaskGroup<?>> groupRef = new AtomicReference<>();
        CountDownLatch groupReady = new CountDownLatch(1);
        try {
            TaskGroup<Void> group = global.groupDraft("nested-guard", TIMEOUT)
                    .par(KEY_OUTER_STRING, global.par(ParId.of("worker")), () -> {
                        groupReady.await(5, TimeUnit.SECONDS);
                        // The inner task's executor is a direct executor service, so it runs inline
                        // on the member thread and the inner execution nests under the member execution on the
                        // same call stack.
                        return global.par(ParId.of("direct"))
                                .submit(
                                        "inner",
                                        () -> {
                                            try {
                                                Objects.requireNonNull(groupRef.get())
                                                        .awaitExecutionExit(Duration.ofMillis(10));
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

            assertThat(group.futureOf(KEY_OUTER_STRING).get(2, TimeUnit.SECONDS))
                    .isEqualTo("guarded");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void blockedObservationCallbackDoesNotBlockExecutionExit() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch callbackRelease = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroup<Void> group = global.groupDraft("observation", TIMEOUT)
                    .par(KEY_QUICK_INTEGER, global.par(ParId.of("worker")), () -> 1)
                    .submitAll();

            Futures.addCallback(
                    group.futureOf(KEY_QUICK_INTEGER).completionFuture(),
                    new FutureCallback<TaskExitObservation<Integer>>() {
                        @Override
                        public void onSuccess(@Nullable TaskExitObservation<Integer> completion) {
                            callbackEntered.countDown();
                            awaitIgnoringInterrupt(callbackRelease);
                        }

                        @Override
                        public void onFailure(Throwable failure) {}
                    },
                    callbackExecutor);

            assertThat(callbackEntered.await(2, TimeUnit.SECONDS)).isTrue();
            // The observation callback is still blocked, but the task execution has already exited and
            // the observation snapshot is published: callbacks run on the consumer's executor,
            // never on the execution path.
            assertThat(group.awaitExecutionExit(Duration.ofSeconds(2))).isTrue();

            callbackRelease.countDown();
            assertThat(group.convergenceFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskClassification.SUCCESS);
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
            TaskGroup<String> group = global.groupDraft("combine-cancel", TIMEOUT)
                    .par(TaskKey.<Integer>named("blocked"), global.par(ParId.of("worker")), () -> {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    })
                    .combineValues(TaskKey.<String>named("assemble"), global.par(ParId.of("worker")), values -> {
                        combineRuns.incrementAndGet();
                        return "combined";
                    })
                    .submitAll();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // Cancelling before the join leaves the combine unsubmitted; its slot is released as
            // skipped, so execution exit still converges.
            group.close();
            assertThat(group.awaitExecutionExit(Duration.ofSeconds(2))).isTrue();
            assertThat(combineRuns).hasValue(0);
            assertThat(group.callableReleased("assemble")).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void successfulGroupWithCombineReportsExecutionExit() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroup<String> group = global.groupDraft("combine-ok", TIMEOUT)
                    .par(TaskKey.<Integer>named("value"), global.par(ParId.of("worker")), () -> 40)
                    .combineValues(
                            TaskKey.<String>named("assemble"), global.par(ParId.of("worker")), values -> "combined")
                    .submitAll();

            assertThat(group.convergenceFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskClassification.SUCCESS);
            assertThat(group.awaitExecutionExit(Duration.ZERO)).isTrue();
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
    void emptyGroupReportsImmediateExecutionExit() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        try {
            TaskGroup<Void> group = global.groupDraft("empty", TIMEOUT).submitAll();
            assertThat(group.awaitExecutionExit(Duration.ZERO)).isTrue();
            group.close();
            assertThat(group.convergenceFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskClassification.SUCCESS);
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
            TaskGroup<Void> group = global.groupDraft("multi-waiter", TIMEOUT)
                    .par(TaskKey.<Integer>named("blocked"), global.par(ParId.of("worker")), () -> {
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
                        if (group.awaitExecutionExit(Duration.ofSeconds(10))) {
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
    void successfulAwaitEstablishesVisibilityOfExecutionWrites() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        int[] writes = new int[2];
        try {
            TaskGroup<Void> group = global.groupDraft("visibility", TIMEOUT)
                    .par(TaskKey.<Integer>named("first"), global.par(ParId.of("worker")), () -> {
                        writes[0] = 1;
                        return 1;
                    })
                    .par(TaskKey.<Integer>named("second"), global.par(ParId.of("worker")), () -> {
                        writes[1] = 2;
                        return 2;
                    })
                    .submitAll();

            assertThat(group.awaitExecutionExit(Duration.ofSeconds(2))).isTrue();
            // Plain field writes by the task executions are visible after a successful wait.
            assertThat(writes).containsExactly(1, 2);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }
}
