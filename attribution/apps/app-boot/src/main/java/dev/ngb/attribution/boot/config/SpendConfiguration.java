package dev.ngb.attribution.boot.config;

import dev.ngb.attribution.links.api.campaign.CampaignPolicyReader;
import dev.ngb.attribution.spend.adapter.cost.persistence.JooqCostReader;
import dev.ngb.attribution.spend.adapter.entry.persistence.JooqSpendReadRepository;
import dev.ngb.attribution.spend.adapter.entry.persistence.JooqSpendRepository;
import dev.ngb.attribution.spend.api.cost.CostReader;
import dev.ngb.attribution.spend.application.entry.command.BulkSetSpendUseCase;
import dev.ngb.attribution.spend.application.entry.command.DeleteManualSpendUseCase;
import dev.ngb.attribution.spend.application.entry.command.SetManualSpendUseCase;
import dev.ngb.attribution.spend.application.entry.port.SpendRepository;
import dev.ngb.attribution.spend.application.entry.query.SpendQueries;
import dev.ngb.attribution.spend.application.entry.query.SpendReadRepository;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the spend context: the manual cost store, its read side and the cost rows insight reports. */
@Configuration(proxyBeanMethods = false)
class SpendConfiguration {

    @Bean
    SpendRepository spendRepository(DSLContext dsl) {
        return new JooqSpendRepository(dsl);
    }

    @Bean
    SpendReadRepository spendReadRepository(DSLContext dsl) {
        return new JooqSpendReadRepository(dsl);
    }

    @Bean
    CostReader costReader(DSLContext dsl) {
        return new JooqCostReader(dsl);
    }

    @Bean
    SpendQueries spendQueries(UnitOfWork unitOfWork, SpendReadRepository costs) {
        return new SpendQueries(unitOfWork, costs);
    }

    @Bean
    SetManualSpendUseCase setManualSpendUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            SpendRepository costs,
            CampaignPolicyReader campaigns,
            IdGenerator ids,
            Clock clock) {
        return new SetManualSpendUseCase(unitOfWork, tenantContext, costs, campaigns, ids, clock);
    }

    @Bean
    BulkSetSpendUseCase bulkSetSpendUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            SpendRepository costs,
            CampaignPolicyReader campaigns,
            IdGenerator ids,
            Clock clock) {
        return new BulkSetSpendUseCase(unitOfWork, tenantContext, costs, campaigns, ids, clock);
    }

    @Bean
    DeleteManualSpendUseCase deleteManualSpendUseCase(UnitOfWork unitOfWork, SpendRepository costs) {
        return new DeleteManualSpendUseCase(unitOfWork, costs);
    }
}
