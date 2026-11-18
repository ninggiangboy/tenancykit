package dev.ngb.tenant.resource.api.order;

/** The pricing model of the order, named the same way the catalog names it. */
public enum PricingModel {
    TIME_BASED,
    USAGE_BASED,
    WORKLOAD_BASED,
    EVENT_BASED,
    SUBSCRIPTION,
    HYBRID
}
