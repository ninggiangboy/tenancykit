package dev.ngb.mailroom.audience.adapter.group.persistence;

import static dev.ngb.mailroom_jooq.Tables.GROUP_MEMBERS;

import dev.ngb.core.id.TenantId;
import dev.ngb.mailroom.audience.application.group.port.MembershipRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/** Group membership. The composite primary key makes re-adding a receiver a no-op. */
public class JooqMembershipRepository implements MembershipRepository {

    private final DSLContext dsl;

    public JooqMembershipRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public int add(TenantId tenantId, UUID groupId, List<UUID> receiverIds, Instant now) {
        if (receiverIds == null || receiverIds.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (UUID receiverId : receiverIds) {
            added += dsl.insertInto(GROUP_MEMBERS)
                    .set(GROUP_MEMBERS.TENANT_ID, tenantId.value())
                    .set(GROUP_MEMBERS.GROUP_ID, groupId)
                    .set(GROUP_MEMBERS.RECEIVER_ID, receiverId)
                    .set(GROUP_MEMBERS.ADDED_AT, now)
                    .onConflict(GROUP_MEMBERS.GROUP_ID, GROUP_MEMBERS.RECEIVER_ID)
                    .doNothing()
                    .execute();
        }
        return added;
    }

    @Override
    public int remove(UUID groupId, List<UUID> receiverIds) {
        if (receiverIds == null || receiverIds.isEmpty()) {
            return 0;
        }
        return dsl.deleteFrom(GROUP_MEMBERS)
                .where(GROUP_MEMBERS.GROUP_ID.eq(groupId))
                .and(GROUP_MEMBERS.RECEIVER_ID.in(receiverIds))
                .execute();
    }
}
