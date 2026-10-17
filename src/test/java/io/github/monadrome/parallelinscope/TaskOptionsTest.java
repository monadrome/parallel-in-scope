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

        // No name, no parallelism, no listener: identity comes from the name declared to the
        // group chain (or the explicit name at Par.submit) and a single task has no fan-out to
        // limit, so those fields must not exist rather than be silently ignored.
        assertThat(names)
                .isEqualTo(new TreeSet<>(Arrays.asList("inheritTimeout", "rejectEnqueue", "taskType", "timeout")));
    }

    @Test
    void inheritTimeoutYieldsAnEmptyTimeoutAccessor() {
        assertThat(TaskOptions.inheritTimeout().timeout()).isEmpty();
    }

    @Test
    void defaultsAreIoBoundEnqueueingAndFailOnRejection() {
        TaskOptions options = TaskOptions.timeout(Duration.ofSeconds(30));

        // IO_BOUND and rejectEnqueue=false travel together: SmartBlockingQueue refuses an offer when
        // the type is CPU_BOUND OR the flag is set, so either default alone would make such a queue
        // refuse every task submitted with default options, leaving its capacity unused and sending
        // every task to the rejection handler. Refusing to enqueue is therefore opt-in.
        assertThat(options.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(options.rejectEnqueue()).isFalse();
    }

    @Test
    void withersReturnNewInstancesWithoutMutatingTheOriginal() {
        TaskOptions base = TaskOptions.timeout(Duration.ofSeconds(1)).taskType(TaskType.CPU_BOUND);

        TaskOptions derived = base.rejectEnqueue(true).taskType(TaskType.IO_BOUND);

        assertThat(base.taskType()).isEqualTo(TaskType.CPU_BOUND);
        assertThat(base.rejectEnqueue()).isFalse();
        assertThat(derived.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(derived.rejectEnqueue()).isTrue();
        assertThat(derived.timeout()).contains(Duration.ofSeconds(1));

        TaskOptions inherited = TaskOptions.inheritTimeout();
        assertThat(inherited.taskType(TaskType.CPU_BOUND).taskType()).isEqualTo(TaskType.CPU_BOUND);
        assertThat(inherited.taskType()).isEqualTo(TaskType.IO_BOUND);
    }

    // NullAway: deliberate null arguments — probes the null-rejection contract
    @SuppressWarnings("NullAway")
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
                .spec("get-user");

        assertThat(spec.name()).isEqualTo("get-user");
        assertThat(spec.requestedParallelism()).isEqualTo(1);
        assertThat(spec.timeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(spec.taskType()).isEqualTo(TaskType.IO_BOUND);
        assertThat(spec.rejectEnqueue()).isFalse();
    }

    @Test
    void specKeepsAnInheritedTimeoutNull() {
        assertThat(TaskOptions.inheritTimeout().spec("member").timeout()).isNull();
    }
}
