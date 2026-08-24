package io.github.huatalk.parallelinscope.queue;

import com.google.common.util.concurrent.Monitor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A dining philosophers demo that uses one Guava {@link Monitor} per fork. */
public final class PooledMonitorDiningPhilosophersDemo {

    private static final int PHILOSOPHER_COUNT = 5;
    private static final int MEALS_PER_PHILOSOPHER = 3;
    private static final int WORKER_COUNT = 4;

    private PooledMonitorDiningPhilosophersDemo() {}

    public static void main(String[] args) throws Exception {
        DiningTable table = new DiningTable(PHILOSOPHER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch philosophersDone = new CountDownLatch(PHILOSOPHER_COUNT);
        AtomicInteger submittedMeals = new AtomicInteger();
        AtomicInteger completedMeals = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(WORKER_COUNT);

        try {
            for (int philosopherId = 0; philosopherId < PHILOSOPHER_COUNT; philosopherId++) {
                submitMeal(
                        executor,
                        table,
                        start,
                        philosophersDone,
                        submittedMeals,
                        completedMeals,
                        failure,
                        philosopherId,
                        1);
            }

            start.countDown();
            if (!philosophersDone.await(10, TimeUnit.SECONDS)) {
                throw new TimeoutException("Philosopher meal chains did not finish in time");
            }
            if (failure.get() != null) {
                throw new IllegalStateException("A meal task failed", failure.get());
            }

            int peakEaters = table.getPeakEaters();
            System.out.printf(
                    "Completed %d/%d submitted meal tasks on %d workers without deadlock; "
                            + "peak concurrent eaters: %d.%n",
                    completedMeals.get(), submittedMeals.get(), WORKER_COUNT, peakEaters);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static void submitMeal(
            ExecutorService executor,
            DiningTable table,
            CountDownLatch start,
            CountDownLatch philosophersDone,
            AtomicInteger submittedMeals,
            AtomicInteger completedMeals,
            AtomicReference<Throwable> failure,
            int philosopherId,
            int meal) {
        int taskId = philosopherId * MEALS_PER_PHILOSOPHER + meal;
        submittedMeals.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    if (meal == 1) {
                        start.await();
                    }
                    pause("thinking", philosopherId, meal);
                    table.pickUpForks(taskId, philosopherId, meal);
                    try {
                        pause("eating", philosopherId, meal);
                    } finally {
                        table.putDownForks(taskId, philosopherId, meal);
                    }
                    completedMeals.incrementAndGet();

                    if (meal < MEALS_PER_PHILOSOPHER) {
                        submitMeal(
                                executor,
                                table,
                                start,
                                philosophersDone,
                                submittedMeals,
                                completedMeals,
                                failure,
                                philosopherId,
                                meal + 1);
                    } else {
                        philosophersDone.countDown();
                    }
                } catch (Throwable taskFailure) {
                    failure.compareAndSet(null, taskFailure);
                    philosophersDone.countDown();
                }
            });
        } catch (RuntimeException submissionFailure) {
            submittedMeals.decrementAndGet();
            throw submissionFailure;
        }
    }

    private static void pause(String action, int philosopherId, int meal) throws InterruptedException {
        System.out.printf(
                "[%s] Philosopher %d is %s (meal %d).%n",
                Thread.currentThread().getName(), philosopherId + 1, action, meal);
        TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(40, 121));
    }

    private static final class DiningTable {
        private final Fork[] forks;
        private final AtomicInteger activeEaters = new AtomicInteger();
        private final AtomicInteger peakEaters = new AtomicInteger();

        private DiningTable(int philosopherCount) {
            forks = new Fork[philosopherCount];
            for (int index = 0; index < philosopherCount; index++) {
                forks[index] = new Fork(index + 1);
            }
        }

        private void pickUpForks(int taskId, int philosopherId, int meal) throws InterruptedException {
            Fork left = leftForkOf(philosopherId);
            Fork right = rightForkOf(philosopherId);

            boolean oddPhilosopher = displayId(philosopherId) % 2 != 0;
            Fork first = oddPhilosopher ? left : right;
            Fork second = oddPhilosopher ? right : left;

            System.out.printf(
                    "Philosopher %d meal %d waits for forks %d -> %d.%n",
                    displayId(philosopherId), meal, first.id, second.id);

            first.acquire(taskId);
            boolean acquiredSecond = false;
            try {
                second.acquire(taskId);
                acquiredSecond = true;
            } finally {
                if (!acquiredSecond) {
                    first.release(taskId, philosopherId);
                }
            }

            int currentEaters = activeEaters.incrementAndGet();
            peakEaters.accumulateAndGet(currentEaters, Math::max);
            System.out.printf(
                    "Philosopher %d acquired forks %d and %d for meal %d " + "(active eaters: %d).%n",
                    displayId(philosopherId), left.id, right.id, meal, currentEaters);
        }

        private void putDownForks(int taskId, int philosopherId, int meal) {
            Fork left = leftForkOf(philosopherId);
            Fork right = rightForkOf(philosopherId);
            boolean oddPhilosopher = displayId(philosopherId) % 2 != 0;
            Fork first = oddPhilosopher ? left : right;
            Fork second = oddPhilosopher ? right : left;

            int currentEaters = activeEaters.decrementAndGet();
            second.release(taskId, philosopherId);
            first.release(taskId, philosopherId);
            System.out.printf(
                    "Philosopher %d finished meal %d and released forks %d and %d " + "(active eaters: %d).%n",
                    displayId(philosopherId), meal, left.id, right.id, currentEaters);
        }

        private int getPeakEaters() {
            return peakEaters.get();
        }

        private Fork leftForkOf(int philosopherId) {
            return forks[philosopherId];
        }

        private Fork rightForkOf(int philosopherId) {
            return forks[(philosopherId + 1) % forks.length];
        }

        private int displayId(int philosopherId) {
            return philosopherId + 1;
        }
    }

    private static final class Fork {
        private final int id;
        private final Monitor monitor = new Monitor(true);
        private final Monitor.Guard available = new Monitor.Guard(monitor) {
            @Override
            public boolean isSatisfied() {
                return owner == -1;
            }
        };
        private int owner = -1;

        private Fork(int id) {
            this.id = id;
        }

        private void acquire(int taskId) throws InterruptedException {
            monitor.enterWhen(available);
            try {
                owner = taskId;
            } finally {
                monitor.leave();
            }
        }

        private void release(int taskId, int philosopherId) {
            monitor.enter();
            try {
                if (owner != taskId) {
                    throw new IllegalStateException("Philosopher " + (philosopherId + 1) + " does not own fork " + id);
                }
                owner = -1;
            } finally {
                // Only one waiter for this fork is signalled when the Guard becomes satisfied.
                monitor.leave();
            }
        }
    }
}
