package dev.ngb.mailroom.content.domain.template;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable version of a template's bodies. A campaign pins a revision and every sent email records the revision
 * it rendered, so history survives an edit.
 */
public record TemplateRevision(
        UUID id,
        TenantId tenantId,
        UUID templateId,
        int revision,
        String subject,
        String html,
        String text,
        Instant createdAt) {

    public TemplateRevision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(createdAt, "createdAt");
        if (revision < 1) {
            throw new IllegalArgumentException("a revision is numbered from 1");
        }
    }
}
