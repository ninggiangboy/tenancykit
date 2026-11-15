package dev.ngb.mailroom.sending.application.campaign.query;

import java.time.Instant;
import java.util.UUID;

/** One campaign as the API shows it. */
public record CampaignView(
        UUID id,
        String name,
        String status,
        UUID templateId,
        int templateRevision,
        String targetType,
        UUID targetId,
        String fromKey,
        String fromName,
        String replyTo,
        Instant scheduledAt,
        Instant startedAt,
        Instant completedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
