package dev.ngb.booking.boot.config;

import dev.ngb.booking.catalog.adapter.group.persistence.JooqGroupReadRepository;
import dev.ngb.booking.catalog.adapter.group.persistence.JooqGroupRepository;
import dev.ngb.booking.catalog.adapter.offering.persistence.JooqOfferingReadRepository;
import dev.ngb.booking.catalog.adapter.offering.persistence.JooqOfferingRepository;
import dev.ngb.booking.catalog.application.group.command.CreateGroupUseCase;
import dev.ngb.booking.catalog.application.group.command.PatchGroupUseCase;
import dev.ngb.booking.catalog.application.group.port.GroupRepository;
import dev.ngb.booking.catalog.application.group.query.GroupQueries;
import dev.ngb.booking.catalog.application.group.query.GroupReadRepository;
import dev.ngb.booking.catalog.application.offering.command.ArchiveOfferingUseCase;
import dev.ngb.booking.catalog.application.offering.command.CreateOfferingUseCase;
import dev.ngb.booking.catalog.application.offering.command.PatchOfferingUseCase;
import dev.ngb.booking.catalog.application.offering.command.ReplaceCancellationWindowsUseCase;
import dev.ngb.booking.catalog.application.offering.command.ReplaceRequirementsUseCase;
import dev.ngb.booking.catalog.application.offering.port.OfferingRepository;
import dev.ngb.booking.catalog.application.offering.query.OfferingQueries;
import dev.ngb.booking.catalog.application.offering.query.OfferingReadRepository;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the catalog's offering side: the groups and offerings repositories, their use cases and their queries. The
 * clock, the id generator and the unit of work come from the infrastructure configuration, so nothing here opens a
 * transaction of its own.
 */
@Configuration(proxyBeanMethods = false)
class CatalogOfferingConfiguration {

    @Bean
    GroupRepository groupRepository(DSLContext dsl) {
        return new JooqGroupRepository(dsl);
    }

    @Bean
    GroupReadRepository groupReadRepository(DSLContext dsl) {
        return new JooqGroupReadRepository(dsl);
    }

    @Bean
    GroupQueries groupQueries(UnitOfWork unitOfWork, GroupReadRepository groups) {
        return new GroupQueries(unitOfWork, groups);
    }

    @Bean
    CreateGroupUseCase createGroupUseCase(
            UnitOfWork unitOfWork, GroupRepository groups, TenantContext tenantContext, IdGenerator ids, Clock clock) {
        return new CreateGroupUseCase(unitOfWork, groups, tenantContext, ids, clock);
    }

    @Bean
    PatchGroupUseCase patchGroupUseCase(UnitOfWork unitOfWork, GroupRepository groups, Clock clock) {
        return new PatchGroupUseCase(unitOfWork, groups, clock);
    }

    @Bean
    OfferingRepository offeringRepository(DSLContext dsl) {
        return new JooqOfferingRepository(dsl);
    }

    @Bean
    OfferingReadRepository offeringReadRepository(DSLContext dsl) {
        return new JooqOfferingReadRepository(dsl);
    }

    @Bean
    OfferingQueries offeringQueries(UnitOfWork unitOfWork, OfferingReadRepository offerings) {
        return new OfferingQueries(unitOfWork, offerings);
    }

    @Bean
    CreateOfferingUseCase createOfferingUseCase(
            UnitOfWork unitOfWork,
            OfferingRepository offerings,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new CreateOfferingUseCase(unitOfWork, offerings, tenantContext, ids, clock);
    }

    @Bean
    PatchOfferingUseCase patchOfferingUseCase(UnitOfWork unitOfWork, OfferingRepository offerings, Clock clock) {
        return new PatchOfferingUseCase(unitOfWork, offerings, clock);
    }

    @Bean
    ArchiveOfferingUseCase archiveOfferingUseCase(UnitOfWork unitOfWork, OfferingRepository offerings, Clock clock) {
        return new ArchiveOfferingUseCase(unitOfWork, offerings, clock);
    }

    @Bean
    ReplaceRequirementsUseCase replaceRequirementsUseCase(
            UnitOfWork unitOfWork, OfferingRepository offerings, Clock clock) {
        return new ReplaceRequirementsUseCase(unitOfWork, offerings, clock);
    }

    @Bean
    ReplaceCancellationWindowsUseCase replaceCancellationWindowsUseCase(
            UnitOfWork unitOfWork, OfferingRepository offerings, Clock clock) {
        return new ReplaceCancellationWindowsUseCase(unitOfWork, offerings, clock);
    }
}
