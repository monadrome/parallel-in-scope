package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Contract pins for {@link TaskOptions}: the two timeout factories, immutable withers, defaults, and
 * the field set a single task execution is allowed to declare.
 */
class TaskOptionsTest {

    @Test
    void exposesOnlyTheSurfaceOneTaskExecutionReads() {
        Set<String> names = Arrays.stream(TaskOptions.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        // No name, no parallelism, no listener: identity comes from the TaskKey and a single task
        // has no fan-out to limit, so those fields must not exist rather than be silently ignored.
        assertThat(names)
                .isEqualTo(new TreeSet<>(
                        Arrays.asList("inheritTimeout", "rejectEnqueue", "runOnCallerThread", "taskType", "timeout")));
    }

    @Test
    void inheritTimeoutYieldsAnEmptyTimeoutAccessor() {
        assertThat(TaskOptions.inheritTimeout().timeout()).isEmpty();
    }

    @Test
    void defaultsAreCpuBoundRejectingAndFailOnRejection() {
        TaskOptions options = TaskOptions.timeout(Duration.ofSeconds(30));

        assertThat(options.taskType()).isEqualTo(TaskType.CPU_BOUND);
        assertThat(options.rejectEnqueue()).isTrue();
        // No task type implies the caller-thread fallback, CPU_BOUND included.
        assertThat(options.runOnCallerThread()).isFalse();
    }

    @Test
    void withersReturnNewInstancesWithoutMutatingTheOriginal() {
        TaskOptions base = TaskOptions.timeout(Duration.ofSeconds(1)).taskType(TaskType.IO_BOUND);

        TaskOptions derived =
                base.rejectEnqueue(false).taskType(TaskType.CPU_BOUND).runOnCallerThread(true);

        assertThat(base.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(base.rejectEnqueue()).isTrue();
        assertThat(base.runOnCallerThread()).isFalse();
        assertThat(derived.taskType()).isEqualTo(TaskType.CPU_BOUND);
        assertThat(derived.rejectEnqueue()).isFalse();
        assertThat(derived.runOnCallerThread()).isTrue();
        assertThat(derived.timeout()).contains(Duration.ofSeconds(1));

        TaskOptions inherited = TaskOptions.inheritTimeout();
        assertThat(inherited.taskType(TaskType.IO_BOUND).taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(inherited.taskType()).isEqualTo(TaskType.CPU_BOUND);
    }

    @Test
    void rejectsNonPositiveExplicitTimeouts() {
        assertThatThrownBy(() -> TaskOptions.timeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskOptions.timeout(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskOptions.timeout(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void specCarriesTheKeyNameAndNoFanOut() {
        UnitSpec spec = TaskOptions.timeout(Duration.ofSeconds(3))
                .taskType(TaskType.IO_BOUND)
                .rejectEnqueue(false)
                .runOnCallerThread(true)
                .spec("get-user");

        assertThat(spec.name()).isEqualTo("get-user");
        assertThat(spec.requestedParallelism()).isEqualTo(1);
        assertThat(spec.timeout()).contains(Duration.ofSeconds(3));
        assertThat(spec.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(spec.rejectEnqueue()).isFalse();
        assertThat(spec.runOnCallerThread()).isTrue();
    }

    @Test
    void specKeepsAnInheritedTimeoutEmpty() {
        assertThat(TaskOptions.inheritTimeout().spec("member").timeout()).isEmpty();
    }
}
