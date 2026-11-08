package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The submission-scope install/restore pair: {@code install} returns the unit it replaced and
 * {@code restore} returns the thread to that unit, so a nested inline submission cannot erase or
 * replace its enclosing submission scope.
 */
class SubmissionScopeTest {

    @AfterEach
    void clearScope() {
        SubmissionScope.restore(null);
    }

    @Test
    void installReturnsTheUnitItReplacedAndRestorePutsItBack() {
        MultiTaskContext outer = context("outer");
        MultiTaskContext inner = context("inner");

        assertThat(SubmissionScope.current()).isNull();

        assertThat(SubmissionScope.install(outer)).isNull();
        assertThat(SubmissionScope.current()).isSameAs(outer);

        MultiTaskContext replaced = SubmissionScope.install(inner);
        assertThat(replaced).isSameAs(outer);
        assertThat(SubmissionScope.current()).isSameAs(inner);

        SubmissionScope.restore(replaced);
        assertThat(SubmissionScope.current())
                .as("restoring the replaced unit returns the thread to the enclosing submission")
                .isSameAs(outer);

        SubmissionScope.restore(null);
        assertThat(SubmissionScope.current()).isNull();
    }

    private static MultiTaskContext context(String taskName) {
        return MultiTaskContext.resolve(MultiTaskContext.resolution(
                BatchOptions.timeout(taskName, Duration.ofSeconds(30)).spec(), 1));
    }
}
