package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

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
}
