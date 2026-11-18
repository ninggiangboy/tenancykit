package dev.ngb.loyalty.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.boot.tiers.TierEvaluationAdapter;
import dev.ngb.loyalty.points.adapter.tier.persistence.JooqTierStore;
import dev.ngb.loyalty.points.api.tier.MemberTiers;
import dev.ngb.loyalty.points.application.tier.port.TierStore;
import dev.ngb.loyalty.points.application.tier.service.MemberTiersService;
import dev.ngb.loyalty.program.api.program.ProgramReader;
import dev.ngb.loyalty.program.api.tier.TierListRevisions;
import dev.ngb.loyalty.tiers.application.evaluation.service.TierEvaluationService;
import dev.ngb.loyalty.tiers.application.evaluation.service.TierEvents;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the tier evaluation: the member's tier columns under the points context's api, the tier list and its revision
 * mark under the program's, and the adapter that answers the points context's two questions with the evaluation.
 */
@Configuration(proxyBeanMethods = false)
class TierEvaluationConfiguration {

    @Bean
    TierStore tierStore(DSLContext dsl) {
        return new JooqTierStore(dsl);
    }

    @Bean
    MemberTiers memberTiers(TierStore tierStore) {
        return new MemberTiersService(tierStore);
    }

    @Bean
    TierEvents tierEvents(EventPublisher publisher, IdGenerator ids) {
        return new TierEvents(publisher, ids);
    }

    @Bean
    TierEvaluationService tierEvaluationService(
            UnitOfWork unitOfWork,
            MemberTiers memberTiers,
            ProgramReader programReader,
            TierListRevisions tierListRevisions,
            TierEvents tierEvents,
            TenantContext tenantContext,
            Clock clock,
            LoyaltySettings settings) {
        return new TierEvaluationService(unitOfWork, memberTiers, programReader, tierListRevisions, tierEvents,
                tenantContext, clock, settings.tierEvaluation().batchSize());
    }

    @Bean
    TierEvaluationAdapter tierEvaluationAdapter(TierEvaluationService tierEvaluationService) {
        return new TierEvaluationAdapter(tierEvaluationService);
    }
}
