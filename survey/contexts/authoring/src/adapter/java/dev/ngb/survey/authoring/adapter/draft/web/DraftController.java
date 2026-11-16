package dev.ngb.survey.authoring.adapter.draft.web;

import dev.ngb.core.http.HttpHeaders;
import dev.ngb.core.security.CurrentActor;
import dev.ngb.core.security.RequiresPermission;
import dev.ngb.core.version.ConditionalUpdate;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.survey.authoring.adapter.document.web.ValidationResult;
import dev.ngb.survey.authoring.application.draft.command.SaveDraftUseCase;
import dev.ngb.survey.authoring.application.draft.command.ValidateDraftUseCase;
import dev.ngb.survey.authoring.application.draft.query.DraftQueries;
import dev.ngb.survey.authoring.application.draft.query.DraftView;
import dev.ngb.survey.authoring.domain.draft.SurveyDraft;
import dev.ngb.survey.shared.ConditionalRequests;
import dev.ngb.survey.shared.SurveyPermissions;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A survey's one editable document.
 *
 * <p>A save requires {@code If-Match} and answers the new tag, so two builders cannot overwrite each other silently.
 * The answer carries the stored document, ids the server assigned included, so a builder that never invents ids still
 * keeps them across saves.
 */
@RestController
@RequestMapping("/api/v1/surveys/{id}/draft")
class DraftController {

    private final SaveDraftUseCase saveDraft;
    private final ValidateDraftUseCase validateDraft;
    private final DraftQueries queries;
    private final CurrentActor currentActor;

    DraftController(
            SaveDraftUseCase saveDraft,
            ValidateDraftUseCase validateDraft,
            DraftQueries queries,
            CurrentActor currentActor) {
        this.saveDraft = saveDraft;
        this.validateDraft = validateDraft;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresPermission(SurveyPermissions.SURVEY_READ)
    ResponseEntity<DraftResponse> get(@PathVariable UUID id) {
        DraftView view = queries.get(id);
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(view.etag()))
                .body(new DraftResponse(view.document(), String.valueOf(view.etag()), view.updatedAt()));
    }

    @PutMapping
    @ConditionalUpdate
    @RequiresPermission(SurveyPermissions.SURVEY_MANAGE)
    ResponseEntity<DraftResponse> save(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody DraftRequest request) {
        SurveyDraft draft = saveDraft.execute(
                id, request.document(), ConditionalRequests.requireIfMatch(ifMatch), actor());
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(draft.etag()))
                .body(new DraftResponse(draft.document(), String.valueOf(draft.etag()), draft.updatedAt()));
    }

    @PostMapping("/validate")
    @RequiresPermission(SurveyPermissions.SURVEY_READ)
    ValidationResult validate(@PathVariable UUID id, @RequestBody DraftRequest request) {
        return ValidationResult.of(validateDraft.execute(id, request.document()));
    }

    private String actor() {
        return currentActor.get().auditName();
    }

    record DraftRequest(Map<String, Object> document) {}

    record DraftResponse(Map<String, Object> document, String etag, Instant updatedAt) {}
}
