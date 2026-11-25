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
        assertThat(names).isEqualTo(new TreeSet<>(Arrays.asList("inheritTimeout", "rejectEnqueue", "timeout")));
    }

    @Test
    void inheritTimeoutYieldsAnEmptyTimeoutAccessor() {
        assertThat(TaskOptions.inheritTimeout().timeout()).isEmpty();
    }

    @Test
    void defaultsAreEnqueueing() {
        TaskOptions options = TaskOptions.timeout(Duration.ofSeconds(30));

        assertThat(options.rejectEnqueue()).isFalse();
    }

    @Test
    void withersReturnNewInstancesWithoutMutatingTheOriginal() {
        TaskOptions base = TaskOptions.timeout(Duration.ofSeconds(1));

        TaskOptions derived = base.rejectEnqueue(true);

        assertThat(base.rejectEnqueue()).isFalse();
        assertThat(derived.rejectEnqueue()).isTrue();
        assertThat(derived.timeout()).contains(Duration.ofSeconds(1));

        TaskOptions inherited = TaskOptions.inheritTimeout();
        assertThat(inherited.rejectEnqueue(true).rejectEnqueue()).isTrue();
        assertThat(inherited.rejectEnqueue()).isFalse();
    }

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
        UnitSpec spec =
                TaskOptions.timeout(Duration.ofSeconds(3)).rejectEnqueue(false).spec("get-user");

        assertThat(spec.name()).isEqualTo("get-user");
        assertThat(spec.requestedParallelism()).isEqualTo(1);
        assertThat(spec.timeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(spec.rejectEnqueue()).isFalse();
    }

    @Test
    void specKeepsAnInheritedTimeoutNull() {
        assertThat(TaskOptions.inheritTimeout().spec("member").timeout()).isNull();
    }
}
