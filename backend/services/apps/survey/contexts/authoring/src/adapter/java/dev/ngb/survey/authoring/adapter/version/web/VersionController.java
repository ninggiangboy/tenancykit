package dev.ngb.survey.authoring.adapter.version.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.spring.query.Listing;
import dev.ngb.survey.authoring.application.version.command.PublishSurveyUseCase;
import dev.ngb.survey.authoring.application.version.query.VersionListing;
import dev.ngb.survey.authoring.application.version.query.VersionQueries;
import dev.ngb.survey.authoring.application.version.query.VersionView;
import dev.ngb.survey.authoring.domain.version.SurveyVersion;
import dev.ngb.survey.shared.ConditionalRequests;
import dev.ngb.survey.shared.SurveyPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishing the draft and reading the immutable history. A publish compares the tag of the draft it is about to
 * freeze, so an edit that landed in between is refused with {@code 412} rather than published under an older read.
 */
@Tag(
        name = "Survey versions",
        description =
                "Publishing the draft as an immutable version and reading the version history.")
@RestController
@RequestMapping("/api/v1/surveys/{id}")
class VersionController {

    private final PublishSurveyUseCase publish;
    private final VersionQueries queries;
    private final CurrentActor currentActor;

    VersionController(PublishSurveyUseCase publish, VersionQueries queries, CurrentActor currentActor) {
        this.publish = publish;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    @Operation(
            summary = "Publish the draft as a new version",
            description =
                    "Freezes the draft as the next immutable version and makes it the survey's current one. Requires "
                    + "`If-Match` with the draft's tag; an edit that landed in between is refused with `412` rather than "
                    + "published under an older read. A document that does not validate answers `422` with every problem. A "
                    + "question whose type changed is given a new id. Answers `201`. Requires `survey:survey:manage`.")
    @PostMapping("/publish")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(SurveyPermissions.SURVEY_MANAGE)
    VersionResponse publish(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return VersionResponse.of(publish.execute(
                id, ConditionalRequests.requireIfMatch(ifMatch), currentActor.get().auditName()));
    }

    @Operation(
            summary = "List a survey's versions",
            description =
                    "Pages through the survey's published versions, newest first by default, with the list-query "
                    + "contract's filtering, sorting and paging. The documents are not listed; read one version for its "
                    + "questions. Requires `survey:survey:read`.")
    @GetMapping("/versions")
    @Listing(VersionListing.class)
    @RequiresPermission(SurveyPermissions.SURVEY_READ)
    Page<VersionSummary> list(@PathVariable UUID id, ListQuery query) {
        return queries.list(id, query).map(VersionSummary::of);
    }

    @Operation(
            summary = "Get a published version",
            description =
                    "Returns published version `no` of the survey with its document. A published version never changes. "
                    + "An unknown survey or version answers `404`. Requires `survey:survey:read`.")
    @GetMapping("/versions/{no}")
    @RequiresPermission(SurveyPermissions.SURVEY_READ)
    VersionResponse get(@PathVariable UUID id, @PathVariable int no) {
        return VersionResponse.of(queries.get(id, no));
    }

    record VersionSummary(UUID id, int versionNo, Instant publishedAt) {

        static VersionSummary of(VersionView view) {
            return new VersionSummary(view.id(), view.versionNo(), view.publishedAt());
        }
    }

    record VersionResponse(UUID id, int versionNo, Instant publishedAt, Map<String, Object> document) {

        static VersionResponse of(SurveyVersion version) {
            return new VersionResponse(
                    version.id(), version.versionNo(), version.publishedAt(), version.document());
        }

        static VersionResponse of(VersionView view) {
            return new VersionResponse(view.id(), view.versionNo(), view.publishedAt(), view.document());
        }
    }
}
