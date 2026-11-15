package dev.ngb.mailroom.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.mailroom.content.adapter.render.mustache.JmustacheTemplateRenderer;
import dev.ngb.mailroom.content.adapter.template.persistence.JooqTemplateReadRepository;
import dev.ngb.mailroom.content.adapter.template.persistence.JooqTemplateRepository;
import dev.ngb.mailroom.content.api.render.RenderTemplate;
import dev.ngb.mailroom.content.application.render.port.TemplateRenderer;
import dev.ngb.mailroom.content.application.render.service.RenderTemplateService;
import dev.ngb.mailroom.content.application.template.command.CreateTemplateUseCase;
import dev.ngb.mailroom.content.application.template.command.DeleteTemplateUseCase;
import dev.ngb.mailroom.content.application.template.command.PreviewTemplateUseCase;
import dev.ngb.mailroom.content.application.template.command.UpdateTemplateUseCase;
import dev.ngb.mailroom.content.application.template.port.TemplateRepository;
import dev.ngb.mailroom.content.application.template.query.TemplateQueries;
import dev.ngb.mailroom.content.application.template.query.TemplateReadRepository;
import dev.ngb.mailroom.content.domain.template.RenderLimits;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the content context: the template store, the sandboxed renderer and the use cases around them. */
@Configuration(proxyBeanMethods = false)
class ContentConfiguration {

    @Bean
    RenderLimits renderLimits() {
        return RenderLimits.DEFAULT;
    }

    @Bean
    TemplateRepository templateRepository(DSLContext dsl) {
        return new JooqTemplateRepository(dsl);
    }

    @Bean
    TemplateReadRepository templateReadRepository(DSLContext dsl) {
        return new JooqTemplateReadRepository(dsl);
    }

    @Bean
    TemplateRenderer templateRenderer(RenderLimits limits) {
        return new JmustacheTemplateRenderer(limits);
    }

    @Bean
    RenderTemplate renderTemplate(
            UnitOfWork unitOfWork, TemplateRepository templates, TemplateRenderer renderer) {
        return new RenderTemplateService(unitOfWork, templates, renderer);
    }

    @Bean
    TemplateQueries templateQueries(UnitOfWork unitOfWork, TemplateReadRepository templates) {
        return new TemplateQueries(unitOfWork, templates);
    }

    @Bean
    CreateTemplateUseCase createTemplateUseCase(
            UnitOfWork unitOfWork,
            TenantContext tenantContext,
            TemplateRepository templates,
            IdGenerator ids,
            Clock clock,
            RenderLimits limits) {
        return new CreateTemplateUseCase(unitOfWork, tenantContext, templates, ids, clock, limits);
    }

    @Bean
    UpdateTemplateUseCase updateTemplateUseCase(
            UnitOfWork unitOfWork, TemplateRepository templates, IdGenerator ids, Clock clock, RenderLimits limits) {
        return new UpdateTemplateUseCase(unitOfWork, templates, ids, clock, limits);
    }

    @Bean
    DeleteTemplateUseCase deleteTemplateUseCase(UnitOfWork unitOfWork, TemplateRepository templates) {
        return new DeleteTemplateUseCase(unitOfWork, templates);
    }

    @Bean
    PreviewTemplateUseCase previewTemplateUseCase(
            UnitOfWork unitOfWork, TemplateRepository templates, TemplateRenderer renderer) {
        return new PreviewTemplateUseCase(unitOfWork, templates, renderer);
    }
}
