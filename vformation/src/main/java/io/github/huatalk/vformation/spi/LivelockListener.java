package io.github.huatalk.vformation.spi;

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

    /**
     * Livelock detection event
     */
    class LivelockEvent {
        private final boolean taskCycle;
        private final boolean selfLoop;
        private final boolean executorCycle;
        private final boolean executorSelfLoop;
        private final String taskEdges;
        private final String executorEdges;

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

        public boolean hasTaskCycle() { return taskCycle; }
        public boolean hasSelfLoop() { return selfLoop; }
        public boolean hasExecutorCycle() { return executorCycle; }
        public boolean hasExecutorSelfLoop() { return executorSelfLoop; }
        public String getTaskEdges() { return taskEdges; }
        public String getExecutorEdges() { return executorEdges; }

        /** Returns true if any potential issue was detected */
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
