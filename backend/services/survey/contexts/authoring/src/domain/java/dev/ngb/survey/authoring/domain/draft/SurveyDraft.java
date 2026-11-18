package dev.ngb.survey.authoring.domain.draft;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The one editable document of a survey, carrying its own optimistic-lock version.
 *
 * <p>The ETag a client compares belongs to the draft, not the survey: editing the document and publishing it are
 * separate decisions, and a publish also rewrites the draft when it has to give a retyped question a new id. The
 * document itself is the version schema's shape, stored as submitted after the server has assigned every missing id.
 */
public record SurveyDraft(
        UUID surveyId, TenantId tenantId, Map<String, Object> document, long etag, Instant updatedAt) {

    public SurveyDraft {
        Objects.requireNonNull(surveyId, "surveyId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (etag < 1) {
            throw new IllegalArgumentException("a draft starts at etag 1");
        }
    }

    /** A new survey's draft: the minimal document a builder starts from. */
    public static SurveyDraft initial(
            UUID surveyId, TenantId tenantId, Map<String, Object> document, Instant now) {
        return new SurveyDraft(surveyId, tenantId, document, 1, now);
    }

    /** The stored document after a save, with the tag the next write has to carry. */
    public SurveyDraft saved(Map<String, Object> storedDocument, Instant now) {
        return new SurveyDraft(surveyId, tenantId, storedDocument, etag + 1, now);
    }
}
