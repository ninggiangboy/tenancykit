package dev.ngb.loyalty.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.api.tier.MemberTiers;
import dev.ngb.loyalty.program.adapter.program.persistence.JooqProgramReadRepository;
import dev.ngb.loyalty.program.adapter.program.persistence.JooqProgramRepository;
import dev.ngb.loyalty.program.adapter.promotion.persistence.JooqPromotionReadRepository;
import dev.ngb.loyalty.program.adapter.promotion.persistence.JooqPromotionRepository;
import dev.ngb.loyalty.program.adapter.promotion.persistence.PromotionConditionsJson;
import dev.ngb.loyalty.program.adapter.tier.persistence.JooqTierReadRepository;
import dev.ngb.loyalty.program.adapter.tier.persistence.JooqTierRepository;
import dev.ngb.loyalty.program.api.program.ProgramReader;
import dev.ngb.loyalty.program.api.tier.TierListRevisions;
import dev.ngb.loyalty.program.application.program.command.SaveProgramUseCase;
import dev.ngb.loyalty.program.application.program.port.LedgerEntryPort;
import dev.ngb.loyalty.program.application.program.port.ProgramRepository;
import dev.ngb.loyalty.program.application.program.query.ProgramQueries;
import dev.ngb.loyalty.program.application.program.query.ProgramReadRepository;
import dev.ngb.loyalty.program.application.program.service.ProgramReaderService;
import dev.ngb.loyalty.program.application.promotion.command.CreatePromotionUseCase;
import dev.ngb.loyalty.program.application.promotion.command.UpdatePromotionUseCase;
import dev.ngb.loyalty.program.application.promotion.port.PromotionRepository;
import dev.ngb.loyalty.program.application.promotion.query.PromotionQueries;
import dev.ngb.loyalty.program.application.promotion.query.PromotionReadRepository;
import dev.ngb.loyalty.program.application.tier.command.ReplaceTiersUseCase;
import dev.ngb.loyalty.program.application.tier.port.MemberTierPort;
import dev.ngb.loyalty.program.application.tier.port.TierRepository;
import dev.ngb.loyalty.program.application.tier.query.TierQueries;
import dev.ngb.loyalty.program.application.tier.query.TierReadRepository;
import dev.ngb.loyalty.program.application.tier.service.TierListRevisionsService;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the program context: its three features, their repositories and the two questions it asks of the contexts that
 * own the points ledger and the members' tiers.
 *
 * <p>The ledger question is still answered permissively because its rows arrive with the erasure rules: no ledger entry
 * means the currency is still free to change. The tier question is answered by the members themselves, so a tier a member
 * still holds cannot leave the list.
 */
@Configuration(proxyBeanMethods = false)
class ProgramConfiguration {

    @Bean
    ProgramRepository programRepository(DSLContext dsl) {
        return new JooqProgramRepository(dsl);
    }

    @Bean
    ProgramReadRepository programReadRepository(DSLContext dsl) {
        return new JooqProgramReadRepository(dsl);
    }

    @Bean
    TierRepository tierRepository(DSLContext dsl) {
        return new JooqTierRepository(dsl);
    }

    @Bean
    TierReadRepository tierReadRepository(DSLContext dsl) {
        return new JooqTierReadRepository(dsl);
    }

    @Bean
    PromotionConditionsJson promotionConditionsJson(JsonMapper json) {
        return new PromotionConditionsJson(json);
    }

    @Bean
    PromotionRepository promotionRepository(DSLContext dsl, PromotionConditionsJson conditions) {
        return new JooqPromotionRepository(dsl, conditions);
    }

    @Bean
    PromotionReadRepository promotionReadRepository(DSLContext dsl, PromotionConditionsJson conditions) {
        return new JooqPromotionReadRepository(dsl, conditions);
    }

    @Bean
    LedgerEntryPort ledgerEntryPort() {
        return () -> false;
    }

    @Bean
    MemberTierPort memberTierPort(MemberTiers memberTiers) {
        return memberTiers::holds;
    }

    @Bean
    TierListRevisions tierListRevisions(
            UnitOfWork unitOfWork, ProgramRepository programs, TenantContext tenantContext, Clock clock) {
        return new TierListRevisionsService(unitOfWork, programs, tenantContext, clock);
    }

    @Bean
    SaveProgramUseCase saveProgramUseCase(
            UnitOfWork unitOfWork,
            ProgramRepository programs,
            LedgerEntryPort ledgerEntries,
            TenantContext tenantContext,
            Clock clock) {
        return new SaveProgramUseCase(unitOfWork, programs, ledgerEntries, tenantContext, clock);
    }

    @Bean
    ReplaceTiersUseCase replaceTiersUseCase(
            UnitOfWork unitOfWork,
            ProgramRepository programs,
            TierRepository tiers,
            MemberTierPort memberTiers,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new ReplaceTiersUseCase(unitOfWork, programs, tiers, memberTiers, tenantContext, ids, clock);
    }

    @Bean
    CreatePromotionUseCase createPromotionUseCase(
            UnitOfWork unitOfWork,
            PromotionRepository promotions,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new CreatePromotionUseCase(unitOfWork, promotions, tenantContext, ids, clock);
    }

    @Bean
    UpdatePromotionUseCase updatePromotionUseCase(
            UnitOfWork unitOfWork, PromotionRepository promotions, Clock clock) {
        return new UpdatePromotionUseCase(unitOfWork, promotions, clock);
    }

    @Bean
    ProgramQueries programQueries(UnitOfWork unitOfWork, ProgramReadRepository programs) {
        return new ProgramQueries(unitOfWork, programs);
    }

    @Bean
    TierQueries tierQueries(UnitOfWork unitOfWork, ProgramReadRepository programs, TierReadRepository tiers) {
        return new TierQueries(unitOfWork, programs, tiers);
    }

    @Bean
    PromotionQueries promotionQueries(
            UnitOfWork unitOfWork, ProgramReadRepository programs, PromotionReadRepository promotions) {
        return new PromotionQueries(unitOfWork, programs, promotions);
    }

    @Bean
    ProgramReader programReader(
            UnitOfWork unitOfWork,
            ProgramReadRepository programs,
            TierReadRepository tiers,
            PromotionReadRepository promotions) {
        return new ProgramReaderService(unitOfWork, programs, tiers, promotions);
    }
}
