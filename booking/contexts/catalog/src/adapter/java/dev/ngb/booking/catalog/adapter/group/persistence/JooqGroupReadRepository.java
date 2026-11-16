package dev.ngb.booking.catalog.adapter.group.persistence;

import static dev.ngb.booking_jooq.Tables.GROUP_MEMBERS;
import static dev.ngb.booking_jooq.Tables.RESOURCE_GROUPS;

import dev.ngb.booking.catalog.application.group.query.GroupMemberView;
import dev.ngb.booking.catalog.application.group.query.GroupReadRepository;
import dev.ngb.booking.catalog.application.group.query.GroupView;
import dev.ngb.booking_jooq.tables.records.ResourceGroupsRecord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Selects groups straight into the view; row-level security narrows every statement to the current tenant.
 *
 * <p>A page's members are read in one statement for the whole page rather than one per group, so the list stays one
 * query plus one lookup whatever the page size.
 */
public class JooqGroupReadRepository implements GroupReadRepository {

    private final DSLContext dsl;

    public JooqGroupReadRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<GroupView> list() {
        List<ResourceGroupsRecord> rows = dsl.selectFrom(RESOURCE_GROUPS)
                .orderBy(RESOURCE_GROUPS.CREATED_AT.desc(), RESOURCE_GROUPS.ID.desc())
                .fetch();
        return toViews(rows);
    }

    @Override
    public Optional<GroupView> findById(UUID id) {
        return dsl.selectFrom(RESOURCE_GROUPS)
                .where(RESOURCE_GROUPS.ID.eq(id))
                .fetchOptional(row -> toView(row, membersOf(List.of(id)).getOrDefault(id, List.of())));
    }

    private List<GroupView> toViews(List<ResourceGroupsRecord> rows) {
        Map<UUID, List<GroupMemberView>> members =
                membersOf(rows.stream().map(ResourceGroupsRecord::getId).toList());
        List<GroupView> views = new ArrayList<>(rows.size());
        for (ResourceGroupsRecord row : rows) {
            views.add(toView(row, members.getOrDefault(row.getId(), List.of())));
        }
        return views;
    }

    private static GroupView toView(ResourceGroupsRecord row, List<GroupMemberView> members) {
        return new GroupView(
                row.getId(),
                row.getCode(),
                row.getName(),
                row.getStrategy(),
                row.getMaxCombine(),
                members,
                row.getVersion());
    }

    private Map<UUID, List<GroupMemberView>> membersOf(Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<GroupMemberView>> byGroup = new HashMap<>();
        dsl.select(GROUP_MEMBERS.GROUP_ID, GROUP_MEMBERS.RESOURCE_ID, GROUP_MEMBERS.PRIORITY)
                .from(GROUP_MEMBERS)
                .where(GROUP_MEMBERS.GROUP_ID.in(groupIds))
                .orderBy(GROUP_MEMBERS.GROUP_ID, GROUP_MEMBERS.PRIORITY, GROUP_MEMBERS.RESOURCE_ID)
                .fetch()
                .forEach(row -> byGroup
                        .computeIfAbsent(row.get(GROUP_MEMBERS.GROUP_ID), id -> new ArrayList<>())
                        .add(new GroupMemberView(
                                row.get(GROUP_MEMBERS.RESOURCE_ID), row.get(GROUP_MEMBERS.PRIORITY))));
        return byGroup;
    }
}
