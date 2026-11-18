package dev.ngb.survey.authoring.domain.flow;

import dev.ngb.survey.authoring.domain.expression.Evaluator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides every question's visibility: it is on the path when its page is visited, and visible when its
 * {@code visible_if} also holds. An absent {@code visible_if} is true.
 *
 * <p>The map covers every question the version carries, not only the visited ones, so a client and the server can
 * compare a full answer rather than inferring what is missing.
 */
public final class Visibility {

    private Visibility() {}

    public static Map<String, Boolean> of(FlowPlan plan, List<String> path, FlowEnvironment environment) {
        Map<String, Boolean> visited = new LinkedHashMap<>();
        for (String pageId : path) {
            visited.put(pageId, Boolean.TRUE);
        }
        Map<String, Boolean> visible = new LinkedHashMap<>();
        for (FlowPlan.Page page : plan.pages()) {
            boolean onPath = visited.containsKey(page.id());
            for (FlowPlan.Question question : page.questions()) {
                if (question.id() == null) {
                    continue;
                }
                boolean holds = question.visibleIf() == null
                        || Evaluator.truthy(Evaluator.evaluate(question.visibleIf(), environment));
                visible.putIfAbsent(question.id(), onPath && holds);
            }
        }
        return visible;
    }
}
