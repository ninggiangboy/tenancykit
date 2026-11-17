package dev.ngb.workflow.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.workflow.definitions.api.expression.ExpressionEvaluator;
import dev.ngb.workflow.definitions.api.version.VersionReader;
import dev.ngb.workflow.runtime.adapter.runtime.persistence.JooqCompensationRunRepository;
import dev.ngb.workflow.runtime.adapter.runtime.persistence.JooqInstanceRepository;
import dev.ngb.workflow.runtime.adapter.runtime.persistence.JooqJoinStateRepository;
import dev.ngb.workflow.runtime.adapter.runtime.persistence.JooqLoopStateRepository;
import dev.ngb.workflow.runtime.api.task.TaskResumer;
import dev.ngb.workflow.runtime.adapter.runtime.persistence.JooqStepRunReadRepository;
import dev.ngb.workflow.runtime.adapter.runtime.persistence.JooqStepRunRepository;
import dev.ngb.workflow.runtime.adapter.runtime.persistence.JooqTimerRepository;
import dev.ngb.workflow.runtime.adapter.runtime.persistence.JooqTokenRepository;
import dev.ngb.workflow.runtime.adapter.runtime.persistence.JooqWaitSubscriptionRepository;
import dev.ngb.workflow.runtime.api.instance.InstanceStarter;
import dev.ngb.workflow.runtime.api.wait.WaitResolver;
import dev.ngb.workflow.runtime.application.runtime.command.CancelInstanceUseCase;
import dev.ngb.workflow.runtime.application.runtime.command.ReplayInstanceUseCase;
import dev.ngb.workflow.runtime.application.runtime.command.RetryInstanceUseCase;
import dev.ngb.workflow.runtime.application.runtime.command.StartInstanceUseCase;
import dev.ngb.workflow.runtime.application.runtime.port.CompensationRunRepository;
import dev.ngb.workflow.runtime.application.runtime.port.InstanceRepository;
import dev.ngb.workflow.runtime.application.runtime.port.JoinStateRepository;
import dev.ngb.workflow.runtime.application.runtime.port.LoopStateRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TaskCanceller;
import dev.ngb.workflow.runtime.application.runtime.port.TaskGateway;
import dev.ngb.workflow.runtime.application.runtime.port.StepRunRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TimerRepository;
import dev.ngb.workflow.runtime.application.runtime.port.TokenRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WaitSubscriptionRepository;
import dev.ngb.workflow.runtime.application.runtime.port.WakeUpPublisher;
import dev.ngb.workflow.runtime.application.runtime.query.InstanceStepsQueries;
import dev.ngb.workflow.runtime.application.runtime.query.StepRunReadRepository;
import dev.ngb.workflow.runtime.application.runtime.service.ClaimAndRun;
import dev.ngb.workflow.runtime.application.runtime.service.CompensationRunner;
import dev.ngb.workflow.runtime.application.runtime.service.InstanceStarterService;
import dev.ngb.workflow.runtime.application.runtime.service.TaskResumerService;
import dev.ngb.workflow.runtime.application.runtime.service.TimerSweep;
import dev.ngb.workflow.runtime.application.runtime.service.TokenSweeper;
import dev.ngb.workflow.runtime.application.runtime.service.WaitResolverService;
import dev.ngb.workflow.runtime.domain.step.BufferedEventLookup;
import dev.ngb.workflow.runtime.domain.step.DefaultNodeExecutor;
import dev.ngb.workflow.runtime.domain.step.NodeExecutor;
import dev.ngb.workflow.runtime.domain.step.OutboundHttp;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the runtime: its stores, the node executor, the manual start, the claim loop, the lease sweeper, the timer sweep
 * and the event-wait resolver.
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
    TokenRepository tokenRepository(DSLContext dsl, JsonMapper json) {
        return new JooqTokenRepository(dsl, json);
    }

    @Bean
    StepRunRepository stepRunRepository(DSLContext dsl, JsonMapper json) {
        return new JooqStepRunRepository(dsl, json);
    }

    @Bean
    StepRunReadRepository stepRunReadRepository(DSLContext dsl, JsonMapper json) {
        return new JooqStepRunReadRepository(dsl, json);
    }

    @Bean
    CompensationRunRepository compensationRunRepository(DSLContext dsl) {
        return new JooqCompensationRunRepository(dsl);
    }

    @Bean
    TimerRepository timerRepository(DSLContext dsl) {
        return new JooqTimerRepository(dsl);
    }

    @Bean
    WaitSubscriptionRepository waitSubscriptionRepository(DSLContext dsl) {
        return new JooqWaitSubscriptionRepository(dsl);
    }

    @Bean
    BufferedEventLookup bufferedEventLookup(
            UnitOfWork unitOfWork, dev.ngb.workflow.triggers.application.trigger.port.InboundEventRepository events) {
        return new TriggeredEventLookup(unitOfWork, events);
    }

    @Bean
    NodeExecutor nodeExecutor(ExpressionEvaluator expressions, BufferedEventLookup events, OutboundHttp http) {
        return new DefaultNodeExecutor(expressions, events, http);
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
    RetryInstanceUseCase retryInstanceUseCase(
            UnitOfWork unitOfWork,
            InstanceRepository instances,
            TokenRepository tokens,
            WakeUpPublisher wakeUp,
            Clock clock) {
        return new RetryInstanceUseCase(unitOfWork, instances, tokens, wakeUp, clock);
    }

    @Bean
    ReplayInstanceUseCase replayInstanceUseCase(
            UnitOfWork unitOfWork,
            InstanceRepository instances,
            TokenRepository tokens,
            VersionReader versions,
            WakeUpPublisher wakeUp,
            IdGenerator ids,
            Clock clock) {
        return new ReplayInstanceUseCase(unitOfWork, instances, tokens, versions, wakeUp, ids, clock);
    }

    @Bean
    CancelInstanceUseCase cancelInstanceUseCase(
            UnitOfWork unitOfWork,
            InstanceRepository instances,
            TokenRepository tokens,
            TimerRepository timers,
            WaitSubscriptionRepository subscriptions,
            CompensationRunner compensation,
            VersionReader versions,
            TaskCanceller tasks,
            WakeUpPublisher wakeUp,
            Clock clock) {
        return new CancelInstanceUseCase(
                unitOfWork, instances, tokens, timers, subscriptions, compensation, versions, tasks, wakeUp, clock);
    }

    @Bean
    InstanceStepsQueries instanceStepsQueries(UnitOfWork unitOfWork, StepRunReadRepository steps) {
        return new InstanceStepsQueries(unitOfWork, steps);
    }

    @Bean
    WaitResolver waitResolver(
            UnitOfWork unitOfWork,
            WaitSubscriptionRepository subscriptions,
            TokenRepository tokens,
            TimerRepository timers,
            WakeUpPublisher wakeUp) {
        return new WaitResolverService(unitOfWork, subscriptions, tokens, timers, wakeUp);
    }

    @Bean
    TimerSweep timerSweep(
            UnitOfWork unitOfWork,
            TimerRepository timers,
            TokenRepository tokens,
            WaitSubscriptionRepository subscriptions,
            WakeUpPublisher wakeUp) {
        return new TimerSweep(unitOfWork, timers, tokens, subscriptions, wakeUp);
    }

    @Bean
    CompensationRunner compensationRunner(
            UnitOfWork unitOfWork,
            InstanceRepository instances,
            StepRunRepository steps,
            CompensationRunRepository compensationRuns,
            NodeExecutor executor,
            IdGenerator ids,
            Clock clock) {
        return new CompensationRunner(unitOfWork, instances, steps, compensationRuns, executor, ids, clock);
    }

    @Bean
    ClaimAndRun claimAndRun(
            UnitOfWork unitOfWork,
            InstanceRepository instances,
            TokenRepository tokens,
            StepRunRepository steps,
            TimerRepository timers,
            WaitSubscriptionRepository subscriptions,
            JoinStateRepository joinStates,
            LoopStateRepository loops,
            TaskGateway tasks,
            VersionReader versions,
            NodeExecutor executor,
            CompensationRunner compensation,
            WakeUpPublisher wakeUp,
            IdGenerator ids,
            Clock clock,
            WorkflowSettings settings) {
        return new ClaimAndRun(
                unitOfWork,
                instances,
                tokens,
                steps,
                timers,
                subscriptions,
                joinStates,
                loops,
                tasks,
                versions,
                executor,
                compensation,
                wakeUp,
                ids,
                clock,
                "workflow-" + UUID.randomUUID(),
                settings.runtime().lease());
    }

    @Bean
    JoinStateRepository joinStateRepository(DSLContext dsl, JsonMapper json) {
        return new JooqJoinStateRepository(dsl, json);
    }

    @Bean
    LoopStateRepository loopStateRepository(DSLContext dsl, JsonMapper json) {
        return new JooqLoopStateRepository(dsl, json);
    }

    @Bean
    TaskResumer taskResumer(TokenRepository tokens, Clock clock) {
        return new TaskResumerService(tokens, clock);
    }

    @Bean
    TokenSweeper tokenSweeper(UnitOfWork unitOfWork, TokenRepository tokens, Clock clock) {
        return new TokenSweeper(unitOfWork, tokens, clock);
    }
}
