package dev.ngb.booking.booking.adapter.series.persistence;

import static dev.ngb.booking_jooq.Tables.BOOKING_SERIES;

import dev.ngb.booking.booking.application.booking.query.BookingReadRepository;
import dev.ngb.booking.booking.application.series.query.SeriesReadRepository;
import dev.ngb.booking.booking.application.series.query.SeriesView;
import dev.ngb.booking_jooq.tables.records.BookingSeriesRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Selects series straight into their views; the occurrences come from the booking read repository, so the list stays
 * two queries rather than one per series.
 */
public class JooqSeriesReadRepository implements SeriesReadRepository {

    private final DSLContext dsl;
    private final BookingReadRepository bookings;

    public JooqSeriesReadRepository(DSLContext dsl, BookingReadRepository bookings) {
        this.dsl = dsl;
        this.bookings = bookings;
    }

    @Override
    public List<SeriesView> list() {
        return dsl.selectFrom(BOOKING_SERIES)
                .orderBy(BOOKING_SERIES.CREATED_AT.desc(), BOOKING_SERIES.ID.desc())
                .fetch(this::toView);
    }

    @Override
    public Optional<SeriesView> findById(UUID seriesId) {
        return dsl.selectFrom(BOOKING_SERIES)
                .where(BOOKING_SERIES.ID.eq(seriesId))
                .fetchOptional(this::toView);
    }

    private SeriesView toView(BookingSeriesRecord row) {
        return new SeriesView(
                row.getId(),
                row.getOfferingId(),
                row.getCustomerRef(),
                row.getMode(),
                row.getStatus(),
                row.getVersion(),
                bookings.listBySeries(row.getId()),
                List.of());
    }
}
