package dev.ngb.tenant.billing.adapter.client.feign;

import dev.ngb.tenant.billing.api.client.BillingAdmin;
import dev.ngb.tenant.billing.api.client.BillingAdmin.CreatePlanRequest;
import dev.ngb.tenant.billing.api.client.BillingCharges;
import dev.ngb.tenant.billing.api.client.BillingCharges.CaptureRequest;
import dev.ngb.tenant.billing.api.client.BillingCharges.ChargeRequest;
import dev.ngb.tenant.billing.api.client.BillingCustomers;
import dev.ngb.tenant.billing.api.client.BillingCustomers.NewCustomer;
import dev.ngb.tenant.billing.api.client.BillingInvoices;
import dev.ngb.tenant.billing.api.client.BillingPayments;
import dev.ngb.tenant.billing.api.client.BillingPayments.TopUpRequest;
import dev.ngb.tenant.billing.api.client.BillingPricing;
import dev.ngb.tenant.billing.api.client.BillingPricing.EstimateRequest;
import dev.ngb.tenant.billing.api.client.BillingPricing.ReserveRequest;
import dev.ngb.tenant.billing.api.client.BillingSessions;
import dev.ngb.tenant.billing.api.client.BillingSubscriptions;
import dev.ngb.tenant.billing.api.client.BillingUsage;
import dev.ngb.tenant.billing.api.client.BillingWallets;
import dev.ngb.tenant.billing.api.client.BillingWallets.OpenWallet;
import dev.ngb.tenant.billing.api.client.BillingWebhooks;
import dev.ngb.tenant.billing.api.client.BillingWebhooks.CreateEndpointRequest;
import dev.ngb.tenant.billing.api.client.BillingWebhooks.Endpoint;
import dev.ngb.tenant.billing.api.client.BillingWebhooks.EndpointWithSecret;
import dev.ngb.tenant.billing.api.model.BillingSession;
import dev.ngb.tenant.billing.api.model.Charge;
import dev.ngb.tenant.billing.api.model.ChargeUsageRequest;
import dev.ngb.tenant.billing.api.model.Customer;
import dev.ngb.tenant.billing.api.model.Estimate;
import dev.ngb.tenant.billing.api.model.Invoice;
import dev.ngb.tenant.billing.api.model.InvoiceDetail;
import dev.ngb.tenant.billing.api.model.Payment;
import dev.ngb.tenant.billing.api.model.PaymentDetail;
import dev.ngb.tenant.billing.api.model.PricingPlan;
import dev.ngb.tenant.billing.api.model.Rating;
import dev.ngb.tenant.billing.api.model.RecordedUsage;
import dev.ngb.tenant.billing.api.model.Reservation;
import dev.ngb.tenant.billing.api.model.ReserveResult;
import dev.ngb.tenant.billing.api.model.Subscription;
import dev.ngb.tenant.billing.api.model.SubscriptionDetail;
import dev.ngb.tenant.billing.api.model.UsageEvent;
import dev.ngb.tenant.billing.api.model.UsageEventRequest;
import dev.ngb.tenant.billing.api.model.Wallet;
import dev.ngb.core.error.UpstreamUnavailableException;
import feign.RetryableException;
import java.time.Instant;
import dev.ngb.core.query.Page;
import dev.ngb.core.util.PageSize;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The api contracts over Feign. Each method delegates the shape to {@link BillingFeignApi} and turns
 * an exhausted retry into {@link UpstreamUnavailableException}, so callers only ever see domain
 * exceptions.
 */
