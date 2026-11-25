package io.github.monadrome.parallelinscope.assurance;

import io.github.monadrome.parallelinscope.BatchOptions;
import io.github.monadrome.parallelinscope.Par;
import io.github.monadrome.parallelinscope.ParId;
import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.TaskOptions;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRACT-TDD-001 (contract C-1).
 *
 * <p>Precondition: the library jar is on the test class path and nothing else. The operation
 * sequence is pure reflection over the frozen public API surface.
 *
 * <p>Observation points: (1) {@code Class.forName} of each removed type name; (2) the public
 * method-name sets of {@code BatchOptions}, {@code TaskOptions}, {@code Par}, and
 * {@code ParRuntime}; (3) the declared public {@code register} overloads of
 * {@code ParRuntime.Builder}.
 *
 * <p>Assertions: {@code io.github.monadrome.parallelinscope.TaskType} and
 * {@code io.github.monadrome.parallelinscope.WorkloadFitReport} do not load; no public method
 * named {@code taskType} appears on {@code BatchOptions}, {@code TaskOptions}, or {@code Par}; no
 * public method named {@code workloadFitReport} appears on {@code ParRuntime}; every public
 * {@code register} method on {@code ParRuntime.Builder} takes exactly the
 * {@code (ParId, ExecutorService)} pair.
 *
 * <p>Uncertainty: this is API-surface evidence only, not behavior; no replacement API exists.
 */
public class ContractTdd001RemovedSurfaceTest {

    @Test
    void removedWorkloadSurfaceIsAbsent() throws Exception {
        assertMissing("io.github.monadrome.parallelinscope.TaskType");
        assertMissing("io.github.monadrome.parallelinscope.WorkloadFitReport");

        assertThat(publicMethodNames(BatchOptions.class)).doesNotContain("taskType");
        assertThat(publicMethodNames(TaskOptions.class)).doesNotContain("taskType");
        assertThat(publicMethodNames(Par.class)).doesNotContain("taskType");
        assertThat(publicMethodNames(ParRuntime.class)).doesNotContain("workloadFitReport");

        boolean sawRegister = false;
        for (Method method : ParRuntime.Builder.class.getMethods()) {
            if ("register".equals(method.getName())) {
                sawRegister = true;
                assertThat(method.getParameterCount()).isEqualTo(2);
                assertThat(method.getParameterTypes())
                        .containsExactly(ParId.class, ExecutorService.class);
            }
        }
        assertThat(sawRegister).isTrue();
    }

    private static void assertMissing(String className) throws Exception {
        try {
            Class.forName(className);
            throw new AssertionError("class should not exist: " + className);
        } catch (ClassNotFoundException expected) {
            // expected
        }
    }

    private static Set<String> publicMethodNames(Class<?> type) {
        Set<String> names = new HashSet<>();
        for (Method method : type.getMethods()) {
            names.add(method.getName());
        }
        return names;
    }
}
