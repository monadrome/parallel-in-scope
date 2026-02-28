package io.github.linzee1.concurrent.spi;

/**
 * SPI: Logging abstraction for the structured-concurrency framework.
 * <p>
 * Default implementation delegates to {@link java.util.logging.Logger}.
 * Users can provide their own implementation (e.g. bridging to SLF4J, Log4j2)
 * via {@code StructuredParallel.setLogger(ParallelLogger)}.
 *
 * @author linqh
 */
public interface ParallelLogger {

    void debug(String message, Object... args);

    void warn(String message, Object... args);

    void error(String message, Object... args);
}
