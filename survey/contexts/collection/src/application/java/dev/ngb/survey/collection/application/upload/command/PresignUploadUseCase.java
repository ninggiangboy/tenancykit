package dev.ngb.survey.collection.application.upload.command;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.survey.collection.application.upload.exception.UploadRefusedException;
import dev.ngb.survey.authoring.api.flow.AnswerIndexer;
import dev.ngb.survey.collection.application.upload.port.Upload;
import dev.ngb.survey.collection.application.upload.port.UploadRepository;
import dev.ngb.survey.collection.application.upload.port.UploadStore;
import dev.ngb.survey.collection.domain.visit.Visit;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Signs a PUT for one file a respondent is about to upload, after checking it against the question's own limits.
 *
 * <p>The object key is derived, never taken from the browser: {@code tenant/<tenant>/survey/<survey>/<upload>}, so a
 * filename a respondent chose cannot escape the prefix it belongs to or overwrite another tenant's object. The length
 * and type are fixed into the signature, so the object cannot arrive larger or of another type than what was declared.
 */
public class PresignUploadUseCase {

    /** How long a presigned PUT stays valid; long enough for a phone on a slow connection, short enough to expire. */
    public static final Duration LIFETIME = Duration.ofMinutes(15);

    /** The most one question's file answer accepts, which bounds a single submit's storage. */
    public static final int MAX_FILES = 5;

    /** One requested upload: the question it answers, the declared name, size and type. */
    public record Request(UUID questionId, String filename, long size, String mime) {}

    /** What the browser receives: where to PUT, with which headers, and the id a submit references. */
    public record Signed(UUID uploadId, String url, Map<String, String> headers) {}

    private final UnitOfWork unitOfWork;
    private final UploadRepository uploads;
    private final UploadStore store;
    private final AnswerIndexer limits;
    private final IdGenerator ids;
    private final Clock clock;

    public PresignUploadUseCase(
            UnitOfWork unitOfWork,
            UploadRepository uploads,
            UploadStore store,
            AnswerIndexer limits,
            IdGenerator ids,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.uploads = uploads;
        this.store = store;
        this.limits = limits;
        this.ids = ids;
        this.clock = clock;
    }

    public Signed execute(Visit visit, Map<String, Object> document, Request request) {
        return unitOfWork.transactional(() -> {
            AnswerIndexer.FileLimit limit = limits
                    .fileLimit(document, request.questionId().toString())
                    .orElseThrow(() -> new UploadRefusedException(
                            UploadRefusedException.Reason.NOT_A_FILE_QUESTION,
                            "this question accepts no file"));

            if (request.size() < 1 || request.size() > limit.maxBytes()) {
                throw new UploadRefusedException(
                        UploadRefusedException.Reason.SIZE_REFUSED, "the declared size is outside the question's limit");
            }
            if (!limit.allowedMime().contains(request.mime())) {
                throw new UploadRefusedException(
                        UploadRefusedException.Reason.TYPE_REFUSED, "the declared type is not one this question allows");
            }
            long alreadyPending = uploads.ofVisit(visit.id()).stream()
                    .filter(upload -> upload.questionId().equals(request.questionId()))
                    .count();
            if (alreadyPending >= Math.min(MAX_FILES, limit.maxFiles())) {
                throw new UploadRefusedException(
                        UploadRefusedException.Reason.TOO_MANY_FILES, "this question already holds its files");
            }

            UUID uploadId = ids.newId();
            String objectKey = "tenant/" + visit.tenantId() + "/survey/" + visit.surveyId() + "/" + uploadId;
            UploadStore.PresignedPut signed = store.presignPut(objectKey, request.size(), request.mime(), LIFETIME);
            uploads.insert(new Upload(
                    uploadId,
                    visit.tenantId(),
                    visit.surveyId(),
                    visit.id(),
                    request.questionId(),
                    objectKey,
                    request.size(),
                    request.mime(),
                    "PENDING",
                    clock.now()));
            return new Signed(uploadId, signed.url(), signed.headers());
        });
    }
}
