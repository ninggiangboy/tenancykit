package dev.ngb.booking.catalog.adapter.group.persistence;

import static dev.ngb.booking_jooq.Tables.GROUP_MEMBERS;
import static dev.ngb.booking_jooq.Tables.RESOURCE_GROUPS;
import static dev.ngb.booking_jooq.Tables.RESOURCES;

import dev.ngb.booking.catalog.application.group.port.GroupRepository;
import dev.ngb.booking.catalog.domain.group.GroupId;
import dev.ngb.booking.catalog.domain.group.GroupMember;
import dev.ngb.booking.catalog.domain.group.GroupStrategy;
import dev.ngb.booking.catalog.domain.group.ResourceGroup;
import dev.ngb.booking_jooq.tables.records.ResourceGroupsRecord;
import dev.ngb.core.id.TenantId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Stores resource groups with their members. The unique key of a group is the Tenant's code; a write replaces the
 * member set in full, because a caller always states it by the time it is stored.
 *
 * <p>Every statement is tenant-scoped by row-level security rather than by a condition here, so a missing tenant scope
 * reads nothing instead of reading every tenant's groups.
 */
public class JooqGroupRepository implements GroupRepository {

    private static final String ACTIVE = "ACTIVE";

    private final DSLContext dsl;

    public JooqGroupRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<ResourceGroup> findById(UUID id) {
        return dsl.selectFrom(RESOURCE_GROUPS).where(RESOURCE_GROUPS.ID.eq(id)).fetchOptional(this::toDomain);
    }

    @Override
    public Optional<ResourceGroup> lockById(UUID id) {
        return dsl.selectFrom(RESOURCE_GROUPS)
                .where(RESOURCE_GROUPS.ID.eq(id))
                .forUpdate()
                .fetchOptional(this::toDomain);
    }

    @Override
    public boolean existsByCode(String code) {
        return dsl.fetchCount(RESOURCE_GROUPS, RESOURCE_GROUPS.CODE.eq(code)) > 0;
    }

    @Override
    public void insert(ResourceGroup group) {
        dsl.insertInto(RESOURCE_GROUPS)
                .set(RESOURCE_GROUPS.TENANT_ID, group.tenantId().value())
                .set(RESOURCE_GROUPS.ID, group.id().value())
                .set(RESOURCE_GROUPS.CODE, group.code())
                .set(RESOURCE_GROUPS.NAME, group.name())
                .set(RESOURCE_GROUPS.STRATEGY, group.strategy().name())
                .set(RESOURCE_GROUPS.MAX_COMBINE, group.maxCombine())
                .set(RESOURCE_GROUPS.CURSOR, group.cursor())
                .set(RESOURCE_GROUPS.VERSION, group.version())
                .set(RESOURCE_GROUPS.CREATED_AT, group.createdAt())
                .set(RESOURCE_GROUPS.UPDATED_AT, group.updatedAt())
                .execute();
        storeMembers(group);
    }

    @Override
    public void update(ResourceGroup group) {
        int updated = dsl.update(RESOURCE_GROUPS)
                .set(RESOURCE_GROUPS.NAME, group.name())
                .set(RESOURCE_GROUPS.STRATEGY, group.strategy().name())
                .set(RESOURCE_GROUPS.MAX_COMBINE, group.maxCombine())
                .set(RESOURCE_GROUPS.CURSOR, group.cursor())
                .set(RESOURCE_GROUPS.UPDATED_AT, group.updatedAt())
                .set(RESOURCE_GROUPS.VERSION, group.version())
                .where(RESOURCE_GROUPS.ID.eq(group.id().value()))
                .and(RESOURCE_GROUPS.VERSION.lt(group.version()))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException(
                    "group " + group.id() + " was not updated: version " + group.version() + " is not newer");
        }
        storeMembers(group);
    }

    @Override
    public boolean resourcesExistAndAreActive(Collection<UUID> resourceIds) {
        if (resourceIds.isEmpty()) {
            return true;
        }
        int active = dsl.fetchCount(
                RESOURCES, RESOURCES.ID.in(resourceIds).and(RESOURCES.STATUS.eq(ACTIVE)));
        return active == resourceIds.size();
    }

    /** The member set of one group: what it holds after this write, and nothing it held before. */
    private void storeMembers(ResourceGroup group) {
        dsl.deleteFrom(GROUP_MEMBERS).where(GROUP_MEMBERS.GROUP_ID.eq(group.id().value())).execute();
        if (!group.members().isEmpty()) {
            var insert = dsl.insertInto(GROUP_MEMBERS)
                    .columns(
                            GROUP_MEMBERS.TENANT_ID,
                            GROUP_MEMBERS.GROUP_ID,
                            GROUP_MEMBERS.RESOURCE_ID,
                            GROUP_MEMBERS.PRIORITY);
            for (GroupMember member : group.members()) {
                insert = insert.values(
                        group.tenantId().value(), group.id().value(), member.resourceId(), member.priority());
            }
            insert.execute();
        }
    }

    private ResourceGroup toDomain(ResourceGroupsRecord row) {
        return ResourceGroup.restore(
                new GroupId(row.getId()),
                new TenantId(row.getTenantId()),
                row.getCode(),
                row.getName(),
                GroupStrategy.valueOf(row.getStrategy()),
                row.getMaxCombine(),
                membersOf(row.getId()),
                row.getCursor(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }

    private List<GroupMember> membersOf(UUID groupId) {
        return dsl.select(GROUP_MEMBERS.RESOURCE_ID, GROUP_MEMBERS.PRIORITY)
                .from(GROUP_MEMBERS)
                .where(GROUP_MEMBERS.GROUP_ID.eq(groupId))
                .orderBy(GROUP_MEMBERS.PRIORITY, GROUP_MEMBERS.RESOURCE_ID)
                .fetch(row -> new GroupMember(row.value1(), row.value2()));
    }
}
