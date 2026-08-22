package io.github.huatalk.parallelinscope.queue;

import com.google.common.util.concurrent.Monitor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** A dining philosophers demo coordinated by one Guava {@link Monitor}. */
public final class MonitorDiningPhilosophersDemo {

    private static final int PHILOSOPHER_COUNT = 5;
    private static final int MEALS_PER_PHILOSOPHER = 3;

    private MonitorDiningPhilosophersDemo() {}

    public static void main(String[] args) throws Exception {
        DiningTable table = new DiningTable(PHILOSOPHER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(PHILOSOPHER_COUNT);
        List<Future<Void>> tasks = new ArrayList<>();

        try {
            for (int id = 0; id < PHILOSOPHER_COUNT; id++) {
                final int philosopherId = id;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int meal = 1; meal <= MEALS_PER_PHILOSOPHER; meal++) {
                        pause("thinking", philosopherId, meal);
                        table.pickUpForks(philosopherId);
                        try {
                            pause("eating", philosopherId, meal);
                        } finally {
                            table.putDownForks(philosopherId);
                        }
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<Void> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
            System.out.println("All philosophers finished without deadlock.");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void pause(String action, int philosopherId, int meal) throws InterruptedException {
        System.out.printf("Philosopher %d is %s (meal %d).%n", philosopherId, action, meal);
        TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(40, 121));
    }

    private enum State {
        THINKING,
        HUNGRY,
        EATING
    }

    private static final class DiningTable {
        private final Monitor monitor = new Monitor(true);
        private final State[] states;
        private final Monitor.Guard[] canEat;

        private DiningTable(int philosopherCount) {
            states = new State[philosopherCount];
            canEat = new Monitor.Guard[philosopherCount];

            for (int id = 0; id < philosopherCount; id++) {
                states[id] = State.THINKING;
                final int philosopherId = id;
                canEat[id] = new Monitor.Guard(monitor) {
                    @Override
                    public boolean isSatisfied() {
                        return states[philosopherId] == State.HUNGRY
                                && states[leftOf(philosopherId)] != State.EATING
                                && states[rightOf(philosopherId)] != State.EATING;
                    }
                };
            }
        }

        private void pickUpForks(int philosopherId) throws InterruptedException {
            monitor.enterInterruptibly();
            try {
                states[philosopherId] = State.HUNGRY;
                try {
                    monitor.waitFor(canEat[philosopherId]);
                } catch (InterruptedException e) {
                    states[philosopherId] = State.THINKING;
                    throw e;
                }

                states[philosopherId] = State.EATING;
                verifyNeighborsAreNotEating(philosopherId);
                System.out.printf("Philosopher %d picked up both forks.%n", philosopherId);
            } finally {
                monitor.leave();
            }
        }

        private void putDownForks(int philosopherId) {
            monitor.enter();
            try {
                states[philosopherId] = State.THINKING;
                System.out.printf("Philosopher %d put down both forks.%n", philosopherId);
            } finally {
                // Leaving the monitor signals one satisfied Guard, if one is waiting.
                monitor.leave();
            }
        }

        private void verifyNeighborsAreNotEating(int philosopherId) {
            if (states[leftOf(philosopherId)] == State.EATING || states[rightOf(philosopherId)] == State.EATING) {
                throw new IllegalStateException("Adjacent philosophers cannot eat together");
            }
        }

        private int leftOf(int philosopherId) {
            return (philosopherId + states.length - 1) % states.length;
        }

        private int rightOf(int philosopherId) {
            return (philosopherId + 1) % states.length;
        }
    }
}
