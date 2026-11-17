package dev.ngb.workflow.runtime.adapter.runtime.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_WAIT_SUBSCRIPTION;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.runtime.application.runtime.port.WaitSubscriptionRepository;
import dev.ngb.workflow.runtime.domain.wait.WaitSubscription;
import dev.ngb.workflow_jooq.tables.records.WorkflowWaitSubscriptionRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/** The wait subscriptions; an arriving event looks them up by source and type. */
public class JooqWaitSubscriptionRepository implements WaitSubscriptionRepository {

    private final DSLContext dsl;

    public JooqWaitSubscriptionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void add(WaitSubscription subscription) {
        dsl.insertInto(WORKFLOW_WAIT_SUBSCRIPTION)
                .set(WORKFLOW_WAIT_SUBSCRIPTION.ID, subscription.id())
                .set(WORKFLOW_WAIT_SUBSCRIPTION.TENANT_ID, subscription.tenantId().value())
                .set(WORKFLOW_WAIT_SUBSCRIPTION.INSTANCE_ID, subscription.instanceId())
                .set(WORKFLOW_WAIT_SUBSCRIPTION.TOKEN_ID, subscription.tokenId())
                .set(WORKFLOW_WAIT_SUBSCRIPTION.SOURCE, subscription.source())
                .set(WORKFLOW_WAIT_SUBSCRIPTION.EVENT_TYPE, subscription.eventType())
                .set(WORKFLOW_WAIT_SUBSCRIPTION.REGISTERED_AT, subscription.registeredAt())
                .set(WORKFLOW_WAIT_SUBSCRIPTION.EXPIRES_AT, subscription.expiresAt())
                .execute();
    }

    @Override
    public List<WaitSubscription> matching(String source, String eventType, Instant now) {
        return dsl.selectFrom(WORKFLOW_WAIT_SUBSCRIPTION)
                .where(WORKFLOW_WAIT_SUBSCRIPTION.SOURCE.in(source, WaitSubscription.ANY_SOURCE))
                .and(WORKFLOW_WAIT_SUBSCRIPTION.EVENT_TYPE.eq(eventType))
                .and(WORKFLOW_WAIT_SUBSCRIPTION.RESOLVED_AT.isNull())
                .and(WORKFLOW_WAIT_SUBSCRIPTION.EXPIRES_AT.gt(now))
                .orderBy(WORKFLOW_WAIT_SUBSCRIPTION.REGISTERED_AT)
                .fetch(row -> toDomain(row));
    }

    @Override
    public boolean resolve(UUID subscriptionId, Instant now) {
        return dsl.update(WORKFLOW_WAIT_SUBSCRIPTION)
                        .set(WORKFLOW_WAIT_SUBSCRIPTION.RESOLVED_AT, now)
                        .where(WORKFLOW_WAIT_SUBSCRIPTION.ID.eq(subscriptionId))
                        .and(WORKFLOW_WAIT_SUBSCRIPTION.RESOLVED_AT.isNull())
                        .execute()
                == 1;
    }

    @Override
    public int resolveForToken(UUID tokenId, Instant now) {
        return dsl.update(WORKFLOW_WAIT_SUBSCRIPTION)
                .set(WORKFLOW_WAIT_SUBSCRIPTION.RESOLVED_AT, now)
                .where(WORKFLOW_WAIT_SUBSCRIPTION.TOKEN_ID.eq(tokenId))
                .and(WORKFLOW_WAIT_SUBSCRIPTION.RESOLVED_AT.isNull())
                .execute();
    }

    private static WaitSubscription toDomain(WorkflowWaitSubscriptionRecord row) {
        return new WaitSubscription(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getInstanceId(),
                row.getTokenId(),
                row.getSource(),
                row.getEventType(),
                row.getRegisteredAt(),
                row.getExpiresAt(),
                row.getResolvedAt());
    }
}
