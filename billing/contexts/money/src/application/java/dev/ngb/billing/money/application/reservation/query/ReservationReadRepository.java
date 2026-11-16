package dev.ngb.billing.money.application.reservation.query;

import dev.ngb.billing.money.api.reservation.ReservationId;
import dev.ngb.core.query.ListQuery;
import dev.ngb.core.query.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only projection of a reservation. */
public interface ReservationReadRepository {

    Optional<ReservationView> findById(ReservationId id);

    /** One page of reservations; {@code walletIds} restricts it, and null reads the whole tenant. */
    Page<ReservationView> list(ListQuery query, List<UUID> walletIds);
}
