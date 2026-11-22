package dev.ngb.customer.customers.application.consent.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Reads a customer's consent history straight into the views, and the id of the customer a reference names. */
public interface ConsentReadRepository {

    /** The id of the customer the current tenant holds under this reference, if it holds one. */
    Optional<UUID> findCustomerId(String externalRef);

    /** Every record of the customer, newest first by the instant it happened, then by id. */
    List<ConsentRecordView> history(UUID customerId);
}
