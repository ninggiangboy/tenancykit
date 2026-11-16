package dev.ngb.billing.coupon.application.redemption.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The redemption read side. */
public interface RedemptionReadRepository {

    Optional<RedemptionView> findById(UUID id);

    List<RedemptionView> findByWallet(UUID walletId, int limit, int offset);
}
