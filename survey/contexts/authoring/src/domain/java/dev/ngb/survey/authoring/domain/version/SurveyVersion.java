package dev.ngb.survey.authoring.domain.version;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One immutable published version of a survey: the document a response records and an aggregate reads. */
public record SurveyVersion(
        UUID id,
        TenantId tenantId,
        UUID surveyId,
        int versionNo,
        Map<String, Object> document,
        Instant publishedAt) {

    public SurveyVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(surveyId, "surveyId");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (versionNo < 1) {
            throw new IllegalArgumentException("a version is numbered from 1");
        }
    }
}
