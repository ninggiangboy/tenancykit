package dev.ngb.billing.sharedkernel.security;

import java.util.Set;

/**
 * Billing's permission codes, as compile-time strings because {@code @RequiresPermission} needs a constant. The
 * codes are exactly the entries of {@code contracts/permissions/billing.yaml}; a test keeps the two in step.
 *
 * <p>Every code is namespaced {@code billing:<resource>:<action>}, and only codes the catalog marks
 * {@code grantable_to_tenant_system: true} may reach a Tenant System credential.
 */
public final class BillingPermissions {

    public static final String CUSTOMER_READ = "billing:customer:read";
    public static final String CUSTOMER_WRITE = "billing:customer:write";

    /** Creates and revokes a Billing Session for one Customer. A Tenant System may hold it. */
    public static final String BILLING_SESSION_CREATE = "billing:billing-session:create";
    /** Manages the return URLs a Billing Session may point back to. Administrators only. */
    public static final String PORTAL_SETTINGS_MANAGE = "billing:portal-settings:manage";

    public static final String WALLET_READ = "billing:wallet:read";
    public static final String WALLET_MANAGE = "billing:wallet:manage";
    public static final String RESERVATION_MANAGE = "billing:reservation:manage";
    public static final String CHARGE_CREATE = "billing:charge:create";
    /** Corrects the ledger. Administrators only: never granted to a Tenant System. */
    public static final String LEDGER_REVERSE = "billing:ledger:reverse";

    /** Webhooks are Billing's, so Billing enforces and namespaces these two codes. */
    public static final String WEBHOOK_READ = "billing:webhook:read";
    public static final String WEBHOOK_MANAGE = "billing:webhook:manage";

    public static final String PRICING_READ = "billing:pricing:read";
    public static final String PRICING_MANAGE = "billing:pricing:manage";
    public static final String USAGE_RECORD = "billing:usage:record";
    public static final String USAGE_READ = "billing:usage:read";

    /** Reads the Stripe configuration (never its secrets), webhook processing history and reconciliation findings. */
    public static final String STRIPE_READ = "billing:stripe:read";
    /** Connects or disconnects Stripe, sets its secrets, retries events and runs reconciliation. Administrators only. */
    public static final String STRIPE_MANAGE = "billing:stripe:manage";
    public static final String PAYMENT_READ = "billing:payment:read";
    /** Starts a wallet top-up through the Tenant's Stripe configuration. */
    public static final String PAYMENT_CREATE = "billing:payment:create";
    /** Sends money back to a customer's card. Administrators only: never granted to a Tenant System. */
    public static final String REFUND_CREATE = "billing:refund:create";

    public static final String INVOICE_READ = "billing:invoice:read";
    /** Asks for an invoice for one charge. */
    public static final String INVOICE_CREATE = "billing:invoice:create";
    /** Voids an invoice or writes it off as uncollectible. Administrators only: it changes what a customer is asked to pay. */
    public static final String INVOICE_MANAGE = "billing:invoice:manage";

    public static final String SUBSCRIPTION_READ = "billing:subscription:read";
    /** Starts and cancels subscriptions. */
    public static final String SUBSCRIPTION_MANAGE = "billing:subscription:manage";

    /** Every code above, so a test can prove the constants, the endpoints and the catalog are one set. */
    public static final Set<String> CODES = Set.of(
            CUSTOMER_READ, CUSTOMER_WRITE, BILLING_SESSION_CREATE, PORTAL_SETTINGS_MANAGE, WALLET_READ, WALLET_MANAGE, RESERVATION_MANAGE,
            CHARGE_CREATE, LEDGER_REVERSE, WEBHOOK_READ, WEBHOOK_MANAGE, PRICING_READ, PRICING_MANAGE, USAGE_RECORD, USAGE_READ, STRIPE_READ,
            STRIPE_MANAGE, PAYMENT_READ, PAYMENT_CREATE, REFUND_CREATE, INVOICE_READ, INVOICE_CREATE, INVOICE_MANAGE, SUBSCRIPTION_READ,
            SUBSCRIPTION_MANAGE);

    private BillingPermissions() {}
}
