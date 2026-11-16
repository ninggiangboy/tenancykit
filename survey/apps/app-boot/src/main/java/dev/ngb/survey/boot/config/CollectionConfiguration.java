package dev.ngb.survey.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.core.visitor.GeoCountryReader;
import dev.ngb.survey.authoring.api.version.PublishedVersionReader;
import dev.ngb.survey.collection.adapter.guard.web.PublicGuardFilter;
import dev.ngb.survey.collection.adapter.link.persistence.JooqLinkReadRepository;
import dev.ngb.survey.collection.adapter.link.persistence.JooqLinkRepository;
import dev.ngb.survey.collection.adapter.visit.persistence.JooqVisitReadRepository;
import dev.ngb.survey.collection.adapter.visit.persistence.JooqVisitRepository;
import dev.ngb.survey.collection.application.guard.service.ClientFingerprint;
import dev.ngb.survey.collection.application.guard.service.PublicRateLimiter;
import dev.ngb.survey.collection.application.link.command.CreatePersonalLinksUseCase;
import dev.ngb.survey.collection.application.link.command.CreatePublicLinkUseCase;
import dev.ngb.survey.collection.application.link.command.RevokeLinkUseCase;
import dev.ngb.survey.collection.application.link.port.LinkRepository;
import dev.ngb.survey.collection.application.link.query.LinkQueries;
import dev.ngb.survey.collection.application.link.query.LinkReadRepository;
import dev.ngb.survey.collection.application.link.service.LinkUrls;
import dev.ngb.survey.collection.application.link.service.SurveyState;
import dev.ngb.survey.collection.application.visit.command.OpenLinkUseCase;
import dev.ngb.survey.collection.application.visit.command.RecordPageViewUseCase;
import dev.ngb.survey.collection.application.visit.command.StartVisitUseCase;
import dev.ngb.survey.collection.application.visit.port.VisitRepository;
import dev.ngb.survey.collection.application.visit.query.VisitQueries;
import dev.ngb.survey.collection.application.visit.query.VisitReadRepository;
import dev.ngb.survey.collection.application.visit.service.ArrivalFacts;
import dev.ngb.survey.collection.domain.link.LinkTokens;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires the collection context: links, visits, the public guards and the page events a browser reports. */
@Configuration(proxyBeanMethods = false)
class CollectionConfiguration {

    @Bean
    GeoCountryReader geoCountryReader(SurveySettings settings) {
        return GeoCountryReader.open(settings.geoip().path());
    }

    @Bean
    ArrivalFacts arrivalFacts(GeoCountryReader geo) {
        return new ArrivalFacts(geo);
    }

    @Bean
    LinkRepository linkRepository(DSLContext dsl) {
        return new JooqLinkRepository(dsl);
    }

    @Bean
    LinkReadRepository linkReadRepository(DSLContext dsl) {
        return new JooqLinkReadRepository(dsl);
    }

    @Bean
    VisitRepository visitRepository(DSLContext dsl, JsonMapper json) {
        return new JooqVisitRepository(dsl, json);
    }

    @Bean
    VisitReadRepository visitReadRepository(DSLContext dsl) {
        return new JooqVisitReadRepository(dsl);
    }

    @Bean
    LinkUrls linkUrls(SurveySettings settings, LinkConfiguration.LinkKeys keys) {
        return new LinkUrls(
                settings.links().publicBaseUrl(),
                new LinkTokens.Keys(keys.activeKeyId(), keys.keys()));
    }

    @Bean
    SurveyState surveyState(dev.ngb.survey.authoring.api.link.SurveyStateReader states) {
        return new SurveyState(states);
    }

    @Bean
    LinkQueries linkQueries(UnitOfWork unitOfWork, LinkReadRepository links, LinkUrls urls) {
        return new LinkQueries(unitOfWork, links, urls);
    }

    @Bean
    CreatePublicLinkUseCase createPublicLinkUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            LinkRepository links,
            SurveyState surveyState,
            IdGenerator ids,
            Clock clock) {
        return new CreatePublicLinkUseCase(unitOfWork, tenantContext, links, surveyState, ids, clock);
    }

    @Bean
    CreatePersonalLinksUseCase createPersonalLinksUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            LinkRepository links,
            SurveyState surveyState,
            IdGenerator ids,
            Clock clock) {
        return new CreatePersonalLinksUseCase(unitOfWork, tenantContext, links, surveyState, ids, clock);
    }

    @Bean
    RevokeLinkUseCase revokeLinkUseCase(
            UnitOfWork unitOfWork, TenantContext tenantContext, LinkRepository links) {
        return new RevokeLinkUseCase(unitOfWork, tenantContext, links);
    }

    @Bean
    StartVisitUseCase startVisitUseCase(
            UnitOfWork unitOfWork, VisitRepository visits, IdGenerator ids, Clock clock) {
        return new StartVisitUseCase(unitOfWork, visits, ids, clock);
    }

    @Bean
    OpenLinkUseCase openLinkUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            LinkRepository links,
            VisitRepository visits,
            SurveyState surveyState,
            PublishedVersionReader versions,
            ArrivalFacts arrivalFacts,
            StartVisitUseCase startVisit,
            Clock clock) {
        return new OpenLinkUseCase(
                unitOfWork, tenantContext, links, visits, surveyState, versions, arrivalFacts, startVisit, clock);
    }

    @Bean
    RecordPageViewUseCase recordPageViewUseCase(
            UnitOfWork unitOfWork, VisitRepository visits, IdGenerator ids, Clock clock) {
        return new RecordPageViewUseCase(unitOfWork, visits, ids, clock);
    }

    @Bean
    VisitQueries visitQueries(
            UnitOfWork unitOfWork, VisitReadRepository visits, PublishedVersionReader versions) {
        return new VisitQueries(unitOfWork, visits, versions);
    }

    @Bean
    ClientFingerprint clientFingerprint(SurveySettings settings, LinkConfiguration.LinkKeys keys) {
        // The fingerprint reuses the link secret, which every deployment already supplies; a separate key would be
        // another value to forget.
        return new ClientFingerprint(keys.keys().get(keys.activeKeyId()));
    }

    @Bean
    PublicRateLimiter publicRateLimiter(SurveySettings settings) {
        return new PublicRateLimiter(settings.publicApi().ratePerMinute(), System::currentTimeMillis);
    }

    @Bean
    PublicGuardFilter publicGuardFilter(
            PublicRateLimiter limiter, ClientFingerprint fingerprints, SurveySettings settings) {
        return new PublicGuardFilter(limiter, fingerprints, settings.publicApi().maxBodyBytes());
    }
}
