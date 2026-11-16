package dev.ngb.booking.catalog.adapter.resource.persistence;

import static dev.ngb.booking_jooq.Tables.RESOURCES;

import dev.ngb.booking.catalog.application.resource.query.ResourceListing;
import dev.ngb.booking.catalog.application.resource.query.ResourceReadRepository;
import dev.ngb.booking.catalog.application.resource.query.ResourceView;
import dev.ngb.booking_jooq.tables.records.ResourcesRecord;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** Selects resources straight into the view; row-level security narrows every statement to the current tenant. */
public class JooqResourceReadRepository implements ResourceReadRepository {

    /** The resource fields a caller may narrow and sort on, bound to their columns. */
    private static final ListBinding BINDING = ListBinding.of(ResourceListing.SCHEMA)
            .column("id", RESOURCES.ID)
            .column("code", RESOURCES.CODE)
            .column("name", RESOURCES.NAME)
            .column("zone", RESOURCES.ZONE)
            .column("status", RESOURCES.STATUS)
            .column("seats", RESOURCES.SEATS)
            .column("created_at", RESOURCES.CREATED_AT)
            .column("updated_at", RESOURCES.UPDATED_AT)
            .build();

    private final DSLContext dsl;

    public JooqResourceReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Page<ResourceView> list(ListQuery query) {
        return JooqLists.fetch(
                dsl, RESOURCES, DSL.noCondition(), BINDING, query, JooqResourceReadRepository::toView);
    }

    @Override
    public Optional<ResourceView> findById(UUID id) {
        return dsl.selectFrom(RESOURCES)
                .where(RESOURCES.ID.eq(id))
                .fetchOptional(JooqResourceReadRepository::toView);
    }

    private static ResourceView toView(ResourcesRecord row) {
        return new ResourceView(
                row.getId(),
                row.getCode(),
                row.getName(),
                row.getZone(),
                row.getSeats(),
                row.getStatus(),
                row.getVersion());
    }
}
