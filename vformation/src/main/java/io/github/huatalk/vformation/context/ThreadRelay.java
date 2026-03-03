package io.github.huatalk.vformation.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import io.github.huatalk.vformation.cancel.CancellationToken;
import io.github.huatalk.vformation.scope.ParOptions;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-thread context relay using {@link TransmittableThreadLocal}.
 * <p>
 * Implements a two-map design ({@code parentMap} and {@code curMap}):
 * when a child thread is spawned, the parent thread's {@code curMap} becomes
 * the child thread's {@code parentMap}. This enables parent-to-child propagation of:
 * <ul>
 *   <li>{@link CancellationToken} - for cascading cancellation</li>
 *   <li>{@link ParOptions} - for context awareness</li>
 *   <li>Task name - for task graph / livelock detection</li>
 * </ul>
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public class ThreadRelay {

    public enum RelayItem {
        CANCELLATION_TOKEN,
        PARALLEL_OPTIONS,
        TASK_NAME,
        EXECUTOR_NAME
    }

    private static final TransmittableThreadLocal<ThreadRelay> THREAD_RELAY_TTL =
            TransmittableThreadLocal.withInitialAndCopier(
                    ThreadRelay::new, tr -> new ThreadRelay(tr.curMap)
            );

    public static ThreadRelay getThreadRelay() {
        return THREAD_RELAY_TTL.get();
    }

    private final Map<RelayItem, Object> parentMap = new ConcurrentHashMap<>();
    private final Map<RelayItem, Object> curMap = new ConcurrentHashMap<>();

    public ThreadRelay() {
    }

    public ThreadRelay(Map<RelayItem, Object> parentContext) {
        if (parentContext != null) {
            this.parentMap.putAll(parentContext);
        }
    }

    // ==================== CancellationToken relay ====================

    public static CancellationToken getParentCancellationToken() {
        ThreadRelay relay = THREAD_RELAY_TTL.get();
        if (relay == null) {
            return null;
        }
        Object token = relay.parentMap.get(RelayItem.CANCELLATION_TOKEN);
        return token instanceof CancellationToken ? (CancellationToken) token : null;
    }

    public static void setCurrentCancellationToken(CancellationToken token) {
        if (token == null) {
            return;
        }
        ThreadRelay relay = THREAD_RELAY_TTL.get();
        if (relay != null) {
            relay.curMap.put(RelayItem.CANCELLATION_TOKEN, token);
        }
    }

    // ==================== ParOptions relay ====================

    @SuppressWarnings("unchecked")
    public static ParOptions getParentParallelOptions() {
        ThreadRelay relay = THREAD_RELAY_TTL.get();
        if (relay == null) {
            return null;
        }
        Object wrapped = relay.parentMap.get(RelayItem.PARALLEL_OPTIONS);
        if (wrapped instanceof Optional) {
            return ((Optional<ParOptions>) wrapped).orElse(null);
        }
        return null;
    }

    public static void setCurrentParallelOptions(ParOptions options) {
        ThreadRelay relay = THREAD_RELAY_TTL.get();
        if (relay != null) {
            relay.curMap.put(RelayItem.PARALLEL_OPTIONS, Optional.ofNullable(options));
        }
    }

    // ==================== TaskName relay ====================

    @SuppressWarnings("unchecked")
    public static String getParentTaskName() {
        ThreadRelay relay = THREAD_RELAY_TTL.get();
        if (relay == null) {
            return null;
        }
        Object wrapped = relay.parentMap.get(RelayItem.TASK_NAME);
        if (wrapped instanceof Optional) {
            return ((Optional<String>) wrapped).orElse(null);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static String getCurrentTaskName() {
        ThreadRelay relay = THREAD_RELAY_TTL.get();
        if (relay == null) {
            return "NA";
        }
        Object wrapped = relay.curMap.get(RelayItem.TASK_NAME);
        if (wrapped instanceof Optional) {
            return ((Optional<String>) wrapped).orElse("NA");
        }
        return "NA";
    }

    public static void setCurrentTaskName(String taskName) {
        ThreadRelay relay = THREAD_RELAY_TTL.get();
        if (relay != null) {
            relay.curMap.put(RelayItem.TASK_NAME, Optional.ofNullable(taskName));
        }
    }

    public static void clearCurrentTaskName() {
        ThreadRelay relay = THREAD_RELAY_TTL.get();
        if (relay != null) {
            relay.curMap.remove(RelayItem.TASK_NAME);
        }
    }

    // ==================== ExecutorName relay ====================

    @SuppressWarnings("unchecked")
    public static String getCurrentExecutorName() {
        ThreadRelay relay = THREAD_RELAY_TTL.get();
        if (relay == null) {
            return "NA";
        }
        Object wrapped = relay.curMap.get(RelayItem.EXECUTOR_NAME);
        if (wrapped instanceof Optional) {
            return ((Optional<String>) wrapped).orElse("NA");
        }
        return "NA";
    }

    public static void setCurrentExecutorName(String executorName) {
        ThreadRelay relay = THREAD_RELAY_TTL.get();
        if (relay != null) {
            relay.curMap.put(RelayItem.EXECUTOR_NAME, Optional.ofNullable(executorName));
        }
    }
}
