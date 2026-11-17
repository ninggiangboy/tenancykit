package dev.ngb.workflow.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.definitions.adapter.definition.persistence.JooqDefinitionReadRepository;
import dev.ngb.workflow.definitions.adapter.definition.persistence.JooqDefinitionRepository;
import dev.ngb.workflow.definitions.adapter.definition.persistence.JooqVersionRepository;
import dev.ngb.workflow.definitions.application.definition.command.CreateDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.PublishDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.RetireDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.SimulateDefinitionUseCase;
import dev.ngb.workflow.definitions.application.definition.command.UpdateDraftUseCase;
import dev.ngb.workflow.definitions.application.definition.port.DefinitionRepository;
import dev.ngb.workflow.definitions.application.definition.port.VersionRepository;
import dev.ngb.workflow.definitions.application.definition.query.DefinitionQueries;
import dev.ngb.workflow.definitions.application.definition.query.DefinitionReadRepository;
import dev.ngb.workflow.definitions.application.definition.service.DefinitionRules;
import dev.ngb.workflow.definitions.application.definition.service.GraphSimulation;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires the definitions context: its two stores, its read side, the validation and the simulation. */
@Configuration(proxyBeanMethods = false)
class DefinitionsConfiguration {

    @Bean
    DefinitionRepository definitionRepository(DSLContext dsl, JsonMapper json) {
        return new JooqDefinitionRepository(dsl, json);
    }

    @Bean
    VersionRepository versionRepository(DSLContext dsl, JsonMapper json) {
        return new JooqVersionRepository(dsl, json);
    }

    @Bean
    DefinitionReadRepository definitionReadRepository(DSLContext dsl, JsonMapper json) {
        return new JooqDefinitionReadRepository(dsl, json);
    }

    @Bean
    DefinitionRules definitionRules() {
        return new DefinitionRules();
    }

    @Bean
    GraphSimulation graphSimulation() {
        return new GraphSimulation();
    }

    @Bean
    DefinitionQueries definitionQueries(UnitOfWork unitOfWork, DefinitionReadRepository definitions) {
        return new DefinitionQueries(unitOfWork, definitions);
    }

    @Bean
    CreateDefinitionUseCase createDefinitionUseCase(
            UnitOfWork unitOfWork,
            DefinitionRepository definitions,
            DefinitionRules rules,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new CreateDefinitionUseCase(unitOfWork, definitions, rules, tenantContext, ids, clock);
    }

    @Bean
    UpdateDraftUseCase updateDraftUseCase(
            UnitOfWork unitOfWork, DefinitionRepository definitions, DefinitionRules rules, Clock clock) {
        return new UpdateDraftUseCase(unitOfWork, definitions, rules, clock);
    }

    @Bean
    PublishDefinitionUseCase publishDefinitionUseCase(
            UnitOfWork unitOfWork,
            DefinitionRepository definitions,
            VersionRepository versions,
            DefinitionRules rules,
            IdGenerator ids,
            Clock clock) {
        return new PublishDefinitionUseCase(unitOfWork, definitions, versions, rules, ids, clock);
    }

    @Bean
    RetireDefinitionUseCase retireDefinitionUseCase(
            UnitOfWork unitOfWork, DefinitionRepository definitions, Clock clock) {
        return new RetireDefinitionUseCase(unitOfWork, definitions, clock);
    }

    @Bean
    SimulateDefinitionUseCase simulateDefinitionUseCase(
            UnitOfWork unitOfWork,
            DefinitionRepository definitions,
            DefinitionRules rules,
            GraphSimulation simulation) {
        return new SimulateDefinitionUseCase(unitOfWork, definitions, rules, simulation);
    }
}
