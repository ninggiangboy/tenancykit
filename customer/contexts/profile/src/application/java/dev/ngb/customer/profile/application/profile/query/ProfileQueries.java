package dev.ngb.customer.profile.application.profile.query;

import dev.ngb.core.error.BadRequestException;
import dev.ngb.core.error.NotFoundException;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.customer.activity.api.activity.ActivityTimeline;
import dev.ngb.customer.customers.api.customer.CustomerProfile;
import dev.ngb.customer.customers.api.customer.CustomerProfileReader;
import dev.ngb.customer.orders.api.order.OrderHistory;
import java.util.List;

/**
 * Reads one customer as one document, for the {@code GET} handler.
 *
 * <p>Each slice is a bounded, indexed read of the context that owns it, taken inside one unit of work so the profile is
 * one consistent call rather than a sequence of independent ones. No slice is computed from another and nothing is
 * aggregated: the orders are the orders and the activities are the activities.
 */
public class ProfileQueries {

    /** How many orders a profile carries when the caller asks for none, and the most it may carry. */
    public static final int DEFAULT_ORDERS = 10;

    public static final int MAX_ORDERS = 50;

    /** How many activities a profile carries when the caller asks for none, and the most it may carry. */
    public static final int DEFAULT_ACTIVITIES = 20;

    public static final int MAX_ACTIVITIES = 100;

    /** The longest reference the Tenant may choose for a customer; the profile is addressed by it. */
    public static final int MAX_EXTERNAL_REF = 128;

    private final UnitOfWork unitOfWork;
    private final CustomerProfileReader customers;
    private final OrderHistory orders;
    private final ActivityTimeline activities;

    public ProfileQueries(
            UnitOfWork unitOfWork,
            CustomerProfileReader customers,
            OrderHistory orders,
            ActivityTimeline activities) {
        this.unitOfWork = unitOfWork;
        this.customers = customers;
        this.orders = orders;
        this.activities = activities;
    }

    /**
     * @param orders how many of the latest orders to include, 0 to {@value #MAX_ORDERS}; null means the default
     * @param activities how many of the latest activities to include, 0 to {@value #MAX_ACTIVITIES}; null means the
     *     default
     * @throws NotFoundException when this tenant has no customer with that reference
     * @throws BadRequestException when a slice size is outside its bound
     */
    public ProfileView read(String externalRef, Integer orders, Integer activities) {
        String ref = reference(externalRef);
        int orderLimit = bounded(orders, DEFAULT_ORDERS, MAX_ORDERS, "orders");
        int activityLimit = bounded(activities, DEFAULT_ACTIVITIES, MAX_ACTIVITIES, "activities");
        return unitOfWork.transactional(() -> {
            CustomerProfile customer = customers
                    .findByExternalRef(ref)
                    .orElseThrow(() -> new NotFoundException("No customer exists here with that reference."));
            return new ProfileView(
                    customer,
                    orderLimit == 0 ? List.of() : this.orders.latest(customer.id(), orderLimit),
                    activityLimit == 0 ? List.of() : this.activities.latest(customer.id(), activityLimit));
        });
    }

    private static String reference(String raw) {
        String ref = raw == null ? "" : raw.strip();
        if (ref.isEmpty() || ref.length() > MAX_EXTERNAL_REF) {
            throw new NotFoundException("No customer exists here with that reference.");
        }
        return ref;
    }

    private static int bounded(Integer value, int fallback, int max, String what) {
        if (value == null) {
            return fallback;
        }
        if (value < 0 || value > max) {
            throw new BadRequestException("A profile carries 0 to " + max + " " + what + ".");
        }
        return value;
    }
}
