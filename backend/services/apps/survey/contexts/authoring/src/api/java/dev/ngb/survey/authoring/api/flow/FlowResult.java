package dev.ngb.survey.authoring.api.flow;

import dev.ngb.survey.authoring.api.document.ValidationError;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one run of a version's logic produces: the pages visited, every question's visibility, the computed
 * variables, the score and any structural error the version failed at.
 *
 * <p>The same answer serves a simulate call and the server-side check a submit runs, so a builder sees exactly what a
 * respondent's browser decides and a collected response cannot disagree with the preview.
 *
 * @param path the page ids visited, in order
 * @param visible every question of the version, mapped to whether it is asked
 * @param vars every computed variable's value, as the JSON it becomes
 * @param score the quiz score, or null when the version disables quiz scoring
 * @param errors every reason the version could not be evaluated, by pointer; a non-empty list means nothing ran
 */
public record FlowResult(
        List<String> path,
        Map<String, Boolean> visible,
        Map<String, Object> vars,
        BigDecimal score,
        List<ValidationError> errors) {

    public FlowResult {
        path = List.copyOf(path);
        visible = Collections.unmodifiableMap(new LinkedHashMap<>(visible));
        // A computed variable that evaluated to null is a present key with a null value, so this map cannot be
        // Map.copyOf: that rejects nulls and would turn a legitimate variable into an error.
        vars = Collections.unmodifiableMap(new LinkedHashMap<>(vars));
        errors = List.copyOf(errors);
    }

    /** True when the version evaluated: no structural error held it back. */
    public boolean evaluated() {
        return errors.isEmpty();
    }
}
