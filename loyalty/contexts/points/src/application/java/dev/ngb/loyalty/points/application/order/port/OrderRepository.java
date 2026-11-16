package dev.ngb.loyalty.points.application.order.port;

import dev.ngb.loyalty.points.domain.order.OrderLine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The recorded orders and their returns, as the command side writes and re-reads them. */
public interface OrderRepository {

    /** The order the Tenant's reference names, or empty when loyalty has not seen it. */
    Optional<StoredOrder> findByRef(String orderRef);

    /** The same read under a row lock, so two calls cannot each apply a grant to one order. */
    Optional<StoredOrder> lockByRef(String orderRef);

    /** The return the Tenant's reference names, or empty when it was never recorded. */
    Optional<StoredReturn> findReturn(String returnRef);

    List<StoredReturn> returns(UUID orderId);

    List<OrderLine> lines(UUID orderId);

    void add(StoredOrder order, List<OrderLine> lines);

    /** Writes the order's current worth, which a return lowers and never raises. */
    void updateWorth(UUID orderId, long grantedPoints, long spendAmount);

    /** Writes the cumulative returned figures of the order's lines. */
    void updateReturned(UUID orderId, List<OrderLine> lines);

    void addReturn(UUID orderId, StoredReturn recorded, List<ReturnLineRow> lines);
}
