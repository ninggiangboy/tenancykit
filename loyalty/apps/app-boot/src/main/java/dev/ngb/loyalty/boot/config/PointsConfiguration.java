package dev.ngb.loyalty.boot.config;

import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.points.adapter.ledger.persistence.JooqLedgerReadRepository;
import dev.ngb.loyalty.points.adapter.ledger.persistence.JooqLedgerRepository;
import dev.ngb.loyalty.points.adapter.member.persistence.JooqMemberReadRepository;
import dev.ngb.loyalty.points.adapter.member.persistence.JooqMemberRepository;
import dev.ngb.loyalty.points.adapter.order.persistence.EarnSnapshotJson;
import dev.ngb.loyalty.points.adapter.order.persistence.JooqOrderReadRepository;
import dev.ngb.loyalty.points.adapter.order.persistence.JooqOrderRepository;
import dev.ngb.loyalty.points.adapter.redemption.persistence.JooqRedemptionReadRepository;
import dev.ngb.loyalty.points.adapter.redemption.persistence.JooqRedemptionRepository;
import dev.ngb.loyalty.points.api.redemption.PointSpender;
import dev.ngb.loyalty.points.application.earn.port.TierEvaluator;
import dev.ngb.loyalty.points.application.earn.service.PointsApplier;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.points.application.ledger.port.LedgerRepository;
import dev.ngb.loyalty.points.application.ledger.query.LedgerQueries;
import dev.ngb.loyalty.points.application.ledger.query.LedgerReadRepository;
import dev.ngb.loyalty.points.application.member.command.EnrolMemberUseCase;
import dev.ngb.loyalty.points.application.member.port.MemberRepository;
import dev.ngb.loyalty.points.application.member.query.MemberQueries;
import dev.ngb.loyalty.points.application.member.query.MemberReadRepository;
import dev.ngb.loyalty.points.application.member.query.TierProgressReadRepository;
import dev.ngb.loyalty.points.application.order.command.RecordOrderUseCase;
import dev.ngb.loyalty.points.application.order.command.RecordReturnUseCase;
import dev.ngb.loyalty.points.application.order.port.OrderRepository;
import dev.ngb.loyalty.points.application.order.query.OrderQueries;
import dev.ngb.loyalty.points.application.order.query.OrderReadRepository;
import dev.ngb.loyalty.points.application.redemption.command.RedeemPointsUseCase;
import dev.ngb.loyalty.points.application.redemption.command.ReverseRedemptionUseCase;
import dev.ngb.loyalty.points.application.redemption.port.RedemptionReversalHook;
import dev.ngb.loyalty.points.application.redemption.port.RedemptionRepository;
import dev.ngb.loyalty.points.application.redemption.query.RedemptionQueries;
import dev.ngb.loyalty.points.application.redemption.query.RedemptionReadRepository;
import dev.ngb.loyalty.points.application.redemption.service.PointSpenderService;
import dev.ngb.loyalty.points.domain.earn.EarnCalculator;
import dev.ngb.loyalty.program.api.program.ProgramReader;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the points context: its four features, the calculator they share, and the two questions it asks of the tiers.
 *
 * <p>Both answers come from the context that owns the tier list, through the seams this context declares: the rise check
 * that runs after an order and the distance to the next tier a member read shows. Neither side imports the other.
 */
@Configuration(proxyBeanMethods = false)
class PointsConfiguration {

    @Bean
    MemberRepository memberRepository(DSLContext dsl, TenantContext tenantContext) {
        return new JooqMemberRepository(dsl, tenantContext);
    }

    @Bean
    MemberReadRepository memberReadRepository(DSLContext dsl) {
        return new JooqMemberReadRepository(dsl);
    }

    @Bean
    OrderRepository orderRepository(DSLContext dsl, TenantContext tenantContext, EarnSnapshotJson snapshots) {
        return new JooqOrderRepository(dsl, tenantContext, snapshots);
    }

    @Bean
    OrderReadRepository orderReadRepository(DSLContext dsl, EarnSnapshotJson snapshots) {
        return new JooqOrderReadRepository(dsl, snapshots);
    }

    @Bean
    EarnSnapshotJson earnSnapshotJson(JsonMapper json) {
        return new EarnSnapshotJson(json);
    }

    @Bean
    LedgerRepository ledgerRepository(DSLContext dsl, TenantContext tenantContext) {
        return new JooqLedgerRepository(dsl, tenantContext);
    }

