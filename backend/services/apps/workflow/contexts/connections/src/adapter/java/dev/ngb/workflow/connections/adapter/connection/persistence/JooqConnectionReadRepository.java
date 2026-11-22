package dev.ngb.workflow.connections.adapter.connection.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_CONNECTION;

import dev.ngb.workflow.connections.application.connection.query.ConnectionReadRepository;
import dev.ngb.workflow.connections.application.connection.query.ConnectionView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The connection read side: the secret columns are never selected. */
public class JooqConnectionReadRepository implements ConnectionReadRepository {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqConnectionReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public List<ConnectionView> all() {
        return dsl.select(
                        WORKFLOW_CONNECTION.ID,
                        WORKFLOW_CONNECTION.NAME,
                        WORKFLOW_CONNECTION.KIND,
                        WORKFLOW_CONNECTION.ALLOWED_HOST,
                        WORKFLOW_CONNECTION.HEADERS,
                        WORKFLOW_CONNECTION.SECRET_KEY_ID,
                        WORKFLOW_CONNECTION.VERIFIED_AT,
                        WORKFLOW_CONNECTION.CREATED_AT,
                        WORKFLOW_CONNECTION.UPDATED_AT,
                        WORKFLOW_CONNECTION.VERSION)
                .from(WORKFLOW_CONNECTION)
                .orderBy(WORKFLOW_CONNECTION.NAME)
                .fetch(row -> new ConnectionView(
                        row.value1(), row.value2(), row.value3(), row.value4(), map(row.value5()),
                        row.value6(), row.value7(), row.value8(), row.value9(), row.value10()));
    }

    @Override
    public Optional<ConnectionView> findById(UUID id) {
        return all().stream().filter(view -> view.id().equals(id)).findFirst();
    }

    private Map<String, String> map(JSONB headers) {
        return headers == null ? Map.of() : json.readValue(headers.data(), HEADERS);
    }
}
