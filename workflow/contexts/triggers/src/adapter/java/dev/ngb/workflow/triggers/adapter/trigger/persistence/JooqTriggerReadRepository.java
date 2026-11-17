package dev.ngb.workflow.triggers.adapter.trigger.persistence;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TRIGGER;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.spring.query.JooqLists;
import dev.ngb.core.spring.query.ListBinding;
import dev.ngb.workflow.triggers.application.trigger.query.TriggerListing;
import dev.ngb.workflow.triggers.application.trigger.query.TriggerReadRepository;
import dev.ngb.workflow.triggers.application.trigger.query.TriggerView;
import dev.ngb.workflow.triggers.domain.trigger.TriggerKind;
import dev.ngb.workflow_jooq.tables.records.WorkflowTriggerRecord;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Selects triggers straight into the view; no aggregate is built for a read. */
public class JooqTriggerReadRepository implements TriggerReadRepository {

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};

    private static final ListBinding BINDING = ListBinding.of(TriggerListing.SCHEMA)
            .column("id", WORKFLOW_TRIGGER.ID)
            .column("name", WORKFLOW_TRIGGER.NAME)
            .column("kind", WORKFLOW_TRIGGER.KIND)
            .column("source", WORKFLOW_TRIGGER.SOURCE)
            .column("event_type", WORKFLOW_TRIGGER.EVENT_TYPE)
            .column("enabled", WORKFLOW_TRIGGER.ENABLED)
            .column("created_at", WORKFLOW_TRIGGER.CREATED_AT)
            .column("updated_at", WORKFLOW_TRIGGER.UPDATED_AT)
            .build();

    private final DSLContext dsl;
    private final JsonMapper json;

    public JooqTriggerReadRepository(DSLContext dsl, JsonMapper json) {
        this.dsl = dsl;
        this.json = json;
    }

    @Override
    public Page<TriggerView> list(ListQuery query) {
        return JooqLists.fetch(
                dsl, WORKFLOW_TRIGGER, org.jooq.impl.DSL.noCondition(), BINDING, query,
                row -> view(row));
    }

    @Override
    public Optional<TriggerView> findById(UUID id) {
        return dsl.selectFrom(WORKFLOW_TRIGGER)
                .where(WORKFLOW_TRIGGER.ID.eq(id))
                .fetchOptional(row -> view(row));
    }

    private TriggerView view(WorkflowTriggerRecord row) {
        return new TriggerView(
                row.getId(),
                row.getName(),
                TriggerKind.valueOf(row.getKind()),
                row.getSource(),
                row.getEventType(),
                row.getDefinitionId(),
                document(row.getFilter()),
                document(row.getCorrelation()),
                document(row.getInput()),
                row.getEnabled(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion());
    }

    private Object document(JSONB value) {
        return value == null ? null : json.readValue(value.data(), DOCUMENT);
    }
}
