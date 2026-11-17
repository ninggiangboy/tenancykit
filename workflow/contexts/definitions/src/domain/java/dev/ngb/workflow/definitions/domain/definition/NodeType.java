package dev.ngb.workflow.definitions.domain.definition;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The node types the graph language defines; a document naming another one is refused. */
public enum NodeType {
    TRIGGER("trigger"),
    SET("set"),
    HTTP("http"),
    BRANCH("branch"),
    DELAY("delay"),
    WAIT("wait"),
    TASK("task"),
    PARALLEL("parallel"),
    JOIN("join"),
    FOREACH("foreach"),
    SUBFLOW("subflow"),
    END("end"),
    FAIL("fail");

    private static final Map<String, NodeType> BY_WIRE_NAME =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(NodeType::wireName, Function.identity()));

    private final String wireName;

    NodeType(String wireName) {
        this.wireName = wireName;
    }

    /** The name the contract spells, which is what a document carries. */
    public String wireName() {
        return wireName;
    }

    /** The type a document names, or null when the language does not define it. */
    public static NodeType named(String wireName) {
        return BY_WIRE_NAME.get(wireName);
    }
}
