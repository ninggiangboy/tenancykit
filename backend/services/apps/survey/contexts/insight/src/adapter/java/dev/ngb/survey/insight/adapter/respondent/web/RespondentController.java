package dev.ngb.survey.insight.adapter.respondent.web;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.Caller;
import dev.ngb.core.security.Callers;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.survey.collection.api.response.RespondentListing;
import dev.ngb.survey.insight.application.respondent.query.RespondentQueries;
import dev.ngb.survey.insight.application.respondent.query.RespondentView;
import dev.ngb.survey.shared.SurveyPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The respondents list a Tenant imports into a mailroom group for a resend. */
@Tag(
        name = "Survey respondents",
        description =
                "The respondents of a survey, for importing into a mailroom group for a reminder.")
@RestController
@RequestMapping("/surveys/{id}/respondents")
@Callers({Caller.TENANT_USER, Caller.TENANT_SYSTEM})
class RespondentController {

    private final RespondentQueries respondents;

    RespondentController(RespondentQueries respondents) {
        this.respondents = respondents;
    }

    @Operation(
            summary = "List a survey's respondents",
            description =
                    "Pages through the people who responded to the survey, the list a tenant imports into a mailroom "
                    + "group to send a reminder, with the list-query contract's filtering, sorting and paging. Requires "
                    + "`survey:response:read`.")
    @GetMapping
    @Listing(RespondentListing.class)
    @RequiresPermission(SurveyPermissions.RESPONSE_READ)
    Page<RespondentView> list(@PathVariable UUID id, ListQuery query) {
        return respondents.page(id, query);
    }
}
