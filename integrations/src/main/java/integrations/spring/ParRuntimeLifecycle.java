package integrations.spring;

import io.github.monadrome.parallelinscope.ParRuntime;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.context.SmartLifecycle;

/**
 * Stops a {@link ParRuntime} during the Spring lifecycle stop phase.
 *
 * <p>{@link #stop()} closes the runtime, so new batches and groups are rejected, and then waits up
 * to the configured timeout for every admitted execution to exit. Spring runs the stop phase before it
 * destroys any singleton, so the clients, data sources, and executors those executions use are still
 * open while they finish. The phase is below Spring Boot's web server phases: the server stops
 * accepting requests before the runtime stops accepting work.
 *
 * <p>When the wait is interrupted, the interrupt flag is restored and the context continues its
 * shutdown; when it times out, a warning carries {@link ParRuntime#snapshot()}. In both cases the
 * executors are shut down afterwards by their own beans.
 */
public final class ParRuntimeLifecycle implements SmartLifecycle {

    /**
     * Stops after Spring Boot's web server lifecycles, whose phases lie within 2048 below {@code
     * DEFAULT_PHASE}.
     */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private static final Logger LOGGER = Logger.getLogger(ParRuntimeLifecycle.class.getName());

    private final ParRuntime runtime;
    private final Duration shutdownTimeout;
    private volatile boolean running;

    /**
     * Creates the lifecycle for one runtime.
     *
     * @param runtime the runtime to stop
     * @param shutdownTimeout how long {@link #stop()} waits for admitted executions
     */
    public ParRuntimeLifecycle(ParRuntime runtime, Duration shutdownTimeout) {
        this.runtime = runtime;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        runtime.close();
        try {
            if (!runtime.awaitQuiescence(shutdownTimeout)) {
                LOGGER.log(Level.WARNING, "ParRuntime did not quiesce within {0}: {1}", new Object[] {
                    shutdownTimeout, runtime.snapshot()
                });
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
