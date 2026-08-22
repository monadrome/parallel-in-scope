package io.github.huatalk.parallelinscope.spi;

import io.github.huatalk.parallelinscope.spi.LivelockListener.LivelockEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract tests for {@link LivelockEvent} diagnostics. */
class LivelockListenerTest {

    @Test
    void event_withNoIssues_hasAnyIssueFalse() {
        LivelockEvent event = new LivelockEvent(false, false, false, false, "tasks", "execs");
        assertThat(event.hasAnyIssue()).isFalse();
        assertThat(event.hasTaskCycle()).isFalse();
        assertThat(event.hasSelfLoop()).isFalse();
        assertThat(event.hasExecutorCycle()).isFalse();
        assertThat(event.hasExecutorSelfLoop()).isFalse();
    }

    @Test
    void event_withSelfLoop_hasAnyIssueTrue() {
        LivelockEvent event = new LivelockEvent(false, true, false, false, "tasks", "execs");
        assertThat(event.hasAnyIssue()).isTrue();
        assertThat(event.hasSelfLoop()).isTrue();
        assertThat(event.getTaskEdges()).isEqualTo("tasks");
        assertThat(event.getExecutorEdges()).isEqualTo("execs");
    }

    @Test
    void event_withTaskCycle_hasAnyIssueTrue() {
        LivelockEvent event = new LivelockEvent(true, false, false, false, "tasks", "execs");
        assertThat(event.hasAnyIssue()).isTrue();
        assertThat(event.hasTaskCycle()).isTrue();
    }

    @Test
    void event_withExecutorCycleOrSelfLoop_hasAnyIssueTrue() {
        assertThat(new LivelockEvent(false, false, true, false, "t", "e").hasAnyIssue()).isTrue();
        assertThat(new LivelockEvent(false, false, false, true, "t", "e").hasAnyIssue()).isTrue();
    }

    @Test
    void toString_containsDiagnostics() {
        LivelockEvent event = new LivelockEvent(true, true, true, true, "task-edges", "exec-edges");
        assertThat(event.toString())
                .contains("taskCycle=true")
                .contains("taskEdges=[task-edges]")
                .contains("executorEdges=[exec-edges]");
    }
}
