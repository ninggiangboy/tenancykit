package dev.ngb.email.sending.domain.campaign;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A campaign and its guarded state machine. Every transition checks the state it is allowed from and throws
 * {@link ConflictException} otherwise, so an illegal action answers 409 rather than corrupting the row.
 *
 * <p>The template revision is pinned for the campaign's whole life: an edit after scheduling changes what a future
 * campaign sends, never what this one already planned.
 */
public record Campaign(
        UUID id,
        TenantId tenantId,
        String name,
        CampaignStatus status,
        UUID templateId,
        int templateRevision,
        CampaignTargetType targetType,
        UUID targetId,
        String fromKey,
        String fromName,
        String replyTo,
        Instant scheduledAt,
        Instant startedAt,
        Instant completedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_NAME = 200;

    public Campaign {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(fromKey, "fromKey");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = requireName(name);
        if (templateRevision < 1) {
            throw new IllegalArgumentException("a campaign pins a revision numbered from 1");
        }
    }

    public static Campaign create(
            UUID id,
            TenantId tenantId,
            String name,
            UUID templateId,
            int templateRevision,
            CampaignTargetType targetType,
            UUID targetId,
            String fromKey,
            String fromName,
            String replyTo,
            Instant now) {
        return new Campaign(
                id, tenantId, name, CampaignStatus.DRAFT, templateId, templateRevision, targetType, targetId,
                fromKey, fromName, replyTo, null, null, null, 1, now, now);
    }

    public Campaign withDetails(
            String name,
            UUID templateId,
            int templateRevision,
            CampaignTargetType targetType,
            UUID targetId,
            String fromKey,
            String fromName,
            String replyTo,
            Instant now) {
        require(CampaignStatus.DRAFT, "edited");
        return new Campaign(
                id, tenantId, name, status, templateId, templateRevision, targetType, targetId,
                fromKey, fromName, replyTo, scheduledAt, startedAt, completedAt, version + 1, createdAt, now);
    }

    public Campaign schedule(Instant at, Instant now) {
        require(CampaignStatus.DRAFT, "scheduled");
        if (at == null) {
            throw new IllegalArgumentException("a scheduled campaign needs a time");
        }
        return new Campaign(
                id, tenantId, name, CampaignStatus.SCHEDULED, templateId, templateRevision, targetType, targetId,
                fromKey, fromName, replyTo, at, startedAt, completedAt, version + 1, createdAt, now);
    }

    public Campaign start(Instant now) {
        if (status != CampaignStatus.DRAFT && status != CampaignStatus.SCHEDULED) {
            throw new ConflictException("a campaign can be sent only from DRAFT or SCHEDULED, not " + status);
        }
        return new Campaign(
                id, tenantId, name, CampaignStatus.SENDING, templateId, templateRevision, targetType, targetId,
                fromKey, fromName, replyTo, scheduledAt, now, null, version + 1, createdAt, now);
    }

    public Campaign pause(Instant now) {
        require(CampaignStatus.SENDING, "paused");
        return withStatus(CampaignStatus.PAUSED, now);
    }

    public Campaign resume(Instant now) {
        require(CampaignStatus.PAUSED, "resumed");
        return withStatus(CampaignStatus.SENDING, now);
    }

    public Campaign cancel(Instant now) {
        if (status.terminal()) {
            throw new ConflictException("a campaign that is " + status + " can no longer be cancelled");
        }
        return new Campaign(
                id, tenantId, name, CampaignStatus.CANCELLED, templateId, templateRevision, targetType, targetId,
                fromKey, fromName, replyTo, scheduledAt, startedAt, now, version + 1, createdAt, now);
    }

    public boolean due(Instant now) {
        return status == CampaignStatus.SCHEDULED && scheduledAt != null && !scheduledAt.isAfter(now);
    }

    private Campaign withStatus(CampaignStatus next, Instant now) {
        return new Campaign(
                id, tenantId, name, next, templateId, templateRevision, targetType, targetId,
                fromKey, fromName, replyTo, scheduledAt, startedAt, completedAt, version + 1, createdAt, now);
    }

    private void require(CampaignStatus expected, String action) {
        if (status != expected) {
            throw new ConflictException("a campaign can be " + action + " only from " + expected + ", not " + status);
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a campaign needs a name");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME) {
            throw new IllegalArgumentException("a campaign name is at most " + MAX_NAME + " characters");
        }
        return trimmed;
    }
}
