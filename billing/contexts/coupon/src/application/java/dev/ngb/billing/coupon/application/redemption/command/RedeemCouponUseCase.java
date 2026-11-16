package dev.ngb.billing.coupon.application.redemption.command;

import dev.ngb.billing.coupon.application.coupon.port.CouponRepository;
import dev.ngb.billing.coupon.application.coupon.port.PromotionCodeRepository;
import dev.ngb.billing.coupon.application.redemption.port.RedemptionRepository;
import dev.ngb.billing.coupon.domain.coupon.Benefit;
import dev.ngb.billing.coupon.domain.coupon.Coupon;
import dev.ngb.billing.coupon.domain.event.CouponEvents;
import dev.ngb.billing.coupon.domain.coupon.CouponId;
import dev.ngb.billing.coupon.domain.coupon.Duration;
import dev.ngb.billing.coupon.domain.coupon.PromotionCode;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
import dev.ngb.billing.customer.api.lookup.CustomerVisibility;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.money.api.wallet.CreditCommand;
import dev.ngb.billing.money.api.wallet.CreditWallet;
import dev.ngb.billing.money.api.wallet.LockWallet;
import dev.ngb.billing.money.api.wallet.WalletSummary;
import dev.ngb.billing.sharedkernel.error.Messages;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.idempotency.ReplayIdentity;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.error.ConflictException;
import dev.ngb.core.event.EventPublisher;
import dev.ngb.core.idempotency.Fingerprint;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;

/**
 * Redeems a coupon onto a wallet, either by a promotion code or by attaching the coupon directly. Both routes share
 * every check; only how the coupon is chosen differs, and the direct route is the one an administrator uses to bypass
 * a code's limits.
 *
 * <p>The lock order is wallet, then coupon, then code, then the new redemption, so no wait cycle forms with a charge,
 * which locks a wallet and then coupon and redemption rows. A wallet-credit coupon hands its benefit over through the
 * ordinary credit path in the same transaction, so a retry credits once.
 *
 * <p>A caller that is a Tenant System sees only the customers it mapped, exactly as the charge path does.
 */
public class RedeemCouponUseCase {

    /**
     * Exactly one of {@code code} or {@code couponId} names the coupon. {@code caller} is the acting Tenant System, or
     * null for a Tenant User.
     */
    public record Command(WalletId walletId, String code, String couponId, String idempotencyKey, Audit audit, UUID caller) {}

    /** {@code replayed} is true when the key was used before and the first redemption is returned. */
    public record Result(Redemption redemption, Coupon coupon, boolean replayed) {}

    private final UnitOfWork unitOfWork;
    private final LockWallet walletLocks;
    private final CouponRepository coupons;
    private final PromotionCodeRepository codes;
    private final RedemptionRepository redemptions;
    private final CreditWallet creditWallet;
    private final EventPublisher events;
    private final CustomerVisibility visibility;
    private final AuditLog auditLog;
    private final TenantContext tenantContext;
    private final IdGenerator ids;
    private final Clock clock;

    public RedeemCouponUseCase(
            UnitOfWork unitOfWork, LockWallet walletLocks, CouponRepository coupons, PromotionCodeRepository codes,
            RedemptionRepository redemptions, CreditWallet creditWallet, CustomerLookup customers, EventPublisher events, AuditLog auditLog,
            TenantContext tenantContext, IdGenerator ids, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.walletLocks = walletLocks;
        this.coupons = coupons;
        this.codes = codes;
        this.redemptions = redemptions;
        this.creditWallet = creditWallet;
        this.events = events;
        this.visibility = new CustomerVisibility(customers);
        this.auditLog = auditLog;
        this.tenantContext = tenantContext;
        this.ids = ids;
        this.clock = clock;
    }

    public Result execute(Command command) {
        Fingerprint.requireKey(command.idempotencyKey());
        if ((command.code() == null) == (command.couponId() == null)) {
            throw new IllegalArgumentException("a redemption names a code or a coupon, not both");
        }
        // A code is case-insensitive, so the fingerprint uses its stored form: typing the same code in another case
        // is the same request, not a conflicting one.
        String code = command.code() == null ? null : PromotionCode.normalize(command.code());
        String hash = Fingerprint.of(command.walletId(), code, command.couponId());
        return unitOfWork.transactional(() -> redeem(command, code, hash));
    }

