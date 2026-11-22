package dev.ngb.workflow.connections.adapter.connection.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_CONNECTION;

import dev.ngb.core.id.TenantId;
import dev.ngb.workflow.connections.application.connection.port.ConnectionRepository;
import dev.ngb.workflow.connections.domain.connection.Connection;
import dev.ngb.workflow.connections.domain.connection.ConnectionKind;
import dev.ngb.workflow_jooq.tables.records.WorkflowConnectionRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.databind.json.JsonMapper;

/** The connection rows; the ciphertext is stored and returned as an envelope, never as a plaintext secret. */
public class JooqConnectionRepository implements ConnectionRepository {

    private static final tools.jackson.core.type.TypeReference<Map<String, String>> HEADERS =
            new tools.jackson.core.type.TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqConnectionRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public void add(Connection connection) {
        dsl.insertInto(WORKFLOW_CONNECTION)
                .set(WORKFLOW_CONNECTION.ID, connection.id())
                .set(WORKFLOW_CONNECTION.TENANT_ID, connection.tenantId().value())
                .set(WORKFLOW_CONNECTION.NAME, connection.name())
                .set(WORKFLOW_CONNECTION.KIND, connection.kind().name())
                .set(WORKFLOW_CONNECTION.ALLOWED_HOST, connection.allowedHost())
                .set(WORKFLOW_CONNECTION.SECRET_CIPHERTEXT, connection.secretCiphertext())
                .set(WORKFLOW_CONNECTION.SECRET_KEY_ID, connection.secretKeyId())
                .set(WORKFLOW_CONNECTION.HEADERS, JSONB.valueOf(json.writeValueAsString(connection.headers())))
                .set(WORKFLOW_CONNECTION.VERIFIED_AT, connection.verifiedAt())
                .set(WORKFLOW_CONNECTION.CREATED_AT, connection.createdAt())
                .set(WORKFLOW_CONNECTION.UPDATED_AT, connection.updatedAt())
                .set(WORKFLOW_CONNECTION.VERSION, connection.version())
                .execute();
    }

    @Override
    public long update(Connection connection, long expectedVersion) {
        int updated = dsl.update(WORKFLOW_CONNECTION)
                .set(WORKFLOW_CONNECTION.NAME, connection.name())
                .set(WORKFLOW_CONNECTION.KIND, connection.kind().name())
                .set(WORKFLOW_CONNECTION.ALLOWED_HOST, connection.allowedHost())
                .set(WORKFLOW_CONNECTION.SECRET_CIPHERTEXT, connection.secretCiphertext())
                .set(WORKFLOW_CONNECTION.SECRET_KEY_ID, connection.secretKeyId())
                .set(WORKFLOW_CONNECTION.HEADERS, JSONB.valueOf(json.writeValueAsString(connection.headers())))
                .set(WORKFLOW_CONNECTION.VERIFIED_AT, connection.verifiedAt())
                .set(WORKFLOW_CONNECTION.UPDATED_AT, connection.updatedAt())
                .set(WORKFLOW_CONNECTION.VERSION, expectedVersion + 1)
                .where(WORKFLOW_CONNECTION.ID.eq(connection.id()))
                .and(WORKFLOW_CONNECTION.VERSION.eq(expectedVersion))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("connection " + connection.id() + " changed under this update");
        }
        return expectedVersion + 1;
    }

    @Override
    public Optional<Connection> findById(UUID id) {
        return dsl.selectFrom(WORKFLOW_CONNECTION)
                .where(WORKFLOW_CONNECTION.ID.eq(id))
                .fetchOptional(row -> toDomain(row));
    }

    @Override
    public Optional<Connection> findByName(String name) {
        return dsl.selectFrom(WORKFLOW_CONNECTION)
                .where(WORKFLOW_CONNECTION.NAME.equalIgnoreCase(name))
                .fetchOptional(row -> toDomain(row));
    }

    @Override
    public List<Connection> all() {
        return dsl.selectFrom(WORKFLOW_CONNECTION)
                .orderBy(WORKFLOW_CONNECTION.NAME)
                .fetch(row -> toDomain(row));
    }

    private Connection toDomain(WorkflowConnectionRecord row) {
        return new Connection(
                row.getId(),
                new TenantId(row.getTenantId()),
                row.getName(),
                ConnectionKind.valueOf(row.getKind()),
                row.getAllowedHost(),
                row.getSecretCiphertext(),
                row.getSecretKeyId(),
                row.getHeaders() == null ? Map.of() : json.readValue(row.getHeaders().data(), HEADERS),
                row.getVerifiedAt(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }
}
