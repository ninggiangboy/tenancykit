package archfixtures.alpha.adapter;

import dev.ngb.billing.fixture.BillingThing;

/** Violates no-dependency-on-another-service: a Platform class names the Billing service's code. */
public class ReachesAnotherService {
    BillingThing billing;
}
