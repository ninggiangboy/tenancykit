package dev.ngb.booking.catalog.adapter.resource.persistence;

import static dev.ngb.booking_jooq.Tables.GROUP_MEMBERS;
import static dev.ngb.booking_jooq.Tables.OFFERING_REQUIREMENTS;
import static dev.ngb.booking_jooq.Tables.RESOURCES;

import dev.ngb.booking.catalog.application.resource.port.ResourceRepository;
import dev.ngb.booking.catalog.domain.resource.Resource;
import dev.ngb.booking.catalog.domain.resource.ResourceId;
import dev.ngb.booking.catalog.domain.resource.ResourceStatus;
import dev.ngb.booking_jooq.tables.records.ResourcesRecord;
import dev.ngb.core.id.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Stores resources. The Tenant's code is the natural key, so a create checks it before it inserts, and nothing is ever
 * changed without the version the caller holds being the one the row carries.
 *
 * <p>Every statement is tenant-scoped by row-level security rather than by a condition here, so a missing tenant scope
 * reads nothing instead of reading every tenant's resources. A delete asks the referencing tables whether anything
 * points at the resource, because a referenced resource is archived and never deleted.
 */
public class JooqResourceRepository implements ResourceRepository {

    private final DSLContext dsl;

    public JooqResourceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Resource> findById(UUID id) {
        return dsl.selectFrom(RESOURCES).where(RESOURCES.ID.eq(id)).fetchOptional(JooqResourceRepository::toDomain);
    }

    @Override
    public Optional<Resource> lockById(UUID id) {
        return dsl.selectFrom(RESOURCES)
                .where(RESOURCES.ID.eq(id))
                .forUpdate()
                .fetchOptional(JooqResourceRepository::toDomain);
    }

    @Override
    public boolean existsByCode(String code) {
        return dsl.fetchExists(RESOURCES, RESOURCES.CODE.eq(code));
    }

    @Override
    public void insert(Resource resource) {
        dsl.insertInto(RESOURCES)
                .set(RESOURCES.TENANT_ID, resource.tenantId().value())
                .set(RESOURCES.ID, resource.id().value())
                .set(RESOURCES.CODE, resource.code())
                .set(RESOURCES.NAME, resource.name())
                .set(RESOURCES.ZONE, resource.zone())
                .set(RESOURCES.SEATS, resource.seats())
                .set(RESOURCES.STATUS, resource.status().name())
                .set(RESOURCES.VERSION, resource.version())
                .set(RESOURCES.CREATED_AT, resource.createdAt())
                .set(RESOURCES.UPDATED_AT, resource.updatedAt())
                .execute();
    }

    @Override
    public void update(Resource resource) {
        int updated = dsl.update(RESOURCES)
                .set(RESOURCES.NAME, resource.name())
                .set(RESOURCES.SEATS, resource.seats())
                .set(RESOURCES.STATUS, resource.status().name())
                .set(RESOURCES.VERSION, resource.version())
                .set(RESOURCES.UPDATED_AT, resource.updatedAt())
                .where(RESOURCES.ID.eq(resource.id().value()))
                .and(RESOURCES.VERSION.lt(resource.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException(
                    "resource " + resource.id() + " was not updated: version " + resource.version() + " is not newer");
        }
    }

    @Override
    public void archive(Resource resource) {
        int archived = dsl.update(RESOURCES)
                .set(RESOURCES.STATUS, resource.status().name())
                .set(RESOURCES.VERSION, resource.version())
                .set(RESOURCES.UPDATED_AT, resource.updatedAt())
                .where(RESOURCES.ID.eq(resource.id().value()))
                .and(RESOURCES.VERSION.lt(resource.version()))
                .execute();
        if (archived != 1) {
            throw new IllegalStateException(
                    "resource " + resource.id() + " was not archived: version " + resource.version() + " is not newer");
        }
    }

    @Override
    public boolean deleteIfUnreferenced(UUID id) {
        return dsl.deleteFrom(RESOURCES)
                        .where(RESOURCES.ID.eq(id))
                        .and(unreferenced(id))
                        .execute()
                == 1;
    }

    @Override
    public boolean isReferenced(UUID id) {
        return dsl.fetchExists(OFFERING_REQUIREMENTS, OFFERING_REQUIREMENTS.RESOURCE_ID.eq(id))
                || dsl.fetchExists(GROUP_MEMBERS, GROUP_MEMBERS.RESOURCE_ID.eq(id));
    }

    private Condition unreferenced(UUID id) {
        return DSL.notExists(
                        dsl.selectOne().from(OFFERING_REQUIREMENTS).where(OFFERING_REQUIREMENTS.RESOURCE_ID.eq(id)))
                .and(DSL.notExists(
                        dsl.selectOne().from(GROUP_MEMBERS).where(GROUP_MEMBERS.RESOURCE_ID.eq(id))));
    }

    private static Resource toDomain(ResourcesRecord row) {
        return Resource.restore(
                new ResourceId(row.getId()),
                new TenantId(row.getTenantId()),
                row.getCode(),
                row.getName(),
                row.getZone(),
                row.getSeats(),
                ResourceStatus.valueOf(row.getStatus()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }
}
