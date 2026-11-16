package dev.ngb.booking.catalog.domain.resource;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Objects;

/**
 * One unit a Tenant may book: the code it chose, the name it shows, the IANA zone its wall-clock hours are read in, and
 * optionally how many seats it holds.
 *
 * <p>The code and the zone are fixed once chosen, because a booking that meant a time in one zone would silently mean
 * another if the zone moved. What may change is the name, the seat count and whether the resource is archived. The
 * version is the ETag: a change moves the instant and bumps the version, and a body identical to what is stored
 * changes nothing at all.
 */
public final class Resource {

    private final ResourceId id;
    private final TenantId tenantId;
    private final String code;
    private final String zone;
    private final Instant createdAt;
    private String name;
    private Integer seats;
    private ResourceStatus status;
    private Instant updatedAt;
    private long version;

    private Resource(
            ResourceId id,
            TenantId tenantId,
            String code,
            String name,
            String zone,
            Integer seats,
            ResourceStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.code = Objects.requireNonNull(code, "code");
        this.name = Objects.requireNonNull(name, "name");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.seats = seats;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** A resource that did not exist until now; active from the moment it is created. */
    public static Resource create(
            ResourceId id,
            TenantId tenantId,
            String code,
            String name,
            String zone,
            Integer seats,
            Instant now) {
        return new Resource(id, tenantId, code, name, zone, seats, ResourceStatus.ACTIVE, now, now, 1);
    }

    /** The resource a repository reads back from storage. */
    public static Resource restore(
            ResourceId id,
            TenantId tenantId,
            String code,
            String name,
            String zone,
            Integer seats,
            ResourceStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        return new Resource(id, tenantId, code, name, zone, seats, status, createdAt, updatedAt, version);
    }

    /** @return whether the name changed, so an identical patch writes nothing */
    public boolean rename(String newName) {
        if (newName == null || newName.equals(name)) {
            return false;
        }
        this.name = newName;
        return true;
    }

    /** @return whether the seat count changed, so an identical patch writes nothing */
    public boolean resize(Integer newSeats) {
        if (Objects.equals(seats, newSeats)) {
            return false;
        }
        this.seats = newSeats;
        return true;
    }

    /**
     * Archives the resource, which keeps its history and refuses new bookings.
     *
     * @return whether this call archived it; archiving an archived resource changes nothing
     */
    public boolean archive() {
        if (isArchived()) {
            return false;
        }
        this.status = ResourceStatus.ARCHIVED;
        return true;
    }

    public boolean isArchived() {
        return status == ResourceStatus.ARCHIVED;
    }

    /** Moves the update instant and bumps the version, which a caller that changed a field calls once. */
    public boolean touch(Instant now) {
        this.updatedAt = now;
        this.version++;
        return true;
    }

    public ResourceId id() {
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

    /** The IANA zone the resource's wall-clock hours are read in. */
    public String zone() {
        return zone;
    }

    /** How many seats the resource holds, or null when it is not seat-limited. */
    public Integer seats() {
        return seats;
    }

    public ResourceStatus status() {
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
