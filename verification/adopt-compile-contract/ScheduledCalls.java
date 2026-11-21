package verification;

import com.google.common.util.concurrent.ListenableScheduledFuture;
import io.github.monadrome.parallelinscope.GroupStart;
import io.github.monadrome.parallelinscope.GroupStep;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.TaskKey;

/** Each method must produce its own forbidden-call or deprecation diagnostic. */
final class ScheduledCalls {
    /** Rejects a statically scheduled first member. */
    GroupStep first(GroupStart start, TaskKey<String> key, ListenableScheduledFuture<String> source) {
        return start.adopt(key, source);
    }

    /** Rejects a statically scheduled first member with producer attribution. */
    GroupStep firstWithProducer(GroupStart start, TaskKey<String> key, Par producer, ListenableScheduledFuture<String> source) {
        return start.adopt(key, producer, source);
    }

    /** Rejects a statically scheduled later member. */
    GroupStep next(GroupStep step, TaskKey<String> key, ListenableScheduledFuture<String> source) {
        return step.adopt(key, source);
    }

    /** Rejects a statically scheduled later member with producer attribution. */
    GroupStep nextWithProducer(GroupStep step, TaskKey<String> key, Par producer, ListenableScheduledFuture<String> source) {
        return step.adopt(key, producer, source);
    }
}
