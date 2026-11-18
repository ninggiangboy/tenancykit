package dev.ngb.loyalty.rewards.application.voucher.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.points.api.member.MemberDirectory;
import dev.ngb.loyalty.rewards.application.voucher.exception.VoucherRefusal;
import dev.ngb.loyalty.rewards.application.voucher.service.VoucherRules;
import dev.ngb.loyalty.rewards.domain.voucher.VoucherStatus;
import java.util.Locale;
import java.util.UUID;

/**
 * The voucher reads: one voucher by the code presented at the point of sale, and the vouchers a member holds.
 *
 * <p>The member is addressed by the Tenant's reference, which this case resolves to the opaque id the voucher rows carry,
 * so a caller never has to know one. Expiry travels as the stored {@code expiresAt} and is derived by whoever reads it;
 * there is no job that moves a voucher along.
 */
public class VoucherQueries {

    private final UnitOfWork unitOfWork;
    private final MemberDirectory members;
    private final VoucherReadRepository vouchers;

    public VoucherQueries(UnitOfWork unitOfWork, MemberDirectory members, VoucherReadRepository vouchers) {
        this.unitOfWork = unitOfWork;
        this.members = members;
        this.vouchers = vouchers;
    }

    public VoucherView get(String code) {
        String canonical = VoucherRules.code(code);
        return unitOfWork.transactional(() -> vouchers.findByCode(canonical).orElseThrow(VoucherRules::notFound));
    }

    public Page<VoucherView> list(String externalRef, String status, ListQuery query) {
        VoucherStatus state = state(status);
        return unitOfWork.transactional(() -> vouchers.page(memberId(externalRef), state, query));
    }

    private UUID memberId(String externalRef) {
        return members
                .find(externalRef)
                .map(MemberDirectory.MemberIdentity::memberId)
                .orElseThrow(() -> VoucherRefusal.notFound(
                        "MEMBER_NOT_FOUND", "No live member holds that externalRef in this tenant."));
    }

    private static VoucherStatus state(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return VoucherStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw VoucherRefusal.invalid("status is ISSUED, USED or VOIDED.");
        }
    }
}
