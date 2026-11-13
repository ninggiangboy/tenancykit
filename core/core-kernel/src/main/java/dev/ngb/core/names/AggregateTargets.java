package dev.ngb.core.names;

/**
 * The aggregate targets Billing's and Platform's events and audit entries name. The separator is {@code /}: a target
 * is {@code <kind>/<id>}, such as {@code wallet/<uuid>}, and a sub-resource appends another {@code /<kind>/<id>} pair,
 * such as {@code user/<uuid>/permission/<code>}.
 *
 * <p>A target is part of the integration contract (it is the {@code aggregate} of an event) and of the audit log, so
 * it is never assembled inline: adding a target is one method here.
 */
public final class AggregateTargets {

    /** The separator between the parts of a target. */
    public static final String SEPARATOR = "/";

    /** The separator between a target and the version of the aggregate it names. */
    public static final String VERSION_SEPARATOR = "@";

    public static final String WALLET = "wallet";
    public static final String CHARGE = "charge";
    public static final String RESERVATION = "reservation";
    public static final String LEDGER_ENTRY = "ledger-entry";
    public static final String PAYMENT = "payment";
    public static final String REFUND = "refund";
    public static final String INVOICE = "invoice";
    public static final String SUBSCRIPTION = "subscription";
    public static final String PRICING_PLAN = "pricing-plan";
    public static final String USAGE_RATING = "usage-rating";
    public static final String WEBHOOK_ENDPOINT = "webhook-endpoint";
    public static final String WEBHOOK_DELIVERY = "webhook-delivery";
    public static final String STRIPE_EVENT = "stripe-event";
    public static final String TENANT = "tenant";
    public static final String TENANT_DOMAIN = "tenant-domain";
    public static final String TENANT_SYSTEM = "tenant-system";
    public static final String USER = "user";
    public static final String ENTITLEMENT = "entitlement";
    public static final String CREDENTIAL = "credential";
    public static final String CUSTOMER = "customer";
    public static final String ORDER = "order";
    public static final String CATALOG_PLAN = "catalog-plan";

    private AggregateTargets() {}

    /** {@code <kind>/<id>}, the shape every target has. */
    public static String of(String kind, Object id) {
        return kind + SEPARATOR + id;
    }

    public static String wallet(Object id) {
        return of(WALLET, id);
    }

    public static String charge(Object id) {
        return of(CHARGE, id);
    }

    public static String reservation(Object id) {
        return of(RESERVATION, id);
    }

    public static String ledgerEntry(Object id) {
        return of(LEDGER_ENTRY, id);
    }

    public static String payment(Object id) {
        return of(PAYMENT, id);
    }

    public static String refund(Object id) {
        return of(REFUND, id);
    }

    public static String invoice(Object id) {
        return of(INVOICE, id);
    }

    public static String subscription(Object id) {
        return of(SUBSCRIPTION, id);
    }

    public static String pricingPlan(Object id) {
        return of(PRICING_PLAN, id);
    }

    public static String usageRating(Object id) {
        return of(USAGE_RATING, id);
    }

    public static String webhookEndpoint(Object id) {
        return of(WEBHOOK_ENDPOINT, id);
    }

    public static String webhookDelivery(Object id) {
        return of(WEBHOOK_DELIVERY, id);
    }

    public static String stripeEvent(Object id) {
        return of(STRIPE_EVENT, id);
    }

    public static String tenant(Object id) {
        return of(TENANT, id);
    }

    public static String tenantDomain(Object id) {
        return of(TENANT_DOMAIN, id);
    }

    public static String tenantSystem(Object id) {
        return of(TENANT_SYSTEM, id);
    }

    public static String user(Object id) {
        return of(USER, id);
    }

    public static String credential(Object id) {
        return of(CREDENTIAL, id);
    }

    public static String customer(Object id) {
        return of(CUSTOMER, id);
    }

    public static String order(Object id) {
        return of(ORDER, id);
    }

    public static String catalogPlan(Object id) {
        return of(CATALOG_PLAN, id);
    }

    /** {@code entitlement/<tenant>/<app>}, the target of an app being enabled or disabled. */
    public static String entitlement(Object tenantId, Object appCode) {
        return of(ENTITLEMENT, tenantId) + SEPARATOR + appCode;
    }

    /** A sub-resource of a target: {@code <target>/<kind>/<id>}. */
    public static String sub(Object target, String kind, Object id) {
        return target + SEPARATOR + kind + SEPARATOR + id;
    }

    /** A field of a target: {@code <target>/<name>}, such as {@code tenant/<uuid>/domain/<hostname>}. */
    public static String with(Object target, String name, Object value) {
        return target + SEPARATOR + name + SEPARATOR + value;
    }

    /** A versioned aggregate: {@code <target>@<version>}. */
    public static String versioned(Object target, int version) {
        return target + VERSION_SEPARATOR + version;
    }
}
