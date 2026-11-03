package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** Kernel lifecycle of one adopted member, below the group API. */
class AdoptedSourceTest {

    private static TaskExecutionContext prepared(ExecutionExitTracker tracker) {
        MultiTaskContext unit = MultiTaskContext.resolve(MultiTaskContext.resolution(
                TaskOptions.timeout(Duration.ofSeconds(5)).spec("adopted"), 1));
        return new TaskExecutionContext(unit, 0, System.nanoTime(), tracker.register(unit));
    }

    @Test
    void abandoningAPreparedSourceReleasesItsExecutionSlotOnceTheSourceSettles() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        SettableFuture<String> source = SettableFuture.create();
        AdoptedSource adopted = new AdoptedSource("adopted", source);
        adopted.attach(prepared(tracker));

        new TaskGroup.RunBindings(new Callable<?>[1], new AdoptedSource[] {adopted}, null).discard();

        assertThat(source.isCancelled()).isTrue();
        assertThat(adopted.mirror().isCancelled()).isTrue();
        assertThat(tracker.isDone()).isTrue();
    }

    @Test
    void abandoningAConnectedSourceLeavesItToTheGroup() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        SettableFuture<String> source = SettableFuture.create();
        AdoptedSource adopted = new AdoptedSource("adopted", source);
        adopted.attach(prepared(tracker));
        adopted.connect();

        adopted.abandon();

        assertThat(source.isCancelled()).isFalse();
        assertThat(tracker.isDone()).isFalse();
        source.set("value");
        assertThat(tracker.isDone()).isTrue();
    }
}
