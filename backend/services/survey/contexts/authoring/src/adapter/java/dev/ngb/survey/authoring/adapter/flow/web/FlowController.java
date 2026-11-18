package dev.ngb.survey.authoring.adapter.flow.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.survey.authoring.api.flow.FlowResult;
import dev.ngb.survey.authoring.application.flow.query.PreviewQueries;
import dev.ngb.survey.authoring.application.flow.query.PreviewView;
import dev.ngb.survey.authoring.application.flow.query.SimulateQueries;
import dev.ngb.survey.shared.SurveyPermissions;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two ways a builder tries its logic: a preview of the draft as one locale renders it, and a simulation of a set
 * of answers through the same evaluator a respondent's submit runs.
 *
 * <p>Both read; neither stores anything. A draft that does not compile answers a {@link FlowResult} whose
 * {@code errors} name every offending pointer, so a builder sees its mistakes without publishing first.
 */
@RestController
@RequestMapping("/api/v1/surveys/{id}")
class FlowController {

    private final PreviewQueries previews;
    private final SimulateQueries simulations;

    FlowController(PreviewQueries previews, SimulateQueries simulations) {
        this.previews = previews;
        this.simulations = simulations;
    }

    @PostMapping("/draft/preview")
    @RequiresPermission(SurveyPermissions.SURVEY_READ)
    PreviewView preview(@PathVariable UUID id, @RequestParam(required = false) String locale) {
        return previews.draft(id, locale);
    }

    @PostMapping("/draft/simulate")
    @RequiresPermission(SurveyPermissions.SURVEY_READ)
    FlowResult simulateDraft(@PathVariable UUID id, @RequestBody SimulateRequest request) {
        return simulations.draft(id, request.answers() == null ? Map.of() : request.answers());
    }

    @PostMapping("/versions/{no}/simulate")
    @RequiresPermission(SurveyPermissions.SURVEY_READ)
    FlowResult simulateVersion(
            @PathVariable UUID id, @PathVariable int no, @RequestBody SimulateRequest request) {
        return simulations.version(id, no, request.answers() == null ? Map.of() : request.answers());
    }

    /**
     * A simulation's body. The locale rides along because a client sends it, but only piping uses it and only a
     * preview renders piping, so the evaluator ignores it.
     */
    record SimulateRequest(Map<String, Object> answers, String locale) {}
}
