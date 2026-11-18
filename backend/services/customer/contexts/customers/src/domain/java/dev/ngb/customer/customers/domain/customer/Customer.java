package dev.ngb.customer.customers.domain.customer;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One of the Tenant's customers: the reference the Tenant's own system chose plus the attributes that system sent.
 * The identity of the person behind it is the Tenant's business — customer never matches one customer to another by phone
 * or email, so nothing here merges, resolves or looks up by an attribute.
 *
 * <p>The reference is immutable while the customer is a person's record, and the version is the ETag: a change replaces
 * the attributes, moves the instant and bumps the version, and a body that matches what is stored changes nothing at
 * all. The version covers the customer's field values and tags as well, because a caller that pinned it asks about the
 * customer as a whole.
 *
 * <p>An erasure is the one transition that leaves nothing behind: every attribute, the field values and the tags are
 * cleared, the status becomes {@code ERASED} and the reference becomes the anonymous marker {@code erased:<id>}. The
 * row stays because the orders and the consent history point at it, and the marker is what releases the Tenant's own
 * reference for a new customer.
 */
public final class Customer {

    /** The prefix of the anonymous reference an erased customer is left with, in front of customer's own id. */
    private static final String ERASED_REF_PREFIX = "erased:";

    private final CustomerId id;
    private final TenantId tenantId;
    private String externalRef;
    private String displayName;
    private String phone;
    private String phoneDigits;
    private String email;
    private Map<String, Object> fields;
    private Set<String> tags;
    private CustomerStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    private Customer(
            CustomerId id,
            TenantId tenantId,
            String externalRef,
            String displayName,
            String phone,
            String phoneDigits,
            String email,
            Map<String, Object> fields,
            Set<String> tags,
            CustomerStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.externalRef = Objects.requireNonNull(externalRef, "externalRef");
        this.displayName = displayName;
        this.phone = phone;
        this.phoneDigits = phoneDigits;
        this.email = email;
        this.fields = copyOfFields(fields);
        this.tags = copyOfTags(tags);
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** A customer that did not exist until now; active from the moment it is created. */
    public static Customer create(
            CustomerId id,
            TenantId tenantId,
            String externalRef,
            String displayName,
            String phone,
            String phoneDigits,
            String email,
            Map<String, Object> fields,
            Set<String> tags,
            Instant now) {
        return new Customer(
                id, tenantId, externalRef, displayName, phone, phoneDigits, email, fields, tags, CustomerStatus.ACTIVE,
                now, now, 1);
    }

    /** The customer a repository reads back from storage. */
    public static Customer restore(
            CustomerId id,
            TenantId tenantId,
            String externalRef,
            String displayName,
            String phone,
            String phoneDigits,
            String email,
            Map<String, Object> fields,
            Set<String> tags,
            CustomerStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new Customer(
                id, tenantId, externalRef, displayName, phone, phoneDigits, email, fields, tags, status, createdAt,
                updatedAt, version);
    }

    /**
     * Replaces the attributes, the field values and the tags with what the caller sent.
     *
     * @return whether anything changed, because a body identical to what is stored must bump no version and announce
     *     nothing
     */
    public boolean replace(
            String newDisplayName,
            String newPhone,
            String newPhoneDigits,
            String newEmail,
            Map<String, Object> newFields,
            Set<String> newTags,
            Instant now) {
        Map<String, Object> storedFields = copyOfFields(newFields);
        Set<String> storedTags = copyOfTags(newTags);
        if (Objects.equals(displayName, newDisplayName)
                && Objects.equals(phone, newPhone)
                && Objects.equals(phoneDigits, newPhoneDigits)
                && Objects.equals(email, newEmail)
                && fields.equals(storedFields)
                && tags.equals(storedTags)) {
            return false;
        }
        this.displayName = newDisplayName;
        this.phone = newPhone;
        this.phoneDigits = newPhoneDigits;
        this.email = newEmail;
        this.fields = storedFields;
        this.tags = storedTags;
        this.updatedAt = now;
        this.version++;
        return true;
    }

    /**
     * Erases the person this row described: every attribute, the field values and the tags are cleared, the status
     * becomes {@code ERASED}, the reference becomes the anonymous marker and the version rises, because a caller that
     * pinned the customer is looking at a different customer now.
     *
     * <p>Only the transition to {@code ERASED} is idempotent: erasing an erased customer changes nothing and returns
     * false, so a retried call answers with what the first one left behind.
     *
     * @return whether the customer was erased by this call
     */
    public boolean erase(Instant now) {
        if (isErased()) {
            return false;
        }
        this.externalRef = ERASED_REF_PREFIX + id;
        this.displayName = null;
        this.phone = null;
        this.phoneDigits = null;
        this.email = null;
        this.fields = Map.of();
        this.tags = Set.of();
        this.status = CustomerStatus.ERASED;
        this.updatedAt = now;
        this.version++;
        return true;
    }

    /** The announcement of this customer's creation, ready for the outbox. */
    public CustomerEvents.Created created(UUID eventId) {
        return new CustomerEvents.Created(eventId, tenantId, id, externalRef, createdAt);
    }

    /** The announcement that this customer's personal data was erased, ready for the outbox. */
    public CustomerEvents.Erased erased(UUID eventId) {
        return new CustomerEvents.Erased(eventId, tenantId, id, updatedAt);
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

    /** The customer's field values, keyed by field-definition key; a key it has no value for is absent. */
    public Map<String, Object> fields() {
        return fields;
    }

    /** The customer's tags, in tag order. */
    public Set<String> tags() {
        return tags;
    }

    /** The tags as a list, for a representation that carries them in a stable order. */
    public List<String> tagList() {
        return List.copyOf(tags);
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

    private static Map<String, Object> copyOfFields(Map<String, Object> fields) {
        return fields == null ? Map.of() : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
    }

    private static Set<String> copyOfTags(Set<String> tags) {
        return tags == null
                ? Set.of()
                : Collections.unmodifiableSortedSet(new TreeSet<>(tags));
    }
}
