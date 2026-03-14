package io.github.huatalk.vformation.context.graph;

import com.google.common.graph.EndpointPair;

/**
 * Bundles an {@link EndpointPair} edge with its associated {@link TaskEdge} metadata.
 * Used in the concurrent queue within {@link TaskGraph.Data}.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
public final class TaskEdgeEntry {

    private final EndpointPair<String> edge;
    private final TaskEdge value;

    public TaskEdgeEntry(EndpointPair<String> edge, TaskEdge value) {
        this.edge = edge;
        this.value = value;
    }

    public EndpointPair<String> getEdge() { return edge; }
    public TaskEdge getValue() { return value; }
}
