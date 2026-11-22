package dev.ngb.workflow.definitions.domain.definition;

import dev.ngb.core.error.ConflictException;
import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.definitions.domain.expression.Json;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One Tenant's workflow definition: its name, the graph it is editing, and the last version it published.
 *
 * <p>The draft is stored as the document the Tenant sent, because a draft may be structurally wrong while it is being
 * written; publishing is what turns it into an immutable version. Every change bumps {@code version}, which is the
 * {@code ETag} a second editor sends back in {@code If-Match}.
 */
public final class Definition {

    private final UUID id;
    private final TenantId tenantId;
    private final String code;
    private String name;
    private DefinitionStatus status;
    private Object draft;
    private int latestVersion;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    public Definition(
            UUID id,
            TenantId tenantId,
            String code,
            String name,
            DefinitionStatus status,
            Object draft,
            int latestVersion,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.code = Objects.requireNonNull(code, "code");
        this.name = Objects.requireNonNull(name, "name");
        this.status = Objects.requireNonNull(status, "status");
        this.draft = draft == null ? null : Json.copy(draft);
        this.latestVersion = latestVersion;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    public static Definition create(UUID id, TenantId tenantId, String code, String name, Instant now) {
        return new Definition(id, tenantId, code, name, DefinitionStatus.DRAFT, null, 0, now, now, 1);
    }

    /** Replaces the draft graph. A retired definition keeps its versions but accepts no new edit. */
    public void replaceDraft(Object draft, Instant now) {
        if (status == DefinitionStatus.RETIRED) {
            throw new ConflictException("a retired definition cannot be edited");
        }
        this.draft = Json.copy(draft);
        this.updatedAt = now;
        this.version++;
    }

    /** Records a published version of the draft that was just validated. */
    public void publish(int number, Instant now) {
        if (status == DefinitionStatus.RETIRED) {
            throw new ConflictException("a retired definition cannot publish new versions");
        }
        this.status = DefinitionStatus.PUBLISHED;
        this.latestVersion = number;
        this.updatedAt = now;
        this.version++;
    }

    /** Stops new instances; a published version stays readable and the instances pinned to it keep running. */
    public void retire(Instant now) {
        if (status != DefinitionStatus.PUBLISHED) {
            throw new ConflictException("only a published definition can be retired");
        }
        this.status = DefinitionStatus.RETIRED;
        this.updatedAt = now;
        this.version++;
    }

    public UUID id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String code() {
        return code;
    }

    public String name() {
        return name;
    }

    public DefinitionStatus status() {
        return status;
    }

    public Object draft() {
        return draft == null ? null : Json.copy(draft);
    }

    public int latestVersion() {
        return latestVersion;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
