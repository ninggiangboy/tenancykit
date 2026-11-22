package dev.ngb.survey.collection.adapter.response.web;

import dev.ngb.core.error.NotFoundException;
import dev.ngb.survey.collection.application.response.command.SaveDraftUseCase;
import dev.ngb.survey.collection.application.response.command.SubmitResponseUseCase;
import dev.ngb.survey.collection.application.response.exception.AlreadySubmittedException;
import dev.ngb.survey.collection.application.response.exception.ResponseRefusedException;
import dev.ngb.survey.collection.application.response.exception.SubmitInvalidException;
import dev.ngb.survey.collection.application.response.query.ResponseQueries;
import dev.ngb.survey.collection.application.response.query.ResponseView;
import dev.ngb.survey.collection.application.upload.command.PresignUploadUseCase;
import dev.ngb.survey.collection.application.link.port.LinkRepository;
import dev.ngb.survey.collection.application.visit.service.VisitLookup;
import dev.ngb.survey.collection.domain.response.Response;
import dev.ngb.survey.collection.domain.visit.Visit;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import dev.ngb.survey.collection.domain.visit.VisitToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import dev.ngb.core.security.PublicEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The respondent's own surface: saving a draft, resuming it, signing an upload and submitting.
 *
 * <p>The visit token is the credential for all four. The response the browser reads is its own draft only; a completed
 * response is a conflict rather than a document, because a browser that reloaded a submitted page should be told why
 * nothing changed.
 *
 * <p>A validation refusal answers {@code 422} with the field and code of every problem, never the value: the browser
 * sent it, and a bot learns nothing it did not already send.
 */
@Tag(
        name = "Respondent answers",
        description =
                "The respondent's own surface, authorised by the visit token: save and resume a draft, sign an upload "
                + "and submit.")
@RestController
@RequestMapping("/p/v/{visitToken}")
class AnswerController {

    private final VisitLookup visitLookup;
    private final ResponseQueries responses;
    private final SaveDraftUseCase saveDraft;
    private final SubmitResponseUseCase submit;
    private final PresignUploadUseCase presign;
    private final LinkRepository links;

    AnswerController(
            VisitLookup visitLookup,
            ResponseQueries responses,
            SaveDraftUseCase saveDraft,
            SubmitResponseUseCase submit,
            PresignUploadUseCase presign,
            LinkRepository links) {
        this.visitLookup = visitLookup;
        this.responses = responses;
        this.saveDraft = saveDraft;
        this.submit = submit;
        this.presign = presign;
        this.links = links;
    }

    @Operation(
            summary = "Save a respondent's draft",
            description =
                    "Saves the respondent's partial `answers`, creating the in-progress response on the first save and "
                    + "moving the visit to `STARTED`. The response records the version the visit opened, so a resume after "
                    + "a newer publish keeps its questions. A visit that already completed answers `409`. Public: the visit "
                    + "token in the path is the credential, and no suite token is needed.")
    @PublicEndpoint
    @PutMapping("/draft")
    ResponseEntity<DraftResponse> saveDraft(
            @PathVariable String visitToken, @RequestBody AnswersRequest request) {
        Resolved resolved = resolve(visitToken);
        ResponseView draft = toView(saveDraft.execute(resolved.visit(), resolved.link(), request.answers()));
        return ResponseEntity.ok(new DraftResponse(draft.status(), draft.answers()));
    }

