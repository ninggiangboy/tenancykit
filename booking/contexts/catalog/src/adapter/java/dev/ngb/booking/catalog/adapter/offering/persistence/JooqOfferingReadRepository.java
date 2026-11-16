package dev.ngb.booking.catalog.adapter.offering.persistence;

import static dev.ngb.booking_jooq.Tables.CANCELLATION_WINDOWS;
import static dev.ngb.booking_jooq.Tables.OFFERINGS;
import static dev.ngb.booking_jooq.Tables.OFFERING_REQUIREMENTS;

import dev.ngb.booking.catalog.application.offering.query.CancellationWindowView;
import dev.ngb.booking.catalog.application.offering.query.OfferingListing;
import dev.ngb.booking.catalog.application.offering.query.OfferingReadRepository;
import dev.ngb.booking.catalog.application.offering.query.OfferingView;
import dev.ngb.booking.catalog.application.offering.query.RequirementView;
import dev.ngb.booking.catalog.domain.offering.OfferingKind;
import dev.ngb.booking.catalog.domain.offering.OfferingStatus;
import dev.ngb.booking.catalog.domain.offering.QueueMode;
import dev.ngb.booking_jooq.tables.records.OfferingsRecord;
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
 * Selects offerings straight into the view; row-level security narrows every statement to the current tenant.
 *
 * <p>A page's requirements and cancellation thresholds are read in one statement each for the whole page rather than
 * one per offering, so the list stays one query plus two lookups whatever the page size.
 */
public class JooqOfferingReadRepository implements OfferingReadRepository {

    /** The offering fields a caller may narrow and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(OfferingListing.SCHEMA)
            .column("id", OFFERINGS.ID)
            .column("code", OFFERINGS.CODE)
            .column("name", OFFERINGS.NAME)
            .column("kind", OFFERINGS.KIND)
            .column("status", OFFERINGS.STATUS)
            .column("created_at", OFFERINGS.CREATED_AT)
            .column("updated_at", OFFERINGS.UPDATED_AT)
            .build();

    private final DSLContext dsl;

    /** @return the binding of the offering listing, for a test that checks every sortable field has an index */
    public static ListBinding listBinding() {
        return BINDING;
    }

    public JooqOfferingReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<OfferingView> list(ListQuery query) {
        return JooqLists.fetchPage(dsl, OFFERINGS, DSL.noCondition(), BINDING, query, this::toViews);
    }

    @Override
    public Optional<OfferingView> findById(UUID id) {
        return dsl.selectFrom(OFFERINGS).where(OFFERINGS.ID.eq(id)).fetchOptional(row -> toView(
                row,
                requirementsOf(List.of(id)).getOrDefault(id, List.of()),
                windowsOf(List.of(id)).getOrDefault(id, List.of())));
    }

    private List<OfferingView> toViews(List<OfferingsRecord> rows) {
        List<UUID> ids = rows.stream().map(OfferingsRecord::getId).toList();
        Map<UUID, List<RequirementView>> requirements = requirementsOf(ids);
        Map<UUID, List<CancellationWindowView>> windows = windowsOf(ids);
        List<OfferingView> views = new ArrayList<>(rows.size());
        for (OfferingsRecord row : rows) {
            views.add(toView(
                    row,
                    requirements.getOrDefault(row.getId(), List.of()),
                    windows.getOrDefault(row.getId(), List.of())));
        }
        return views;
    }

    private static OfferingView toView(
            OfferingsRecord row, List<RequirementView> requirements, List<CancellationWindowView> windows) {
        return new OfferingView(
                row.getId(),
                row.getCode(),
                row.getName(),
                OfferingKind.valueOf(row.getKind()),
                OfferingStatus.valueOf(row.getStatus()),
                row.getDurationMin(),
                row.getDurationMax(),
                row.getDurationStep(),
                row.getSlotStep(),
                row.getBufferBefore(),
                row.getBufferAfter(),
                row.getLeadTimeSeconds(),
                row.getHorizonDays(),
                row.getHoldTtlSeconds(),
                row.getMaxQty(),
                row.getPublicShowsRemaining(),
                QueueMode.valueOf(row.getQueueMode()),
                row.getAdmitPerSecond(),
                row.getAdmitWindowSeconds(),
                row.getVersion(),
                requirements,
                windows);
    }

    private Map<UUID, List<RequirementView>> requirementsOf(Collection<UUID> offeringIds) {
        if (offeringIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<RequirementView>> byOffering = new HashMap<>();
        dsl.select(
                        OFFERING_REQUIREMENTS.OFFERING_ID,
                        OFFERING_REQUIREMENTS.SLOT_CODE,
                        OFFERING_REQUIREMENTS.RESOURCE_ID,
                        OFFERING_REQUIREMENTS.GROUP_ID)
                .from(OFFERING_REQUIREMENTS)
                .where(OFFERING_REQUIREMENTS.OFFERING_ID.in(offeringIds))
                .orderBy(OFFERING_REQUIREMENTS.OFFERING_ID, OFFERING_REQUIREMENTS.SLOT_CODE)
                .fetch()
                .forEach(row -> byOffering
                        .computeIfAbsent(row.get(OFFERING_REQUIREMENTS.OFFERING_ID), id -> new ArrayList<>())
                        .add(new RequirementView(
                                row.get(OFFERING_REQUIREMENTS.SLOT_CODE),
                                row.get(OFFERING_REQUIREMENTS.RESOURCE_ID),
                                row.get(OFFERING_REQUIREMENTS.GROUP_ID))));
        return byOffering;
    }

    private Map<UUID, List<CancellationWindowView>> windowsOf(Collection<UUID> offeringIds) {
        if (offeringIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<CancellationWindowView>> byOffering = new HashMap<>();
        dsl.select(
                        CANCELLATION_WINDOWS.OFFERING_ID,
                        CANCELLATION_WINDOWS.MINUTES_BEFORE_START,
                        CANCELLATION_WINDOWS.CODE)
                .from(CANCELLATION_WINDOWS)
                .where(CANCELLATION_WINDOWS.OFFERING_ID.in(offeringIds))
                .orderBy(
                        CANCELLATION_WINDOWS.OFFERING_ID,
                        CANCELLATION_WINDOWS.MINUTES_BEFORE_START.desc())
                .fetch()
                .forEach(row -> byOffering
                        .computeIfAbsent(row.get(CANCELLATION_WINDOWS.OFFERING_ID), id -> new ArrayList<>())
                        .add(new CancellationWindowView(
                                row.get(CANCELLATION_WINDOWS.MINUTES_BEFORE_START),
                                row.get(CANCELLATION_WINDOWS.CODE))));
        return byOffering;
    }
}
