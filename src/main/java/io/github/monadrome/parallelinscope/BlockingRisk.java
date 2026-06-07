package io.github.monadrome.parallelinscope;

/**
 * Resource shape of a supplied executor, read once at registration from the executor's own
 * structure — never from its class name and never from runtime statistics.
 *
 * <p>The value tells where the executor's saturation risk lives. It does not by itself tell whether
 * a task body can be starved of a thread while blocking on a child task: a fixed pool with an
 * unbounded queue is {@link #UNBOUNDED} here and still starvation-prone, because its worker count
 * is capped. That second fact is read separately at registration; see {@code
 * ExecutorRuntime#starvationProne()}.
 *
 * <table>
 *   <caption>Registration shape to classification</caption>
 *   <tr><th>Supplied executor</th><th>Classification</th></tr>
 *   <tr>
 *     <td>{@link java.util.concurrent.ThreadPoolExecutor} with finite queue capacity and finite
 *         {@code maximumPoolSize}</td>
 *     <td>{@link #BOUNDED_PLATFORM_POOL}</td>
 *   </tr>
 *   <tr>
 *     <td>{@link java.util.concurrent.ThreadPoolExecutor} with an unbounded queue (for example a
 *         fixed pool's default {@code LinkedBlockingQueue}) or an unbounded thread upper bound
 *         (for example a cached pool's {@code SynchronousQueue} + {@code Integer.MAX_VALUE})</td>
 *     <td>{@link #UNBOUNDED}</td>
 *   </tr>
 *   <tr>
 *     <td>Anything else — {@code ForkJoinPool}, a framework-managed pool, or an executor the user
 *         decorated before registering, which hides the physical pool</td>
 *     <td>{@link #UNKNOWN}</td>
 *   </tr>
 * </table>
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
enum BlockingRisk {
    /**
     * No readable structure. Queue purge and deadlock-risk coverage do not apply to this executor;
     * registration reports that once at the composition root.
     */
    UNKNOWN,

    /**
     * A platform pool that can only grow and buffer within known limits, so its saturation behavior
     * — reject or run inline — is inferable from its configuration.
     */
    BOUNDED_PLATFORM_POOL,

    /**
     * A platform pool whose threads or queue are unbounded: either it can always add a thread, or
     * it can absorb work without limit. The risk is resource growth rather than a hard ceiling.
     */
    UNBOUNDED,

    /**
     * Reserved for a virtual-thread-per-task executor. Never produced under the Java 8 baseline:
     * no such type can be named from Java 8 source, and probing for one by class name would be a
     * guess rather than a structural read.
     */
    VIRTUAL_THREAD_PER_TASK
}
