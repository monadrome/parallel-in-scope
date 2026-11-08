package io.github.monadrome.parallelinscope;

/**
 * Kind tag for the identity of a task unit: a single task, a {@code Par.map} batch, a task-group
 * member, or a terminal combine.
 *
 * <p>A phantom tag, not a value type: nothing implements or instantiates it, and it exists only as
 * the type argument that separates {@code TaskId<TaskUnit>} from {@link TaskGroupRun}. See {@link
 * TaskId}.
 *
 * @since 0.3.0
 */
public interface TaskUnit {}