    @Bean
    LedgerReadRepository ledgerReadRepository(DSLContext dsl) {
        return new JooqLedgerReadRepository(dsl);
    }

    @Bean
    RedemptionRepository redemptionRepository(DSLContext dsl, TenantContext tenantContext) {
        return new JooqRedemptionRepository(dsl, tenantContext);
    }

    @Bean
    RedemptionReadRepository redemptionReadRepository(DSLContext dsl) {
        return new JooqRedemptionReadRepository(dsl);
    }

    /**
     * The reversal's hook is a no-op until a feature issues something the spend must undo with it; the reversal's own
     * rules do not wait for that feature.
     */
    @Bean
    RedemptionReversalHook redemptionReversalHook() {
        return (memberId, redemptionId) -> {};
    }

    @Bean
    PointSpender pointSpender(
            MemberRepository members,
            LedgerRepository ledger,
            RedemptionRepository redemptions,
            PointsEvents events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new PointSpenderService(members, ledger, redemptions, events, tenantContext, ids, clock);
    }

    @Bean
    EarnCalculator earnCalculator() {
        return new EarnCalculator();
    }

    @Bean
    PointsApplier pointsApplier(LedgerRepository ledger, IdGenerator ids) {
        return new PointsApplier(ledger, ids);
    }

    @Bean
    PointsEvents pointsEvents(EventPublisher publisher, IdGenerator ids) {
        return new PointsEvents(publisher, ids);
    }

    @Bean
    EnrolMemberUseCase enrolMemberUseCase(
            UnitOfWork unitOfWork,
            MemberRepository members,
            PointsEvents events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new EnrolMemberUseCase(unitOfWork, members, events, tenantContext, ids, clock);
    }

    @Bean
    RecordOrderUseCase recordOrderUseCase(
            UnitOfWork unitOfWork,
            OrderRepository orders,
            MemberRepository members,
            LedgerRepository ledger,
            PointsApplier applier,
            EarnCalculator calculator,
            PointsEvents events,
            TierEvaluator tierEvaluator,
            ProgramReader programReader,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new RecordOrderUseCase(unitOfWork, orders, members, ledger, applier, calculator, events, tierEvaluator,
                programReader, tenantContext, ids, clock);
    }

    @Bean
    RecordReturnUseCase recordReturnUseCase(
            UnitOfWork unitOfWork,
            OrderRepository orders,
            MemberRepository members,
            LedgerRepository ledger,
            PointsApplier applier,
            EarnCalculator calculator,
            PointsEvents events,
            TierEvaluator tierEvaluator,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new RecordReturnUseCase(unitOfWork, orders, members, ledger, applier, calculator, events,
                tierEvaluator, tenantContext, ids, clock);
    }

    @Bean
    MemberQueries memberQueries(
            UnitOfWork unitOfWork,
            MemberReadRepository members,
            ProgramReader programReader,
            TierProgressReadRepository tierProgress,
            Clock clock) {
        return new MemberQueries(unitOfWork, members, programReader, tierProgress, clock);
    }

    @Bean
    OrderQueries orderQueries(UnitOfWork unitOfWork, OrderReadRepository orders, Clock clock) {
        return new OrderQueries(unitOfWork, orders, clock);
    }

    @Bean
    LedgerQueries ledgerQueries(
            UnitOfWork unitOfWork, MemberReadRepository members, LedgerReadRepository ledger, Clock clock) {
        return new LedgerQueries(unitOfWork, members, ledger, clock);
    }

    @Bean
    RedeemPointsUseCase redeemPointsUseCase(UnitOfWork unitOfWork, PointSpender spender) {
        return new RedeemPointsUseCase(unitOfWork, spender);
    }

    @Bean
    ReverseRedemptionUseCase reverseRedemptionUseCase(
            UnitOfWork unitOfWork,
            MemberRepository members,
            RedemptionRepository redemptions,
            LedgerRepository ledger,
            RedemptionReversalHook hook,
            PointsEvents events,
            TenantContext tenantContext,
            IdGenerator ids,
            Clock clock) {
        return new ReverseRedemptionUseCase(unitOfWork, members, redemptions, ledger, hook, events, tenantContext, ids,
                clock);
    }

    @Bean
    RedemptionQueries redemptionQueries(
            UnitOfWork unitOfWork, MemberReadRepository members, RedemptionReadRepository redemptions, Clock clock) {
        return new RedemptionQueries(unitOfWork, members, redemptions, clock);
    }
}
