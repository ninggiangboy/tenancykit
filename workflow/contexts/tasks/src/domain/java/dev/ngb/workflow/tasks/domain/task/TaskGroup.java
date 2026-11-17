package dev.ngb.workflow.tasks.domain.task;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A named list of users a task may be assigned to. The Tenant owns it; Platform's roles are not involved. */
public record TaskGroup(
        UUID id,
        TenantId tenantId,
        String code,
        String name,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public TaskGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public TaskGroup renamed(String name, Instant now) {
        return new TaskGroup(id, tenantId, code, name, createdAt, now, version + 1);
    }
}
