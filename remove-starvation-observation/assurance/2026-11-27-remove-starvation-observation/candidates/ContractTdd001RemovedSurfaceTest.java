package io.github.monadrome.parallelinscope.assurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.monadrome.parallelinscope.ParRuntime;
import io.github.monadrome.parallelinscope.ParRuntimeSnapshot;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Candidate CONTRACT-TDD-001, contract C-1.
 *
 * <p>Precondition: the public API of package {@code io.github.monadrome.parallelinscope} is on the
 * classpath; no runtime is built.
 *
 * <p>Operation sequence: attempt {@code Class.forName} for the two removed type names, then collect
 * the public method names of {@link ParRuntime} and {@link ParRuntimeSnapshot}.
 *
 * <p>Observation points: reflective type lookups; reflective method-name sets.
 *
 * <p>Assertions: both removed type lookups fail with {@code ClassNotFoundException}; the runtime
 * method set contains no {@code openThreadPoolStarvationObservation} and the snapshot method set
 * contains no {@code activeObservations}.
 *
 * <p>Uncertainty: the nested {@code Status} and {@code Evidence} names are only loadable as members
 * of the removed outer type, so the two outer lookups imply their absence.
 */
public class ContractTdd001RemovedSurfaceTest {

    @Test
    void removedStarvationObservationSurfaceIsAbsent() {
        assertThatThrownBy(
                () -> Class.forName(
                        "io.github.monadrome.parallelinscope.ThreadPoolStarvationObservationScope"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(
                () -> Class.forName(
                        "io.github.monadrome.parallelinscope.ThreadPoolStarvationReport"))
                .isInstanceOf(ClassNotFoundException.class);

        Set<String> runtimeMethods = Arrays.stream(ParRuntime.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        Set<String> snapshotMethods = Arrays.stream(ParRuntimeSnapshot.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(runtimeMethods).doesNotContain("openThreadPoolStarvationObservation");
        assertThat(snapshotMethods).doesNotContain("activeObservations");
    }
}
