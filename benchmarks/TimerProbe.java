import java.util.concurrent.*;
public class TimerProbe {
    public static void main(String[] a) throws Exception {
        for (boolean policy : new boolean[]{false, true}) {
            ScheduledThreadPoolExecutor t = new ScheduledThreadPoolExecutor(1);
            t.setRemoveOnCancelPolicy(policy);
            int n = 100_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                ScheduledFuture<?> f = t.schedule(() -> {}, 300, TimeUnit.SECONDS);
                f.cancel(false);
            }
            long ns = System.nanoTime() - t0;
            System.out.printf("removeOnCancelPolicy=%-5s queue=%7d  %6.1f ns per (schedule+cancel)%n",
                    policy, t.getQueue().size(), (double) ns / n);
            t.shutdownNow();
        }
    }
}
