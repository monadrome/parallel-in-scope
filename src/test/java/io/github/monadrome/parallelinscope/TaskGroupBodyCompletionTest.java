package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
            TaskGroupDefinition.Builder builder = global.defineGroup("close-waits", TIMEOUT);
            TaskGroupDefinition.Member<Integer> blocked = builder.task("blocked", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(blocked, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            Thread closing = new Thread(() -> {
                group.close();
                closeReturned.countDown();
            });
            closing.start();

            // The cancel lands and the future converges while the body is still parked: close must
            // keep waiting for the body to exit rather than returning with the future.
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.GROUP_CANCELED);
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
            TaskGroupDefinition.Builder builder = global.defineGroup("derived", Duration.ofMillis(300));
            TaskGroupDefinition.Member<Integer> ignoring = builder.task(
                    "ignoring", global.par(ParId.of("worker")), TaskOptions.timeout(Duration.ofMillis(300)));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(ignoring, () -> {
                        entered.countDown();
                        try {
                            awaitIgnoringInterrupt(release);
                        } finally {
                            bodyExited.countDown();
                        }
                        return 1;
                    }));
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
            TaskGroupDefinition.Builder builder =
                    global.defineGroup("astronomic-grace", TIMEOUT).closeGrace(Duration.ofSeconds(Long.MAX_VALUE));
            TaskGroupDefinition.Member<Integer> ignoring = builder.task("ignoring", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(ignoring, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    }));
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
            TaskGroupDefinition.Builder builder =
                    global.defineGroup("exhausted", Duration.ofMillis(300)).closeGrace(Duration.ofMillis(400));
            TaskGroupDefinition.Member<Integer> ignoring = builder.task(
                    "ignoring", global.par(ParId.of("worker")), TaskOptions.timeout(Duration.ofMillis(300)));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(ignoring, () -> {
                        entered.countDown();
                        try {
                            awaitIgnoringInterrupt(release);
                        } finally {
                            bodyExited.countDown();
                        }
                        return 1;
                    }));
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
            TaskGroupDefinition.Builder builder =
                    global.defineGroup("zero-grace", TIMEOUT).closeGrace(Duration.ZERO);
            TaskGroupDefinition.Member<Integer> ignoring = builder.task("ignoring", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(ignoring, () -> {
                        entered.countDown();
                        try {
                            awaitIgnoringInterrupt(release);
                        } finally {
                            bodyExited.countDown();
                        }
                        return 1;
                    }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            long closeStart = System.nanoTime();
            group.close();
            long closeElapsedMillis = (System.nanoTime() - closeStart) / 1_000_000;
            // No wait at all: cancellation took effect, the body is still parked, and close
            // returned far below any grace.
            assertThat(closeElapsedMillis).isLessThan(1000);
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.GROUP_CANCELED);
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
        java.util.logging.Logger groupLogger = java.util.logging.Logger.getLogger(TaskGroup.class.getName());
        java.util.List<java.util.logging.LogRecord> records =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        groupLogger.addHandler(capture);
        try {
            TaskGroupDefinition.Builder builder =
                    global.defineGroup("warn-visible", TIMEOUT).closeGrace(Duration.ofMillis(200));
            TaskGroupDefinition.Member<Integer> blocked = builder.task("blocked", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(blocked, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            group.close();

            // The grace elapsed with the body still running: the leak is visible data, naming the
            // group and the outstanding member.
            assertThat(records).anySatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(java.util.logging.Level.WARNING);
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
            TaskGroupDefinition.Builder builder = global.defineGroup("interrupted-entry", TIMEOUT);
            TaskGroupDefinition.Member<Integer> blocked = builder.task("blocked", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(blocked, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    }));
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
                    .isEqualTo(TaskOutcome.GROUP_CANCELED);

            release.countDown();
            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void awaitBodyCompletionValidatesArgumentsAndHonoursInterruption() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("await-contract", TIMEOUT);
            TaskGroupDefinition.Member<Integer> blocked = builder.task("blocked", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(blocked, () -> {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    }));
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
            TaskGroupDefinition.Builder builder = global.defineGroup("mid-wait", TIMEOUT);
            TaskGroupDefinition.Member<Integer> blocked = builder.task("blocked", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(blocked, () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    }));
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
        AtomicReference<TaskGroup> groupRef = new AtomicReference<>();
        CountDownLatch groupReady = new CountDownLatch(1);
        AtomicReference<Throwable> awaitFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("self-await", TIMEOUT);
            TaskGroupDefinition.Member<Integer> self = builder.task("self", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(self, () -> {
                        groupReady.await(5, TimeUnit.SECONDS);
                        try {
                            groupRef.get().awaitBodyCompletion(Duration.ofMillis(10));
                        } catch (Throwable failure) {
                            awaitFailure.set(failure);
                        }
                        try {
                            groupRef.get().close();
                        } catch (Throwable failure) {
                            closeFailure.set(failure);
                        }
                        return 1;
                    }));
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
        ExecutorService rejecting = new AbstractExecutorService() {
            private volatile boolean shutdown;

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public java.util.List<Runnable> shutdownNow() {
                shutdown = true;
                return java.util.Collections.emptyList();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                throw new RejectedExecutionException("rejected");
            }
        };
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .register(ParId.of("rejecting"), rejecting)
                .build();
        AtomicReference<TaskGroup> groupRef = new AtomicReference<>();
        CountDownLatch groupReady = new CountDownLatch(1);
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("nested-guard", TIMEOUT);
            TaskGroupDefinition.Member<String> member = builder.task("outer", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(member, () -> {
                        groupReady.await(5, TimeUnit.SECONDS);
                        // The inner task asks for the caller-thread fallback, so the rejecting
                        // executor runs it inline on the member thread and the inner body nests
                        // under the member body on the same call stack.
                        return global.par(ParId.of("rejecting"))
                                .submit(
                                        "inner",
                                        () -> {
                                            try {
                                                groupRef.get().awaitBodyCompletion(Duration.ofMillis(10));
                                                return "unguarded";
                                            } catch (IllegalStateException guarded) {
                                                return "guarded";
                                            }
                                        },
                                        TaskOptions.inheritTimeout().runOnCallerThread(true))
                                .get(5, TimeUnit.SECONDS);
                    }));
            groupRef.set(group);
            groupReady.countDown();

            assertThat(group.future(member).get(2, TimeUnit.SECONDS)).isEqualTo("guarded");
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void blockingListenerDoesNotBlockBodyCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch listenerRelease = new CountDownLatch(1);
        CountDownLatch listenerEntered = new CountDownLatch(1);
        ParRuntime global = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .taskListener(event -> {
                    listenerEntered.countDown();
                    awaitIgnoringInterrupt(listenerRelease);
                })
                .build();
        try {
            TaskGroupDefinition.Builder builder = global.defineGroup("listener", TIMEOUT);
            TaskGroupDefinition.Member<Integer> quick = builder.task("quick", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(builder.build(), bindings -> bindings.task(quick, () -> 1));

            assertThat(listenerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            // The listener is still blocked, but the task body has already exited.
            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();

            listenerRelease.countDown();
            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
        } finally {
            listenerRelease.countDown();
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
            TaskGroupDefinition.Builder builder = global.defineGroup("combine-cancel", TIMEOUT);
            TaskGroupDefinition.Member<Integer> blocked = builder.task("blocked", global.par(ParId.of("worker")));
            TaskGroupDefinition.Member<String> assemble = builder.combine("assemble", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(builder.build(), bindings -> {
                bindings.task(blocked, () -> {
                    entered.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return 1;
                });
                bindings.combine(assemble, values -> {
                    combineRuns.incrementAndGet();
                    return "combined";
                });
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // Cancelling before the join leaves the combine unsubmitted; its slot is released as
            // skipped, so body completion still converges.
            group.close();
            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(combineRuns).hasValue(0);
            assertThat(group.callableReleased(assemble)).isTrue();
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
            TaskGroupDefinition.Builder builder = global.defineGroup("combine-ok", TIMEOUT);
            TaskGroupDefinition.Member<Integer> value = builder.task("value", global.par(ParId.of("worker")));
            TaskGroupDefinition.Member<String> assemble = builder.combine("assemble", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(builder.build(), bindings -> {
                bindings.task(value, () -> 40);
                bindings.combine(assemble, values -> "combined");
            });

            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isTrue();
            // The future's own holder release sits in run()'s finally, just after completion:
            // await the probe instead of racing it.
            org.awaitility.Awaitility.await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(group.callableReleased(value)).isTrue();
                assertThat(group.callableReleased(assemble)).isTrue();
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
            TaskGroup group =
                    global.submitGroup(global.defineGroup("empty", TIMEOUT).build(), bindings -> {});
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
            TaskGroupDefinition.Builder builder = global.defineGroup("multi-waiter", TIMEOUT);
            TaskGroupDefinition.Member<Integer> blocked = builder.task("blocked", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(
                    builder.build(),
                    bindings -> bindings.task(blocked, () -> {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    }));
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
            TaskGroupDefinition.Builder builder = global.defineGroup("visibility", TIMEOUT);
            TaskGroupDefinition.Member<Integer> first = builder.task("first", global.par(ParId.of("worker")));
            TaskGroupDefinition.Member<Integer> second = builder.task("second", global.par(ParId.of("worker")));
            TaskGroup group = global.submitGroup(builder.build(), bindings -> {
                bindings.task(first, () -> {
                    writes[0] = 1;
                    return 1;
                });
                bindings.task(second, () -> {
                    writes[1] = 2;
                    return 2;
                });
            });

            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            // Plain field writes by the task bodies are visible after a successful wait.
            assertThat(writes).containsExactly(1, 2);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }
}
