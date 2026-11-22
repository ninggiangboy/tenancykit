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
import dev.ngb.survey.collection.adapter.response.persistence.JooqResponseReadRepository;
import dev.ngb.survey.collection.adapter.response.persistence.JooqResponseReader;
import dev.ngb.survey.collection.adapter.response.persistence.JooqResponseRepository;
import dev.ngb.survey.collection.adapter.upload.persistence.JooqUploadRepository;
import dev.ngb.survey.collection.adapter.upload.s3.S3UploadStore;
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
import dev.ngb.survey.collection.application.visit.command.AbandonIdleVisitsUseCase;
import dev.ngb.survey.collection.application.visit.command.OpenLinkUseCase;
import dev.ngb.survey.collection.application.visit.command.RecordPageViewUseCase;
import dev.ngb.survey.collection.application.visit.command.StartVisitUseCase;
import dev.ngb.survey.collection.application.visit.port.VisitRepository;
import dev.ngb.survey.collection.application.visit.query.VisitQueries;
import dev.ngb.survey.collection.application.visit.query.VisitReadRepository;
import dev.ngb.survey.collection.application.visit.service.ArrivalFacts;
import dev.ngb.survey.collection.application.visit.service.VisitLookup;
import dev.ngb.survey.collection.application.response.command.DeleteResponseUseCase;
import dev.ngb.survey.collection.application.response.command.SaveDraftUseCase;
import dev.ngb.survey.collection.application.response.command.SubmitResponseUseCase;
import dev.ngb.survey.collection.api.response.ResponseDeleter;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.collection.application.response.port.ResponseRepository;
import dev.ngb.survey.collection.application.response.service.ResponseDeleterService;
import dev.ngb.survey.authoring.api.flow.AnswerIndexer;
import dev.ngb.survey.collection.application.response.query.ResponseQueries;
import dev.ngb.survey.collection.application.response.query.ResponseReadRepository;
import dev.ngb.survey.collection.application.upload.command.PresignUploadUseCase;
import dev.ngb.survey.collection.application.upload.command.PurgePendingUploadsUseCase;
import dev.ngb.survey.collection.application.upload.port.UploadRepository;
import dev.ngb.survey.collection.application.upload.port.UploadStore;
import dev.ngb.survey.collection.application.upload.service.UploadVerifier;
import dev.ngb.survey.authoring.api.flow.SurveyFlow;
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
            UnitOfWork unitOfWork, VisitReadRepository reads, PublishedVersionReader versions) {
        return new VisitQueries(unitOfWork, reads, versions);
    }

    @Bean
    VisitLookup visitLookup(
            UnitOfWork unitOfWork, VisitRepository visits, PublishedVersionReader versions) {
        return new VisitLookup(unitOfWork, visits, versions);
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
    UploadRepository uploadRepository(DSLContext dsl) {
        return new JooqUploadRepository(dsl);
    }

    @Bean
    UploadStore uploadStore(SurveySettings settings) {
        var uploads = settings.uploads();
        return new S3UploadStore(
                uploads.endpoint(),
                uploads.publicEndpoint(),
                uploads.bucket(),
                uploads.region(),
                uploads.accessKey(),
                uploads.secretKey());
    }

    @Bean
    ResponseRepository responseRepository(DSLContext dsl, JsonMapper json) {
        return new JooqResponseRepository(dsl, json);
    }

    @Bean
    ResponseReadRepository responseReadRepository(DSLContext dsl, JsonMapper json) {
        return new JooqResponseReadRepository(dsl, json);
    }

    @Bean
    ResponseReader responseReader(DSLContext dsl, JsonMapper json) {
        return new JooqResponseReader(dsl, json);
    }

    @Bean
    ResponseDeleter responseDeleter(DeleteResponseUseCase deleteResponseUseCase) {
        return new ResponseDeleterService(deleteResponseUseCase);
    }

    @Bean
    UploadVerifier uploadVerifier(UploadRepository uploads, UploadStore store, AnswerIndexer indexer) {
        return new UploadVerifier(uploads, store, indexer);
    }

    @Bean
    ResponseQueries responseQueries(UnitOfWork unitOfWork, ResponseReadRepository responses) {
        return new ResponseQueries(unitOfWork, responses);
    }

    @Bean
    SaveDraftUseCase saveResponseDraftUseCase(
            UnitOfWork unitOfWork,
            VisitRepository visits,
            ResponseRepository responses,
            IdGenerator ids,
            Clock clock) {
        return new SaveDraftUseCase(unitOfWork, visits, responses, ids, clock);
    }

    @Bean
    SubmitResponseUseCase submitResponseUseCase(
            UnitOfWork unitOfWork,
            VisitRepository visits,
            ResponseRepository responses,
            PublishedVersionReader versions,
            SurveyFlow flow,
            AnswerIndexer answers,
            UploadRepository uploads,
            UploadVerifier verifier,
            dev.ngb.core.event.EventPublisher events,
            IdGenerator ids,
            Clock clock) {
        return new SubmitResponseUseCase(
                unitOfWork, visits, responses, versions, flow, answers, uploads, verifier, events, ids, clock);
    }

    @Bean
    PresignUploadUseCase presignUploadUseCase(
            UnitOfWork unitOfWork,
            UploadRepository uploads,
            UploadStore store,
            AnswerIndexer limits,
            IdGenerator ids,
            Clock clock) {
        return new PresignUploadUseCase(unitOfWork, uploads, store, limits, ids, clock);
    }

    @Bean
    AbandonIdleVisitsUseCase abandonIdleVisitsUseCase(UnitOfWork unitOfWork, VisitRepository visits, Clock clock) {
        return new AbandonIdleVisitsUseCase(unitOfWork, visits, clock);
    }

    @Bean
    PurgePendingUploadsUseCase purgePendingUploadsUseCase(
            UnitOfWork unitOfWork, UploadRepository uploads, UploadStore store, Clock clock) {
        return new PurgePendingUploadsUseCase(unitOfWork, uploads, store, clock);
    }

    @Bean
    DeleteResponseUseCase deleteResponseUseCase(
            UnitOfWork unitOfWork,
            ResponseRepository responses,
            UploadRepository uploads,
            UploadStore store,
            VisitRepository visits) {
        return new DeleteResponseUseCase(unitOfWork, responses, uploads, store, visits);
    }

    @Bean
    PublicGuardFilter publicGuardFilter(
            PublicRateLimiter limiter, ClientFingerprint fingerprints, SurveySettings settings) {
        return new PublicGuardFilter(limiter, fingerprints, settings.publicApi().maxBodyBytes());
    }
}
