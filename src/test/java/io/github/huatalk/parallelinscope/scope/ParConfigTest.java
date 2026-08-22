package io.github.huatalk.parallelinscope.scope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Default-value contract tests for {@link ParConfig}. */
class ParConfigTest {

    @Test
    void defaultTimeoutMillis_isSixtySeconds() {
        ParConfig config = ParConfig.builder().build();
        assertThat(config.getDefaultTimeoutMillis()).isEqualTo(60_000L);
    }

    @Test
    void livelockDetection_isDisabledByDefault() {
        ParConfig config = ParConfig.builder().build();
        assertThat(config.isLivelockDetectionEnabled()).isFalse();
    }

    @Test
    void explicitLivelockDetection_isRespected() {
        ParConfig enabled = ParConfig.builder().livelockDetectionEnabled(true).build();
        assertThat(enabled.isLivelockDetectionEnabled()).isTrue();
    }
}