    @Operation(
            summary = "Resume a respondent's draft",
            description =
                    "Returns the answers the visit saved so far, so a browser can resume; before the first save the "
                    + "answers are empty. It reads only this visit's own draft; a completed response answers `409` rather "
                    + "than a document. Public: the visit token in the path is the credential, and no suite token is "
                    + "needed.")
    @PublicEndpoint
    @GetMapping("/draft")
    ResponseEntity<DraftResponse> readDraft(@PathVariable String visitToken) {
        Resolved resolved = resolve(visitToken);
        Optional<ResponseView> draft = responses.ofVisit(resolved.visit().id());
        if (draft.isEmpty()) {
            return ResponseEntity.ok(new DraftResponse("IN_PROGRESS", Map.of()));
        }
        ResponseView view = draft.get();
        if ("COMPLETED".equals(view.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already_completed");
        }
        return ResponseEntity.ok(new DraftResponse(view.status(), view.answers()));
    }

    @Operation(
            summary = "Request a file upload",
            description =
                    "Asks to upload a file for question `questionId`, declaring `filename`, `size` and `mime`, and "
                    + "answers `201` with a signed upload (`uploadId`, `url` and the `headers` to send) the browser then "
                    + "uses. A file the question does not accept, or one over the size limit, is refused. Public: the visit "
                    + "token in the path is the credential, and no suite token is needed.")
    @PublicEndpoint
    @PostMapping("/uploads")
    ResponseEntity<PresignUploadUseCase.Signed> upload(
            @PathVariable String visitToken, @RequestBody UploadRequest request) {
        Resolved resolved = resolve(visitToken);
        Map<String, Object> document = visitLookup.versionOf(resolved.visit()).document();
        PresignUploadUseCase.Signed signed = presign.execute(
                resolved.visit(), document, new PresignUploadUseCase.Request(
                        request.questionId(), request.filename(), request.size(), request.mime()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new PresignUploadUseCase.Signed(signed.uploadId(), signed.url(), signed.headers()));
    }

    @Operation(
            summary = "Submit a respondent's answers",
            description =
                    "Completes the response: validates `answers`, recomputes the variables and score, and enforces the "
                    + "survey's window, cap, single use and idempotency in one transaction. Without an `Idempotency-Key` "
                    + "header it answers `428`, and a repeated key returns the first result. A validation failure answers "
                    + "`422` with the field and code of every problem, never the value. A completed visit answers `409`. "
                    + "Public: the visit token in the path is the credential, and no suite token is needed.")
    @PublicEndpoint
    @PostMapping("/submit")
    ResponseEntity<SubmitResult> submit(
            @PathVariable String visitToken,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AnswersRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "an Idempotency-Key is required");
        }
        Resolved resolved = resolve(visitToken);
        try {
            SubmitResponseUseCase.Submitted submitted = submit.execute(
                    resolved.visit(),
                    resolved.link(),
                    new SubmitResponseUseCase.Submission(request.answers(), request.locale(), idempotencyKey));
            return ResponseEntity.ok(new SubmitResult(
                    submitted.responseId(),
                    submitted.replayed() ? null : submitted.score()));
        } catch (SubmitInvalidException invalid) {
            return ResponseEntity.unprocessableContent()
                    .body(new SubmitResult(null, null, invalid.problems().stream()
                            .map(problem -> new Problem(problem.field(), problem.code()))
                            .toList()));
        } catch (AlreadySubmittedException already) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "already_completed", already);
        } catch (ResponseRefusedException refused) {
            throw new ResponseStatusException(refusedStatus(refused.reason()), refused.reason().name().toLowerCase(java.util.Locale.ROOT), refused);
        }
    }

    private static HttpStatus refusedStatus(ResponseRefusedException.Reason reason) {
        return switch (reason) {
            case CAP_REACHED, WINDOW_CLOSED -> HttpStatus.CONFLICT;
            case VERSION_GONE -> HttpStatus.GONE;
        };
    }

    /** Resolves the visit and the link it came from, or answers the same not-found an unknown token answers. */
    private Resolved resolve(String visitToken) {
        byte[] hash = VisitToken.hashOfPresented(visitToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No link exists here."));
        Visit visit = visitLookup.byToken(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No link exists here."));
        SurveyLink link = links.findById(visit.linkId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No link exists here."));
        return new Resolved(visit, link);
    }

    private ResponseView toView(Response response) {
        return new ResponseView(
                response.id(), response.surveyId(), response.visitId(), response.versionId(), response.status().name(),
                response.locale(), response.answers(), response.vars(), response.score(),
                response.respondentIdentity(), response.externalRef(), response.source(), response.startedAt(),
                response.submittedAt());
    }

    /** The visit and the link its token belongs to. */
    private record Resolved(Visit visit, SurveyLink link) {}

    /** A body of answers, with the locale a respondent read. */
    record AnswersRequest(Map<String, Object> answers, String locale) {}

    /** What a browser reads when it saves or resumes its draft. */
    record DraftResponse(String status, Map<String, Object> answers) {}

    /** What a browser requests: the question, the declared name, size and type. */
    record UploadRequest(UUID questionId, String filename, long size, String mime) {}

    /** What a submit answers: the response id and, unless the version withholds it, the score. */
    record SubmitResult(UUID responseId, java.math.BigDecimal score, List<Problem> problems) {

        SubmitResult(UUID responseId, java.math.BigDecimal score) {
            this(responseId, score, List.of());
        }
    }

    /** One reason a submission is refused, by field and code. */
    record Problem(String field, String code) {}
}
