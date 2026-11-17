package io.github.monadrome.parallelinscope;

/**
 * Kind tag for the identity of a task unit: a single task, a {@code Par.map} batch, a task-group
 * member, or a terminal combine.
 *
 * <p>A phantom tag, not a value type: nothing implements or instantiates it, and it exists only as
 * the type argument in {@code TaskId<TaskUnit>}. See {@link TaskId}.
 */
public interface TaskUnit {}
