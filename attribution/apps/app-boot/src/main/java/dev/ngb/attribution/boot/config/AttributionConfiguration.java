package dev.ngb.attribution.boot.config;

import dev.ngb.attribution.attribution.adapter.conversion.persistence.JooqConversionJobs;
import dev.ngb.attribution.attribution.adapter.conversion.persistence.JooqConversionPersonEraser;
import dev.ngb.attribution.attribution.adapter.conversion.persistence.JooqRecomputeQueue;
import dev.ngb.attribution.attribution.application.conversion.port.RecomputeQueue;
import dev.ngb.attribution.attribution.adapter.conversion.persistence.JooqConversionReadRepository;
import dev.ngb.attribution.attribution.adapter.conversion.persistence.JooqConversionRepository;
import dev.ngb.attribution.attribution.adapter.goal.persistence.JooqGoalReadRepository;
import dev.ngb.attribution.attribution.adapter.goal.persistence.JooqGoalRepository;
import dev.ngb.attribution.attribution.application.conversion.command.RecordConversionUseCase;
import dev.ngb.attribution.attribution.application.conversion.command.RecomputeCampaignUseCase;
import dev.ngb.attribution.attribution.application.conversion.command.SettleConversionsUseCase;
import dev.ngb.attribution.attribution.application.conversion.port.ConversionJobs;
import dev.ngb.attribution.attribution.application.conversion.query.ConversionQueries;
import dev.ngb.attribution.attribution.application.conversion.query.ConversionReadRepository;
import dev.ngb.attribution.attribution.application.conversion.service.ConversionAttributor;
import dev.ngb.attribution.attribution.application.goal.command.CreateGoalUseCase;
import dev.ngb.attribution.attribution.application.goal.command.UpdateGoalUseCase;
import dev.ngb.attribution.attribution.application.goal.port.GoalRepository;
import dev.ngb.attribution.attribution.application.goal.query.GoalQueries;
import dev.ngb.attribution.attribution.application.goal.query.GoalReadRepository;

import dev.ngb.attribution.tracking.api.chain.TouchpointReader;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The attribution context's wiring: goals, the postback, the settle and recompute use cases, and the queue a model
 * change feeds.
 *
 * <p>The click keys are the same ones the redirect signed with, so a postback verifies a token the app issued and no
 * other. The chain comes from tracking through its api and the owning campaign's policy from links through its api,
 * which is the only way this context reaches either.
 */
@Configuration(proxyBeanMethods = false)
class AttributionConfiguration {

    @Bean
    GoalRepository goalRepository(DSLContext dsl) {
        return new JooqGoalRepository(dsl);
    }

    @Bean
    GoalReadRepository goalReadRepository(DSLContext dsl) {
        return new JooqGoalReadRepository(dsl);
    }

    @Bean
    GoalQueries goalQueries(UnitOfWork unitOfWork, GoalReadRepository goals) {
        return new GoalQueries(unitOfWork, goals);
    }

    @Bean
    CreateGoalUseCase createGoalUseCase(
            UnitOfWork unitOfWork, TenantContext tenantContext, GoalRepository goals, IdGenerator ids, Clock clock) {
        return new CreateGoalUseCase(unitOfWork, tenantContext, goals, ids, clock);
    }

    @Bean
    UpdateGoalUseCase updateGoalUseCase(UnitOfWork unitOfWork, GoalRepository goals, Clock clock) {
        return new UpdateGoalUseCase(unitOfWork, goals, clock);
    }

    /** One store, exposed as both its port and the concrete type the job queries and the list read need. */
    @Bean
    JooqConversionRepository conversionRepository(DSLContext dsl, JsonMapper json) {
        return new JooqConversionRepository(dsl, json);
    }

    @Bean
    ConversionJobs conversionJobs(DSLContext dsl, JooqConversionRepository conversions) {
        return new JooqConversionJobs(dsl, conversions);
    }

    @Bean
    ConversionReadRepository conversionReadRepository(DSLContext dsl) {
        return new JooqConversionReadRepository(dsl);
    }

    @Bean
    ConversionQueries conversionQueries(UnitOfWork unitOfWork, ConversionReadRepository conversions) {
        return new ConversionQueries(unitOfWork, conversions);
    }

    @Bean
    ConversionAttributor conversionAttributor(
            Clock clock, TouchpointReader touchpoints, dev.ngb.attribution.links.api.campaign.CampaignPolicyReader policies) {
        return new ConversionAttributor(clock, touchpoints, policies);
    }

    @Bean
    RecordConversionUseCase recordConversionUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            JooqConversionRepository conversions,
            GoalRepository goals,
            AttributionKeysConfiguration.AttributionKeys keys,
            IdGenerator ids,
            Clock clock) {
        return new RecordConversionUseCase(
                unitOfWork, tenantContext, conversions, goals, Map.copyOf(keys.clickKeys()), ids, clock);
    }

    @Bean
    SettleConversionsUseCase settleConversionsUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            JooqConversionRepository conversions,
            ConversionAttributor attributor) {
        return new SettleConversionsUseCase(unitOfWork, tenantContext, conversions, attributor);
    }

    @Bean
    RecomputeCampaignUseCase recomputeCampaignUseCase(
            UnitOfWork unitOfWork,
            JooqConversionRepository conversions,
            ConversionJobs jobs,
            ConversionAttributor attributor) {
        return new RecomputeCampaignUseCase(unitOfWork, conversions, jobs, attributor);
    }

    @Bean
    dev.ngb.attribution.tracking.api.erasure.ConversionPersonEraser conversionPersonEraser(DSLContext dsl) {
        return new JooqConversionPersonEraser(dsl);
    }

    @Bean
    RecomputeQueue recomputeQueue(
            UnitOfWork unitOfWork, DSLContext dsl, TenantContext tenantContext, IdGenerator ids, Clock clock) {
        return new JooqRecomputeQueue(unitOfWork, dsl, tenantContext, ids, clock);
    }

    @Bean
    dev.ngb.attribution.links.application.campaign.port.RecomputeTrigger recomputeTrigger(
            dev.ngb.attribution.attribution.application.conversion.port.RecomputeQueue queue) {
        return (campaignId, reason) -> queue.enqueueCurrentTenant(campaignId, reason);
    }
}
