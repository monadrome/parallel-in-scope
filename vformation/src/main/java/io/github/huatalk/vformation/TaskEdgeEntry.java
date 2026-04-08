package io.github.huatalk.vformation;

import com.google.common.graph.EndpointPair;

/**
 * Bundles an {@link EndpointPair} edge with its associated {@link TaskEdge} metadata.
 * Used in the concurrent queue within {@link TaskGraph.Data}.
 *
 * @author Eric Lin (linqinghua4 at gmail dot com)
 */
final class TaskEdgeEntry {

    private final EndpointPair<String> edge;
    private final TaskEdge value;

    TaskEdgeEntry(EndpointPair<String> edge, TaskEdge value) {
        this.edge = edge;
        this.value = value;
    }

    EndpointPair<String> getEdge() { return edge; }
    TaskEdge getValue() { return value; }
}
