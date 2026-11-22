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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
        name = "Survey drafts",
        description =
                "A survey's one editable document: read, save under If-Match, and validate.")
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

    @Operation(
            summary = "Get a survey's draft",
            description =
                    "Returns the survey's one editable document with its `ETag`, the tag the next save has to carry. An "
                    + "unknown survey answers `404`. Requires `survey:survey:read`.")
    @GetMapping
    @RequiresPermission(SurveyPermissions.SURVEY_READ)
    ResponseEntity<DraftResponse> get(@PathVariable UUID id) {
        DraftView view = queries.get(id);
        return ResponseEntity.ok()
                .eTag(ExpectedVersion.etag(view.etag()))
                .body(new DraftResponse(view.document(), String.valueOf(view.etag()), view.updatedAt()));
    }

    @Operation(
            summary = "Save a survey's draft",
            description =
                    "Replaces the draft with `document`. Requires `If-Match` with the draft's tag, so two builders cannot "
                    + "overwrite each other silently. The server gives an id to every structural node that arrived without "
                    + "one and answers the stored document, so a builder that never invents ids keeps them across saves. "
                    + "Only the shape is enforced: a draft in progress may be incomplete, and translations and jumps are "
                    + "checked on validate and publish. Requires `survey:survey:manage`.")
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

    @Operation(
            summary = "Validate a draft document",
            description =
                    "Reports every reason the `document` sent would not publish, without storing it: the schema's shape "
                    + "check, the semantic rules and the expression budgets, all in one answer so a builder fixes them in "
                    + "one round. The same check runs again at publish. Requires `survey:survey:manage`.")
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
