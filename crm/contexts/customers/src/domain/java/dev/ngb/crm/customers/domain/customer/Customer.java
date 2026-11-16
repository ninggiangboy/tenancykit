package dev.ngb.crm.customers.domain.customer;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One of the Tenant's customers: the reference the Tenant's own system chose plus the attributes that system sent.
 * The identity of the person behind it is the Tenant's business — crm never matches one customer to another by phone
 * or email, so nothing here merges, resolves or looks up by an attribute.
 *
 * <p>The reference is immutable and the version is the ETag: a change replaces the attributes, moves the instant and
 * bumps the version, and a body that matches what is stored changes nothing at all.
 */
public final class Customer {

    private final CustomerId id;
    private final TenantId tenantId;
    private final String externalRef;
    private String displayName;
    private String phone;
    private String phoneDigits;
    private String email;
    private CustomerStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Customer(
            CustomerId id, TenantId tenantId, String externalRef, String displayName, String phone, String phoneDigits,
            String email, CustomerStatus status, Instant createdAt, Instant updatedAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.externalRef = Objects.requireNonNull(externalRef, "externalRef");
        this.displayName = displayName;
        this.phone = phone;
        this.phoneDigits = phoneDigits;
        this.email = email;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** A customer that did not exist until now; active from the moment it is created. */
    public static Customer create(
            CustomerId id, TenantId tenantId, String externalRef, String displayName, String phone, String phoneDigits,
            String email, Instant now) {
        return new Customer(
                id, tenantId, externalRef, displayName, phone, phoneDigits, email, CustomerStatus.ACTIVE, now, now, 1);
    }

    /** The customer a repository reads back from storage. */
    public static Customer restore(
            CustomerId id, TenantId tenantId, String externalRef, String displayName, String phone, String phoneDigits,
            String email, CustomerStatus status, Instant createdAt, Instant updatedAt, long version) {
        return new Customer(
                id, tenantId, externalRef, displayName, phone, phoneDigits, email, status, createdAt, updatedAt, version);
    }

    /**
     * Replaces the attributes with what the caller sent.
     *
     * @return whether anything changed, because a body identical to what is stored must bump no version and announce
     *     nothing
     */
    public boolean replace(
            String newDisplayName, String newPhone, String newPhoneDigits, String newEmail, Instant now) {
        if (Objects.equals(displayName, newDisplayName)
                && Objects.equals(phone, newPhone)
                && Objects.equals(phoneDigits, newPhoneDigits)
                && Objects.equals(email, newEmail)) {
            return false;
        }
        this.displayName = newDisplayName;
        this.phone = newPhone;
        this.phoneDigits = newPhoneDigits;
        this.email = newEmail;
        this.updatedAt = now;
        this.version++;
        return true;
    }

    /** The announcement of this customer's creation, ready for the outbox. */
    public CustomerEvents.Created created(UUID eventId) {
        return new CustomerEvents.Created(eventId, tenantId, id, externalRef, createdAt);
    }

    public boolean isErased() {
        return status == CustomerStatus.ERASED;
    }

    public CustomerId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String externalRef() {
        return externalRef;
    }

    public String displayName() {
        return displayName;
    }

    public String phone() {
        return phone;
    }

    public String phoneDigits() {
        return phoneDigits;
    }

    public String email() {
        return email;
    }

    public CustomerStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** The version an ETag carries; a PATCH that pins it must match for the change to proceed. */
    public long version() {
        return version;
    }
}
