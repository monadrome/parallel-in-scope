package io.github.huatalk.parallelinscope.spi;

import java.util.HashMap;
import java.util.Map;

/**
 * SPI: Livelock/deadlock detection event listener.
 * <p>
 * Receives notifications when potential livelock or deadlock situations
 * are detected in the task dependency graph.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
@FunctionalInterface
public interface LivelockListener {

    /**
     * Called when livelock detection completes for a request.
     *
     * @param event the detection result
     */
    void onDetection(LivelockEvent event);

    /** Result of a livelock detection pass. */
    class LivelockEvent {
        private final boolean taskCycle;
        private final boolean selfLoop;
        private final boolean executorCycle;
        private final boolean executorSelfLoop;
        private final String taskEdges;
        private final String executorEdges;

        /**
         * Creates an immutable livelock detection event.
         *
         * @param taskCycle       whether the task graph contains a cycle
         * @param selfLoop        whether the task graph contains a self-loop
         * @param executorCycle   whether the executor graph contains a cycle
         * @param executorSelfLoop whether the executor graph contains a self-loop
         * @param taskEdges       the formatted task-edge diagnostics
         * @param executorEdges   the formatted executor-edge diagnostics
         */
        public LivelockEvent(boolean taskCycle, boolean selfLoop,
                             boolean executorCycle, boolean executorSelfLoop,
                             String taskEdges, String executorEdges) {
            this.taskCycle = taskCycle;
            this.selfLoop = selfLoop;
            this.executorCycle = executorCycle;
            this.executorSelfLoop = executorSelfLoop;
            this.taskEdges = taskEdges;
            this.executorEdges = executorEdges;
        }

        /**
         * Checks for a task-graph cycle.
         *
         * @return whether the task graph contains a cycle
         */
        public boolean hasTaskCycle() { return taskCycle; }
        /**
         * Checks for a task self-loop.
         *
         * @return whether the task graph contains a self-loop
         */
        public boolean hasSelfLoop() { return selfLoop; }
        /**
         * Checks for an executor-graph cycle.
         *
         * @return whether the executor graph contains a cycle
         */
        public boolean hasExecutorCycle() { return executorCycle; }
        /**
         * Checks for an executor self-loop.
         *
         * @return whether the executor graph contains a self-loop
         */
        public boolean hasExecutorSelfLoop() { return executorSelfLoop; }
        /**
         * Gets the task-edge diagnostics.
         *
         * @return the formatted task-edge diagnostics
         */
        public String getTaskEdges() { return taskEdges; }
        /**
         * Gets the executor-edge diagnostics.
         *
         * @return the formatted executor-edge diagnostics
         */
        public String getExecutorEdges() { return executorEdges; }

        /**
         * Checks whether any cycle or self-loop was detected.
         *
         * @return {@code true} if any issue was detected
         */
        public boolean hasAnyIssue() {
            return taskCycle || selfLoop || executorCycle || executorSelfLoop;
        }

        @Override
        public String toString() {
            return String.format("taskCycle=%s, selfLoop=%s, executorCycle=%s, executorSelfLoop=%s, " +
                            "taskEdges=[%s], executorEdges=[%s]",
                    taskCycle, selfLoop, executorCycle, executorSelfLoop,
                    taskEdges, executorEdges);
        }
    }
}
