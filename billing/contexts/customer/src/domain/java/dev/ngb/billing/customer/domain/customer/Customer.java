package dev.ngb.billing.customer.domain.customer;

import dev.ngb.core.id.TenantId;
import dev.ngb.billing.sharedkernel.id.CustomerId;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A person or business that uses a Tenant's services. It owns the wallet, usage, charges and invoices, needs
 * no Billing login, and may be mapped to accounts in the Tenant's own systems.
 */
public final class Customer {

    private final CustomerId id;
    private final TenantId tenantId;
    private final Instant createdAt;
    private final String idempotencyKey;
    private final String requestHash;
    private final Set<ExternalIdentity> identities;
    private String displayName;
    private String email;
    private CustomerStatus status;
    private long version;

    private Customer(
            CustomerId id,
            TenantId tenantId,
            String displayName,
            String email,
            CustomerStatus status,
            Instant createdAt,
            String idempotencyKey,
            String requestHash,
            Set<ExternalIdentity> identities,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.displayName = requireName(displayName);
        this.email = normalizeEmail(email);
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        this.identities = new HashSet<>(identities);
        this.version = version;
    }

    public static Customer create(
            CustomerId id, TenantId tenantId, String displayName, String email, String idempotencyKey, String requestHash, Instant now) {
        return new Customer(id, tenantId, displayName, email, CustomerStatus.ACTIVE, now, idempotencyKey, requestHash, Set.of(), 1);
    }

    public static Customer restore(
            CustomerId id, TenantId tenantId, String displayName, String email, CustomerStatus status, Instant createdAt,
            String idempotencyKey, String requestHash, Set<ExternalIdentity> identities, long version) {
        return new Customer(id, tenantId, displayName, email, status, createdAt, idempotencyKey, requestHash, identities, version);
    }

    /** Maps a Tenant System's user to this customer. The database enforces that one such user maps to one customer. */
    public void mapTo(ExternalIdentity identity) {
        identities.add(Objects.requireNonNull(identity, "identity"));
    }

    /** @return false when the contact is unchanged, so no write and no version bump happen */
    public boolean changeContact(String newDisplayName, String newEmail) {
        requireActive();
        String normalizedName = requireName(newDisplayName);
        String normalizedEmail = normalizeEmail(newEmail);
        if (normalizedName.equals(displayName) && Objects.equals(normalizedEmail, email)) {
            return false;
        }
        this.displayName = normalizedName;
        this.email = normalizedEmail;
        version++;
        return true;
    }

    /** Deactivating twice is allowed and changes nothing. @return false when it was already deactivated */
    public boolean deactivate() {
        if (status == CustomerStatus.DEACTIVATED) {
            return false;
        }
        status = CustomerStatus.DEACTIVATED;
        version++;
        return true;
    }

    public boolean isActive() {
        return status == CustomerStatus.ACTIVE;
    }

    public CustomerId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String displayName() {
        return displayName;
    }

    /** May be null: a customer needs no email. */
    public String email() {
        return email;
    }

    public CustomerStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestHash() {
        return requestHash;
    }

    public Set<ExternalIdentity> identities() {
        return Set.copyOf(identities);
    }

    /** The version an ETag carries; an update that pins it must match for the write to proceed. */
    public long version() {
        return version;
    }

    private void requireActive() {
        if (!isActive()) {
            throw new IllegalStateException("a deactivated customer cannot be changed");
        }
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "displayName");
        if (name.isBlank() || name.length() > 200) {
            throw new IllegalArgumentException("display name must be 1-200 characters");
        }
        return name.strip();
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String value = email.strip();
        int at = value.indexOf('@');
        if (at < 1 || at != value.lastIndexOf('@') || at == value.length() - 1 || value.length() > 254 || value.contains(" ")) {
            throw new IllegalArgumentException("invalid email address");
        }
        return value;
    }
}
