package dev.ngb.booking.boot.config;

import dev.ngb.booking.catalog.adapter.block.persistence.JooqBlockReadRepository;
import dev.ngb.booking.catalog.adapter.block.persistence.JooqBlockRepository;
import dev.ngb.booking.catalog.adapter.recurrence.rfc5545.Rfc5545RecurrenceExpander;
import dev.ngb.booking.catalog.adapter.session.persistence.JooqSessionReadRepository;
import dev.ngb.booking.catalog.adapter.session.persistence.JooqSessionRepository;
import dev.ngb.booking.catalog.application.block.command.CreateBlockUseCase;
import dev.ngb.booking.catalog.application.block.command.DeleteBlockUseCase;
import dev.ngb.booking.catalog.application.block.port.BlockRepository;
import dev.ngb.booking.catalog.application.block.query.BlockQueries;
import dev.ngb.booking.catalog.application.block.query.BlockReadRepository;
import dev.ngb.booking.catalog.application.block.service.BlockRules;
import dev.ngb.booking.catalog.application.offering.query.OfferingReadRepository;
import dev.ngb.booking.catalog.application.recurrence.port.RecurrenceExpander;
import dev.ngb.booking.catalog.application.recurrence.service.RecurrencePreview;
import dev.ngb.booking.catalog.application.recurrence.service.RecurrenceValidator;
import dev.ngb.booking.catalog.application.resource.query.ResourceReadRepository;
import dev.ngb.booking.catalog.application.session.command.CancelSessionUseCase;
import dev.ngb.booking.catalog.application.session.command.CreateSessionSeriesUseCase;
import dev.ngb.booking.catalog.application.session.command.MaterializeHorizonUseCase;
import dev.ngb.booking.catalog.application.session.command.PatchSessionSeriesUseCase;
import dev.ngb.booking.catalog.application.session.command.ResizePoolsUseCase;
import dev.ngb.booking.catalog.application.session.port.SessionRepository;
import dev.ngb.booking.catalog.application.session.query.SessionQueries;
import dev.ngb.booking.catalog.application.session.query.SessionReadRepository;
import dev.ngb.booking.catalog.application.session.service.SessionMaterializer;
import dev.ngb.booking.catalog.application.session.service.SessionRules;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the catalog's recurrence, blocks and sessions: the port the rest of the app expands a rule through, the closed
 * periods a resource carries, the series that repeat, the sessions they materialize and the pools of each session.
 *
 * <p>The recurrence engine is named in exactly one place, so replacing it touches no callers. The horizon a series is
 * materialized to is the offering's own, and the tenant-level default is read here once so the application layer never
 * sees a settings tree.
 */
@Configuration(proxyBeanMethods = false)
class CatalogSessionConfiguration {

    @Bean
    RecurrenceExpander recurrenceExpander() {
        return new Rfc5545RecurrenceExpander();
    }

    @Bean
    RecurrenceValidator recurrenceValidator(RecurrenceExpander expander) {
        return new RecurrenceValidator(expander);
    }

    @Bean
    RecurrencePreview recurrencePreview(RecurrenceValidator recurrence) {
        return new RecurrencePreview(recurrence);
    }

    @Bean
    BlockRepository blockRepository(DSLContext dsl) {
        return new JooqBlockRepository(dsl);
    }

    @Bean
    BlockReadRepository blockReadRepository(DSLContext dsl) {
        return new JooqBlockReadRepository(dsl);
    }

    @Bean
    BlockQueries blockQueries(UnitOfWork unitOfWork, BlockReadRepository blocks) {
        return new BlockQueries(unitOfWork, blocks);
    }

    @Bean
    BlockRules blockRules(ResourceReadRepository resources, RecurrenceValidator recurrence) {
        return new BlockRules(resources, recurrence);
    }

    @Bean
    CreateBlockUseCase createBlockUseCase(
            UnitOfWork unitOfWork,
            BlockRepository blocks,
            BlockRules rules,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new CreateBlockUseCase(unitOfWork, blocks, rules, tenantContext, ids, clock);
    }

    @Bean
    DeleteBlockUseCase deleteBlockUseCase(UnitOfWork unitOfWork, BlockRepository blocks) {
        return new DeleteBlockUseCase(unitOfWork, blocks);
    }

    @Bean
    SessionRepository sessionRepository(DSLContext dsl, TenantContext tenantContext) {
        return new JooqSessionRepository(dsl, tenantContext);
    }

    @Bean
    SessionReadRepository sessionReadRepository(DSLContext dsl) {
        return new JooqSessionReadRepository(dsl);
    }

    @Bean
    SessionQueries sessionQueries(UnitOfWork unitOfWork, SessionReadRepository sessions) {
        return new SessionQueries(unitOfWork, sessions);
    }

    @Bean
    SessionRules sessionRules(OfferingReadRepository offerings) {
        return new SessionRules(offerings);
    }

    @Bean
    SessionMaterializer sessionMaterializer(
            RecurrenceValidator recurrence,
            SessionRepository sessions,
            OfferingReadRepository offerings,
            TenantContext tenantContext,
            IdGenerator ids,
            BookingSettings settings) {
        return new SessionMaterializer(
                recurrence, sessions, offerings, tenantContext, ids, (int) settings.timings().horizonDays());
    }

    @Bean
    CreateSessionSeriesUseCase createSessionSeriesUseCase(
            UnitOfWork unitOfWork,
            SessionRepository sessions,
            SessionRules rules,
            SessionMaterializer materializer,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new CreateSessionSeriesUseCase(
                unitOfWork, sessions, rules, materializer, tenantContext, ids, clock);
    }

    @Bean
    PatchSessionSeriesUseCase patchSessionSeriesUseCase(
            UnitOfWork unitOfWork, SessionRepository sessions, SessionMaterializer materializer, Clock clock) {
        return new PatchSessionSeriesUseCase(unitOfWork, sessions, materializer, clock);
    }

    @Bean
    CancelSessionUseCase cancelSessionUseCase(UnitOfWork unitOfWork, SessionRepository sessions, Clock clock) {
        return new CancelSessionUseCase(unitOfWork, sessions, clock);
    }

    @Bean
    ResizePoolsUseCase resizePoolsUseCase(UnitOfWork unitOfWork, SessionRepository sessions) {
        return new ResizePoolsUseCase(unitOfWork, sessions);
    }

    @Bean
    MaterializeHorizonUseCase materializeHorizonUseCase(
            UnitOfWork unitOfWork, SessionRepository sessions, SessionMaterializer materializer, Clock clock) {
        return new MaterializeHorizonUseCase(unitOfWork, sessions, materializer, clock);
    }
}
