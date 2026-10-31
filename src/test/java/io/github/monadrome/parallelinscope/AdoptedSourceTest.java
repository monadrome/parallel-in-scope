package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** Kernel lifecycle of one adopted member, below the group API: abandonment, null values, and logging. */
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
        AdoptedSource adopted = new AdoptedSource("adopted", source, null, MoreExecutors.directExecutor());
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
        AdoptedSource adopted = new AdoptedSource("adopted", source, null, MoreExecutors.directExecutor());
        adopted.attach(prepared(tracker));
        adopted.connect();

        adopted.abandon();

        assertThat(source.isCancelled()).isFalse();
        assertThat(tracker.isDone()).isFalse();
        source.set("value");
        assertThat(tracker.isDone()).isTrue();
    }

    @Test
    void aDiscardedNullValueIsNeverPassedToTheCloser() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        AtomicInteger closes = new AtomicInteger();
        AdoptedSource adopted = new AdoptedSource(
                "adopted",
                Futures.immediateFuture(null),
                value -> closes.incrementAndGet(),
                MoreExecutors.directExecutor());
        adopted.attach(prepared(tracker));
        adopted.connect();

        adopted.decide(true);

        assertThat(closes).hasValue(0);
        assertThat(tracker.isDone()).isTrue();
    }

    @Test
    void aFailingOrInterruptedCloserIsReportedWithTheMemberName() {
        Logger logger = Logger.getLogger(AdoptedSource.class.getName());
        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(capture);
        try {
            for (Exception failure :
                    new Exception[] {new IllegalStateException("broken"), new InterruptedException("stop")}) {
                ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
                AdoptedSource adopted = new AdoptedSource(
                        "adopted",
                        Futures.immediateFuture("value"),
                        value -> {
                            throw failure;
                        },
                        MoreExecutors.directExecutor());
                adopted.attach(prepared(tracker));
                adopted.connect();

                adopted.decide(true);

                assertThat(tracker.isDone()).isTrue();
            }
            assertThat(Thread.interrupted()).isTrue();
        } finally {
            logger.removeHandler(capture);
        }

        assertThat(records).hasSize(2);
        for (LogRecord record : records) {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage()).contains("'adopted'");
        }
        assertThat(records.get(1).getThrown()).isInstanceOf(InterruptedException.class);
    }

    @Test
    void aReleaseTheExecutorRefusesRunsOnTheSettlingThread() {
        ExecutionExitTracker tracker = ExecutionExitTracker.create(1);
        AtomicInteger closes = new AtomicInteger();
        AdoptedSource adopted = new AdoptedSource(
                "adopted", Futures.immediateFuture("value"), value -> closes.incrementAndGet(), command -> {
                    throw new RejectedExecutionException("services shut down");
                });
        adopted.attach(prepared(tracker));
        adopted.connect();

        adopted.decide(true);

        assertThat(closes).hasValue(1);
        assertThat(tracker.isDone()).isTrue();
    }
}
