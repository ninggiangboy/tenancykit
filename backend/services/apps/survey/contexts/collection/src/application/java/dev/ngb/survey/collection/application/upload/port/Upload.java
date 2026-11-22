package dev.ngb.survey.collection.application.upload.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An upload a file question references: a presigned object that becomes part of a response once the server has checked
 * its size and magic bytes.
 *
 * <p>The object key is derived from the tenant, the survey and the upload id, so a filename a respondent chose is
 * never a key and cannot escape the prefix it belongs to.
 */
public record Upload(
        UUID id,
        UUID tenantId,
        UUID surveyId,
        UUID visitId,
        UUID questionId,
        String objectKey,
        long declaredSize,
        String declaredMime,
        String status,
        Instant createdAt) {}
