package io.github.huatalk.parallelinscope.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.huatalk.parallelinscope.spi.TaskListener.TaskEvent;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TaskListenerTest {
    @Test
    void eventExposesTimingMetadataAndFailure() {
        IllegalStateException failure = new IllegalStateException("failed");
        TaskEvent event = new TaskEvent("task", 10, 30, 80, true, failure);

        assertThat(event.getTaskName()).isEqualTo("task");
        assertThat(event.getSubmitTimeNanos()).isEqualTo(10);
        assertThat(event.getStartTimeNanos()).isEqualTo(30);
        assertThat(event.getEndTimeNanos()).isEqualTo(80);
        assertThat(event.isEnqueued()).isTrue();
        assertThat(event.getException()).isSameAs(failure);
        assertThat(event.waitTime()).isEqualTo(Duration.ofNanos(20));
        assertThat(event.executionTime()).isEqualTo(Duration.ofNanos(50));
        assertThat(event.totalTime()).isEqualTo(Duration.ofNanos(70));
    }

    @Test
    void successfulEventCanCarryNoException() {
        TaskEvent event = new TaskEvent("task", 0, 0, 0, false, null);
        assertThat(event.isEnqueued()).isFalse();
        assertThat(event.getException()).isNull();
    }
}
