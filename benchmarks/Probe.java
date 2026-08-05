import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskOptions;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Quantifies the retained-cancelled-deadline cost: N completed submits with a long
 * timeout, then how many timer entries survive and how much heap they pin.
 */
public final class Probe {

    static final int N = 100_000;

    public static void main(String[] args) throws Exception {
        ExecutorService direct = new DirectExecutor();
        ParRuntime rt = ParRuntime.builder()
                .register(ParId.of("d"), direct)
                .defaultPar(ParId.of("d"))
                .build();
        Par par = rt.par(ParId.of("d"));

        // warm up the code paths so the delta is mostly retained timer state
        for (int i = 0; i < 2_000; i++) {
            par.submit("w", Probe::one, TaskOptions.timeout(Duration.ofSeconds(300)));
        }
        long usedBefore = usedAfterGc();
        int queueBefore = timerQueue(rt).getQueue().size();

        for (int i = 0; i < N; i++) {
            par.submit("p", Probe::one, TaskOptions.timeout(Duration.ofSeconds(300)));
        }
        long usedAfter = usedAfterGc();
        int queueAfter = timerQueue(rt).getQueue().size();

        System.out.println("timer entries before: " + queueBefore);
        System.out.println("timer entries after " + N + " completed submits: " + queueAfter);
        System.out.println("retained entries delta: " + (queueAfter - queueBefore));
        System.out.println("heap delta: " + (usedAfter - usedBefore) / (1024 * 1024) + " MB");
        System.out.println("bytes per retained deadline: "
                + (usedAfter - usedBefore) / Math.max(1, queueAfter - queueBefore));
        System.out.println("timeout was 300s: these entries outlive the tasks by up to 5 minutes each");
        System.out.flush();
        System.exit(0);
    }

    static long usedAfterGc() throws Exception {
        Runtime r = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(250);
        }
        return r.totalMemory() - r.freeMemory();
    }

    static ScheduledThreadPoolExecutor timerQueue(ParRuntime rt) throws Exception {
        Method m = ParRuntime.class.getDeclaredMethod("timerService");
        m.setAccessible(true);
        Object o = m.invoke(rt);
        while (!(o instanceof ScheduledThreadPoolExecutor)) {
            java.lang.reflect.Field f = null;
            for (java.lang.reflect.Field cand : o.getClass().getDeclaredFields()) {
                if (java.util.concurrent.ScheduledExecutorService.class.isAssignableFrom(cand.getType())) {
                    f = cand;
                    break;
                }
            }
            f.setAccessible(true);
            o = f.get(o);
        }
        return (ScheduledThreadPoolExecutor) o;
    }

    static Integer one() {
        return 1;
    }

    static final class DirectExecutor extends AbstractExecutorService {
        public void execute(Runnable c) {
            c.run();
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

        public boolean awaitTermination(long t, TimeUnit u) {
            return true;
        }
    }
}
