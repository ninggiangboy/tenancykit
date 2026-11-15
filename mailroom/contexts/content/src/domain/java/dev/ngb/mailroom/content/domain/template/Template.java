package dev.ngb.mailroom.content.domain.template;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailroom.shared.TemplateKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A template as a tenant addresses it: one row with a stable key, the pointer to its current revision and the
 * optimistic-lock version the API exposes as an ETag.
 *
 * <p>The aggregate is immutable; an edit produces a new {@link TemplateRevision} and a new pointer. A later edit can
 * therefore never change an email that already recorded the revision it rendered.
 */
public record Template(
        UUID id,
        TenantId tenantId,
        TemplateKey key,
        String name,
        int currentRevision,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_NAME = 200;

    public Template {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = requireName(name);
        if (currentRevision < 1) {
            throw new IllegalArgumentException("a template starts at revision 1");
        }
        if (version < 1) {
            throw new IllegalArgumentException("a template starts at version 1");
        }
    }

    public static Template create(UUID id, TenantId tenantId, TemplateKey key, String name, Instant now) {
        return new Template(id, tenantId, key, name, 1, 1, now, now);
    }

    /** Points at a new immutable revision and bumps the version the caller compares with {@code If-Match}. */
    public Template withRevision(int revision, String name, Instant now) {
        if (revision != currentRevision + 1) {
            throw new IllegalArgumentException("a revision is the next one after the current");
        }
        return new Template(id, tenantId, key, name, revision, version + 1, createdAt, now);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a template needs a name");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME) {
            throw new IllegalArgumentException("a template name is at most " + MAX_NAME + " characters");
        }
        return trimmed;
    }
}
