package dev.ngb.survey.authoring.domain.flow;

import dev.ngb.survey.authoring.domain.expression.Evaluator;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks a version's pages and decides which ones a respondent reaches.
 *
 * <p>A page is skipped when its {@code skip_if} holds. After a visited page, the first jump whose condition holds
 * redirects to its target page, or stops the survey when the target is {@code end}; a page with no matching jump
 * falls through to the next one. Because a jump may only lead forward, the walk always terminates.
 */
public final class PathResolver {

    private PathResolver() {}

    /**
     * @return the page ids visited, in order
     */
    public static List<String> resolve(FlowPlan plan, FlowEnvironment environment) {
        List<String> path = new ArrayList<>();
        int index = 0;
        while (index < plan.pages().size()) {
            FlowPlan.Page page = plan.pageAt(index);
            if (page.skipIf() != null && Evaluator.truthy(Evaluator.evaluate(page.skipIf(), environment))) {
                index++;
                continue;
            }
            path.add(page.id());
            String target = firstMatch(page, environment);
            if (target == null) {
                index++;
            } else if (target.equals("end")) {
                break;
            } else {
                int next = plan.indexOfPage(target);
                if (next < 0) {
                    // A broken jump cannot move the walk; validation reports it, and falling through keeps the run finite.
                    index++;
                } else {
                    index = next;
                }
            }
        }
        return List.copyOf(path);
    }

    private static String firstMatch(FlowPlan.Page page, FlowEnvironment environment) {
        for (FlowPlan.Jump jump : page.jumps()) {
            if (jump.when() != null && Evaluator.truthy(Evaluator.evaluate(jump.when(), environment))) {
                return jump.toPage();
            }
        }
        return null;
    }
}
