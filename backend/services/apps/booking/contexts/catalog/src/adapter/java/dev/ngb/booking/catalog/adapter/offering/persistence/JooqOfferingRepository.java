package dev.ngb.booking.catalog.adapter.offering.persistence;

import static dev.ngb.booking_jooq.Tables.CANCELLATION_WINDOWS;
import static dev.ngb.booking_jooq.Tables.OFFERINGS;
import static dev.ngb.booking_jooq.Tables.OFFERING_REQUIREMENTS;
import static dev.ngb.booking_jooq.Tables.RESOURCE_GROUPS;
import static dev.ngb.booking_jooq.Tables.RESOURCES;

import dev.ngb.booking.catalog.application.offering.port.OfferingRepository;
import dev.ngb.booking.catalog.domain.offering.CancellationWindow;
import dev.ngb.booking.catalog.domain.offering.Offering;
import dev.ngb.booking.catalog.domain.offering.OfferingId;
import dev.ngb.booking.catalog.domain.offering.OfferingKind;
import dev.ngb.booking.catalog.domain.offering.OfferingStatus;
import dev.ngb.booking.catalog.domain.offering.QueueMode;
import dev.ngb.booking.catalog.domain.offering.Requirement;
import dev.ngb.booking_jooq.tables.records.OfferingsRecord;
import dev.ngb.core.id.TenantId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Stores offerings with their requirements and cancellation thresholds. The unique key of an offering is the Tenant's
 * code; a write replaces both child sets in full, because a caller states them by the time it is stored.
 *
 * <p>Every statement is tenant-scoped by row-level security rather than by a condition here, so a missing tenant scope
 * reads nothing instead of reading every tenant's offerings.
 */
public class JooqOfferingRepository implements OfferingRepository {

    private static final String ACTIVE = "ACTIVE";

    private final DSLContext dsl;

    public JooqOfferingRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Offering> findById(UUID id) {
        return dsl.selectFrom(OFFERINGS).where(OFFERINGS.ID.eq(id)).fetchOptional(this::toDomain);
    }

    @Override
    public Optional<Offering> lockById(UUID id) {
        return dsl.selectFrom(OFFERINGS).where(OFFERINGS.ID.eq(id)).forUpdate().fetchOptional(this::toDomain);
    }

    @Override
    public boolean existsByCode(String code) {
        return dsl.fetchCount(OFFERINGS, OFFERINGS.CODE.eq(code)) > 0;
    }

    @Override
    public void insert(Offering offering) {
        dsl.insertInto(OFFERINGS)
                .set(OFFERINGS.TENANT_ID, offering.tenantId().value())
                .set(OFFERINGS.ID, offering.id().value())
                .set(OFFERINGS.CODE, offering.code())
                .set(OFFERINGS.NAME, offering.name())
                .set(OFFERINGS.KIND, offering.kind().name())
                .set(OFFERINGS.STATUS, offering.status().name())
                .set(OFFERINGS.DURATION_MIN, offering.durationMin())
                .set(OFFERINGS.DURATION_MAX, offering.durationMax())
                .set(OFFERINGS.DURATION_STEP, offering.durationStep())
                .set(OFFERINGS.SLOT_STEP, offering.slotStep())
                .set(OFFERINGS.BUFFER_BEFORE, offering.bufferBefore())
                .set(OFFERINGS.BUFFER_AFTER, offering.bufferAfter())
                .set(OFFERINGS.LEAD_TIME_SECONDS, offering.leadTimeSeconds())
                .set(OFFERINGS.HORIZON_DAYS, offering.horizonDays())
                .set(OFFERINGS.HOLD_TTL_SECONDS, offering.holdTtlSeconds())
                .set(OFFERINGS.MAX_QTY, offering.maxQty())
                .set(OFFERINGS.PUBLIC_SHOWS_REMAINING, offering.publicShowsRemaining())
                .set(OFFERINGS.QUEUE_MODE, offering.queueMode().name())
                .set(OFFERINGS.ADMIT_PER_SECOND, offering.admitPerSecond())
                .set(OFFERINGS.ADMIT_WINDOW_SECONDS, offering.admitWindowSeconds())
                .set(OFFERINGS.VERSION, offering.version())
                .set(OFFERINGS.CREATED_AT, offering.createdAt())
                .set(OFFERINGS.UPDATED_AT, offering.updatedAt())
                .execute();
        storeChildren(offering);
    }

