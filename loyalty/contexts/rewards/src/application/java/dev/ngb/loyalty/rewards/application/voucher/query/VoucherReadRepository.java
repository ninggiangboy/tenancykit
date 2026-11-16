package dev.ngb.loyalty.rewards.application.voucher.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import dev.ngb.loyalty.rewards.domain.voucher.VoucherStatus;
import java.util.Optional;
import java.util.UUID;

/** Read-only projections of the current tenant's vouchers. */
public interface VoucherReadRepository {

    /** One page of the vouchers a reward redemption issued; {@code status} narrows it when the caller names one. */
    Page<VoucherView> page(UUID memberId, VoucherStatus status, ListQuery query);

    Optional<VoucherView> findByCode(String code);
}
