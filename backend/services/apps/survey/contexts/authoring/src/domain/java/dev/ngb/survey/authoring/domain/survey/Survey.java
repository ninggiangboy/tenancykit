package dev.ngb.survey.authoring.domain.survey;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A survey as a tenant addresses it: one row holding the name, the lifecycle status, the pointer to its current
 * version and the optimistic-lock version the API exposes as an ETag.
 *
 * <p>The aggregate is immutable. Publishing moves the pointer and open state; closing withdraws every version from
 * the public surface. The draft is a separate aggregate, so editing the document and publishing it are two decisions
 * a client compares with two different tags.
 */
public record Survey(
        UUID id,
        TenantId tenantId,
        String name,
        SurveyStatus status,
        UUID currentVersionId,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /** The longest name the API accepts, matching the version document's own title budget. */
    public static final int MAX_NAME = 200;

    public Survey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = requireName(name);
        if (version < 1) {
            throw new IllegalArgumentException("a survey starts at version 1");
        }
        if (status == SurveyStatus.DRAFT && currentVersionId != null) {
            throw new IllegalArgumentException("a draft survey points at no version");
        }
        if (status == SurveyStatus.OPEN && currentVersionId == null) {
            throw new IllegalArgumentException("an open survey points at the version it serves");
        }
    }

    public static Survey create(UUID id, TenantId tenantId, String name, Instant now) {
        return new Survey(id, tenantId, name, SurveyStatus.DRAFT, null, 1, now, now);
    }

    /**
     * Moves the pointer to a freshly published version. The first publish opens the survey; a later one leaves an
     * already closed survey closed, so publishing a version never reopens a survey a Tenant withdrew.
     */
    public Survey published(UUID versionId, Instant now) {
        Objects.requireNonNull(versionId, "versionId");
        SurveyStatus published = status == SurveyStatus.DRAFT ? SurveyStatus.OPEN : status;
        return new Survey(id, tenantId, name, published, versionId, version + 1, createdAt, now);
    }

    /** Withdraws every version from the public surface. Closing twice is a no-op the caller recognises first. */
    public Survey closed(Instant now) {
        if (status == SurveyStatus.CLOSED) {
            return this;
        }
        return new Survey(id, tenantId, name, SurveyStatus.CLOSED, currentVersionId, version + 1, createdAt, now);
    }

    /**
     * Opens the survey again. A survey closed before its first publish returns to {@code DRAFT}, because there is no
     * version to serve.
     */
    public Survey reopened(Instant now) {
        if (status != SurveyStatus.CLOSED) {
            return this;
        }
        SurveyStatus reopened = currentVersionId == null ? SurveyStatus.DRAFT : SurveyStatus.OPEN;
        return new Survey(id, tenantId, name, reopened, currentVersionId, version + 1, createdAt, now);
    }

    public boolean isClosed() {
        return status == SurveyStatus.CLOSED;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a survey needs a name");
        }
        String trimmed = name.strip();
        if (trimmed.length() > MAX_NAME) {
            throw new IllegalArgumentException("a survey name is at most " + MAX_NAME + " characters");
        }
        return trimmed;
    }
}
