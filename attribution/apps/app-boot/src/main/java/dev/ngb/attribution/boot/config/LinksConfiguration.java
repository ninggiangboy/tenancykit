package dev.ngb.attribution.boot.config;

import dev.ngb.attribution.links.adapter.campaign.persistence.JooqCampaignPolicyReader;
import dev.ngb.attribution.links.adapter.campaign.persistence.JooqCampaignReadRepository;
import dev.ngb.attribution.links.adapter.campaign.persistence.JooqCampaignRepository;
import dev.ngb.attribution.links.adapter.link.crypto.BcryptPasswordHasher;
import dev.ngb.attribution.links.adapter.link.persistence.JooqBulkRequestStore;
import dev.ngb.attribution.links.adapter.settings.persistence.JooqClickParamProvider;
import dev.ngb.attribution.links.adapter.link.persistence.JooqLinkReadRepository;
import dev.ngb.attribution.links.adapter.link.persistence.JooqLinkRepository;
import dev.ngb.attribution.links.adapter.link.persistence.JooqLinkStatsReadRepository;
import dev.ngb.attribution.links.adapter.link.qr.ZxingQrRenderer;
import dev.ngb.attribution.links.application.campaign.command.ArchiveCampaignUseCase;
import dev.ngb.attribution.links.application.campaign.command.CreateCampaignUseCase;
import dev.ngb.attribution.links.application.campaign.command.UpdateCampaignUseCase;
import dev.ngb.attribution.links.api.campaign.CampaignPolicyReader;
import dev.ngb.attribution.links.application.campaign.port.CampaignRepository;
import dev.ngb.attribution.links.application.campaign.port.RecomputeTrigger;
import dev.ngb.attribution.links.application.campaign.query.CampaignQueries;
import dev.ngb.attribution.links.application.campaign.query.CampaignReadRepository;
import dev.ngb.attribution.links.application.link.command.ChangeLinkStateUseCase;
import dev.ngb.attribution.links.application.link.command.CreateBulkLinksUseCase;
import dev.ngb.attribution.links.application.link.command.CreateLinkUseCase;
import dev.ngb.attribution.links.application.link.command.UpdateLinkUseCase;
import dev.ngb.attribution.links.application.link.port.BulkRequestStore;
import dev.ngb.attribution.links.application.link.port.ClickParamProvider;
import dev.ngb.attribution.links.application.link.port.LinkRepository;
import dev.ngb.attribution.links.application.link.port.PasswordHasher;
import dev.ngb.attribution.links.application.link.query.LinkQueries;
import dev.ngb.attribution.links.application.link.query.LinkReadRepository;
import dev.ngb.attribution.links.application.link.query.LinkStatsQueries;
import dev.ngb.attribution.links.application.link.query.LinkStatsReadRepository;
import dev.ngb.attribution.links.application.link.service.SuiteHosts;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

/** Wires the links context: campaigns, tracked links, destinations, the QR renderer and the bulk ledger. */
@Configuration(proxyBeanMethods = false)
class LinksConfiguration {

    /**
     * The suite's own hosts a destination may not point at. The base URL is where {@code /go/<slug>} is served, so a
     * link previews and a QR code encode the same address every instance agrees on.
     */
    @Bean
    SuiteHosts suiteHosts(AttributionSettings settings) {
        String base = settings.keys().publicBaseUrl();
        return new SuiteHosts(base == null || base.isBlank() ? List.of() : List.of(), base);
    }

    @Bean
    CampaignRepository campaignRepository(DSLContext dsl) {
        return new JooqCampaignRepository(dsl);
    }

    @Bean
    CampaignReadRepository campaignReadRepository(DSLContext dsl) {
        return new JooqCampaignReadRepository(dsl);
    }

    @Bean
    CampaignPolicyReader campaignPolicyReader(DSLContext dsl) {
        return new JooqCampaignPolicyReader(dsl);
    }

    @Bean
    CampaignQueries campaignQueries(UnitOfWork unitOfWork, CampaignReadRepository campaigns) {
        return new CampaignQueries(unitOfWork, campaigns);
    }

