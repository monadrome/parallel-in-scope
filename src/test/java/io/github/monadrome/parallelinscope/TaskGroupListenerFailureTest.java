package io.github.monadrome.parallelinscope;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.reflect.TypeToken;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A caller's listener must never be able to stop the framework from converging.
 *
 * <p>Guava's listener fan-out catches {@code RuntimeException} but not {@code Error}, and a listener
 * registered with a direct executor runs inline on whichever framework thread completes the future.
 * An {@code Error} raised there therefore escapes into the framework's own publication path. The
 * group's contract says every task failure is reported through futures and results — never by
 * wedging the group — so each publication that a caller can attach to needs a guard.
 *
 * <p>The values-future case is covered in {@link TaskGroupValuesFutureTest}; this class covers the
 * earlier one, where the throwing listener is attached to a member's own observation future.
 */
class TaskGroupListenerFailureTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void anErrorFromAMemberObservationListenerDoesNotStrandTheGroup() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ParRuntime global =
                ParRuntime.builder().register(ParId.of("worker"), executor).build();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskGroup<String, Void> group = global.groupDraft("page", TIMEOUT)
                    .par("user", global.par(ParId.of("worker")), String.class, () -> {
                        started.countDown();
                        release.await(10, TimeUnit.SECONDS);
                        return "value";
                    })
                    .submitAll();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            // The member's observation future is caller-reachable, and a throwing direct listener on
            // it runs on the thread that publishes the observation — inside the member's own
            // completion path, before the barrier has counted that member.
            group.futureOf("user")
                    .completionFuture()
                    .addListener(
                            () -> {
                                throw new AssertionError("observation boom");
                            },
                            directExecutor());
            release.countDown();

            // Both the member and the group must still settle. If the Error escapes into the
            // publication path, the member's observation finishes but the barrier never counts it,
            // and the group stays pending forever.
            assertThat(group.futureOf("user", TypeToken.of(String.class)).get(5, TimeUnit.SECONDS))
                    .isEqualTo("value");
            assertThat(group.completionFuture().get(5, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TaskOutcome.SUCCESS);
            assertThat(group.valuesFuture().get(5, TimeUnit.SECONDS).typedValues())
                    .isEqualTo("value");
        } finally {
            release.countDown();
            global.close();
            executor.shutdownNow();
        }
    }
}
