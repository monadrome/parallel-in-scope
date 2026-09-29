package io.github.monadrome.parallelinscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The shared argument checks, and the messages they produce.
 *
 * <p>These rules reach users from several entry points — a blank name from {@code BatchOptions},
 * {@code ParId}, {@code ParRuntime.group}, and a group member declaration — and each copy used to
 * word the failure differently. The assertions on message content are the point: a caller who
 * mistypes a duration should be told which value was rejected, not just that something was wrong.
 */
// NullAway: the null-rejection cases pass null deliberately.
@SuppressWarnings("NullAway")
class ValidationTest {

    @Test
    void requireNameAcceptsAnyNonBlankNameAndReturnsItUnchanged() {
        assertThat(Validation.requireName("orders", "name")).isEqualTo("orders");
        // No normalization: surrounding whitespace is the caller's business as long as something is
        // left after trimming.
        assertThat(Validation.requireName("  orders  ", "name")).isEqualTo("  orders  ");
    }

    @Test
    void requireNameRejectsNullAndBlankWithTheArgumentsOwnRole() {
        assertThatThrownBy(() -> Validation.requireName(null, "group name"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("group name cannot be null");
        assertThatThrownBy(() -> Validation.requireName("", "Par id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Par id cannot be blank");
        assertThatThrownBy(() -> Validation.requireName("   ", "member name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("member name cannot be blank");
    }

    @Test
    void requirePositiveRejectsZeroBecauseAnExpiredBudgetIsNotAChoice() {
        assertThat(Validation.requirePositive(Duration.ofSeconds(1), "timeout")).isEqualTo(Duration.ofSeconds(1));
        assertThatThrownBy(() -> Validation.requirePositive(Duration.ZERO, "timeout"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout must be positive")
                .hasMessageContaining("PT0S");
        assertThatThrownBy(() -> Validation.requirePositive(Duration.ofMillis(-5), "timeout"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PT-0.005S");
        assertThatThrownBy(() -> Validation.requirePositive(null, "timeout")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void requireNonNegativeDurationAcceptsZeroBecauseItMeansCancelOnly() {
        assertThat(Validation.requireNonNegative(Duration.ZERO, "closeGrace")).isEqualTo(Duration.ZERO);
        assertThatThrownBy(() -> Validation.requireNonNegative(Duration.ofSeconds(-1), "closeGrace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closeGrace must not be negative")
                .hasMessageContaining("PT-1S");
    }

    @Test
    void requireNonNegativeCountNamesTheRejectedValue() {
        assertThat(Validation.requireNonNegative(0, "taskCount")).isZero();
        assertThatThrownBy(() -> Validation.requireNonNegative(-2, "taskIndex"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskIndex must not be negative: -2");
    }

    @Test
    void theEntryPointsThatShareARuleNowShareItsMessage() {
        // One rule, four entry points: the wording no longer depends on which one you reached.
        assertThatThrownBy(() -> BatchOptions.timeout("batch", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout must be positive");
        assertThatThrownBy(() -> TaskOptions.timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeout must be positive");
        assertThatThrownBy(() -> ParId.of(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Par id cannot be blank");
        assertThatThrownBy(() -> BatchOptions.inheritTimeout(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name cannot be blank");
    }
}
