package dev.ngb.survey.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.survey.authoring.api.version.PublishedVersionReader;
import dev.ngb.survey.collection.api.response.ResponseReader;
import dev.ngb.survey.insight.adapter.analytics.version.AuthoringQuestionTypes;
import dev.ngb.survey.insight.adapter.export.persistence.JooqExportRepository;
import dev.ngb.survey.insight.adapter.export.s3.S3ExportStore;
import dev.ngb.survey.insight.application.analytics.query.QuestionTypes;
import dev.ngb.survey.insight.application.analytics.query.AggregateQueries;
import dev.ngb.survey.insight.application.analytics.query.CrossTabQueries;
import dev.ngb.survey.insight.application.analytics.query.TrendQueries;
import dev.ngb.survey.insight.application.export.command.RequestExportUseCase;
import dev.ngb.survey.insight.application.export.command.RunExportUseCase;
import dev.ngb.survey.insight.application.export.port.ExportRepository;
import dev.ngb.survey.insight.application.export.port.ExportStore;
import dev.ngb.survey.insight.application.export.query.ExportQueries;
import dev.ngb.survey.insight.application.export.service.CsvExportWriter;
import dev.ngb.survey.insight.application.funnel.query.FunnelQueries;
import dev.ngb.survey.insight.application.respondent.query.RespondentQueries;
import dev.ngb.survey.insight.application.response.query.ResponseQueries;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Wires the insight context: the reports it reads and the export ledger it writes. */
@Configuration(proxyBeanMethods = false)
class InsightConfiguration {

    @Bean
    QuestionTypes questionTypes(UnitOfWork unitOfWork, PublishedVersionReader versions) {
        return new AuthoringQuestionTypes(unitOfWork, versions);
    }

    @Bean
    AggregateQueries aggregateQueries(UnitOfWork unitOfWork, ResponseReader responses, QuestionTypes types) {
        return new AggregateQueries(unitOfWork, responses, types);
    }

    @Bean
    CrossTabQueries crossTabQueries(UnitOfWork unitOfWork, ResponseReader responses) {
        return new CrossTabQueries(unitOfWork, responses);
    }

    @Bean
    TrendQueries trendQueries(UnitOfWork unitOfWork, ResponseReader responses, QuestionTypes types) {
        return new TrendQueries(unitOfWork, responses, types);
    }

    @Bean
    FunnelQueries funnelQueries(UnitOfWork unitOfWork, ResponseReader responses) {
        return new FunnelQueries(unitOfWork, responses);
    }

    @Bean
    RespondentQueries respondentQueries(UnitOfWork unitOfWork, ResponseReader responses) {
        return new RespondentQueries(unitOfWork, responses);
    }

    @Bean
    ResponseQueries insightResponseQueries(UnitOfWork unitOfWork, ResponseReader responses) {
        return new ResponseQueries(unitOfWork, responses);
    }

    @Bean
    ExportRepository exportRepository(DSLContext dsl, JsonMapper json) {
        return new JooqExportRepository(dsl, json);
    }

    @Bean
    dev.ngb.survey.insight.application.export.query.ExportReadRepository exportReadRepository(DSLContext dsl) {
        return new dev.ngb.survey.insight.adapter.export.persistence.JooqExportReadRepository(dsl);
    }

    @Bean
    S3ExportStore exportStore(SurveySettings settings) {
        var uploads = settings.uploads();
        return new S3ExportStore(
                uploads.endpoint(),
                uploads.publicEndpoint(),
                uploads.bucket(),
                uploads.region(),
                uploads.accessKey(),
                uploads.secretKey());
    }

    @Bean
    CsvExportWriter csvExportWriter(ResponseReader responses) {
        return new CsvExportWriter(responses);
    }

    @Bean
    RequestExportUseCase requestExportUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            ExportRepository exports,
            IdGenerator ids,
            Clock clock) {
        return new RequestExportUseCase(unitOfWork, tenantContext, exports, ids, clock);
    }

    @Bean
    RunExportUseCase runExportUseCase(
            UnitOfWork unitOfWork,
            ExportRepository exports,
            ExportStore store,
            ResponseReader responses,
            PublishedVersionReader versions,
            CsvExportWriter writer,
            Clock clock) {
        return new RunExportUseCase(unitOfWork, exports, store, responses, versions, writer, clock);
    }

    @Bean
    ExportQueries exportQueries(
            UnitOfWork unitOfWork,
            dev.ngb.survey.insight.application.export.query.ExportReadRepository exports,
            S3ExportStore store) {
        return new ExportQueries(unitOfWork, exports, store);
    }
}
