package dev.ngb.workflow.runtime.domain.step;

import dev.ngb.workflow.definitions.api.version.GraphNode;
import dev.ngb.workflow.definitions.api.version.GraphView;
import dev.ngb.workflow.runtime.domain.instance.Instance;
import dev.ngb.workflow.runtime.domain.token.Token;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Everything one node execution may read: the instance, the graph, the node, the token and the variables so far. */
public record ExecutionContext(
        Token token,
        Instance instance,
        GraphView graph,
        GraphNode node,
        Map<String, Object> variables,
        Instant now) {

    public ExecutionContext {
        variables = variables == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
}
