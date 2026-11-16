package dev.ngb.survey.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.survey.authoring.adapter.document.schema.JsonSchemaDocumentVerifier;
import dev.ngb.survey.authoring.adapter.draft.persistence.JooqDraftReadRepository;
import dev.ngb.survey.authoring.adapter.draft.persistence.JooqDraftRepository;
import dev.ngb.survey.authoring.adapter.flow.persistence.JooqFlowDocumentReadRepository;
import dev.ngb.survey.authoring.adapter.survey.persistence.JooqSurveyReadRepository;
import dev.ngb.survey.authoring.adapter.survey.persistence.JooqSurveyRepository;
import dev.ngb.survey.authoring.adapter.version.persistence.JooqVersionReadRepository;
import dev.ngb.survey.authoring.adapter.version.persistence.JooqVersionRepository;
import dev.ngb.survey.authoring.api.flow.SurveyFlow;
import dev.ngb.survey.authoring.application.draft.command.SaveDraftUseCase;
import dev.ngb.survey.authoring.application.draft.command.ValidateDraftUseCase;
import dev.ngb.survey.authoring.application.draft.port.DocumentVerifier;
import dev.ngb.survey.authoring.application.draft.port.DraftRepository;
import dev.ngb.survey.authoring.application.draft.query.DraftQueries;
import dev.ngb.survey.authoring.application.draft.query.DraftReadRepository;
import dev.ngb.survey.authoring.application.draft.service.DraftValidationService;
import dev.ngb.survey.authoring.application.flow.query.FlowDocumentReadRepository;
import dev.ngb.survey.authoring.application.flow.query.PreviewQueries;
import dev.ngb.survey.authoring.application.flow.query.SimulateQueries;
import dev.ngb.survey.authoring.application.flow.service.PreviewService;
import dev.ngb.survey.authoring.application.flow.service.SurveyFlowService;
import dev.ngb.survey.authoring.application.survey.command.CloseSurveyUseCase;
import dev.ngb.survey.authoring.application.survey.command.CreateSurveyUseCase;
import dev.ngb.survey.authoring.application.survey.command.ReopenSurveyUseCase;
import dev.ngb.survey.authoring.application.survey.port.SurveyRepository;
import dev.ngb.survey.authoring.application.survey.query.SurveyQueries;
import dev.ngb.survey.authoring.application.survey.query.SurveyReadRepository;
import dev.ngb.survey.authoring.application.version.command.PublishSurveyUseCase;
import dev.ngb.survey.authoring.application.version.port.VersionRepository;
import dev.ngb.survey.authoring.application.version.query.VersionQueries;
import dev.ngb.survey.authoring.application.version.query.VersionReadRepository;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires the authoring context: the survey pointer, its draft, its versions and the rules both publish by. */
@Configuration(proxyBeanMethods = false)
class AuthoringConfiguration {

    @Bean
    DocumentVerifier documentVerifier(JsonMapper json) {
        return new JsonSchemaDocumentVerifier(json);
    }

    @Bean
    DraftValidationService draftValidationService(DocumentVerifier verifier) {
        return new DraftValidationService(verifier);
    }

    @Bean
    SurveyRepository surveyRepository(DSLContext dsl) {
        return new JooqSurveyRepository(dsl);
    }

    @Bean
    SurveyReadRepository surveyReadRepository(DSLContext dsl) {
        return new JooqSurveyReadRepository(dsl);
    }

    @Bean
    DraftRepository draftRepository(DSLContext dsl, JsonMapper json) {
        return new JooqDraftRepository(dsl, json);
    }

    @Bean
    DraftReadRepository draftReadRepository(DSLContext dsl, JsonMapper json) {
        return new JooqDraftReadRepository(dsl, json);
    }

    @Bean
    VersionRepository versionRepository(DSLContext dsl, JsonMapper json) {
        return new JooqVersionRepository(dsl, json);
    }

    @Bean
    VersionReadRepository versionReadRepository(DSLContext dsl, JsonMapper json) {
        return new JooqVersionReadRepository(dsl, json);
    }

    @Bean
    SurveyQueries surveyQueries(UnitOfWork unitOfWork, SurveyReadRepository surveys) {
        return new SurveyQueries(unitOfWork, surveys);
    }

    @Bean
    DraftQueries draftQueries(UnitOfWork unitOfWork, DraftReadRepository drafts) {
        return new DraftQueries(unitOfWork, drafts);
    }

    @Bean
    VersionQueries versionQueries(UnitOfWork unitOfWork, VersionReadRepository versions) {
        return new VersionQueries(unitOfWork, versions);
    }

    @Bean
    CreateSurveyUseCase createSurveyUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            SurveyRepository surveys,
            DraftRepository drafts,
            IdGenerator ids,
            Clock clock) {
        return new CreateSurveyUseCase(unitOfWork, tenantContext, surveys, drafts, ids, clock);
    }

    @Bean
    CloseSurveyUseCase closeSurveyUseCase(UnitOfWork unitOfWork, SurveyRepository surveys, Clock clock) {
        return new CloseSurveyUseCase(unitOfWork, surveys, clock);
    }

    @Bean
    ReopenSurveyUseCase reopenSurveyUseCase(UnitOfWork unitOfWork, SurveyRepository surveys, Clock clock) {
        return new ReopenSurveyUseCase(unitOfWork, surveys, clock);
    }

    @Bean
    SaveDraftUseCase saveDraftUseCase(
            UnitOfWork unitOfWork,
            SurveyRepository surveys,
            DraftRepository drafts,
            DocumentVerifier verifier,
            IdGenerator ids,
            Clock clock) {
        return new SaveDraftUseCase(unitOfWork, surveys, drafts, verifier, ids, clock);
    }

    @Bean
    ValidateDraftUseCase validateDraftUseCase(
            UnitOfWork unitOfWork,
            SurveyRepository surveys,
            DraftValidationService validation,
            IdGenerator ids) {
        return new ValidateDraftUseCase(unitOfWork, surveys, validation, ids);
    }

    @Bean
    PublishSurveyUseCase publishSurveyUseCase(
            UnitOfWork unitOfWork,
            SurveyRepository surveys,
            DraftRepository drafts,
            VersionRepository versions,
            DraftValidationService validation,
            IdGenerator ids,
            Clock clock) {
        return new PublishSurveyUseCase(unitOfWork, surveys, drafts, versions, validation, ids, clock);
    }

    @Bean
    SurveyFlow surveyFlow() {
        return new SurveyFlowService();
    }

    @Bean
    FlowDocumentReadRepository flowDocumentReadRepository(DSLContext dsl, JsonMapper json) {
        return new JooqFlowDocumentReadRepository(dsl, json);
    }

    @Bean
    PreviewService previewService() {
        return new PreviewService();
    }

    @Bean
    PreviewQueries previewQueries(
            UnitOfWork unitOfWork, FlowDocumentReadRepository documents, PreviewService preview) {
        return new PreviewQueries(unitOfWork, documents, preview);
    }

    @Bean
    SimulateQueries simulateQueries(
            UnitOfWork unitOfWork, FlowDocumentReadRepository documents, SurveyFlow flow) {
        return new SimulateQueries(unitOfWork, documents, flow);
    }
}
