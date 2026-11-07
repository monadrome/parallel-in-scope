package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Diagnostic delivery is best effort: no failure in starting or running a delivery reaches the caller. */
class AnnouncementsTest {

    @Test
    void aMessageRunsOnTheGivenExecutor() {
        AtomicInteger runs = new AtomicInteger();

        Announcements.deliver(Runnable::run, runs::incrementAndGet);

        assertThat(runs).hasValue(1);
    }

    @Test
    void aFailingHandlerIsDropped() {
        Announcements.deliver(Runnable::run, () -> {
            throw new IllegalStateException("handler failure");
        });
    }

    @Test
    void aRejectedOrFailedDeliveryIsDropped() {
        AtomicInteger runs = new AtomicInteger();

        Announcements.deliver(
                command -> {
                    throw new RejectedExecutionException("services shut down");
                },
                runs::incrementAndGet);
        Announcements.deliver(
                command -> {
                    throw new SecurityException("thread creation denied");
                },
                runs::incrementAndGet);

        assertThat(runs).hasValue(0);
    }

    /**
     * Creating the delivery thread can fail with an {@link OutOfMemoryError} on the admitting thread,
     * and a handler can fail with an {@link Error}; neither reaches the caller. Each delivery runs
     * inside {@code assertThatCode}, so an error that escapes fails this test instead of the test JVM.
     */
    @Test
    void anErrorStartingOrRunningADeliveryIsDropped() {
        assertThatCode(() -> Announcements.deliver(
                        command -> {
                            throw new OutOfMemoryError("unable to create native thread");
                        },
                        () -> {}))
                .doesNotThrowAnyException();
        assertThatCode(() -> Announcements.deliver(Runnable::run, () -> {
                    throw new AssertionError("handler error");
                }))
                .doesNotThrowAnyException();
    }
}
