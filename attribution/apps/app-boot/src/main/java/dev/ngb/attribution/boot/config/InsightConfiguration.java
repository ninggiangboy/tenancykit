package dev.ngb.attribution.boot.config;

import dev.ngb.attribution.attribution.api.conversion.ConversionRollupSource;
import dev.ngb.attribution.insight.adapter.report.persistence.JooqReportReadRepository;
import dev.ngb.attribution.insight.adapter.rollup.persistence.JooqRollupRepository;
import dev.ngb.attribution.insight.adapter.rollup.persistence.JooqRollupStateReader;
import dev.ngb.attribution.insight.api.rollup.RollupStateReader;
import dev.ngb.attribution.insight.application.report.query.LinkStatsQueries;
import dev.ngb.attribution.insight.application.report.query.ReportQueries;
import dev.ngb.attribution.insight.application.report.query.ReportReadRepository;
import dev.ngb.attribution.insight.application.rollup.command.RunRollupUseCase;
import dev.ngb.attribution.insight.application.rollup.port.RollupRepository;
import dev.ngb.attribution.links.api.campaign.CampaignPolicyReader;
import dev.ngb.attribution.links.api.link.LinkMetadataReader;
import dev.ngb.attribution.spend.api.cost.CostReader;
import dev.ngb.attribution.tracking.api.traffic.TrafficSource;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the insight context: its rollup store, its reports and the cost report that joins spend to the rollups. */
@Configuration(proxyBeanMethods = false)
class InsightConfiguration {

    @Bean
    RollupRepository rollupRepository(DSLContext dsl, TenantContext tenantContext) {
        return new JooqRollupRepository(dsl, tenantContext);
    }

    @Bean
    RollupStateReader rollupStateReader(DSLContext dsl) {
        return new JooqRollupStateReader(dsl);
    }

    @Bean
    RunRollupUseCase runRollupUseCase(
            UnitOfWork unitOfWork,
            TrafficSource traffic,
            ConversionRollupSource conversions,
            RollupRepository rollups,
            RollupStateReader state) {
        return new RunRollupUseCase(unitOfWork, traffic, conversions, rollups, state);
    }

    @Bean
    ReportReadRepository reportReadRepository(
            DSLContext dsl, TrafficSource traffic, ConversionRollupSource conversions) {
        return new JooqReportReadRepository(dsl, traffic, conversions);
    }

    @Bean
    LinkStatsQueries linkStatsQueries(
            UnitOfWork unitOfWork, ReportReadRepository reports, LinkMetadataReader links, RollupStateReader state) {
        return new LinkStatsQueries(unitOfWork, reports, links, state);
    }

    @Bean
    ReportQueries reportQueries(
            UnitOfWork unitOfWork,
            ReportReadRepository reports,
            RollupStateReader state,
            CostReader costs,
            CampaignPolicyReader campaigns,
            LinkMetadataReader links) {
        return new ReportQueries(unitOfWork, reports, state, costs, campaigns, links);
    }
}
