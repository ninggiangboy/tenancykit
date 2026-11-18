package dev.ngb.workflow.runtime.adapter.instance.web;

import static dev.ngb.workflow.shared.WorkflowPermissions.INSTANCE_MANAGE;
import static dev.ngb.workflow.shared.WorkflowPermissions.INSTANCE_READ;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INBOUND_EVENT;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INSTANCE;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_STEP_RUN;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TENANT_QUOTA;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.boot.config.RetentionSweep;
import dev.ngb.workflow.definitions.application.definition.command.CreateDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.PublishDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.UpdateDraftUseCase;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.definitions.domain.definition.PublishedVersion;
import dev.ngb.workflow.runtime.application.runtime.service.ClaimAndRun;
import dev.ngb.workflow.support.WorkflowApiTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A Tenant's cap refuses a start with 429; its history shows state without the input; old history expires, open never. */
class HistoryQuotaAndRetentionTest extends WorkflowApiTest {

    private static final List<String> CODES = List.of(INSTANCE_READ, INSTANCE_MANAGE);

    @Autowired private CreateDefinitionUseCase createDefinition;
    @Autowired private UpdateDraftUseCase updateDraft;
    @Autowired private PublishDefinitionUseCase publishDefinition;
    @Autowired private ClaimAndRun claimAndRun;
    @Autowired private RetentionSweep retention;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aStartPastTheTenantCapAnswers429() throws Exception {
        Session session = tenant(CODES.toArray(String[]::new));
        Definition definition = definition(session, "capped", simpleGraph());
        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> {
            dsl.insertInto(WORKFLOW_TENANT_QUOTA)
                    .set(WORKFLOW_TENANT_QUOTA.TENANT_ID, session.tenant().value())
                    .set(WORKFLOW_TENANT_QUOTA.CONCURRENT_INSTANCES, 1)
                    .set(WORKFLOW_TENANT_QUOTA.ACTIVE_DEFINITIONS, 10)
                    .set(WORKFLOW_TENANT_QUOTA.UPDATED_AT, Instant.now())
                    .execute();
            return null;
        }));

        assertThat(startStatus(session, definition.id())).isEqualTo(201);
        assertThat(startStatus(session, definition.id())).isEqualTo(429);
    }

    @Test
    void historyShowsStateAndNoInput() throws Exception {
        Session session = tenant(CODES.toArray(String[]::new));
        Definition definition = definition(session, "history", simpleGraph());
        UUID instance = start(session, definition.id());
        for (int step = 0; step < 20 && !status(session, instance).equals("COMPLETED"); step++) {
            inTenant(session.tenant(), () -> claimAndRun.runOnce());
        }

        MvcResult listed = getAs(session, "/api/v1/instances?definition=history").andReturn();
        JsonNode rows = json.readTree(listed.getResponse().getContentAsString());
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).get("id").asString()).isEqualTo(instance.toString());
        assertThat(rows.get(0).get("status").asString()).isEqualTo("COMPLETED");

        MvcResult one = getAs(session, "/api/v1/instances/{id}", instance).andReturn();
        JsonNode view = json.readTree(one.getResponse().getContentAsString());
        assertThat(view.get("definitionCode").asString()).isEqualTo("history");
        assertThat(view.has("input")).as("history never carries the trigger input").isFalse();
        assertThat(view.get("steps").asLong()).isEqualTo(3);
        assertThat(view.get("liveTokens").asLong()).isZero();
    }

    @Test
    void retentionPurgesFinishedInstancesOnlyAndExpiredEvents() throws Exception {
        Session session = tenant(CODES.toArray(String[]::new));
        Definition definition = definition(session, "retained", simpleGraph());
        UUID old = start(session, definition.id());
        UUID fresh = start(session, definition.id());
        for (UUID instance : List.of(old, fresh)) {
            for (int step = 0; step < 20 && !status(session, instance).equals("COMPLETED"); step++) {
                inTenant(session.tenant(), () -> claimAndRun.runOnce());
            }
        }
        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> {
            dsl.update(WORKFLOW_INSTANCE)
                    .set(WORKFLOW_INSTANCE.FINISHED_AT, Instant.now().minusSeconds(200L * 24 * 3600))
                    .where(WORKFLOW_INSTANCE.ID.eq(old))
                    .execute();
            dsl.insertInto(WORKFLOW_INBOUND_EVENT)
                    .set(WORKFLOW_INBOUND_EVENT.ID, UUID.randomUUID())
                    .set(WORKFLOW_INBOUND_EVENT.TENANT_ID, session.tenant().value())
                    .set(WORKFLOW_INBOUND_EVENT.SOURCE, "billing")
                    .set(WORKFLOW_INBOUND_EVENT.EVENT_ID, UUID.randomUUID())
                    .set(WORKFLOW_INBOUND_EVENT.EVENT_TYPE, "subscription.payment_failed")
                    .set(WORKFLOW_INBOUND_EVENT.PAYLOAD, org.jooq.JSONB.valueOf("{}"))
                    .set(WORKFLOW_INBOUND_EVENT.RECEIVED_AT, Instant.now().minusSeconds(10 * 24 * 3600))
                    .set(WORKFLOW_INBOUND_EVENT.EXPIRES_AT, Instant.now().minusSeconds(24 * 3600))
                    .execute();
            return null;
        }));

        int purged = inTenant(session.tenant(), () -> unitOfWork.transactional(() -> retention.purge(Instant.now())));

        assertThat(purged).isEqualTo(1);
        inTenant(session.tenant(), () -> unitOfWork.transactional(() -> {
            assertThat(dsl.fetchCount(WORKFLOW_INSTANCE, WORKFLOW_INSTANCE.ID.eq(old))).isZero();
            assertThat(dsl.fetchCount(WORKFLOW_TOKEN, WORKFLOW_TOKEN.INSTANCE_ID.eq(old))).isZero();
            assertThat(dsl.fetchCount(WORKFLOW_STEP_RUN, WORKFLOW_STEP_RUN.INSTANCE_ID.eq(old))).isZero();
            assertThat(dsl.fetchCount(WORKFLOW_INSTANCE, WORKFLOW_INSTANCE.ID.eq(fresh))).isEqualTo(1);
            assertThat(dsl.fetchCount(WORKFLOW_INBOUND_EVENT)).isZero();
            return null;
        }));
    }

    // ------------------------------------------------------------------ helpers

    private Definition definition(Session session, String code, Object graph) {
        Definition definition = inTenant(session.tenant(), () -> createDefinition.execute(
                new CreateDefinitionUseCase.Command(code, "Flow")));
        inTenant(session.tenant(), () -> updateDraft.execute(
                new UpdateDraftUseCase.Command(definition.id(), graph, ExpectedVersion.any())));
        inTenant(session.tenant(), () -> publishDefinition
                .execute(new PublishDefinitionUseCase.Command(definition.id(), ExpectedVersion.any(), "test"))
                .version());
        return definition;
    }

    private int startStatus(Session session, UUID definitionId) throws Exception {
        return postAs(session, "/api/v1/instances",
                        json.writeValueAsString(Map.of("definition", definitionId.toString(), "input", Map.of())))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private UUID start(Session session, UUID definitionId) throws Exception {
        MvcResult started = postAs(session, "/api/v1/instances",
                        json.writeValueAsString(Map.of("definition", definitionId.toString(), "input", Map.of())))
                .andReturn();
        assertThat(started.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(json.readTree(started.getResponse().getContentAsString()).get("id").asString());
    }

    private String status(Session session, UUID instance) {
        return inTenant(session.tenant(), () -> unitOfWork.transactional(() -> dsl.select(WORKFLOW_INSTANCE.STATUS)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instance))
                .fetchOne(WORKFLOW_INSTANCE.STATUS)));
    }

    private static Object simpleGraph() {
        return Map.of(
                "start", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "keep"),
                        Map.of("id", "keep", "type", "set", "values", Map.of("seen", true), "next", "done"),
                        Map.of("id", "done", "type", "end")));
    }
}
