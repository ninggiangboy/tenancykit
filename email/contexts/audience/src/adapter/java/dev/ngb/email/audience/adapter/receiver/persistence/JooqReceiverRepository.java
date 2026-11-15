package dev.ngb.email.audience.adapter.receiver.persistence;

import static dev.ngb.email_jooq.Tables.RECEIVERS;

import dev.ngb.core.id.TenantId;
import dev.ngb.email.audience.application.receiver.port.ReceiverRepository;
import dev.ngb.email.audience.domain.receiver.Receiver;
import dev.ngb.email.audience.domain.receiver.ReceiverStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The receiver store; an upsert is keyed by the tenant-scoped normalized address. */
public class JooqReceiverRepository implements ReceiverRepository {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqReceiverRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Optional<Receiver> findById(UUID receiverId) {
        return dsl.selectFrom(RECEIVERS)
                .where(RECEIVERS.ID.eq(receiverId))
                .fetchOptional(this::toReceiver);
    }

    @Override
    public Optional<Receiver> findByAddress(String normalizedAddress) {
        return dsl.selectFrom(RECEIVERS)
                .where(RECEIVERS.EMAIL_NORMALIZED.eq(normalizedAddress))
                .fetchOptional(this::toReceiver);
    }

    @Override
    public Receiver save(Receiver receiver) {
        dsl.insertInto(RECEIVERS)
                .set(RECEIVERS.ID, receiver.id())
                .set(RECEIVERS.TENANT_ID, receiver.tenantId().value())
                .set(RECEIVERS.EMAIL, receiver.email())
                .set(RECEIVERS.EMAIL_NORMALIZED, receiver.emailNormalized())
                .set(RECEIVERS.FIRST_NAME, receiver.firstName())
                .set(RECEIVERS.LAST_NAME, receiver.lastName())
                .set(RECEIVERS.ATTRIBUTES, jsonb(receiver.attributes()))
                .set(RECEIVERS.STATUS, receiver.status().name())
                .set(RECEIVERS.VERSION, receiver.version())
                .set(RECEIVERS.CREATED_AT, receiver.createdAt())
                .set(RECEIVERS.UPDATED_AT, receiver.updatedAt())
                .onConflict(RECEIVERS.TENANT_ID, RECEIVERS.EMAIL_NORMALIZED)
                .doUpdate()
                .set(RECEIVERS.EMAIL, receiver.email())
                .set(RECEIVERS.FIRST_NAME, receiver.firstName())
                .set(RECEIVERS.LAST_NAME, receiver.lastName())
                .set(RECEIVERS.ATTRIBUTES, jsonb(receiver.attributes()))
                .set(RECEIVERS.VERSION, RECEIVERS.VERSION.plus(1))
                .set(RECEIVERS.UPDATED_AT, receiver.updatedAt())
                .execute();
        return findByAddress(receiver.emailNormalized())
                .orElseThrow(() -> new IllegalStateException("the upserted receiver could not be read back"));
    }

    @Override
    public boolean update(Receiver receiver, long expectedVersion) {
        return dsl.update(RECEIVERS)
                        .set(RECEIVERS.EMAIL, receiver.email())
                        .set(RECEIVERS.FIRST_NAME, receiver.firstName())
                        .set(RECEIVERS.LAST_NAME, receiver.lastName())
                        .set(RECEIVERS.ATTRIBUTES, jsonb(receiver.attributes()))
                        .set(RECEIVERS.STATUS, receiver.status().name())
                        .set(RECEIVERS.VERSION, receiver.version())
                        .set(RECEIVERS.UPDATED_AT, receiver.updatedAt())
                        .where(RECEIVERS.ID.eq(receiver.id()))
                        .and(RECEIVERS.VERSION.eq(expectedVersion))
                        .execute()
                == 1;
    }

    @Override
    public boolean delete(UUID receiverId) {
        return dsl.deleteFrom(RECEIVERS).where(RECEIVERS.ID.eq(receiverId)).execute() == 1;
    }

    @Override
    public List<UUID> existingIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return dsl.select(RECEIVERS.ID)
                .from(RECEIVERS)
                .where(RECEIVERS.ID.in(ids))
                .fetch(RECEIVERS.ID);
    }

    private JSONB jsonb(Map<String, Object> attributes) {
        return JSONB.valueOf(json.writeValueAsString(attributes));
    }

    private Receiver toReceiver(dev.ngb.email_jooq.tables.records.ReceiversRecord row) {
        return new Receiver(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getEmail(),
                row.getEmailNormalized(),
                row.getFirstName(),
                row.getLastName(),
                json.readValue(row.getAttributes().data(), ATTRIBUTES),
                ReceiverStatus.valueOf(row.getStatus()),
                row.getVersion(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
