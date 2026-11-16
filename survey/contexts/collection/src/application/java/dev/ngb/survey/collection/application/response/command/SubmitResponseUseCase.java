package dev.ngb.survey.collection.application.response.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.authoring.api.flow.FlowResult;
import dev.ngb.survey.authoring.api.flow.SubmitProblem;
import dev.ngb.survey.authoring.api.flow.SurveyFlow;
import dev.ngb.survey.authoring.api.version.PublishedVersionReader;
import dev.ngb.survey.collection.application.response.exception.AlreadySubmittedException;
import dev.ngb.survey.collection.application.response.exception.ResponseRefusedException;
import dev.ngb.survey.collection.application.response.exception.SubmitInvalidException;
import dev.ngb.survey.collection.application.response.port.ResponseRepository;
import dev.ngb.survey.collection.application.response.port.TypedAnswer;
import dev.ngb.survey.authoring.api.flow.AnswerIndexer;
import dev.ngb.survey.collection.application.upload.port.Upload;
import dev.ngb.survey.collection.application.upload.port.UploadRepository;
import dev.ngb.survey.collection.application.upload.service.UploadVerifier;
import dev.ngb.survey.collection.application.visit.port.VisitRepository;
import dev.ngb.survey.collection.domain.link.SurveyLink;
import dev.ngb.survey.collection.domain.response.Response;
import dev.ngb.survey.collection.domain.visit.Visit;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Completes a respondent's response: validates the answers, recomputes the variables and the score, and enforces the
 * window, the cap, single use and idempotency in one transaction.
 *
 * <p>Three facts are the database's decision rather than a check-then-write, because a burst of concurrent submits is
 * exactly what a public endpoint sees: the counter's conditional update, the partial unique index on a completed
 * personal link, and the idempotency key's unique constraint. A repeated key returns the first result, so a client that
 * retried a request it never saw the answer to does not create a second response.
 *
 * <p>A client's own {@code vars} or {@code score} is never read: the server recomputes both and returns its own score.
 */
public class SubmitResponseUseCase {

    /** What a submission produced: the response, the score the server computed and whether it was a replay. */
    public record Submitted(UUID responseId, java.math.BigDecimal score, boolean replayed) {}

    private final UnitOfWork unitOfWork;
    private final VisitRepository visits;
    private final ResponseRepository responses;
    private final PublishedVersionReader versions;
    private final SurveyFlow flow;
    private final AnswerIndexer answers;
    private final UploadRepository uploads;
    private final UploadVerifier verifier;
    private final IdGenerator ids;
    private final Clock clock;

