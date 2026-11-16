package dev.ngb.survey.insight.adapter.respondent.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.survey.insight.application.respondent.query.RespondentView;
import dev.ngb.survey.insight.application.respondent.query.RespondentQueries;
import dev.ngb.survey.shared.SurveyPermissions;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The respondents list a Tenant imports into a mailroom group for a resend. */
@RestController
@RequestMapping("/api/v1/surveys/{id}/respondents")
class RespondentController {

    private final RespondentQueries respondents;

    RespondentController(RespondentQueries respondents) {
        this.respondents = respondents;
    }

    @GetMapping
    @RequiresPermission(SurveyPermissions.RESPONSE_READ)
    Page list(
            @PathVariable UUID id,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        RespondentView.Page page = respondents.page(
                id,
                RespondentView.State.named(state),
                source,
                Math.max(0, offset),
                Math.min(Math.max(1, limit), 500));
        return new Page(List.copyOf(page.rows()), page.total());
    }

    /** A page of respondents and the total. */
    record Page(List<RespondentView> respondents, long total) {}
}