public class FeignBillingClient
        implements BillingCustomers,
                BillingPricing,
                BillingWallets,
                BillingCharges,
                BillingUsage,
                BillingSessions,
                BillingPayments,
                BillingInvoices,
                BillingSubscriptions,
                BillingAdmin,
                BillingWebhooks {

    private final BillingFeignApi api;

    FeignBillingClient(BillingFeignApi api) {
        this.api = api;
    }

    @Override
    public Customer create(String idempotencyKey, NewCustomer customer) {
        requireKey(idempotencyKey);
        return call(() -> api.createCustomer(idempotencyKey, customer));
    }

    @Override
    public Customer get(String id) {
        return call(() -> api.customer(id));
    }

    @Override
    public Customer byExternalUser(String externalUserId) {
        return call(() -> api.customerByExternalUser(externalUserId));
    }

    @Override
    public List<PricingPlan> plans(String status) {
        Map<String, String> filters = new LinkedHashMap<>();
        equal(filters, "status", status);
        return readUpTo(PageSize.MAX, filters, api::plans);
    }

    @Override
    public PricingPlan plan(String id) {
        return call(() -> api.plan(id));
    }

    @Override
    public Estimate estimate(EstimateRequest request) {
        return call(() -> api.estimate(request));
    }

    @Override
    public ReserveResult reserve(String idempotencyKey, ReserveRequest request) {
        requireKey(idempotencyKey);
        return call(() -> api.reserve(idempotencyKey, request));
    }

    @Override
    public List<Wallet> wallets(String customerId) {
        Map<String, String> filters = new LinkedHashMap<>();
        equal(filters, "customer_id", customerId);
        return readUpTo(PageSize.MAX, filters, api::wallets);
    }

    @Override
    public Wallet wallet(String id) {
        return call(() -> api.wallet(id));
    }

    @Override
    public Wallet open(String idempotencyKey, OpenWallet request) {
        requireKey(idempotencyKey);
        return call(() -> api.openWallet(idempotencyKey, request));
    }

    @Override
    public Charge charge(String idempotencyKey, ChargeRequest request) {
        requireKey(idempotencyKey);
        return call(() -> api.charge(idempotencyKey, request));
    }

    @Override
    public Charge capture(String idempotencyKey, String reservationId, CaptureRequest request) {
        requireKey(idempotencyKey);
        return call(() -> api.capture(idempotencyKey, reservationId, request));
    }

    @Override
    public Reservation release(String reservationId) {
        return call(() -> api.release(reservationId));
    }

    @Override
    public Rating chargeUsage(ChargeUsageRequest request) {
        return call(() -> api.chargeUsage(request));
    }

    @Override
    public Rating rating(String id) {
        return call(() -> api.rating(id));
    }

    @Override
    public RecordedUsage record(UsageEventRequest request) {
        return call(() -> api.record(request));
    }

    @Override
    public List<UsageEvent> history(String customerId, String metric, Instant from, Instant to, int limit) {
        Map<String, String> filters = new LinkedHashMap<>();
        equal(filters, "customer_id", customerId);
        equal(filters, "metric", metric);
        if (from != null) {
            filters.put("filter[occurred_at][gte]", instant(from));
        }
        if (to != null) {
            filters.put("filter[occurred_at][lt]", instant(to));
        }
        return readUpTo(limit, filters, api::history);
    }

    @Override
    public BillingSession create(String customerId, String returnUrl, List<String> permissions, Long expiresInSeconds) {
        return call(() -> api.createSession(
                new BillingFeignApi.SessionRequest(customerId, returnUrl, permissions, expiresInSeconds)));
    }

    @Override
    public void revoke(String id) {
        run(() -> api.revokeSession(id));
    }

    @Override
    public List<Payment> payments(String walletId, String status, int limit) {
        Map<String, String> filters = new LinkedHashMap<>();
        equal(filters, "wallet_id", walletId);
        equal(filters, "status", status);
        return readUpTo(limit, filters, api::payments);
    }

    @Override
    public PaymentDetail payment(String id) {
        return call(() -> api.payment(id));
    }

    @Override
    public Payment createTopUp(String idempotencyKey, TopUpRequest request) {
        requireKey(idempotencyKey);
        return call(() -> api.createTopUp(idempotencyKey, request));
    }

    @Override
    public List<Invoice> invoices(String customerId, String walletId, String status, int limit) {
        Map<String, String> filters = new LinkedHashMap<>();
        equal(filters, "customer_id", customerId);
        equal(filters, "wallet_id", walletId);
        equal(filters, "status", status);
        return readUpTo(limit, filters, api::invoices);
    }

    private static void equal(Map<String, String> filters, String field, String value) {
        if (value != null) {
            filters.put("filter[" + field + "][eq]", value);
        }
    }

    /**
     * Reads rows page by page until {@code limit} rows are collected or the listing is exhausted, following the cursor
     * Billing hands back. A page holds at most {@link PageSize#MAX} rows, so a larger {@code limit} needs several.
     */
    private <T> List<T> readUpTo(int limit, Map<String, String> filters, Function<Map<String, String>, Page<T>> fetch) {
        List<T> rows = new ArrayList<>();
        String cursor = null;
        while (rows.size() < limit) {
            Map<String, String> query = new LinkedHashMap<>(filters);
            query.put("limit", Integer.toString(Math.min(limit - rows.size(), PageSize.MAX)));
            query.put("page_info", "true");
            if (cursor != null) {
                query.put("cursor", cursor);
            }
            Page<T> page = call(() -> fetch.apply(query));
            rows.addAll(page.data());
            cursor = page.page() == null ? null : page.page().nextCursor();
            if (cursor == null) {
                break;
            }
        }
        return rows;
    }

    @Override
    public InvoiceDetail invoice(String id) {
        return call(() -> api.invoice(id));
    }

    @Override
    public SubscriptionDetail subscribe(String idempotencyKey, String walletId, String planId) {
        requireKey(idempotencyKey);
        return call(() -> api.subscribe(idempotencyKey, new BillingFeignApi.SubscribeRequest(walletId, planId)));
    }

    @Override
    public SubscriptionDetail subscription(String id) {
        return call(() -> api.subscription(id));
    }

    @Override
    public SubscriptionDetail cancel(String id) {
        return call(() -> api.cancel(id));
    }

    @Override
    public List<Subscription> subscriptions(String customerId, String walletId, String status, int limit) {
        Map<String, String> filters = new LinkedHashMap<>();
        equal(filters, "customer_id", customerId);
        equal(filters, "wallet_id", walletId);
        equal(filters, "status", status);
        return readUpTo(limit, filters, api::subscriptions);
    }

    @Override
    public PricingPlan createPlan(CreatePlanRequest request) {
        return call(() -> api.createPlan(request));
    }

    @Override
    public List<Endpoint> endpoints() {
        return readUpTo(PageSize.MAX, new LinkedHashMap<>(), api::endpoints);
    }

    @Override
    public EndpointWithSecret createEndpoint(CreateEndpointRequest request) {
        return call(() -> api.createEndpoint(request));
    }

    private <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (RetryableException e) {
            throw new UpstreamUnavailableException("Billing could not be reached", e);
        }
    }

    private void run(Runnable call) {
        try {
            call.run();
        } catch (RetryableException e) {
            throw new UpstreamUnavailableException("Billing could not be reached", e);
        }
    }

    /** A write retried without its key could be recorded twice, so a missing key fails before any request. */
    private static void requireKey(String idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "a write to Billing needs an Idempotency-Key");
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}
