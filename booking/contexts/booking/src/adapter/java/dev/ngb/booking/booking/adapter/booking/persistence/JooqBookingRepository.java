package dev.ngb.booking.booking.adapter.booking.persistence;

import static dev.ngb.booking_jooq.Tables.BOOKINGS;
import static dev.ngb.booking_jooq.Tables.BOOKING_RESOURCES;

import dev.ngb.booking.booking.application.booking.port.BookingRepository;
import dev.ngb.booking.booking.domain.booking.AssignedResource;
import dev.ngb.booking.booking.domain.booking.Booking;
import dev.ngb.booking.booking.domain.booking.BookingId;
import dev.ngb.booking.booking.domain.booking.BookingSource;
import dev.ngb.booking.booking.domain.booking.BookingStatus;
import dev.ngb.booking_jooq.tables.records.BookingsRecord;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Stores the bookings of the current tenant and the resources they took; every statement is tenant-scoped by row-level
 * security.
 *
 * <p>A booking is inserted through the unique idempotency key, so a repeated create inserts nothing rather than racing
 * a read; a change raises the version and the update only reaches a row whose stored version is older, which is what
 * makes a stale writer fail loudly instead of silently overwriting newer state.
 */
public class JooqBookingRepository implements BookingRepository {

    private static final String HELD = BookingStatus.HELD.name();

    private final DSLContext dsl;
    private final TenantContext tenantContext;

    public JooqBookingRepository(DSLContext dsl, TenantContext tenantContext) {
        this.dsl = dsl;
        this.tenantContext = tenantContext;
    }

    @Override
    public Optional<Booking> lockById(UUID bookingId) {
        return dsl.selectFrom(BOOKINGS)
                .where(BOOKINGS.ID.eq(bookingId))
                .forUpdate()
                .fetchOptional(row -> toBooking(row, resourcesOf(bookingId)));
    }

    @Override
    public boolean insertIfAbsent(Booking booking) {
        return dsl.insertInto(BOOKINGS)
                        .set(BOOKINGS.TENANT_ID, booking.tenantId().value())
                        .set(BOOKINGS.ID, booking.id().value())
                        .set(BOOKINGS.OFFERING_ID, booking.offeringId())
                        .set(BOOKINGS.OFFERING_KIND, booking.offeringKind())
                        .set(BOOKINGS.CUSTOMER_REF, booking.customerRef())
                        .set(BOOKINGS.STATUS, booking.status().name())
                        .set(BOOKINGS.QTY, booking.qty())
                        .set(BOOKINGS.SESSION_ID, booking.sessionId())
                        .set(BOOKINGS.POOL_CODE, booking.poolCode())
                        .set(BOOKINGS.STARTS_AT, booking.startsAt())
                        .set(BOOKINGS.ENDS_AT, booking.endsAt())
                        .set(BOOKINGS.SOURCE, booking.source().name())
                        .set(BOOKINGS.HOLD_EXPIRES_AT, booking.holdExpiresAt())
                        .set(BOOKINGS.EXTENDED_SECONDS, booking.extendedSeconds())
                        .set(BOOKINGS.CONFIRMED_AT, booking.confirmedAt())
                        .set(BOOKINGS.CANCELLED_AT, booking.cancelledAt())
                        .set(BOOKINGS.POLICY_CODE, booking.policyCode())
                        .set(BOOKINGS.SERIES_ID, booking.seriesId())
                        .set(BOOKINGS.IDEMPOTENCY_KEY, booking.idempotencyKey())
                        .set(BOOKINGS.REQUEST_FINGERPRINT, booking.requestFingerprint())
                        .set(BOOKINGS.VERSION, booking.version())
                        .set(BOOKINGS.CREATED_AT, booking.createdAt())
                        .set(BOOKINGS.UPDATED_AT, booking.updatedAt())
                        .onConflict(BOOKINGS.TENANT_ID, BOOKINGS.IDEMPOTENCY_KEY)
                        .doNothing()
                        .execute()
                == 1;
    }

