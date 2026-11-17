package dev.ngb.customer.orders.application.order.port;

import dev.ngb.customer.orders.domain.order.Order;
import dev.ngb.customer.orders.domain.returns.OrderReturn;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * How the write side stores orders, their lines and the returns recorded against them. The Tenant's reference for an
 * order is the key; the id is customer's own.
 *
 * <p>An order is inserted once and never rewritten except by a return, which the caller applies after locking the
 * order row. That lock is the whole concurrency rule: two returns of one order run one after the other, so the second
 * sees the cumulative figures the first stored and cannot take back the same unit twice.
 */
public interface OrderRepository {

    /** One order with its lines and returns, read without locking it. */
    Optional<Order> findByOrderRef(String orderRef);

    /** One order with its lines and returns, its row locked until the transaction ends. */
    Optional<Order> lockByOrderRef(String orderRef);

    /**
     * Inserts an order with its lines, the caller believing the reference is new.
     *
     * @return false when another caller stored the same reference first, which the caller answers by reading that
     *     order back and comparing its body to the one it sent
     */
    boolean insertIfAbsent(Order order);

    /**
     * Stores a return: the return itself with its lines, and the order's new version, status and cumulative returned
     * figures. The caller holds the order's row lock and passes the order it already updated.
     *
     * @param fingerprint the digest of the body that recorded it, stored with it so a replay is told from a change
     */
    void applyReturn(Order order, OrderReturn recorded, String fingerprint, Instant recordedAt);

    /** A return recorded under this reference, whatever order it belongs to; empty when the tenant has none. */
    Optional<StoredReturn> findReturn(String returnRef);

    /**
     * A return found by its reference.
     *
     * @param orderId the order it was recorded against, so a reference reused for another order is a conflict
     * @param fingerprint the digest of the body that recorded it, so a replay is told from a changed body
     */
    record StoredReturn(UUID id, UUID orderId, String returnRef, String fingerprint) {}
}
