package io.github.huatalk.vformation;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-thread context relay using {@link TransmittableThreadLocal.Transmitter}.
 * <p>
 * Implements a two-map design ({@code parentMap} and {@code curMap}):
 * when a child thread is spawned, the parent thread's {@code curMap} becomes
 * the child thread's {@code parentMap}. This enables parent-to-child propagation of:
 * <ul>
 *   <li>{@link CancellationToken} - for cascading cancellation</li>
 *   <li>{@link ParOptions} - for context awareness</li>
 *   <li>Task name - for task graph / livelock detection</li>
 * </ul>
 * <p>
 * Uses {@link TransmittableThreadLocal.Transmitter#registerThreadLocal} to register
 * a plain {@link ThreadLocal} for TTL propagation, following the recommended
 * third-party library integration pattern.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
class ThreadRelay {

    enum RelayItem {
        CANCELLATION_TOKEN,
        PARALLEL_OPTIONS,
        TASK_NAME,
        EXECUTOR_NAME
    }

    private static final ThreadLocal<ThreadRelay> THREAD_RELAY_TL =
            ThreadLocal.withInitial(ThreadRelay::new);

    static {
        TransmittableThreadLocal.Transmitter.registerThreadLocal(
                THREAD_RELAY_TL, tr -> new ThreadRelay(tr.curMap));
    }

    static ThreadRelay getThreadRelay() {
        return THREAD_RELAY_TL.get();
    }

    private final Map<RelayItem, Object> parentMap = new ConcurrentHashMap<>();
    private final Map<RelayItem, Object> curMap = new ConcurrentHashMap<>();

    ThreadRelay() {
    }

    ThreadRelay(Map<RelayItem, Object> parentContext) {
        if (parentContext != null) {
            this.parentMap.putAll(parentContext);
        }
    }

    // ==================== CancellationToken relay ====================

    static CancellationToken getParentCancellationToken() {
        ThreadRelay relay = THREAD_RELAY_TL.get();
        if (relay == null) {
            return null;
        }
        Object token = relay.parentMap.get(RelayItem.CANCELLATION_TOKEN);
        return token instanceof CancellationToken ? (CancellationToken) token : null;
    }

    static void setCurrentCancellationToken(CancellationToken token) {
        if (token == null) {
            return;
        }
        ThreadRelay relay = THREAD_RELAY_TL.get();
        if (relay != null) {
            relay.curMap.put(RelayItem.CANCELLATION_TOKEN, token);
        }
    }

    // ==================== ParOptions relay ====================

    static void setCurrentParallelOptions(ParOptions options) {
        ThreadRelay relay = THREAD_RELAY_TL.get();
        if (relay != null) {
            relay.curMap.put(RelayItem.PARALLEL_OPTIONS, Optional.ofNullable(options));
        }
    }

    // ==================== TaskName relay ====================

    @SuppressWarnings("unchecked")
    static String getCurrentTaskName() {
        ThreadRelay relay = THREAD_RELAY_TL.get();
        if (relay == null) {
            return "NA";
        }
        Object wrapped = relay.curMap.get(RelayItem.TASK_NAME);
        if (wrapped instanceof Optional) {
            return ((Optional<String>) wrapped).orElse("NA");
        }
        return "NA";
    }

    static void setCurrentTaskName(String taskName) {
        ThreadRelay relay = THREAD_RELAY_TL.get();
        if (relay != null) {
            relay.curMap.put(RelayItem.TASK_NAME, Optional.ofNullable(taskName));
        }
    }

    // ==================== ExecutorName relay ====================

    @SuppressWarnings("unchecked")
    static String getCurrentExecutorName() {
        ThreadRelay relay = THREAD_RELAY_TL.get();
        if (relay == null) {
            return "NA";
        }
        Object wrapped = relay.curMap.get(RelayItem.EXECUTOR_NAME);
        if (wrapped instanceof Optional) {
            return ((Optional<String>) wrapped).orElse("NA");
        }
        return "NA";
    }

    static void setCurrentExecutorName(String executorName) {
        ThreadRelay relay = THREAD_RELAY_TL.get();
        if (relay != null) {
            relay.curMap.put(RelayItem.EXECUTOR_NAME, Optional.ofNullable(executorName));
        }
    }
}
