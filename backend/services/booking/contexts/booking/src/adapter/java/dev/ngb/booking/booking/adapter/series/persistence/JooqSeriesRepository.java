package dev.ngb.booking.booking.adapter.series.persistence;

import static dev.ngb.booking_jooq.Tables.BOOKING_SERIES;

import dev.ngb.booking.booking.application.series.port.SeriesRepository;
import dev.ngb.booking.booking.domain.series.BookingSeries;
import dev.ngb.booking.booking.domain.series.SeriesMode;
import dev.ngb.booking.booking.domain.series.SeriesStatus;
import dev.ngb.booking_jooq.tables.records.BookingSeriesRecord;
import dev.ngb.core.id.TenantId;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** Stores series through jOOQ; row-level security narrows every statement to the current tenant. */
public class JooqSeriesRepository implements SeriesRepository {

    private final DSLContext dsl;

    public JooqSeriesRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(BookingSeries series) {
        dsl.insertInto(BOOKING_SERIES)
                .set(BOOKING_SERIES.TENANT_ID, series.tenantId().value())
                .set(BOOKING_SERIES.ID, series.id())
                .set(BOOKING_SERIES.OFFERING_ID, series.offeringId())
                .set(BOOKING_SERIES.CUSTOMER_REF, series.customerRef())
                .set(BOOKING_SERIES.ZONE, series.zone())
                .set(BOOKING_SERIES.DTSTART, series.dtstart())
                .set(BOOKING_SERIES.RULE, series.rule().toArray(String[]::new))
                .set(BOOKING_SERIES.MODE, series.mode().name())
                .set(BOOKING_SERIES.STATUS, series.status().name())
                .set(BOOKING_SERIES.DURATION_MINUTES, series.durationMinutes())
                .set(BOOKING_SERIES.POOL_CODE, series.poolCode())
                .set(BOOKING_SERIES.VERSION, series.version())
                .set(BOOKING_SERIES.CREATED_AT, series.createdAt())
                .set(BOOKING_SERIES.UPDATED_AT, series.updatedAt())
                .execute();
    }

    @Override
    public Optional<BookingSeries> lockById(UUID seriesId) {
        return dsl.selectFrom(BOOKING_SERIES)
                .where(BOOKING_SERIES.ID.eq(seriesId))
                .forUpdate()
                .fetchOptional(JooqSeriesRepository::toSeries);
    }

    @Override
    public void update(BookingSeries series) {
        int updated = dsl.update(BOOKING_SERIES)
                .set(BOOKING_SERIES.STATUS, series.status().name())
                .set(BOOKING_SERIES.VERSION, series.version())
                .set(BOOKING_SERIES.UPDATED_AT, series.updatedAt())
                .where(BOOKING_SERIES.ID.eq(series.id()))
                .and(BOOKING_SERIES.VERSION.lt(series.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("series " + series.id() + " was not updated: version "
                    + series.version() + " is not newer");
        }
    }

    static BookingSeries toSeries(BookingSeriesRecord row) {
        return BookingSeries.restore(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getOfferingId(),
                row.getCustomerRef(),
                row.getZone(),
                row.getDtstart(),
                Arrays.asList(row.getRule()),
                SeriesMode.valueOf(row.getMode()),
                SeriesStatus.valueOf(row.getStatus()),
                row.getDurationMinutes(),
                row.getPoolCode(),
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
