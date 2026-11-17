package archfixtures.profile.application.query;

import archfixtures.customers.domain.Customer;

/** Violates profile-reads-through-api: the read model reaches another context's domain instead of its api package. */
public class ProfileReachesCustomerDomain {

    public String ref(Customer customer) {
        return customer.externalRef();
    }
}
