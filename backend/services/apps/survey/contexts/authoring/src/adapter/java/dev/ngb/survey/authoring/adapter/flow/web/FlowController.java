package dev.ngb.survey.authoring.adapter.flow.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.survey.authoring.api.flow.FlowResult;
import dev.ngb.survey.authoring.application.flow.query.PreviewQueries;
import dev.ngb.survey.authoring.application.flow.query.PreviewView;
import dev.ngb.survey.authoring.application.flow.query.SimulateQueries;
import dev.ngb.survey.shared.SurveyPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Survey preview and simulation",
        description =
                "Try a draft's logic without publishing: preview a locale, or simulate answers through the draft or a "
                + "version.")
@RestController
@RequestMapping("/api/v1/surveys/{id}")
class FlowController {

    private final PreviewQueries previews;
    private final SimulateQueries simulations;

    FlowController(PreviewQueries previews, SimulateQueries simulations) {
        this.previews = previews;
        this.simulations = simulations;
    }

    @Operation(
            summary = "Preview the draft in one locale",
            description =
                    "Returns the draft's pages in presentation order as `locale` renders them, with every question, its "
                    + "type and every option label. It is presentation only: nothing is skipped, hidden or substituted, and "
                    + "piping stays as its stored placeholders. Nothing is stored. Requires `survey:survey:read`.")
    @PostMapping("/draft/preview")
    @RequiresPermission(SurveyPermissions.SURVEY_READ)
    PreviewView preview(@PathVariable UUID id, @RequestParam(required = false) String locale) {
        return previews.draft(id, locale);
    }

    @Operation(
            summary = "Simulate answers through the draft",
            description =
                    "Runs `answers` through the draft with the same evaluator a respondent's submit uses and reports the "
                    + "path, variables and score. A draft that does not compile answers `errors` naming every offending "
                    + "pointer. Nothing is stored. Requires `survey:survey:read`.")
    @PostMapping("/draft/simulate")
    @RequiresPermission(SurveyPermissions.SURVEY_READ)
    FlowResult simulateDraft(@PathVariable UUID id, @RequestBody SimulateRequest request) {
        return simulations.draft(id, request.answers() == null ? Map.of() : request.answers());
    }

    @Operation(
            summary = "Simulate answers through a published version",
            description =
                    "Runs `answers` through published version `no` of the survey with the evaluator a respondent's submit "
                    + "uses. Nothing is stored. An unknown survey or version answers `404`. Requires `survey:survey:read`.")
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
