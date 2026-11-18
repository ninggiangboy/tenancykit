package archfixtures.alpha.adapter;

import dev.ngb.billing.fixture.BillingThing;

/** Violates no-dependency-on-another-service: an attribution class names the Billing service's code. */
public class ReachesAnotherService {
    BillingThing billing;
}
