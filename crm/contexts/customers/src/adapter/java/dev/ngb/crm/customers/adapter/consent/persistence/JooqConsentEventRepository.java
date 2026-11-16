package dev.ngb.crm.customers.adapter.consent.persistence;

import static dev.ngb.crm_jooq.Tables.CONSENT_EVENTS;

import dev.ngb.core.id.TenantId;
import dev.ngb.crm.customers.application.consent.port.ConsentEventRepository;
import dev.ngb.crm.customers.domain.consent.ConsentEvent;
import dev.ngb.crm.customers.domain.consent.ConsentStatus;
import dev.ngb.crm.customers.domain.customer.CustomerId;
import dev.ngb.crm_jooq.tables.records.ConsentEventsRecord;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * Appends consent records. There is no update: the table refuses one to the application role, because the history of an
 * agreement is evidence rather than a current value.
 *
 * <p>The caller's own reference is a partial unique key, so a retry that slipped past the replay check cannot append a
 * second record of the same agreement.
 */
public class JooqConsentEventRepository implements ConsentEventRepository {

    private final DSLContext dsl;

    public JooqConsentEventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean insertIfAbsent(ConsentEvent event) {
        var insert = dsl.insertInto(CONSENT_EVENTS)
                .set(CONSENT_EVENTS.ID, event.id())
                .set(CONSENT_EVENTS.TENANT_ID, event.tenantId().value())
                .set(CONSENT_EVENTS.CUSTOMER_ID, event.customerId().value())
                .set(CONSENT_EVENTS.TOPIC_KEY, event.topicKey())
                .set(CONSENT_EVENTS.STATUS, event.status().name())
                .set(CONSENT_EVENTS.OCCURRED_AT, event.occurredAt())
                .set(CONSENT_EVENTS.RECORDED_AT, event.recordedAt())
                .set(CONSENT_EVENTS.SOURCE, event.source())
                .set(CONSENT_EVENTS.EVIDENCE_REF, event.evidenceRef())
                .set(CONSENT_EVENTS.CONSENT_REF, event.consentRef());
        if (event.consentRef() == null) {
            return insert.execute() == 1;
        }
        return insert.onConflict(
                                CONSENT_EVENTS.TENANT_ID, CONSENT_EVENTS.CUSTOMER_ID, CONSENT_EVENTS.CONSENT_REF)
                        .where(CONSENT_EVENTS.CONSENT_REF.isNotNull())
                        .doNothing()
                        .execute()
                == 1;
    }

    @Override
    public Optional<ConsentEvent> findByConsentRef(CustomerId customerId, String consentRef) {
        if (consentRef == null) {
            return Optional.empty();
        }
        return dsl.selectFrom(CONSENT_EVENTS)
                .where(CONSENT_EVENTS.CUSTOMER_ID.eq(customerId.value()))
                .and(CONSENT_EVENTS.CONSENT_REF.eq(consentRef))
                .fetchOptional(JooqConsentEventRepository::toDomain);
    }

    private static ConsentEvent toDomain(ConsentEventsRecord row) {
        return ConsentEvent.restore(
                row.getId(),
                new TenantId(row.getTenantId()),
                new CustomerId(row.getCustomerId()),
                row.getTopicKey(),
                ConsentStatus.valueOf(row.getStatus()),
                row.getOccurredAt(),
                row.getRecordedAt(),
                row.getSource(),
                row.getEvidenceRef(),
                row.getConsentRef());
    }
}