    @Bean
    CreateCampaignUseCase createCampaignUseCase(
            UnitOfWork unitOfWork, TenantContext tenantContext, CampaignRepository campaigns, IdGenerator ids,
            Clock clock) {
        return new CreateCampaignUseCase(unitOfWork, tenantContext, campaigns, ids, clock);
    }

    @Bean
    UpdateCampaignUseCase updateCampaignUseCase(
            UnitOfWork unitOfWork, CampaignRepository campaigns, RecomputeTrigger recompute, Clock clock) {
        return new UpdateCampaignUseCase(unitOfWork, campaigns, recompute, clock);
    }

    @Bean
    ArchiveCampaignUseCase archiveCampaignUseCase(UnitOfWork unitOfWork, CampaignRepository campaigns, Clock clock) {
        return new ArchiveCampaignUseCase(unitOfWork, campaigns, clock);
    }

    @Bean
    LinkRepository linkRepository(DSLContext dsl, JsonMapper json, UnitOfWork unitOfWork) {
        // The redirect's capped-link counter runs in its own transaction, because row-level security binds through the
        // tenant setting the unit of work installs; a use case that already opened one joins it.
        return new JooqLinkRepository(dsl, json, unitOfWork);
    }

    @Bean
    LinkReadRepository linkReadRepository(DSLContext dsl, JsonMapper json) {
        return new JooqLinkReadRepository(dsl, json);
    }

    @Bean
    LinkStatsReadRepository linkStatsReadRepository(DSLContext dsl) {
        return new JooqLinkStatsReadRepository(dsl);
    }

    @Bean
    BulkRequestStore bulkRequestStore(DSLContext dsl, JsonMapper json, TenantContext tenantContext, Clock clock) {
        return new JooqBulkRequestStore(dsl, json, tenantContext, clock);
    }

    @Bean
    ClickParamProvider clickParamProvider(DSLContext dsl, TenantContext tenantContext, AttributionSettings settings) {
        return new JooqClickParamProvider(dsl, tenantContext, settings.defaults().clickParam());
    }

    @Bean
    PasswordHasher passwordHasher() {
        return new BcryptPasswordHasher(new BCryptPasswordEncoder());
    }

    @Bean
    ZxingQrRenderer zxingQrRenderer() {
        return new ZxingQrRenderer();
    }

    @Bean
    LinkQueries linkQueries(UnitOfWork unitOfWork, LinkReadRepository links, SuiteHosts hosts) {
        return new LinkQueries(unitOfWork, links);
    }

    @Bean
    LinkStatsQueries linkStatsQueries(UnitOfWork unitOfWork, LinkStatsReadRepository stats) {
        return new LinkStatsQueries(unitOfWork, stats);
    }

    @Bean
    CreateLinkUseCase createLinkUseCase(
            UnitOfWork unitOfWork, TenantContext tenantContext, LinkRepository links, SuiteHosts hosts,
            ClickParamProvider clickParam, PasswordHasher hasher, IdGenerator ids, Clock clock) {
        return new CreateLinkUseCase(
                unitOfWork, tenantContext, links, hosts, clickParam, hasher, ids, clock);
    }

    @Bean
    UpdateLinkUseCase updateLinkUseCase(
            UnitOfWork unitOfWork, LinkRepository links, SuiteHosts hosts, ClickParamProvider clickParam,
            PasswordHasher hasher, IdGenerator ids, Clock clock) {
        return new UpdateLinkUseCase(unitOfWork, links, hosts, clickParam, hasher, ids, clock);
    }

    @Bean
    ChangeLinkStateUseCase changeLinkStateUseCase(UnitOfWork unitOfWork, LinkRepository links, Clock clock) {
        return new ChangeLinkStateUseCase(unitOfWork, links, clock);
    }

    @Bean
    CreateBulkLinksUseCase createBulkLinksUseCase(
            UnitOfWork unitOfWork, TenantContext tenantContext, LinkRepository links, BulkRequestStore requests,
            SuiteHosts hosts, ClickParamProvider clickParam, IdGenerator ids, Clock clock) {
        return new CreateBulkLinksUseCase(
                unitOfWork, tenantContext, links, requests, hosts, clickParam, ids, clock);
    }
}
