package dev.ngb.crm.activity.adapter.activity.persistence;

import static dev.ngb.crm_jooq.Tables.ACTIVITIES;

import dev.ngb.core.id.TenantId;
import dev.ngb.crm.activity.application.activity.service.ActivityPayloads;
import dev.ngb.crm.activity.application.activity.port.ActivityRepository;
import dev.ngb.crm.activity.domain.activity.Activity;
import dev.ngb.crm.activity.domain.activity.ActivityId;
import dev.ngb.crm_jooq.tables.records.ActivitiesRecord;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;

/**
 * Appends activities. The caller's reference, when it sent one, is a unique key per tenant, so a second insert of the
 * same reference inserts nothing instead of failing and the caller re-reads the row that won.
 *
 * <p>Every statement is narrowed to the current tenant by row-level security rather than by a condition here, so a
 * missing tenant scope reads nothing instead of reading every tenant's activities.
 */
public class JooqActivityRepository implements ActivityRepository {

    private final DSLContext dsl;
    private final ActivityPayloads payloads;

    public JooqActivityRepository(DSLContext dsl, ActivityPayloads payloads) {
        this.dsl = dsl;
        this.payloads = payloads;
    }

    @Override
    public Optional<Activity> findByActivityRef(String activityRef) {
        return dsl.selectFrom(ACTIVITIES)
                .where(ACTIVITIES.ACTIVITY_REF.eq(activityRef))
                .fetchOptional(this::toDomain);
    }

    @Override
    public boolean insertIfAbsent(Activity activity) {
        var insert = dsl.insertInto(ACTIVITIES)
                .set(ACTIVITIES.TENANT_ID, activity.tenantId().value())
                .set(ACTIVITIES.ID, activity.id().value())
                .set(ACTIVITIES.CUSTOMER_ID, activity.customerId())
                .set(ACTIVITIES.TYPE, activity.type())
                .set(ACTIVITIES.OCCURRED_AT, activity.occurredAt())
                .set(ACTIVITIES.SOURCE, activity.source())
                .set(ACTIVITIES.PAYLOAD, JSONB.valueOf(payloads.toJson(activity.payload())))
                .set(ACTIVITIES.ACTIVITY_REF, activity.activityRef())
                .set(ACTIVITIES.CREATED_AT, activity.createdAt());
        if (activity.activityRef() == null) {
            // Without a reference there is no key to conflict on: every call appends one more fact.
            return insert.execute() == 1;
        }
        return insert.onConflict(ACTIVITIES.TENANT_ID, ACTIVITIES.ACTIVITY_REF)
                       .where(ACTIVITIES.ACTIVITY_REF.isNotNull())
                       .doNothing()
                       .execute()
                == 1;
    }

    private Activity toDomain(ActivitiesRecord row) {
        return Activity.restore(
                new ActivityId(row.getId()),
                new TenantId(row.getTenantId()),
                row.getCustomerId(),
                row.getType(),
                row.getOccurredAt(),
                row.getSource(),
                payloads.fromJson(row.getPayload() == null ? null : row.getPayload().data()),
                row.getActivityRef(),
                row.getCreatedAt());
    }
}
