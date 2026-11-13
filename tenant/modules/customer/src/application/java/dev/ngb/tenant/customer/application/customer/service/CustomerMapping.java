package dev.ngb.tenant.customer.application.customer.service;

import dev.ngb.tenant.billing.api.client.BillingCustomers;
import dev.ngb.tenant.billing.api.model.Customer;
import dev.ngb.tenant.customer.application.customer.port.CustomerRepository;
import dev.ngb.core.error.ConflictException;

/**
 * Creates the Billing Customer and stores the returned id as the mapping. Billing derives this Tenant System from
 * the access token, so the mapping never names it; the external user id is this system's own key.
 */
public class CustomerMapping {

    private final CustomerRepository customers;
    private final BillingCustomers billing;

    public CustomerMapping(CustomerRepository customers, BillingCustomers billing) {
        this.customers = customers;
        this.billing = billing;
    }

    public dev.ngb.tenant.customer.domain.customer.Customer map(dev.ngb.tenant.customer.domain.customer.Customer customer) {
        String externalUserId = customer.userId().toString();
        try {
            Customer created = billing.create(
                    "customer:" + externalUserId,
                    new BillingCustomers.NewCustomer(externalUserId, customer.displayName(), customer.email()));
            return store(customer, created.id());
        } catch (ConflictException conflict) {
            // A mapping already exists under another key; the external-user lookup recovers it.
            Customer existing = billing.byExternalUser(externalUserId);
            return store(customer, existing.id());
        }
    }

    private dev.ngb.tenant.customer.domain.customer.Customer store(
            dev.ngb.tenant.customer.domain.customer.Customer customer, String billingCustomerId) {
        var mapped = customer.mapped(billingCustomerId);
        customers.save(mapped);
        return mapped;
    }
}
