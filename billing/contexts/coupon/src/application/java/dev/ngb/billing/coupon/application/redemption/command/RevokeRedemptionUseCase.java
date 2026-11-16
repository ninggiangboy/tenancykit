package dev.ngb.billing.coupon.application.redemption.command;

import dev.ngb.billing.coupon.application.redemption.port.RedemptionRepository;
import dev.ngb.billing.coupon.application.redemption.service.EndingRedemptions;
import dev.ngb.billing.coupon.domain.redemption.EndReason;
import dev.ngb.billing.coupon.domain.redemption.Redemption;
import dev.ngb.billing.coupon.domain.redemption.RedemptionId;
import dev.ngb.billing.customer.api.lookup.CustomerVisibility;
import dev.ngb.billing.customer.api.lookup.CustomerLookup;
import dev.ngb.billing.money.api.wallet.LockWallet;
import dev.ngb.billing.sharedkernel.error.Messages;
import dev.ngb.core.audit.Audit;
import dev.ngb.core.audit.AuditEntry;
import dev.ngb.core.audit.AuditLog;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import java.time.Instant;
import java.util.UUID;

/**
 * Ends one redemption early. Nothing is released or refunded: the discounts already given were charged correctly at
 * the time, and the wallet's rows are untouched. A repeat of a revoke publishes no second event.
 *
 * <p>The wallet is locked first and the redemption second, the order a charge takes, and the redemption is read under
 * its lock. Reading it unlocked and writing it back would overwrite the application a charge recorded in between.
 */
public class RevokeRedemptionUseCase {

    public record Command(RedemptionId redemptionId, Audit audit, UUID caller) {}

    private final UnitOfWork unitOfWork;
    private final LockWallet walletLocks;
    private final RedemptionRepository redemptions;
    private final EndingRedemptions endingRedemptions;
    private final CustomerVisibility visibility;
    private final AuditLog auditLog;
    private final Clock clock;

    public RevokeRedemptionUseCase(
            UnitOfWork unitOfWork, LockWallet walletLocks, RedemptionRepository redemptions, EndingRedemptions endingRedemptions,
            CustomerLookup customers, AuditLog auditLog, Clock clock) {
        this.unitOfWork = unitOfWork;
        this.walletLocks = walletLocks;
        this.redemptions = redemptions;
        this.endingRedemptions = endingRedemptions;
        this.visibility = new CustomerVisibility(customers);
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public Redemption execute(Command command) {
        return unitOfWork.transactional(() -> {
            // The unlocked read only names the wallet to lock; the state that is changed is read again under the locks.
            Redemption named = redemptions.findById(command.redemptionId()).orElseThrow(Messages::couponRedemptionNotFound);
            walletLocks.lockById(named.walletId()).orElseThrow(Messages::couponRedemptionNotFound);
            Redemption redemption = redemptions.lockById(command.redemptionId()).orElseThrow(Messages::couponRedemptionNotFound);
            visibility.requireVisible(redemption.customerId(), command.caller(), Messages::couponRedemptionNotFound);
            Instant now = clock.now();
            if (redemption.status() == dev.ngb.billing.coupon.domain.redemption.RedemptionStatus.ACTIVE) {
                endingRedemptions.end(redemption, EndReason.REVOKED, now);
                auditLog.record(AuditEntry.tenant(redemption.tenantId(), command.audit().actor(), "coupon-redemption.revoked",
                        "coupon-redemption/" + redemption.id(), command.audit().requestId(), now));
            }
            return redemption;
        });
    }
}