    public SubmitResponseUseCase(
            UnitOfWork unitOfWork,
            VisitRepository visits,
            ResponseRepository responses,
            PublishedVersionReader versions,
            SurveyFlow flow,
            AnswerIndexer answers,
            UploadRepository uploads,
            UploadVerifier verifier,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.visits = visits;
        this.responses = responses;
        this.versions = versions;
        this.flow = flow;
        this.answers = answers;
        this.uploads = uploads;
        this.verifier = verifier;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * @param visit the visit the browser's token names
     * @param link the link the visit came from, which supplies the personal reference and the source
     * @param submitted the answers, the locale and the idempotency key the client sent
     */
    public Submitted execute(Visit visit, SurveyLink link, Submission submitted) {
        return unitOfWork.transactional(() -> {
            Optional<UUID> replay = responses.completedByIdempotencyKey(submitted.idempotencyKey());
            if (replay.isPresent()) {
                return new Submitted(replay.get(), null, true);
            }

            Map<String, Object> document = versions.byNumber(visit.surveyId(), visit.versionNo())
                    .map(PublishedVersionReader.PublishedVersion::document)
                    .orElseThrow(() -> new ResponseRefusedException(
                            ResponseRefusedException.Reason.VERSION_GONE, "the version this visit opened is gone"));

            List<Upload> visitUploads = uploads.ofVisit(visit.id());
            Set<String> knownUploads = visitUploads.stream()
                    .map(upload -> upload.id().toString())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            List<SubmitProblem> problems = new java.util.ArrayList<>(
                    flow.submitProblems(document, submitted.answers(), knownUploads));
            // A referenced upload whose bytes do not match what it declared is refused: the presigned length and
            // type are a claim, and the object's first bytes are what the server trusts.
            List<Upload> referenced = referencedUploads(document, submitted.answers(), visitUploads);
            if (!problems.isEmpty()) {
                throw new SubmitInvalidException(problems);
            }
            for (Upload upload : referenced) {
                verifier.problem(document, List.of(upload), upload.questionId().toString())
                        .ifPresent(ignored -> problems.add(
                                new SubmitProblem(upload.questionId().toString(), SubmitProblem.INVALID_UPLOAD)));
            }
            if (!problems.isEmpty()) {
                throw new SubmitInvalidException(problems);
            }

            FlowResult evaluated = flow.run(document, submitted.answers());
            Instant now = clock.now();
            Optional<Response> stored = responses.findByVisitId(visit.id());
            Response draft = stored.orElseGet(() -> Response.draft(
                    ids.newId(),
                    visit.tenantId(),
                    visit.surveyId(),
                    visit.versionId(),
                    visit.linkId(),
                    visit.id(),
                    submitted.locale(),
                    link.source(),
                    link.externalRef(),
                    link.personal() ? link.id() : null,
                    now));
            if (stored.isEmpty()) {
                // A browser that submits without ever saving a draft still needs its row, because completion is an
                // update guarded by the row's status rather than an insert.
                responses.insert(draft);
            }

            if (draft.status() == dev.ngb.survey.collection.domain.response.ResponseStatus.COMPLETED) {
                throw new AlreadySubmittedException();
            }
            if (!responses.countCompletion(visit.surveyId(), visit.tenantId(), capOf(document))) {
                throw new ResponseRefusedException(
                        ResponseRefusedException.Reason.CAP_REACHED, "the survey has reached its response cap");
            }

            Response completed = draft.completed(
                    submitted.answers(),
                    evaluated.vars(),
                    evaluated.score(),
                    identity(document, link, submitted.answers()),
                    submitted.idempotencyKey(),
                    now);

            List<TypedAnswer> typed = typed(completed, document);
            if (!responses.complete(completed, typed)) {
                // The conditional update lost the race: a second completion on this personal link, or a visit that
                // completed between the read and the write. Either way nothing was stored.
                throw new AlreadySubmittedException();
            }
            verifier.attach(referenced);
            visits.save(visit.completed(completed.id(), now), visit.state().name());
            return new Submitted(completed.id(), completed.score(), false);
        });
    }

    /** The uploads a submission references, from the visit's own pending uploads. */
    private static List<Upload> referencedUploads(
            Map<String, Object> document, Map<String, Object> submitted, List<Upload> visitUploads) {
        Map<String, Upload> byId = new java.util.LinkedHashMap<>();
        visitUploads.forEach(upload -> byId.put(upload.id().toString(), upload));
        List<Upload> referenced = new java.util.ArrayList<>();
        Object pages = document.get("pages");
        if (!(pages instanceof List<?> pageList)) {
            return referenced;
        }
        for (Object rawPage : pageList) {
            if (!(rawPage instanceof Map<?, ?> page) || !(page.get("questions") instanceof List<?> questions)) {
                continue;
            }
            for (Object rawQuestion : questions) {
                if (!(rawQuestion instanceof Map<?, ?> question)
                        || !"file".equals(question.get("type"))
                        || !(submitted.get(String.valueOf(question.get("id"))) instanceof List<?> ids)) {
                    continue;
                }
                ids.forEach(id -> {
                    Upload upload = byId.get(String.valueOf(id));
                    if (upload != null) {
                        referenced.add(upload);
                    }
                });
            }
        }
        return referenced;
    }

    /** The version's response cap, or null when it states none. */
    private static Long capOf(Map<String, Object> document) {
        Object settings = document.get("settings");
        if (!(settings instanceof Map<?, ?> members)) {
            return null;
        }
        Object cap = members.get("response_cap");
        return cap instanceof Number number ? number.longValue() : null;
    }

    private List<TypedAnswer> typed(Response response, Map<String, Object> document) {
        return answers.index(document, response.answers()).stream()
                .map(row -> new TypedAnswer(
                        response.id(),
                        response.tenantId(),
                        response.surveyId(),
                        response.versionId(),
                        row.questionId(),
                        row.optionId(),
                        row.rowId(),
                        row.numValue(),
                        row.textValue(),
                        row.dateValue(),
                        response.source(),
                        response.submittedAt()))
                .toList();
    }

    /**
     * The respondent's identity: a personal link's reference wins, because the Tenant already knows that person; else the
     * value of the question the version marks {@code identity: true}, lower-cased and trimmed.
     */
    private String identity(Map<String, Object> document, SurveyLink link, Map<String, Object> submitted) {
        if (link.externalRef() != null) {
            return link.externalRef();
        }
        String questionId = answers.identityQuestion(document).orElse(null);
        Object value = questionId == null ? null : submitted.get(questionId);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * One submission, as the public endpoint receives it.
     *
     * @param answers the answers keyed by stable question id
     * @param locale the locale the respondent read, or null
     * @param idempotencyKey the client's key, which a retry repeats
     */
    public record Submission(Map<String, Object> answers, String locale, String idempotencyKey) {}
}
