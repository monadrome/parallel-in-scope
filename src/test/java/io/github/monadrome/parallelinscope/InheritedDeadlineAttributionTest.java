package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.base.Ticker;
import com.google.common.util.concurrent.MoreExecutors;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class InheritedDeadlineAttributionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checkpointTimeoutReachesGroupEvenWhenMemberBindIsSkipped(boolean terminal) {
        AtomicLong nanos = new AtomicLong();
        Ticker ticker = new Ticker() {
            @Override
            public long read() {
                return nanos.get();
            }
        };
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> timer = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(ignored -> timer);
        ExecutorService executor = MoreExecutors.newDirectExecutorService();
        ParRuntime runtime = ParRuntime.builder()
                .register(ParId.of("worker"), executor)
                .ticker(ticker)
                .timeoutScheduler(scheduler)
                .build();
        try {
            Par par = runtime.par(ParId.of("worker"));
            GroupStep<Integer> group = runtime.group("deadline", Duration.ofNanos(10))
                    .closeGrace(Duration.ZERO)
                    .par("member", par, Integer.class, () -> terminal ? 1 : expireAtCheckpoint(nanos));
            TaskGroupResult<Integer, Integer> combined;
            if (terminal) {
                combined = group.combine("terminal", par, Integer.class, value -> expireAtCheckpoint(nanos))
                        .runAll();
                assertThat(combined.terminalResult()).isNotNull();
                assertThat(combined.terminalResult().outcome()).isEqualTo(TaskOutcome.TIMEOUT);
                assertThat(combined.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            } else {
                TaskGroupResult<Integer, Void> result = group.runAll();
                assertThat(result.resultOf("member").outcome()).isEqualTo(TaskOutcome.TIMEOUT);
                assertThat(result.outcome()).isEqualTo(TaskOutcome.TIMEOUT);
            }
        } finally {
            runtime.close();
            executor.shutdownNow();
        }
    }

    private static int expireAtCheckpoint(AtomicLong nanos) {
        nanos.set(10);
        Checkpoints.checkpoint();
        throw new AssertionError("expired checkpoint returned");
    }
}
