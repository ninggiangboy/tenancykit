package dev.ngb.survey.authoring.adapter.survey.web;

import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.survey.authoring.application.survey.command.CloseSurveyUseCase;
import dev.ngb.survey.authoring.application.survey.command.CreateSurveyUseCase;
import dev.ngb.survey.authoring.application.survey.command.ReopenSurveyUseCase;
import dev.ngb.survey.authoring.application.survey.query.SurveyQueries;
import dev.ngb.survey.authoring.application.survey.query.SurveyView;
import dev.ngb.survey.authoring.domain.survey.Survey;
import dev.ngb.survey.authoring.domain.survey.SurveyStatus;
import dev.ngb.survey.shared.SurveyPermissions;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A tenant's surveys and their lifecycle. A missing survey is a {@code 404} here exactly as it is in every other
 * tenant, because row-level security hides the row before this class sees it.
 */
@RestController
@RequestMapping("/api/v1/surveys")
class SurveyController {

    private final CreateSurveyUseCase create;
    private final CloseSurveyUseCase close;
    private final ReopenSurveyUseCase reopen;
    private final SurveyQueries queries;

    SurveyController(
            CreateSurveyUseCase create,
            CloseSurveyUseCase close,
            ReopenSurveyUseCase reopen,
            SurveyQueries queries) {
        this.create = create;
        this.close = close;
        this.reopen = reopen;
        this.queries = queries;
    }

    @GetMapping
    @RequiresPermission(SurveyPermissions.SURVEY_READ)
    List<SurveyResponse> list() {
        return queries.list().stream().map(SurveyResponse::of).toList();
    }

    @GetMapping("/{id}")
    @RequiresPermission(SurveyPermissions.SURVEY_READ)
    ResponseEntity<SurveyResponse> get(@PathVariable UUID id) {
        SurveyView view = queries.get(id);
        return ResponseEntity.ok().eTag(ExpectedVersion.etag(view.version())).body(SurveyResponse.of(view));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(SurveyPermissions.SURVEY_MANAGE)
    SurveyResponse create(@RequestBody SurveyRequest request) {
        return SurveyResponse.of(create.execute(new CreateSurveyUseCase.Command(request.name())));
    }

    @PostMapping("/{id}/close")
    @RequiresPermission(SurveyPermissions.SURVEY_MANAGE)
    ResponseEntity<SurveyResponse> close(@PathVariable UUID id) {
        Survey survey = close.execute(id);
        return answered(queries.get(id), survey.version());
    }

    @PostMapping("/{id}/reopen")
    @RequiresPermission(SurveyPermissions.SURVEY_MANAGE)
    ResponseEntity<SurveyResponse> reopen(@PathVariable UUID id) {
        Survey survey = reopen.execute(id);
        return answered(queries.get(id), survey.version());
    }

    /** The read view carries the version number the write cannot know without a second query; the tag stays the write's. */
    private static ResponseEntity<SurveyResponse> answered(SurveyView view, long version) {
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(version))
                .body(SurveyResponse.of(view));
    }

    record SurveyRequest(String name) {}

    record SurveyResponse(
            UUID id, String name, SurveyStatus status, UUID currentVersionId, Integer currentVersionNo, long version) {

        static SurveyResponse of(Survey survey) {
            return new SurveyResponse(
                    survey.id(), survey.name(), survey.status(), survey.currentVersionId(), null, survey.version());
        }

        static SurveyResponse of(SurveyView view) {
            return new SurveyResponse(
                    view.id(), view.name(), view.status(), view.currentVersionId(), view.currentVersionNo(),
                    view.version());
        }
    }
}
