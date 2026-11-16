package dev.ngb.survey.insight.adapter.respondent.web;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.survey.collection.api.response.RespondentListing;
import dev.ngb.survey.insight.application.respondent.query.RespondentQueries;
import dev.ngb.survey.insight.application.respondent.query.RespondentView;
import dev.ngb.survey.shared.SurveyPermissions;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
    @Listing(RespondentListing.class)
    @RequiresPermission(SurveyPermissions.RESPONSE_READ)
    Page<RespondentView> list(@PathVariable UUID id, ListQuery query) {
        return respondents.page(id, query);
    }
}
