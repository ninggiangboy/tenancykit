package dev.ngb.crm.activity.adapter.activity.persistence;

import static dev.ngb.crm_jooq.Tables.ACTIVITIES;

import dev.ngb.crm.customers.api.customer.CustomerErasureParticipant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;

/**
 * The activity context's part of a customer erasure: an activity stays as a typed, timed marker that something
 * happened, and loses the payload, the source and the caller's reference, which are where a person's data ends up.
 *
 * <p>It runs inside the erasure's transaction, so a customer is never left erased while its activities still carry the
 * person, and it reaches the table it owns rather than having another context reach in.
 */
public class JooqActivityErasure implements CustomerErasureParticipant {

    private static final String EMPTY_PAYLOAD = "{}";

    private final DSLContext dsl;

    public JooqActivityErasure(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void erase(UUID customerId) {
        dsl.update(ACTIVITIES)
                .set(ACTIVITIES.PAYLOAD, JSONB.valueOf(EMPTY_PAYLOAD))
                .set(ACTIVITIES.SOURCE, (String) null)
                .set(ACTIVITIES.ACTIVITY_REF, (String) null)
                .where(ACTIVITIES.CUSTOMER_ID.eq(customerId))
                .execute();
    }
}
