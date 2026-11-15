package dev.ngb.mailroom.audience.adapter.group.persistence;

import static dev.ngb.mailroom_jooq.Tables.RECEIVER_GROUPS;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailroom.audience.application.group.port.GroupRepository;
import dev.ngb.mailroom.audience.domain.group.ReceiverGroup;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/** The group store. A rename is guarded by the version the caller read. */
public class JooqGroupRepository implements GroupRepository {

    private final DSLContext dsl;

    public JooqGroupRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(ReceiverGroup group) {
        dsl.insertInto(RECEIVER_GROUPS)
                .set(RECEIVER_GROUPS.ID, group.id())
                .set(RECEIVER_GROUPS.TENANT_ID, group.tenantId().value())
                .set(RECEIVER_GROUPS.NAME, group.name())
                .set(RECEIVER_GROUPS.VERSION, group.version())
                .set(RECEIVER_GROUPS.CREATED_AT, group.createdAt())
                .set(RECEIVER_GROUPS.UPDATED_AT, group.updatedAt())
                .execute();
    }

    @Override
    public boolean update(ReceiverGroup group, long expectedVersion) {
        return dsl.update(RECEIVER_GROUPS)
                        .set(RECEIVER_GROUPS.NAME, group.name())
                        .set(RECEIVER_GROUPS.VERSION, group.version())
                        .set(RECEIVER_GROUPS.UPDATED_AT, group.updatedAt())
                        .where(RECEIVER_GROUPS.ID.eq(group.id()))
                        .and(RECEIVER_GROUPS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    @Override
    public boolean delete(UUID groupId) {
        return dsl.deleteFrom(RECEIVER_GROUPS).where(RECEIVER_GROUPS.ID.eq(groupId)).execute() == 1;
    }

    @Override
    public Optional<ReceiverGroup> findById(UUID groupId) {
        return dsl.selectFrom(RECEIVER_GROUPS)
                .where(RECEIVER_GROUPS.ID.eq(groupId))
                .fetchOptional(row -> new ReceiverGroup(
                        row.getId(),
                        new TenantId(row.getTenantId()),
                        row.getName(),
                        row.getVersion(),
                        row.getCreatedAt(),
                        row.getUpdatedAt()));
    }
}
