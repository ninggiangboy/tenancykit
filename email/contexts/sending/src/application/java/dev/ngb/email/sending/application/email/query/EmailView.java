package dev.ngb.email.sending.application.email.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One sent email as the API shows it, including its event history and whether the body has been purged. */
public record EmailView(
        UUID id,
        String kind,
        String status,
        String statusReason,
        String toAddress,
        String subject,
        UUID campaignId,
        UUID receiverId,
        UUID templateId,
        Integer templateRevision,
        String bodyHtml,
        String bodyText,
        boolean bodyPurged,
        Instant deliveryUpdatedAt,
        Instant createdAt,
        Instant updatedAt,
        List<EmailEventView> events) {}
