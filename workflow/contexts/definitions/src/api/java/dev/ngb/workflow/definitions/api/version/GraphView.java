package dev.ngb.workflow.definitions.api.version;

import java.util.List;
import java.util.Optional;

/** One published graph as the runtime walks it. */
public record GraphView(String start, List<GraphNode> nodes) {

    public GraphView {
        nodes = List.copyOf(nodes);
    }

    public Optional<GraphNode> node(String id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst();
    }
}
