package dev.ngb.workflow.definitions.domain.definition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One version's graph: the node the instance starts at and every node it can reach.
 *
 * <p>The graph is data. It is parsed from the document the tenant sent, validated as a whole by
 * {@link StructureChecker}, and then executed by the runtime; nothing here calls out.
 */
public record Graph(String start, List<Node> nodes) {

    public Graph {
        nodes = List.copyOf(nodes);
    }

    /** Every node by id; a document that repeats an id keeps the last one, and the checker reports the repeat. */
    public Map<String, Node> byId() {
        Map<String, Node> byId = new LinkedHashMap<>();
        nodes.forEach(node -> byId.put(node.id(), node));
        return Map.copyOf(byId);
    }

    public Optional<Node> node(String id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst();
    }

    /** The one trigger node, when the graph has exactly one. */
    public Optional<Node> trigger() {
        return nodes.stream().filter(node -> node.type() == NodeType.TRIGGER).findFirst();
    }
}
