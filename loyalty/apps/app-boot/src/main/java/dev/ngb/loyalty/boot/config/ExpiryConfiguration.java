package dev.ngb.loyalty.boot.config;

import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.boot.infrastructure.lease.JobLease;
import dev.ngb.loyalty.points.adapter.expiry.persistence.JooqExpiryRepository;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.points.application.expiry.command.ExpirePointsUseCase;
import dev.ngb.loyalty.points.application.expiry.command.NoticeExpiringPointsUseCase;
import dev.ngb.loyalty.points.application.expiry.port.ExpiryRepository;
import dev.ngb.loyalty.points.application.ledger.port.LedgerRepository;
import dev.ngb.loyalty.points.application.member.port.MemberRepository;
import dev.ngb.loyalty.program.api.program.ProgramReader;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the two scheduled runs that keep the ledger in step with time: the write-off of expired lots and the advance
 * notice about the ones about to expire.
 *
 * <p>Both runs share one lease mechanism, because both are per-tenant sweeps a second worker instance must not repeat at
 * the same moment.
 */
@Configuration(proxyBeanMethods = false)
class ExpiryConfiguration {

    @Bean
    JobLease expiryJobLease(PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        return new JobLease(transactionManager, jdbc);
    }

    @Bean
    ExpiryRepository expiryRepository(DSLContext dsl, TenantContext tenantContext) {
        return new JooqExpiryRepository(dsl, tenantContext);
    }

    @Bean
    ExpirePointsUseCase expirePointsUseCase(
            UnitOfWork unitOfWork,
            MemberRepository members,
            LedgerRepository ledger,
            ExpiryRepository expiry,
            PointsEvents events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock,
            LoyaltySettings settings) {
        return new ExpirePointsUseCase(unitOfWork, members, ledger, expiry, events, tenantContext, ids, clock,
                settings.expiry().batchSize());
    }

    @Bean
    NoticeExpiringPointsUseCase noticeExpiringPointsUseCase(
            UnitOfWork unitOfWork,
            MemberRepository members,
            LedgerRepository ledger,
            ExpiryRepository expiry,
            ProgramReader programReader,
            PointsEvents events,
            TenantContext tenantContext,
            Clock clock,
            LoyaltySettings settings) {
        return new NoticeExpiringPointsUseCase(unitOfWork, members, ledger, expiry, programReader, events, tenantContext,
                clock, settings.expiry().batchSize());
    }
}
