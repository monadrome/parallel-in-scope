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

    private static TaskGroupOptions groupOptions(String name) {
        return TaskGroupOptions.timeout(name, Duration.ofSeconds(30));
    }

    private static TaskOptions memberOptions() {
        return TaskOptions.timeout(Duration.ofSeconds(30));
    }

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
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        try {
            TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(groupOptions("close-waits"));
            definition.task(
                    new TaskKey<>("blocked") {},
                    ParName.of("worker"),
                    () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    },
                    memberOptions());
            TaskGroup group = TaskGroup.submit(global, definition.build());
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
    void closeReturnsWithoutWaitingWhenDeadlineBudgetIsExhausted() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch bodyExited = new CountDownLatch(1);
        try {
            TaskGroupDefinition.Builder definition =
                    TaskGroupDefinition.builder(TaskGroupOptions.timeout("exhausted", Duration.ofMillis(300)));
            definition.task(
                    new TaskKey<>("ignoring") {},
                    ParName.of("worker"),
                    () -> {
                        entered.countDown();
                        try {
                            awaitIgnoringInterrupt(release);
                        } finally {
                            bodyExited.countDown();
                        }
                        return 1;
                    },
                    TaskOptions.timeout(Duration.ofMillis(300)));
            TaskGroup group = TaskGroup.submit(global, definition.build());
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // Let the execution deadline lapse so close has no remaining budget left.
            Thread.sleep(400);
            group.close();
            // close returned while the interrupt-ignoring body is still inside user code.
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
    void closeOnEntryWithInterruptFlagCancelsButSkipsWaitAndPreservesFlag() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(groupOptions("interrupted-entry"));
            definition.task(
                    new TaskKey<>("blocked") {},
                    ParName.of("worker"),
                    () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    },
                    memberOptions());
            TaskGroup group = TaskGroup.submit(global, definition.build());
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
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(groupOptions("await-contract"));
            definition.task(
                    new TaskKey<>("blocked") {},
                    ParName.of("worker"),
                    () -> {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    },
                    memberOptions());
            TaskGroup group = TaskGroup.submit(global, definition.build());
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
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        try {
            TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(groupOptions("mid-wait"));
            definition.task(
                    new TaskKey<>("blocked") {},
                    ParName.of("worker"),
                    () -> {
                        entered.countDown();
                        awaitIgnoringInterrupt(release);
                        return 1;
                    },
                    memberOptions());
            TaskGroup group = TaskGroup.submit(global, definition.build());
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
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        AtomicReference<TaskGroup> groupRef = new AtomicReference<>();
        CountDownLatch groupReady = new CountDownLatch(1);
        AtomicReference<Throwable> awaitFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        try {
            TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(groupOptions("self-await"));
            definition.task(
                    new TaskKey<>("self") {},
                    ParName.of("worker"),
                    () -> {
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
                    },
                    memberOptions());
            TaskGroup group = TaskGroup.submit(global, definition.build());
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
        GlobalPar global = GlobalPar.builder()
                .register(ParName.of("worker"), executor)
                .register(ParName.of("rejecting"), rejecting)
                .build();
        AtomicReference<TaskGroup> groupRef = new AtomicReference<>();
        CountDownLatch groupReady = new CountDownLatch(1);
        try {
            TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(groupOptions("nested-guard"));
            TaskKey<String> member = definition.task(
                    new TaskKey<>("outer") {},
                    ParName.of("worker"),
                    () -> {
                        groupReady.await(5, TimeUnit.SECONDS);
                        // The rejecting executor runs this CPU-bound task inline on the member thread, so
                        // the inner body nests under the member body on the same call stack.
                        return global.par(ParName.of("rejecting"))
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
                                        TaskOptions.inheritTimeout())
                                .get(5, TimeUnit.SECONDS);
                    },
                    memberOptions());
            TaskGroup group = TaskGroup.submit(global, definition.build());
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
        GlobalPar global = GlobalPar.builder()
                .register(ParName.of("worker"), executor)
                .taskListener(event -> {
                    listenerEntered.countDown();
                    awaitIgnoringInterrupt(listenerRelease);
                })
                .build();
        try {
            TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(groupOptions("listener"));
            definition.task(new TaskKey<>("quick") {}, ParName.of("worker"), () -> 1, memberOptions());
            TaskGroup group = TaskGroup.submit(global, definition.build());

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
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger combineRuns = new AtomicInteger();
        try {
            TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(groupOptions("combine-cancel"));
            definition.task(
                    new TaskKey<>("blocked") {},
                    ParName.of("worker"),
                    () -> {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    },
                    memberOptions());
            TaskGroup group = TaskGroup.submit(
                    global, definition.buildWithCombiner(new TaskKey<>("assemble") {}, ParName.of("worker"), values -> {
                        combineRuns.incrementAndGet();
                        return "combined";
                    }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // Cancelling before the join leaves the combine unsubmitted; its slot is released as
            // skipped, so body completion still converges.
            group.close();
            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            assertThat(combineRuns).hasValue(0);
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void successfulGroupWithCombineReportsBodyCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        try {
            TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(groupOptions("combine-ok"));
            definition.task(new TaskKey<>("value") {}, ParName.of("worker"), () -> 40, memberOptions());
            TaskGroup group = TaskGroup.submit(
                    global,
                    definition.buildWithCombiner(
                            new TaskKey<>("assemble") {}, ParName.of("worker"), values -> "combined"));

            assertThat(group.completionFuture().get(2, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
            assertThat(group.awaitBodyCompletion(Duration.ZERO)).isTrue();
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }

    @Test
    void emptyGroupReportsImmediateBodyCompletion() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        try {
            TaskGroup group = TaskGroup.submit(
                    global, TaskGroupDefinition.builder(groupOptions("empty")).build());
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
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger trueResults = new AtomicInteger();
        try {
            TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(groupOptions("multi-waiter"));
            definition.task(
                    new TaskKey<>("blocked") {},
                    ParName.of("worker"),
                    () -> {
                        entered.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return 1;
                    },
                    memberOptions());
            TaskGroup group = TaskGroup.submit(global, definition.build());
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
        GlobalPar global =
                GlobalPar.builder().register(ParName.of("worker"), executor).build();
        int[] writes = new int[2];
        try {
            TaskGroupDefinition.Builder definition = TaskGroupDefinition.builder(groupOptions("visibility"));
            definition.task(
                    new TaskKey<>("first") {},
                    ParName.of("worker"),
                    () -> {
                        writes[0] = 1;
                        return 1;
                    },
                    memberOptions());
            definition.task(
                    new TaskKey<>("second") {},
                    ParName.of("worker"),
                    () -> {
                        writes[1] = 2;
                        return 2;
                    },
                    memberOptions());
            TaskGroup group = TaskGroup.submit(global, definition.build());

            assertThat(group.awaitBodyCompletion(Duration.ofSeconds(2))).isTrue();
            // Plain field writes by the task bodies are visible after a successful wait.
            assertThat(writes).containsExactly(1, 2);
        } finally {
            global.close();
            executor.shutdownNow();
        }
    }
}