    @Override
    public void update(Offering offering) {
        int updated = dsl.update(OFFERINGS)
                .set(OFFERINGS.NAME, offering.name())
                .set(OFFERINGS.STATUS, offering.status().name())
                .set(OFFERINGS.DURATION_MIN, offering.durationMin())
                .set(OFFERINGS.DURATION_MAX, offering.durationMax())
                .set(OFFERINGS.DURATION_STEP, offering.durationStep())
                .set(OFFERINGS.SLOT_STEP, offering.slotStep())
                .set(OFFERINGS.BUFFER_BEFORE, offering.bufferBefore())
                .set(OFFERINGS.BUFFER_AFTER, offering.bufferAfter())
                .set(OFFERINGS.LEAD_TIME_SECONDS, offering.leadTimeSeconds())
                .set(OFFERINGS.HORIZON_DAYS, offering.horizonDays())
                .set(OFFERINGS.HOLD_TTL_SECONDS, offering.holdTtlSeconds())
                .set(OFFERINGS.MAX_QTY, offering.maxQty())
                .set(OFFERINGS.PUBLIC_SHOWS_REMAINING, offering.publicShowsRemaining())
                .set(OFFERINGS.QUEUE_MODE, offering.queueMode().name())
                .set(OFFERINGS.ADMIT_PER_SECOND, offering.admitPerSecond())
                .set(OFFERINGS.ADMIT_WINDOW_SECONDS, offering.admitWindowSeconds())
                .set(OFFERINGS.UPDATED_AT, offering.updatedAt())
                .set(OFFERINGS.VERSION, offering.version())
                .where(OFFERINGS.ID.eq(offering.id().value()))
                .and(OFFERINGS.VERSION.lt(offering.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException(
                    "offering " + offering.id() + " was not updated: version " + offering.version() + " is not newer");
        }
        storeChildren(offering);
    }

    @Override
    public void archive(Offering offering) {
        int archived = dsl.update(OFFERINGS)
                .set(OFFERINGS.STATUS, offering.status().name())
                .set(OFFERINGS.UPDATED_AT, offering.updatedAt())
                .set(OFFERINGS.VERSION, offering.version())
                .where(OFFERINGS.ID.eq(offering.id().value()))
                .and(OFFERINGS.VERSION.lt(offering.version()))
                .execute();
        if (archived != 1) {
            throw new IllegalStateException(
                    "offering " + offering.id() + " was not archived: version " + offering.version() + " is not newer");
        }
    }

    @Override
    public boolean resourcesExistAndAreActive(Collection<UUID> resourceIds) {
        if (resourceIds.isEmpty()) {
            return true;
        }
        int active = dsl.fetchCount(RESOURCES, RESOURCES.ID.in(resourceIds).and(RESOURCES.STATUS.eq(ACTIVE)));
        return active == resourceIds.size();
    }

    @Override
    public boolean groupsExist(Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return true;
        }
        return dsl.fetchCount(RESOURCE_GROUPS, RESOURCE_GROUPS.ID.in(groupIds)) == groupIds.size();
    }

    /** The requirement and threshold sets of one offering: what it holds after this write, and nothing it held before. */
    private void storeChildren(Offering offering) {
        dsl.deleteFrom(OFFERING_REQUIREMENTS)
                .where(OFFERING_REQUIREMENTS.OFFERING_ID.eq(offering.id().value()))
                .execute();
        if (!offering.requirements().isEmpty()) {
            var insert = dsl.insertInto(OFFERING_REQUIREMENTS)
                    .columns(
                            OFFERING_REQUIREMENTS.TENANT_ID,
                            OFFERING_REQUIREMENTS.OFFERING_ID,
                            OFFERING_REQUIREMENTS.SLOT_CODE,
                            OFFERING_REQUIREMENTS.RESOURCE_ID,
                            OFFERING_REQUIREMENTS.GROUP_ID);
            for (Requirement requirement : offering.requirements()) {
                insert = insert.values(
                        offering.tenantId().value(),
                        offering.id().value(),
                        requirement.slotCode(),
                        requirement.resourceId(),
                        requirement.groupId());
            }
            insert.execute();
        }
        dsl.deleteFrom(CANCELLATION_WINDOWS)
                .where(CANCELLATION_WINDOWS.OFFERING_ID.eq(offering.id().value()))
                .execute();
        if (!offering.cancellationWindows().isEmpty()) {
            var insert = dsl.insertInto(CANCELLATION_WINDOWS)
                    .columns(
                            CANCELLATION_WINDOWS.TENANT_ID,
                            CANCELLATION_WINDOWS.OFFERING_ID,
                            CANCELLATION_WINDOWS.MINUTES_BEFORE_START,
                            CANCELLATION_WINDOWS.CODE);
            for (CancellationWindow window : offering.cancellationWindows()) {
                insert = insert.values(
                        offering.tenantId().value(),
                        offering.id().value(),
                        window.minutesBeforeStart(),
                        window.code());
            }
            insert.execute();
        }
    }

    private Offering toDomain(OfferingsRecord row) {
        return Offering.restore(
                new OfferingId(row.getId()),
                new TenantId(row.getTenantId()),
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
                requirementsOf(row.getId()),
                windowsOf(row.getId()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }

    private List<Requirement> requirementsOf(UUID offeringId) {
        return dsl.select(
                        OFFERING_REQUIREMENTS.SLOT_CODE,
                        OFFERING_REQUIREMENTS.RESOURCE_ID,
                        OFFERING_REQUIREMENTS.GROUP_ID)
                .from(OFFERING_REQUIREMENTS)
                .where(OFFERING_REQUIREMENTS.OFFERING_ID.eq(offeringId))
                .orderBy(OFFERING_REQUIREMENTS.SLOT_CODE)
                .fetch(row -> new Requirement(row.value1(), row.value2(), row.value3()));
    }

    private List<CancellationWindow> windowsOf(UUID offeringId) {
        return dsl.select(CANCELLATION_WINDOWS.MINUTES_BEFORE_START, CANCELLATION_WINDOWS.CODE)
                .from(CANCELLATION_WINDOWS)
                .where(CANCELLATION_WINDOWS.OFFERING_ID.eq(offeringId))
                .orderBy(CANCELLATION_WINDOWS.MINUTES_BEFORE_START.desc())
                .fetch(row -> new CancellationWindow(row.value1(), row.value2()));
    }
}
