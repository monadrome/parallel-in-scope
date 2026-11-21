package verification;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import io.github.monadrome.parallelinscope.GroupStart;
import io.github.monadrome.parallelinscope.GroupStep;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.TaskKey;
import java.util.concurrent.TimeUnit;

/** Supported finite-source calls must compile under Java 8 with either compiler configuration. */
final class FiniteCalls {
    /** A one-shot result uses a general future reference before selecting the adoption overload. */
    GroupStep oneShot(GroupStart start, TaskKey<String> key, ListeningScheduledExecutorService scheduler) {
        ListenableFuture<String> source = scheduler.schedule(() -> "value", 0, TimeUnit.MILLISECONDS);
        return start.adopt(key, source);
    }

    /** A finite first member supports explicit producer attribution. */
    GroupStep firstWithProducer(GroupStart start, TaskKey<String> key, Par producer, ListenableFuture<String> source) {
        return start.adopt(key, producer, source);
    }

    /** A later member accepts a finite source without producer attribution. */
    GroupStep next(GroupStep step, TaskKey<String> key, ListenableFuture<String> source) {
        return step.adopt(key, source);
    }

    /** A later finite member also supports producer attribution. */
    GroupStep nextWithProducer(GroupStep step, TaskKey<String> key, Par producer, ListenableFuture<String> source) {
        return step.adopt(key, producer, source);
    }
}
