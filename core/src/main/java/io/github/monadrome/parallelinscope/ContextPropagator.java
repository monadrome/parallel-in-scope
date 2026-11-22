package io.github.monadrome.parallelinscope;

/**
 * Carries one application context mechanism from the thread that admits a task to the thread that
 * runs it, and puts the running thread back afterwards.
 *
 * <p>A propagator has three stages, each a single method, used in a fixed order:
 *
 * <ol>
 *   <li>{@link #capture()} runs on the admitting thread and returns a {@link Snapshot} of the
 *       context that thread sees.
 *   <li>{@link Snapshot#install()} runs on the thread about to run the task code, makes the
 *       captured context current there, and returns a {@link Scope} holding whatever it replaced.
 *   <li>{@link Scope#close()} runs on that same thread after the task code has exited and restores
 *       what {@code install()} replaced.
 * </ol>
 *
 * <p>Written as lambdas, the three stages nest, and each closure holds exactly the state its stage
 * needs:
 *
 * <pre>{@code
 * ContextPropagator requestId = () -> {
 *     String captured = REQUEST_ID.get();            // admitting thread
 *     return () -> {
 *         String previous = REQUEST_ID.get();        // running thread, before the task code
 *         REQUEST_ID.set(captured);
 *         return () -> REQUEST_ID.set(previous);     // same thread, after the task code
 *     };
 * };
 * }</pre>
 *
 * <p>Register propagators with {@link ParRuntime.Builder#propagate(ContextPropagator)}. The runtime
 * then applies them to every task it runs through its entries: single submissions, batch elements
 * including those that wait for a parallelism slot, task-group members, and terminal combines.
 * Alibaba TransmittableThreadLocal values are always carried and need no propagator.
 *
 * <p><b>Order.</b> Propagators install in registration order after the built-in
 * TransmittableThreadLocal context, and their scopes close in the reverse order, so a later
 * propagator may read what an earlier one installed.
 *
 * <p><b>Failures.</b> The stages follow try-with-resources:
 *
 * <ul>
 *   <li>If {@code capture()} throws, the admitting call throws that failure and no task code of the
 *       unit being admitted runs.
 *   <li>If {@code install()} throws, the scopes already opened for that task close in reverse
 *       order, the task code does not run, and the task fails with the install failure.
 *   <li>If the task code throws, its failure remains the task's failure and any {@code close()}
 *       failure is added to it as suppressed.
 *   <li>If the task code succeeds and {@code close()} throws, the task fails with the close failure.
 *       Every scope is still closed; later close failures are added as suppressed.
 * </ul>
 *
 * <p>A stage that returns {@code null} fails with {@link NullPointerException} under the same
 * rules.
 *
 * <p><b>What implementations must not do.</b> Stages run on threads the library borrows, between
 * its own bookkeeping and the task code. They must return promptly without blocking, must not
 * change the thread's interrupt status, and must not call {@link Checkpoints}. They receive no task
 * identity, value, or failure. One propagator instance is used concurrently by many tasks and must
 * not keep per-task mutable state outside the snapshot and scope it returns.
 *
 * <p><b>What is not covered.</b> Only the task code's synchronous execution on its own thread sees
 * the installed context. Work the task code hands to another thread or leaves to run after it
 * returns does not. Members adopted from an external future run no task code and install nothing.
 * {@link ContextExecutors} carry TransmittableThreadLocal values only.
 */
@FunctionalInterface
public interface ContextPropagator {

    /**
     * Captures the calling thread's current context.
     *
     * <p>Called once for each task being prepared, on the thread that admits it. A task that admits
     * further work from inside its task code is the admitting thread for that work, so nested tasks
     * capture whatever is installed at that moment.
     *
     * @return the captured context; never null
     */
    Snapshot capture();

    /**
     * One mechanism's context as the admitting thread saw it.
     *
     * <p>A snapshot has no thread affinity and needs no release: a task that is cancelled or rejected
     * before it runs drops its snapshot without installing it. The library installs the snapshot of
     * one task at most once. A snapshot does not copy the captured value, so a mutable value is
     * shared between the admitting thread and the task unless {@link ContextPropagator#capture()}
     * copies it.
     */
    @FunctionalInterface
    interface Snapshot {

        /**
         * Makes the captured context current on the calling thread.
         *
         * @return a scope that restores what this call replaced; never null
         */
        Scope install();
    }

    /**
     * The right to undo one {@link Snapshot#install()}.
     *
     * <p>The library closes a scope exactly once, on the thread that installed it, after the task
     * code has exited and before the task's result is published. Scopes opened for one task close in
     * the reverse of their install order.
     */
    @FunctionalInterface
    interface Scope extends AutoCloseable {

        /** Restores the context that the matching {@link Snapshot#install()} replaced. */
        @Override
        void close();
    }
}
