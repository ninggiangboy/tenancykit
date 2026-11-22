package dev.ngb.workflow.triggers.application.trigger.command;

import static dev.ngb.workflow_jooq.Tables.WORKFLOW_INSTANCE;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_SCHEDULE;
import static dev.ngb.workflow_jooq.Tables.WORKFLOW_TRIGGER;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.version.ExpectedVersion;
import dev.ngb.workflow.definitions.application.definition.command.CreateDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.PublishDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.UpdateDraftUseCase;
import dev.ngb.workflow.definitions.domain.definition.Definition;
import dev.ngb.workflow.support.ReplicaFixtures;
import dev.ngb.workflow.support.WorkflowIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A due schedule fires once, and running the sweeper again changes nothing: the tick is claimed with SKIP LOCKED and its
 * next due time advances before the instance starts.
 */
class ScheduleFireTest extends WorkflowIntegrationTest {

    @Autowired private FireScheduleUseCase fireSchedule;
    @Autowired private CreateDefinitionUseCase createDefinition;
    @Autowired private UpdateDraftUseCase updateDraft;
    @Autowired private PublishDefinitionUseCase publishDefinition;
    @Autowired private ReplicaFixtures replica;
    @Autowired private DSLContext dsl;
    @Autowired private UnitOfWork unitOfWork;

    @Test
    void oneTickFiresOnceEvenWhenTheSweeperRunsTwice() {
        ReplicaFixtures.TenantSeed seed = replica.tenant();
        TenantId tenant = seed.id();
        UUID definitionId = publishedDefinition(tenant);

        inTenant(tenant, () -> unitOfWork.transactional(() -> {
            UUID trigger = UUID.randomUUID();
            Instant now = Instant.now();
            dsl.insertInto(WORKFLOW_TRIGGER)
                    .set(WORKFLOW_TRIGGER.ID, trigger)
                    .set(WORKFLOW_TRIGGER.TENANT_ID, tenant.value())
                    .set(WORKFLOW_TRIGGER.NAME, "Nightly")
                    .set(WORKFLOW_TRIGGER.KIND, "SCHEDULE")
                    .set(WORKFLOW_TRIGGER.SOURCE, "schedule")
                    .set(WORKFLOW_TRIGGER.DEFINITION_ID, definitionId)
                    .set(WORKFLOW_TRIGGER.ENABLED, true)
                    .set(WORKFLOW_TRIGGER.CREATED_AT, now)
                    .set(WORKFLOW_TRIGGER.UPDATED_AT, now)
                    .execute();
            dsl.insertInto(WORKFLOW_SCHEDULE)
                    .set(WORKFLOW_SCHEDULE.ID, UUID.randomUUID())
                    .set(WORKFLOW_SCHEDULE.TENANT_ID, tenant.value())
                    .set(WORKFLOW_SCHEDULE.TRIGGER_ID, trigger)
                    .set(WORKFLOW_SCHEDULE.CRON, "0 0 2 * * *")
                    .set(WORKFLOW_SCHEDULE.TIMEZONE, "UTC")
                    // Due now: the test is the tick, not the clock.
                    .set(WORKFLOW_SCHEDULE.NEXT_DUE, now.minusSeconds(1))
                    .execute();
            return null;
        }));

        assertThat(inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.fetchCount(WORKFLOW_SCHEDULE))))
                .as("schedule rows")
                .isEqualTo(1);
        assertThat(inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.fetchCount(WORKFLOW_TRIGGER))))
                .as("trigger rows")
                .isEqualTo(1);

        inTenant(tenant, () -> {
            Instant now = Instant.now();
            assertThat(fireSchedule.fireDue(now, 10)).isEqualTo(1);
            assertThat(fireSchedule.fireDue(now, 10)).isZero();
        });

        assertThat(inTenant(tenant, () -> unitOfWork.transactional(() -> (long) dsl.fetchCount(WORKFLOW_INSTANCE))))
                .isEqualTo(1);
        Instant nextDue = inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.select(WORKFLOW_SCHEDULE.NEXT_DUE)
                .from(WORKFLOW_SCHEDULE)
                .fetchOne(WORKFLOW_SCHEDULE.NEXT_DUE)));
        assertThat(nextDue).isAfter(Instant.now().plus(Duration.ofMinutes(1)));
    }

    private UUID publishedDefinition(TenantId tenant) {
        Definition definition = inTenant(tenant, () -> createDefinition.execute(new CreateDefinitionUseCase.Command(
                "schedule" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), "Scheduled")));
        inTenant(tenant, () -> updateDraft.execute(new UpdateDraftUseCase.Command(
                definition.id(),
                Map.of("start", "start", "nodes", List.of(
                        Map.of("id", "start", "type", "trigger", "next", "done"),
                        Map.of("id", "done", "type", "end"))),
                ExpectedVersion.any())));
        return inTenant(tenant, () -> publishDefinition
                .execute(new PublishDefinitionUseCase.Command(definition.id(), ExpectedVersion.any(), "test"))
                .definition()
                .id());
    }
}
