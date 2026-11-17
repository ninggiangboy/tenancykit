package dev.ngb.workflow.webhook.domain.endpoint;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Where a Tenant wants its events sent, and which ones. The signing secret is held only as ciphertext; the
 * plaintext exists once, in the response that creates or rotates it. {@code tenantSystemId} names the Tenant
 * System that registered it, or is null when a Tenant User owns it.
 */
public final class WebhookEndpoint {

    /** Subscribes to every event type. */
    public static final String ALL_EVENTS = "*";

    private final WebhookEndpointId id;
    private final TenantId tenantId;
    private String url;
    private String description;
    private Set<String> eventTypes;
    private EndpointStatus status;
    private String keyId;
    private String secretCiphertext;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant secretRotatedAt;
    private long version;

    private WebhookEndpoint(
            WebhookEndpointId id, TenantId tenantId, String url, String description, Set<String> eventTypes,
            EndpointStatus status, String keyId, String secretCiphertext, Instant createdAt, Instant updatedAt, Instant secretRotatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.url = Objects.requireNonNull(url, "url");
        this.description = description;
        this.eventTypes = normalize(eventTypes);
        this.status = Objects.requireNonNull(status, "status");
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        this.secretCiphertext = Objects.requireNonNull(secretCiphertext, "secretCiphertext");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.secretRotatedAt = secretRotatedAt;
        this.version = version;
    }

    /** A new endpoint, active from the moment it is created. Its secret exists only as ciphertext from here on. */
    public static WebhookEndpoint create(
            WebhookEndpointId id, TenantId tenantId, String url, String description, Collection<String> eventTypes, String keyId,
            String secretCiphertext, Instant now) {
        return new WebhookEndpoint(id, tenantId, url, cleanDescription(description), Set.copyOf(eventTypes), EndpointStatus.ACTIVE,
                keyId, secretCiphertext, now, now, null, 1);
    }

    /** The endpoint a repository reads back from storage. */
    public static WebhookEndpoint restore(
            WebhookEndpointId id, TenantId tenantId, String url, String description, Collection<String> eventTypes, EndpointStatus status,
            String keyId, String secretCiphertext, Instant createdAt, Instant updatedAt, Instant secretRotatedAt, long version) {
        return new WebhookEndpoint(id, tenantId, url, description, Set.copyOf(eventTypes), status, keyId, secretCiphertext, createdAt,
                updatedAt, secretRotatedAt, version);
    }

    public void reconfigure(String newUrl, String newDescription, Collection<String> newEventTypes, Instant now) {
        this.url = Objects.requireNonNull(newUrl, "url");
        this.description = cleanDescription(newDescription);
        this.eventTypes = normalize(Set.copyOf(newEventTypes));
        this.updatedAt = now;
        this.version++;
    }

    public void changeStatus(EndpointStatus newStatus, Instant now) {
        this.status = Objects.requireNonNull(newStatus, "status");
        this.updatedAt = now;
        this.version++;
    }

    /**
     * Stops every future delivery and hides the endpoint, while the deliveries it already received stay readable. The
     * row is kept rather than deleted: a delivery's endpoint is a foreign key, and the history is evidence.
     */
    public void delete(Instant now) {
        this.status = EndpointStatus.DELETED;
        this.updatedAt = now;
        this.version++;
    }

    /** Secret rotation is outside the If-Match scope, but it changes the row, so the version moves with it. */
    public void rotateSecret(String newKeyId, String newCiphertext, Instant now) {
        this.keyId = Objects.requireNonNull(newKeyId, "keyId");
        this.secretCiphertext = Objects.requireNonNull(newCiphertext, "secretCiphertext");
        this.secretRotatedAt = now;
        this.updatedAt = now;
        this.version++;
    }

    public boolean isDeleted() {
        return status == EndpointStatus.DELETED;
    }

    public boolean subscribesTo(String eventType) {
        return status == EndpointStatus.ACTIVE && (eventTypes.contains(ALL_EVENTS) || eventTypes.contains(eventType));
    }

    private static Set<String> normalize(Set<String> types) {
        if (types.isEmpty()) {
            throw new IllegalArgumentException("subscribe to at least one event type");
        }
        return Collections.unmodifiableSet(new TreeSet<>(types));
    }

    private static String cleanDescription(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.length() > 500) {
            throw new IllegalArgumentException("the description is limited to 500 characters");
        }
        return text.strip();
    }

    public WebhookEndpointId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String url() {
        return url;
    }

    public String description() {
        return description;
    }

    public Set<String> eventTypes() {
        return eventTypes;
    }

    public EndpointStatus status() {
        return status;
    }

    public String keyId() {
        return keyId;
    }

    public String secretCiphertext() {
        return secretCiphertext;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant secretRotatedAt() {
        return secretRotatedAt;
    }

    /** The version an ETag carries; a conditional PATCH that pins it must match for the write to proceed. */
    public long version() {
        return version;
    }
}
