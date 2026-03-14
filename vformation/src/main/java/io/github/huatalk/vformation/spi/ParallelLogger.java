package io.github.huatalk.vformation.spi;

/**
 * SPI: Logging abstraction for the vformation framework.
 * <p>
 * Default implementation delegates to {@link java.util.logging.Logger}.
 * Users can provide their own implementation (e.g. bridging to SLF4J, Log4j2)
 * via {@code ParConfig.setLogger(ParallelLogger)}.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public interface ParallelLogger {

    void debug(String message, Object... args);

    void warn(String message, Object... args);

    void error(String message, Object... args);
}
