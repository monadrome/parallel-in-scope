package io.github.monadrome.parallelinscope;

/**
 * Kind tag for the identity of one task-group run.
 *
 * <p>A phantom tag, not a value type: nothing implements or instantiates it, and it exists only as
 * the type argument that separates {@code TaskId<TaskGroupRun>} from {@link TaskUnit}. A group run
 * is not a task unit — it has no body of its own — so its identity is a separate space that must
 * not be filed among unit ids. See {@link TaskId}.
 *
 * @since 0.3.0
 */
public interface TaskGroupRun {}
