package dev.ngb.crm.boot.config;

import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.crm.activity.api.activity.ActivityTimeline;
import dev.ngb.crm.customers.api.customer.CustomerProfileReader;
import dev.ngb.crm.orders.api.order.OrderHistory;
import dev.ngb.crm.profile.application.profile.query.ProfileQueries;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the profile context: the one read model, built out of the api packages of the customers, orders and activity
 * contexts.
 *
 * <p>The three seams are beans each of those contexts publishes, so the profile knows no repository, no table and no
 * query of another context — and holds no table of its own.
 */
@Configuration(proxyBeanMethods = false)
class ProfileConfiguration {

    @Bean
    ProfileQueries profileQueries(
            UnitOfWork unitOfWork,
            CustomerProfileReader customers,
            OrderHistory orders,
            ActivityTimeline activities) {
        return new ProfileQueries(unitOfWork, customers, orders, activities);
    }
}
