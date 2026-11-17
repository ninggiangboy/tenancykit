package dev.ngb.workflow.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.definitions.api.expression.ExpressionEvaluator;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.runtime.adapter.runtime.persistence.JooqInstanceRepository;
import dev.ngb.workflow.runtime.adapter.runtime.persistence.JooqStepRunRepository;
import dev.ngb.workflow.runtime.adapter.runtime.persistence.JooqTokenRepository;
import dev.ngb.workflow.runtime.application.runtime.command.StartInstanceUseCase;
import dev.ngb.workflow.runtime.application.runtime.port.InstanceRepository;
import dev.ngb.workflow.runtime.application.runtime.port.StepRunRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WakeUpPublisher;
import dev.ngb.workflow.runtime.api.instance.InstanceStarter;
import dev.ngb.workflow.runtime.application.runtime.service.ClaimAndRun;
import dev.ngb.workflow.runtime.application.runtime.service.InstanceStarterService;
import dev.ngb.workflow.runtime.application.runtime.service.TokenSweeper;
import dev.ngb.workflow.runtime.domain.step.DefaultNodeExecutor;
import dev.ngb.workflow.runtime.domain.step.NodeExecutor;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the runtime: its three stores, the node executor, the manual start, the claim loop and the lease sweeper.
 *
 * <p>The worker id is created once per process, so every claim this runtime makes is attributable and a restarted
 * process never reuses another's name while its lease is still live.
 */
@Configuration(proxyBeanMethods = false)
class RuntimeConfiguration {

    @Bean
    InstanceRepository instanceRepository(DSLContext dsl, JsonMapper json) {
        return new JooqInstanceRepository(dsl, json);
    }

    @Bean
    TokenRepository tokenRepository(DSLContext dsl) {
        return new JooqTokenRepository(dsl);
    }

    @Bean
    StepRunRepository stepRunRepository(DSLContext dsl, JsonMapper json) {
        return new JooqStepRunRepository(dsl, json);
    }

    @Bean
    NodeExecutor nodeExecutor(ExpressionEvaluator expressions) {
        return new DefaultNodeExecutor(expressions);
    }

    @Bean
    StartInstanceUseCase startInstanceUseCase(
            UnitOfWork unitOfWork,
            VersionReader versions,
            InstanceRepository instances,
            TokenRepository tokens,
            WakeUpPublisher wakeUp,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new StartInstanceUseCase(
                unitOfWork, versions, instances, tokens, wakeUp, tenantContext, ids, clock);
    }

    @Bean
    InstanceStarter instanceStarter(StartInstanceUseCase start) {
        return new InstanceStarterService(start);
    }

    @Bean
    ClaimAndRun claimAndRun(
            UnitOfWork unitOfWork,
            InstanceRepository instances,
            TokenRepository tokens,
            StepRunRepository steps,
            VersionReader versions,
            NodeExecutor executor,
            WakeUpPublisher wakeUp,
            IdGenerator ids,
            Clock clock,
            WorkflowSettings settings) {
        return new ClaimAndRun(
                unitOfWork,
                instances,
                tokens,
                steps,
                versions,
                executor,
                wakeUp,
                ids,
                clock,
                "workflow-" + UUID.randomUUID(),
                settings.runtime().lease());
    }

    @Bean
    TokenSweeper tokenSweeper(UnitOfWork unitOfWork, TokenRepository tokens, Clock clock) {
        return new TokenSweeper(unitOfWork, tokens, clock);
    }
}
