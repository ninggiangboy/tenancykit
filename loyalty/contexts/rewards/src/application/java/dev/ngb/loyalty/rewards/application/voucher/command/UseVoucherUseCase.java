package dev.ngb.loyalty.rewards.application.voucher.command;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.core.tenancy.TenantContext;
import dev.ngb.loyalty.rewards.application.voucher.port.VoucherRepository;
import dev.ngb.loyalty.rewards.application.voucher.service.VoucherEvents;
import dev.ngb.loyalty.rewards.application.voucher.service.VoucherRules;
import dev.ngb.loyalty.rewards.domain.voucher.Voucher;
import java.time.Instant;

/**
 * Consumes a voucher once.
 *
 * <p>It locks nothing but the voucher's own row, so a use can never form a lock cycle with the member's row a redemption
 * or a reversal holds. The voucher answers three things: the same {@code useRef} replays what it wrote, a different one
 * is a conflict, and a voucher whose expiry instant has passed is refused rather than used. Only the first answer writes,
 * and only a writing call announces an event.
 */
public class UseVoucherUseCase {

    /** One use as the caller sends it: the code presented at the point of sale and the caller's reference for it. */
    public record Command(String code, String useRef) {}

    /** The voucher after the call and whether this call is the one that consumed it. */
    public record Result(Voucher voucher, boolean used) {}

    private final UnitOfWork unitOfWork;
    private final VoucherRepository vouchers;
    private final VoucherEvents events;
    private final TenantContext tenantContext;
    private final Clock clock;

    public UseVoucherUseCase(
            UnitOfWork unitOfWork,
            VoucherRepository vouchers,
            VoucherEvents events,
            TenantContext tenantContext,
            Clock clock) {
        this.unitOfWork = unitOfWork;
        this.vouchers = vouchers;
        this.events = events;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    public Result execute(Command command) {
        String code = VoucherRules.code(command.code());
        String useRef = VoucherRules.useRef(command.useRef());
        return unitOfWork.transactional(() -> use(code, useRef));
    }

    private Result use(String code, String useRef) {
        TenantId tenantId = tenantContext.requireTenant();
        Instant now = clock.now();
        Voucher voucher = vouchers.lockByCode(code).orElseThrow(VoucherRules::notFound);
        if (!VoucherRules.shouldWrite(voucher, useRef, now)) {
            return new Result(voucher, false);
        }
        voucher.use(useRef, now);
        vouchers.markUsed(voucher.id(), useRef, now);
        events.voucherUsed(tenantId, now, voucher);
        return new Result(voucher, true);
    }
}
