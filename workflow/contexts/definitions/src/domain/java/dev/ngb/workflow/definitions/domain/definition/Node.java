package dev.ngb.workflow.definitions.domain.definition;

import dev.ngb.workflow.definitions.domain.expression.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node of a graph: its id, its type, and every other member the type gives meaning to.
 *
 * <p>The settings are kept as the tenant wrote them, so a node type whose behaviour arrives later (an http call, a
 * wait) needs no schema change here. The checker is the one place that says what each type must carry.
 */
public record Node(String id, NodeType type, Map<String, Object> settings) {

    public Node {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (settings != null) {
            settings.forEach((key, value) -> copy.put(key, Json.copy(value)));
        }
        settings = Collections.unmodifiableMap(copy);
    }

    /** The text of a member, or null when it is absent or another kind of value. */
    public String text(String name) {
        return Json.text(settings.get(name));
    }

    /** The document of a member, whatever its shape. */
    public Object document(String name) {
        return settings.get(name);
    }

    /** The object of a member; an absent or mistyped one is an empty map. */
    public Map<String, Object> object(String name) {
        Map<String, Object> members = Json.object(settings.get(name));
        return members == null ? Map.of() : members;
    }

    /** The array of a member; an absent or mistyped one is an empty list. */
    public List<Object> list(String name) {
        List<Object> members = Json.array(settings.get(name));
        return members == null ? List.of() : members;
    }

    /** The text members of an array, in order; a member that is not text is skipped. */
    public List<String> textList(String name) {
        List<String> values = new ArrayList<>();
        for (Object member : list(name)) {
            String text = Json.text(member);
            if (text != null) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    /** The node the token goes to when this node finishes. */
    public String next() {
        return text("next");
    }

    /** The node the token goes to when this node fails after its retries. */
    public String onError() {
        return text("on_error");
    }

    /** The http node that compensates this node when its instance is rolled back. */
    public String compensate() {
        return text("compensate");
    }
}
