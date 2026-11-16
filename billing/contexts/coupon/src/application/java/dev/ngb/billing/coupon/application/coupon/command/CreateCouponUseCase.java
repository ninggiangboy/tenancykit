package dev.ngb.billing.coupon.application.coupon.command;

import dev.ngb.billing.coupon.application.coupon.port.CouponRepository;
import dev.ngb.billing.coupon.application.coupon.port.PromotionCodeRepository;
import dev.ngb.billing.coupon.application.coupon.service.PromotionCodes;
import dev.ngb.billing.coupon.domain.coupon.Benefit;
import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.Duration;
import dev.ngb.billing.coupon.domain.coupon.Limits;
import dev.ngb.billing.coupon.domain.coupon.PromotionCode;
import dev.ngb.billing.coupon.domain.coupon.PromotionCodeId;
import dev.ngb.billing.coupon.domain.coupon.Scope;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;

/**
 * Creates a coupon's immutable terms and, optionally, its first promotion code. The domain is the only validator: a
 * coupon without a total bound, a benefit out of range or a currency mismatch is refused here, before a row exists.
 *
 * <p>The creating actor is stored on the coupon, so a value handed out by a Tenant System is attributable.
 */
public class CreateCouponUseCase {

    public record Command(
            String name,
            Benefit benefit,
            Duration duration,
            Scope scope,
            Limits limits,
            Currency currency,
            boolean stackable,
            String code,
            Long codeMaxRedemptions,
            Audit audit) {}

    public record Result(CouponId couponId, PromotionCodeId codeId, String code) {}

    private final UnitOfWork unitOfWork;
    private final CouponRepository coupons;
    private final PromotionCodeRepository codes;
    private final AuditLog auditLog;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateCouponUseCase(
            UnitOfWork unitOfWork, CouponRepository coupons, PromotionCodeRepository codes, AuditLog auditLog, TenantContext tenantContext,
            IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.coupons = coupons;
        this.codes = codes;
        this.auditLog = auditLog;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        return unitOfWork.transactional(() -> {
            Instant now = clock.now();
            Coupon coupon = Coupon.create(new CouponId(ids.newId()), tenantContext.requireTenant(), command.name(), command.benefit(),
                    command.duration(), command.scope(), command.limits(), command.currency(), command.stackable(), command.audit().actor(), now);
            coupons.add(coupon);

            PromotionCodeId codeId = null;
            String codeText = null;
            boolean wantsCode = command.code() != null || command.codeMaxRedemptions() != null || coupon.isCredit();
            if (wantsCode) {
                String requested = command.code() == null || command.code().isBlank() ? PromotionCodes.generate() : command.code();
                PromotionCode code = PromotionCode.create(new PromotionCodeId(ids.newId()), coupon.tenantId(), coupon.id(), requested,
                        command.codeMaxRedemptions(), null, command.audit().actor(), now);
                codes.add(code);
                codeId = code.id();
                codeText = code.code();
            }
            auditLog.record(AuditEntry.tenant(coupon.tenantId(), command.audit().actor(), "coupon.created", "coupon/" + coupon.id(),
                    command.audit().requestId(), now));
            return new Result(coupon.id(), codeId, codeText);
        });
    }
}
