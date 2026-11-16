package dev.ngb.crm.orders.domain.order;

/**
 * How much of an order has come back.
 *
 * <p>{@code RECORDED} is an order nothing was returned from, {@code RETURNED} one whose every line came back in full,
 * and {@code PARTIALLY_RETURNED} everything between. The status is derived from the lines rather than counted, so it
 * cannot disagree with the returned figures a webhook carries.
 */
public enum OrderStatus {
    RECORDED,
    PARTIALLY_RETURNED,
    RETURNED
}
