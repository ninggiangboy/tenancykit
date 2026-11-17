package dev.ngb.workflow.triggers.adapter.trigger.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INBOUND_EVENT;

import dev.ngb.workflow.triggers.application.trigger.port.InboundEventRepository;
import dev.ngb.workflow.triggers.domain.event.InboundEvent;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.databind.json.JsonMapper;

/** The event buffer; the unique index is what makes a redelivery a duplicate rather than a second event. */
public class JooqInboundEventRepository implements InboundEventRepository {

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqInboundEventRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public boolean insertIfAbsent(InboundEvent event) {
        return dsl.insertInto(WORKFLOW_INBOUND_EVENT)
                        .set(WORKFLOW_INBOUND_EVENT.ID, event.id())
                        .set(WORKFLOW_INBOUND_EVENT.TENANT_ID, event.tenantId())
                        .set(WORKFLOW_INBOUND_EVENT.SOURCE, event.source())
                        .set(WORKFLOW_INBOUND_EVENT.EVENT_ID, event.eventId())
                        .set(WORKFLOW_INBOUND_EVENT.EVENT_TYPE, event.eventType())
                        .set(WORKFLOW_INBOUND_EVENT.PAYLOAD, JSONB.valueOf(json.writeValueAsString(event.payload())))
                        .set(WORKFLOW_INBOUND_EVENT.RECEIVED_AT, event.receivedAt())
                        .set(WORKFLOW_INBOUND_EVENT.EXPIRES_AT, event.expiresAt())
                        .onConflictDoNothing()
                        .execute()
                == 1;
    }
}
