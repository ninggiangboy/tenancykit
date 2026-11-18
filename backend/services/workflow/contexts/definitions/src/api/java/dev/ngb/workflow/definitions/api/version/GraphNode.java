package dev.ngb.workflow.definitions.api.version;

import java.util.List;
import java.util.Map;

/**
 * One node of a published graph, as another context may see it: the id, the type and the settings the type gives
 * meaning to. The map is a plain document; the runtime reads the members its node type needs.
 */
public record GraphNode(String id, String type, Map<String, Object> settings) {

    public GraphNode {
        settings = settings == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(settings));
    }

    /** The text of a member, or null when it is absent or another kind of value. */
    public String text(String name) {
        return settings.get(name) instanceof String text ? text : null;
    }

    /** The document of a member, whatever its shape. */
    public Object document(String name) {
        return settings.get(name);
    }

    /** The array members of a member that are text, in order. */
    public List<String> textList(String name) {
        if (!(settings.get(name) instanceof List<?> members)) {
            return List.of();
        }
        return members.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    /** The node this one goes to when it finishes. */
    public String next() {
        return text("next");
    }
}
