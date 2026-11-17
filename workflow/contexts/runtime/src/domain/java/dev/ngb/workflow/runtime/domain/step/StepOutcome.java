package dev.ngb.workflow.runtime.domain.step;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What running one node concluded: did it fail, what does the step history record, which nodes follow and what
 * variables the instance carries afterwards.
 *
 * <p>A failure is a normal outcome, not an exception: the engine records it and stops the instance. The token's next
 * ids are empty when the path ends.
 */
public record StepOutcome(
        boolean failed, String failure, Object response, List<String> nextNodeIds, Map<String, Object> variables) {

    public StepOutcome {
        nextNodeIds = nextNodeIds == null ? List.of() : List.copyOf(nextNodeIds);
        variables = variables == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public static StepOutcome succeeded(Object response, String next, Map<String, Object> variables) {
        return new StepOutcome(false, null, response, next == null ? List.of() : List.of(next), variables);
    }

    public static StepOutcome failed(String failure, Object response, Map<String, Object> variables) {
        return new StepOutcome(true, failure, response, List.of(), variables);
    }
}