    @Override
    public Optional<Booking> findByIdempotencyKey(String idempotencyKey) {
        return dsl.selectFrom(BOOKINGS)
                .where(BOOKINGS.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(row -> toBooking(row, resourcesOf(row.getId())));
    }

    @Override
    public void update(Booking booking) {
        int updated = dsl.update(BOOKINGS)
                .set(BOOKINGS.STATUS, booking.status().name())
                .set(BOOKINGS.HOLD_EXPIRES_AT, booking.holdExpiresAt())
                .set(BOOKINGS.EXTENDED_SECONDS, booking.extendedSeconds())
                .set(BOOKINGS.CONFIRMED_AT, booking.confirmedAt())
                .set(BOOKINGS.CANCELLED_AT, booking.cancelledAt())
                .set(BOOKINGS.POLICY_CODE, booking.policyCode())
                .set(BOOKINGS.VERSION, booking.version())
                .set(BOOKINGS.UPDATED_AT, booking.updatedAt())
                .where(BOOKINGS.ID.eq(booking.id().value()))
                .and(BOOKINGS.VERSION.lt(booking.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("booking " + booking.id() + " was not updated: version "
                    + booking.version() + " is not newer");
        }
    }

    @Override
    public void insertResources(UUID bookingId, List<AssignedResource> resources) {
        UUID tenantId = tenantContext.requireTenant().value();
        for (AssignedResource resource : resources) {
            dsl.insertInto(BOOKING_RESOURCES)
                    .set(BOOKING_RESOURCES.TENANT_ID, tenantId)
                    .set(BOOKING_RESOURCES.BOOKING_ID, bookingId)
                    .set(BOOKING_RESOURCES.SLOT_CODE, resource.slotCode())
                    .set(BOOKING_RESOURCES.RESOURCE_ID, resource.resourceId())
                    .onConflict(
                            BOOKING_RESOURCES.TENANT_ID,
                            BOOKING_RESOURCES.BOOKING_ID,
                            BOOKING_RESOURCES.SLOT_CODE,
                            BOOKING_RESOURCES.RESOURCE_ID)
                    .doNothing()
                    .execute();
        }
    }

    @Override
    public List<AssignedResource> resourcesOf(UUID bookingId) {
        return dsl.select(BOOKING_RESOURCES.SLOT_CODE, BOOKING_RESOURCES.RESOURCE_ID)
                .from(BOOKING_RESOURCES)
                .where(BOOKING_RESOURCES.BOOKING_ID.eq(bookingId))
                .orderBy(BOOKING_RESOURCES.SLOT_CODE)
                .fetch(row -> new AssignedResource(
                        row.get(BOOKING_RESOURCES.SLOT_CODE), row.get(BOOKING_RESOURCES.RESOURCE_ID)));
    }

    @Override
    public List<Booking> lockExpired(Instant cutoff, int limit) {
        List<BookingsRecord> rows = dsl.selectFrom(BOOKINGS)
                .where(BOOKINGS.STATUS.eq(HELD))
                .and(BOOKINGS.HOLD_EXPIRES_AT.lt(cutoff))
                .orderBy(BOOKINGS.HOLD_EXPIRES_AT)
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(BookingsRecord::getId).toList();
        Map<UUID, List<AssignedResource>> resources = resourcesByBooking(ids);
        List<Booking> bookings = new ArrayList<>(rows.size());
        for (BookingsRecord row : rows) {
            bookings.add(toBooking(row, resources.getOrDefault(row.getId(), List.of())));
        }
        return List.copyOf(bookings);
    }

    private Map<UUID, List<AssignedResource>> resourcesByBooking(Collection<UUID> bookingIds) {
        Map<UUID, List<AssignedResource>> byBooking = new HashMap<>();
        dsl.select(BOOKING_RESOURCES.BOOKING_ID, BOOKING_RESOURCES.SLOT_CODE, BOOKING_RESOURCES.RESOURCE_ID)
                .from(BOOKING_RESOURCES)
                .where(BOOKING_RESOURCES.BOOKING_ID.in(bookingIds))
                .orderBy(BOOKING_RESOURCES.BOOKING_ID, BOOKING_RESOURCES.SLOT_CODE)
                .fetch()
                .forEach(row -> byBooking
                        .computeIfAbsent(row.get(BOOKING_RESOURCES.BOOKING_ID), id -> new ArrayList<>())
                        .add(new AssignedResource(
                                row.get(BOOKING_RESOURCES.SLOT_CODE), row.get(BOOKING_RESOURCES.RESOURCE_ID))));
        return byBooking;
    }

    private static Booking toBooking(BookingsRecord row, List<AssignedResource> resources) {
        return Booking.restore(
                new BookingId(row.getId()),
                new TenantId(row.getTenantId()),
                row.getOfferingId(),
                row.getOfferingKind(),
                row.getCustomerRef(),
                BookingStatus.valueOf(row.getStatus()),
                row.getQty(),
                row.getSessionId(),
                row.getPoolCode(),
                row.getStartsAt(),
                row.getEndsAt(),
                BookingSource.valueOf(row.getSource()),
                row.getHoldExpiresAt(),
                row.getExtendedSeconds(),
                row.getConfirmedAt(),
                row.getCancelledAt(),
                row.getPolicyCode(),
                row.getSeriesId(),
                resources,
                row.getIdempotencyKey(),
                row.getRequestFingerprint(),
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
