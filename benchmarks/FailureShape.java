import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskBatchResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * What does valuesOrThrow() actually throw when a mid-batch element fails and
 * fail-fast cancels its earlier siblings? The user guide promises an
 * ExecutionException carrying the first failure.
 */
public final class FailureShape {

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        ParRuntime rt = ParRuntime.builder()
                .register(ParId.of("pool"), pool)
                .defaultPar(ParId.of("pool"))
                .build();
        Par par = rt.par(ParId.of("pool"));

        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(i);
        }

        for (int failingIndex : new int[] {5, 2}) {
            TaskBatchResult<Integer> batch = par.map(ids, i -> {
                if (i == failingIndex) {
                    throw new IllegalStateException("boom at " + i);
                }
                if (i < failingIndex) {
                    try {
                        Thread.sleep(300);   // earlier elements still running when the failure hits
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return i;
            }, BatchOptions.timeout("probe", Duration.ofSeconds(5)));
            try {
                batch.valuesOrThrow();
                System.out.println("failingIndex=" + failingIndex + " -> no exception (!)");
            } catch (Throwable t) {
                System.out.println("failingIndex=" + failingIndex
                        + " -> " + t.getClass().getName()
                        + "  cause=" + (t.getCause() == null ? "none" : t.getCause().toString()));
            }
        }

        rt.close();
        pool.shutdownNow();
        System.exit(0);
    }
}
