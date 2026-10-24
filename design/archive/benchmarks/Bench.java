import com.sun.management.ThreadMXBean;
import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;
import io.github.monadrome.parallelinscope.TaskOptions;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Standalone overhead harness (not JMH): measures allocated bytes per operation
 * (exact, from the JVM) and wall-clock per batch (best of N after warmup), plus a
 * probe of the runtime's internal deadline-timer queue size.
 */
public final class Bench {

    private static final ThreadMXBean ALLOC = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final long ME = Thread.currentThread().getId();

    public static void main(String[] args) throws Exception {
        ExecutorService directPool = new DirectExecutor();
        ExecutorService fixedPool = Executors.newFixedThreadPool(4);
        ExecutorService rawPool = Executors.newFixedThreadPool(4);
        ExecutorService rawSingle = Executors.newSingleThreadExecutor();

        ParRuntime rt = ParRuntime.builder()
                .register(ParId.of("direct"), directPool)
                .register(ParId.of("pool"), fixedPool)
                .defaultPar(ParId.of("direct"))
                .build();
        Par direct = rt.par(ParId.of("direct"));
        Par pooled = rt.par(ParId.of("pool"));

        section("environment");
        log("java " + System.getProperty("java.version")
                + ", cores " + Runtime.getRuntime().availableProcessors());
        log("timer executor class = " + timerExecutor(rt).getClass().getName());
        log("timer removeOnCancelPolicy = " + timerExecutor(rt).getRemoveOnCancelPolicy());

        // ---------- baselines ----------
        final int SUB = 10_000;
        section("raw JDK baseline (no library)");
        run("direct.execute(noop)", SUB, 3, 3, () -> {
            for (int i = 0; i < SUB; i++) {
                directPool.execute(Bench::noop);
            }
        });
        run("singlePool.execute(noop)", SUB, 3, 3, () -> {
            for (int i = 0; i < SUB; i++) {
                rawSingle.execute(Bench::noop);
            }
        });
        final List<java.util.concurrent.Callable<Integer>> calls = new ArrayList<>(SUB);
        for (int i = 0; i < SUB; i++) {
            calls.add(Bench::one);
        }
        run("pool.submit(callable)+get", SUB, 3, 3, () -> {
            List<Future<Integer>> fs = new ArrayList<>(SUB);
            for (java.util.concurrent.Callable<Integer> c : calls) {
                fs.add(rawPool.submit(c));
            }
            for (Future<Integer> f : fs) {
                f.get();
            }
        });

        // ---------- Par.submit ----------
        final int SUBS = 2_000;
        section("Par.submit x" + SUBS + " (direct executor, explicit 60s timeout)");
        run("Par.submit", SUBS, 3, 3, () -> {
            for (int i = 0; i < SUBS; i++) {
                direct.submit("bench", Bench::one, TaskOptions.timeout(Duration.ofSeconds(60)));
            }
        });

        // ---------- Par.map ----------
        final int N = 10_000;
        final List<Integer> input = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            input.add(i);
        }
        section("Par.map x" + N + " (one unit, one token, one timer per batch)");
        run("direct, valuesOrThrow", N, 3, 3, () -> {
            try (TaskBatchResult<Integer> r = direct.map(input, i -> i,
                    BatchOptions.timeout("bench", Duration.ofSeconds(60)))) {
                r.valuesOrThrow();
            }
        });
        run("direct, no wait", N, 3, 3, () -> {
            try (TaskBatchResult<Integer> r = direct.map(input, i -> i,
                    BatchOptions.timeout("bench", Duration.ofSeconds(60)))) {
                // deliberately no valuesOrThrow
            }
        });
        run("fixed(4) pool, valuesOrThrow", N, 3, 3, () -> {
            try (TaskBatchResult<Integer> r = pooled.map(input, i -> i,
                    BatchOptions.timeout("bench", Duration.ofSeconds(60)))) {
                r.valuesOrThrow();
            }
        });

        // ---------- deadline-timer retention probe ----------
        section("deadline-timer queue probe");
        ScheduledThreadPoolExecutor timer = timerExecutor(rt);
        log("queue size before probe: " + timer.getQueue().size());
        int probe = 5_000;
        for (int i = 0; i < probe; i++) {
            direct.submit("probe", Bench::one, TaskOptions.timeout(Duration.ofSeconds(60)));
        }
        log("after " + probe + " completed submits with a 60s timeout, queue size: "
                + timer.getQueue().size());
        Thread.sleep(200);
        log("after a 200ms settle, queue size: " + timer.getQueue().size()
                + "  (each entry is a cancelled deadline that will not fire for ~60s)");

        rt.close();
        rawSingle.shutdownNow();
        rawPool.shutdownNow();
        fixedPool.shutdownNow();
        log("closed cleanly");
    }

    // ------------------------------------------------------------------

    /** timerService is Executors.newSingleThreadScheduledExecutor(...) -> DelegatedScheduledExecutorService. */
    private static ScheduledThreadPoolExecutor timerExecutor(ParRuntime rt) throws Exception {
        Method m = ParRuntime.class.getDeclaredMethod("timerService");
        m.setAccessible(true);
        Object o = m.invoke(rt);
        while (!(o instanceof ScheduledThreadPoolExecutor)) {
            java.lang.reflect.Field f = findDelegateField(o.getClass());
            f.setAccessible(true);
            o = f.get(o);
        }
        return (ScheduledThreadPoolExecutor) o;
    }

    private static java.lang.reflect.Field findDelegateField(Class<?> type) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (ScheduledThreadPoolExecutor.class.isAssignableFrom(f.getType())
                        || java.util.concurrent.ScheduledExecutorService.class.isAssignableFrom(f.getType())) {
                    return f;
                }
            }
        }
        throw new IllegalStateException("no delegate field on " + type);
    }

    interface Body {
        void run() throws Exception;
    }

    static void run(String label, int ops, int warmup, int rounds, Body body) throws Exception {
        for (int i = 0; i < warmup; i++) {
            body.run();
        }
        long bestNs = Long.MAX_VALUE;
        long allocTotal = 0;
        for (int i = 0; i < rounds; i++) {
            long a0 = ALLOC.getThreadAllocatedBytes(ME);
            long t0 = System.nanoTime();
            body.run();
            long ns = System.nanoTime() - t0;
            allocTotal += ALLOC.getThreadAllocatedBytes(ME) - a0;
            if (ns < bestNs) {
                bestNs = ns;
            }
        }
        log(String.format("%-32s %9.1f ns/op %10.1f B/op", label,
                (double) bestNs / ops, (double) allocTotal / ((long) rounds * ops)));
    }

    static void section(String title) {
        log("");
        log("--- " + title + " ---");
    }

    static synchronized void log(String s) {
        System.out.println(s);
        System.out.flush();
    }

    static void noop() {
    }

    static Integer one() {
        return 1;
    }

    /** Runs the command on the calling thread, isolating submission cost from scheduling. */
    static final class DirectExecutor extends AbstractExecutorService {
        public void execute(Runnable command) {
            command.run();
        }

        public void shutdown() {
        }

        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        public boolean isShutdown() {
            return false;
        }

        public boolean isTerminated() {
            return false;
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
