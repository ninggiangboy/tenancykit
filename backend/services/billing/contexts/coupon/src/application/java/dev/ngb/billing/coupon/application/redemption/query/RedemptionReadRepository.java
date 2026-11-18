package dev.ngb.billing.coupon.application.redemption.query;

import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.Optional;
import java.util.UUID;

/** The redemption read side. */
public interface RedemptionReadRepository {

    Optional<RedemptionView> findById(UUID id);

    Page<RedemptionView> list(ListQuery query, UUID walletId);
}
