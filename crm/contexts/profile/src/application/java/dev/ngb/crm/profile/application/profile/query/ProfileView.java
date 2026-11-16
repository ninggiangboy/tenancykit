package dev.ngb.crm.profile.application.profile.query;

import dev.ngb.crm.activity.api.activity.ActivitySnapshot;
import dev.ngb.crm.customers.api.customer.CustomerProfile;
import dev.ngb.crm.orders.api.order.OrderSnapshot;
import java.util.List;

/**
 * One customer read back as one document: the customer with its field values, its tags and its consents, plus the
 * bounded slice of its latest orders and of its latest activities.
 *
 * <p>Every slice was already bounded by the request, so this is a fixed amount of work whatever the customer's history
 * holds. Nothing here is aggregated: no total, no lifetime value and no score, because those are the Tenant's own
 * questions and not facts crm keeps.
 *
 * @param customer the customer as the customers context reads it
 * @param orders the latest orders, newest first
 * @param activities the latest activities, newest first
 */
public record ProfileView(
        CustomerProfile customer, List<OrderSnapshot> orders, List<ActivitySnapshot> activities) {

    public ProfileView {
        orders = List.copyOf(orders);
        activities = List.copyOf(activities);
    }
}