    private Result redeem(Command command, String normalizedCode, String hash) {
        WalletSummary wallet = walletLocks.lockById(command.walletId()).orElseThrow(Messages::walletNotFound);
        visibility.requireVisible(wallet.customerId(), command.caller(), Messages::walletNotFound);

        Instant now = clock.now();
        PromotionCode code = null;
        Coupon coupon;
        if (normalizedCode != null) {
            // The documented order is wallet, coupon, then code, so the unlocked read only names the coupon to lock.
            PromotionCode found = codes.findByCode(normalizedCode).orElseThrow(Messages::couponNotRedeemable);
            coupon = coupons.lockById(found.couponId()).orElseThrow(Messages::couponNotRedeemable);
            code = codes.lockByCode(normalizedCode).orElseThrow(Messages::couponNotRedeemable);
        } else {
            coupon = coupons.lockById(CouponId.of(command.couponId())).orElseThrow(Messages::couponNotFound);
        }
        var existing = redemptions.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            Redemption original = existing.get();
            Fingerprint.requireSameRequest("coupon-redemption", command.idempotencyKey(), original.requestHash(), hash);
            return new Result(original, coupon, true);
        }

        // Every reason a coupon or its code cannot start a redemption reads the same, so a caller cannot probe which
        // codes exist, which are inactive and which are spent.
        if (!coupon.isOpenForRedemption(now) || (code != null && !code.isOpen(now))) {
            throw Messages.couponNotRedeemable();
        }

        if (!coupon.currency().equals(wallet.currency())) {
            throw new IllegalArgumentException("the coupon is in " + coupon.currency() + " and the wallet in " + wallet.currency());
        }
        if (coupon.limits().maxPerCustomer() != null
                && redemptions.countForCustomer(wallet.customerId(), coupon.id()) >= coupon.limits().maxPerCustomer()) {
            throw new ConflictException("the customer has reached this coupon's limit");
        }
        if (!redemptions.lockActiveByWalletAndCoupon(wallet.id(), coupon.id()).isEmpty()) {
            throw new ConflictException("the wallet already has this coupon");
        }
        if (!coupons.countRedemption(coupon.id())) {
            throw Messages.couponNotRedeemable();
        }
        if (code != null && !codes.countRedemption(code.id())) {
            throw Messages.couponNotRedeemable();
        }

        Instant endsAt = endsAt(coupon.duration(), now);
        RedemptionId redemptionId = new RedemptionId(ids.newId());
        Redemption redemption = coupon.isCredit()
                ? Redemption.granted(redemptionId, tenantContext.requireTenant(), coupon.id(), code == null ? null : code.id(), wallet.id(),
                        wallet.customerId(), command.audit().actor(), new ReplayIdentity(command.idempotencyKey(), hash), coupon.currency(), now)
                : Redemption.activate(redemptionId, tenantContext.requireTenant(), coupon.id(), code == null ? null : code.id(), wallet.id(),
                        wallet.customerId(), coupon.duration().maxApplications(), coupon.duration().maxTotalDiscount(), endsAt,
                        command.audit().actor(), new ReplayIdentity(command.idempotencyKey(), hash), coupon.currency(), now);
        redemptions.add(redemption);

        if (coupon.benefit() instanceof Benefit.WalletCredit credit) {
            creditWallet.credit(new CreditCommand(wallet.id(), credit.amount(), "coupon-redemption:" + redemption.id(),
                    "coupon " + coupon.name(), "coupon-redemption:" + redemption.id(), command.audit().actor()));
        }
        events.publish(new CouponEvents.CouponRedeemed(ids.newId(), redemption, coupon.name(), coupon.benefit(), now));
        auditLog.record(AuditEntry.tenant(coupon.tenantId(), command.audit().actor(), "coupon.redeemed", "coupon-redemption/" + redemption.id(),
                command.audit().requestId(), now));
        return new Result(redemption, coupon, false);
    }

    private static Instant endsAt(Duration duration, Instant now) {
        return duration.window() == null ? null : duration.window().endsAt(now);
    }
}
