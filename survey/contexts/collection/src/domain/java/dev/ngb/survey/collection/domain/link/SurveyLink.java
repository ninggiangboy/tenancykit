package dev.ngb.survey.collection.domain.link;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One way a respondent reaches a survey: a public link for a Tenant-named source, or a personal one for a recipient.
 *
 * <p>The token in the URL is derived from the link's id and is not stored: the id is what a link's URL is rebuilt from
 * at any time, so the Tenant can read a link back long after it was created. Revoking moves the status rather than
 * deleting the row, because a link's URL must keep answering the same 404 forever.
 *
 * @param expiresAt when the link stops opening the survey, or null when only revocation stops it
 * @param externalRef a personal link's recipient reference; null for a public link
 */
public record SurveyLink(
        UUID id,
        TenantId tenantId,
        UUID surveyId,
        LinkKind kind,
        String source,
        String externalRef,
        Instant expiresAt,
        LinkStatus status,
        Instant createdAt) {

    public SurveyLink {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(surveyId, "surveyId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (source.isBlank() || source.length() > 100) {
            throw new IllegalArgumentException("a link's source is 1 to 100 characters");
        }
        if ((kind == LinkKind.PERSONAL) != (externalRef != null)) {
            throw new IllegalArgumentException("only a personal link carries an external reference");
        }
        if (externalRef != null && (externalRef.isBlank() || externalRef.length() > 200)) {
            throw new IllegalArgumentException("a personal link's reference is 1 to 200 characters");
        }
    }

    /** A public link for a source, active from the moment it is created. */
    public static SurveyLink publicLink(UUID id, TenantId tenantId, UUID surveyId, String source, Instant now) {
        return new SurveyLink(
                id, tenantId, surveyId, LinkKind.PUBLIC, source, null, null, LinkStatus.ACTIVE, now);
    }

    /** A personal link for one recipient, optionally expiring. */
    public static SurveyLink personalLink(
            UUID id, TenantId tenantId, UUID surveyId, String source, String externalRef, Instant expiresAt, Instant now) {
        return new SurveyLink(
                id, tenantId, surveyId, LinkKind.PERSONAL, source, externalRef, expiresAt, LinkStatus.ACTIVE, now);
    }

    /** Whether the link can open the survey at this instant: active and, when it expires, not yet expired. */
    public boolean opensAt(Instant now) {
        return status == LinkStatus.ACTIVE && (expiresAt == null || now.isBefore(expiresAt));
    }

    /** The link after the Tenant revokes it. Revoking twice is not a new decision and leaves the row alone. */
    public SurveyLink revoked() {
        return new SurveyLink(
                id, tenantId, surveyId, kind, source, externalRef, expiresAt, LinkStatus.REVOKED, createdAt);
    }

    /** Whether this is a personal link, which is what makes a second completion impossible. */
    public boolean personal() {
        return kind == LinkKind.PERSONAL;
    }
}
