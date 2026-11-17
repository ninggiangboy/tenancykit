package dev.ngb.booking.booking.adapter.booking.persistence;

import static dev.ngb.booking_jooq.Tables.BOOKINGS;
import static dev.ngb.booking_jooq.Tables.BOOKING_RESOURCES;

import dev.ngb.booking.booking.application.booking.query.AssignedResourceView;
import dev.ngb.booking.booking.application.booking.query.BookingListing;
import dev.ngb.booking.booking.application.booking.query.BookingReadRepository;
import dev.ngb.booking.booking.application.booking.query.BookingView;
import dev.ngb.booking_jooq.tables.records.BookingsRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Selects bookings straight into their views; row-level security narrows every statement to the current tenant.
 *
 * <p>A page's resources are read in one statement for the whole page rather than one per booking, so the list stays
 * one query plus one lookup whatever the page size.
 */
public class JooqBookingReadRepository implements BookingReadRepository {

    /** The booking fields a caller may narrow and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(BookingListing.SCHEMA)
            .column("id", BOOKINGS.ID)
            .column("status", BOOKINGS.STATUS)
            .column("offering_id", BOOKINGS.OFFERING_ID)
            .column("customer_ref", BOOKINGS.CUSTOMER_REF)
            .column("source", BOOKINGS.SOURCE)
            .column("starts_at", BOOKINGS.STARTS_AT)
            .column("created_at", BOOKINGS.CREATED_AT)
            .column("updated_at", BOOKINGS.UPDATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the booking listing, for a test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqBookingReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<BookingView> list(ListQuery query) {
        return JooqLists.fetchPage(dsl, BOOKINGS, DSL.noCondition(), BINDING, query, this::toViews);
    }

    @Override
    public Optional<BookingView> findById(UUID bookingId) {
        return dsl.selectFrom(BOOKINGS)
                .where(BOOKINGS.ID.eq(bookingId))
                .fetchOptional(row -> toView(
                        row, resourcesOf(List.of(bookingId)).getOrDefault(bookingId, List.of())));
    }

    private List<BookingView> toViews(List<BookingsRecord> rows) {
        List<UUID> ids = rows.stream().map(BookingsRecord::getId).toList();
        Map<UUID, List<AssignedResourceView>> resources = resourcesOf(ids);
        List<BookingView> views = new ArrayList<>(rows.size());
        for (BookingsRecord row : rows) {
            views.add(toView(row, resources.getOrDefault(row.getId(), List.of())));
        }
        return views;
    }

    private static BookingView toView(BookingsRecord row, List<AssignedResourceView> resources) {
        return new BookingView(
                row.getId(),
                row.getStatus(),
                row.getOfferingId(),
                row.getOfferingKind(),
                row.getCustomerRef(),
                row.getQty(),
                row.getSource(),
                row.getSessionId(),
                row.getPoolCode(),
                row.getStartsAt(),
                row.getEndsAt(),
                resources,
                row.getSeriesId(),
                row.getHoldExpiresAt(),
                row.getExtendedSeconds(),
                row.getConfirmedAt(),
                row.getCancelledAt(),
                row.getPolicyCode(),
                row.getVersion());
    }

    private Map<UUID, List<AssignedResourceView>> resourcesOf(Collection<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<AssignedResourceView>> byBooking = new HashMap<>();
        dsl.select(BOOKING_RESOURCES.BOOKING_ID, BOOKING_RESOURCES.SLOT_CODE, BOOKING_RESOURCES.RESOURCE_ID)
                .from(BOOKING_RESOURCES)
                .where(BOOKING_RESOURCES.BOOKING_ID.in(bookingIds))
                .orderBy(BOOKING_RESOURCES.BOOKING_ID, BOOKING_RESOURCES.SLOT_CODE)
                .fetch()
                .forEach(row -> byBooking
                        .computeIfAbsent(row.get(BOOKING_RESOURCES.BOOKING_ID), id -> new ArrayList<>())
                        .add(new AssignedResourceView(
                                row.get(BOOKING_RESOURCES.SLOT_CODE), row.get(BOOKING_RESOURCES.RESOURCE_ID))));
        return byBooking;
    }
}
