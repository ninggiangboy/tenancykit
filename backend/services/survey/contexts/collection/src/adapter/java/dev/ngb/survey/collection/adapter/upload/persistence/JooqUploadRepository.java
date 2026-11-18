package dev.ngb.survey.collection.adapter.upload.persistence;

import static dev.ngb.survey_jooq.Tables.UPLOADS;

import dev.ngb.survey.collection.application.upload.port.Upload;
import dev.ngb.survey.collection.application.upload.port.UploadRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/** The upload store: what one visit created, what a submit attaches, and what the sweep deletes. */
public class JooqUploadRepository implements UploadRepository {

    private final DSLContext dsl;

    public JooqUploadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(Upload upload) {
        dsl.insertInto(UPLOADS)
                .set(UPLOADS.ID, upload.id())
                .set(UPLOADS.TENANT_ID, upload.tenantId())
                .set(UPLOADS.SURVEY_ID, upload.surveyId())
                .set(UPLOADS.VISIT_ID, upload.visitId())
                .set(UPLOADS.QUESTION_ID, upload.questionId())
                .set(UPLOADS.OBJECT_KEY, upload.objectKey())
                .set(UPLOADS.DECLARED_SIZE, upload.declaredSize())
                .set(UPLOADS.DECLARED_MIME, upload.declaredMime())
                .set(UPLOADS.STATUS, upload.status())
                .set(UPLOADS.CREATED_AT, upload.createdAt())
                .execute();
    }

    @Override
    public Optional<Upload> findById(UUID uploadId) {
        return dsl.selectFrom(UPLOADS).where(UPLOADS.ID.eq(uploadId)).fetchOptional(this::toUpload);
    }

    @Override
    public List<Upload> ofVisit(UUID visitId) {
        return dsl.selectFrom(UPLOADS).where(UPLOADS.VISIT_ID.eq(visitId)).fetch(this::toUpload);
    }

    @Override
    public void attach(UUID uploadId) {
        dsl.update(UPLOADS)
                .set(UPLOADS.STATUS, "ATTACHED")
                .where(UPLOADS.ID.eq(uploadId))
                .execute();
    }

    @Override
    public List<Upload> pendingBefore(Instant before, int limit) {
        return dsl.selectFrom(UPLOADS)
                .where(UPLOADS.STATUS.eq("PENDING"))
                .and(UPLOADS.CREATED_AT.lt(before))
                .orderBy(UPLOADS.CREATED_AT.asc())
                .limit(limit)
                .fetch(this::toUpload);
    }

    @Override
    public void delete(UUID uploadId) {
        dsl.deleteFrom(UPLOADS).where(UPLOADS.ID.eq(uploadId)).execute();
    }

    private Upload toUpload(Record row) {
        return new Upload(
                row.get(UPLOADS.ID),
                row.get(UPLOADS.TENANT_ID),
                row.get(UPLOADS.SURVEY_ID),
                row.get(UPLOADS.VISIT_ID),
                row.get(UPLOADS.QUESTION_ID),
                row.get(UPLOADS.OBJECT_KEY),
                row.get(UPLOADS.DECLARED_SIZE),
                row.get(UPLOADS.DECLARED_MIME),
                row.get(UPLOADS.STATUS),
                row.get(UPLOADS.CREATED_AT));
    }
}
