package dev.ngb.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.billing.Application;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.billing.invoicing.application.cycle.command.CloseBillingCycleUseCase;
import dev.ngb.billing.money.api.charge.ChargeCommand;
import dev.ngb.billing.money.api.charge.ChargeResult;
import dev.ngb.billing.money.api.charge.ChargeWallet;
import dev.ngb.billing.pricing.PricingTestWorld;
import dev.ngb.billing.pricing.api.plan.BillingCycle;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.support.FakePaymentProvider;
import dev.ngb.billing.support.FakePaymentProviderConfig;
import dev.ngb.billing.support.HttpWorld;
import dev.ngb.billing.support.HttpWorld.Account;
import dev.ngb.billing.support.OwnerDatabase;
import dev.ngb.billing.support.PostgresContainerConfig;
import dev.ngb.billing.support.TestTokensConfig;
import dev.ngb.billing.support.ReplicaFixtures;
import dev.ngb.billing.support.ScopeTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Invoices and subscriptions over HTTP with real tokens: who may read, pay, void and subscribe, what a Tenant System
 * is allowed to see, and that no request marks an invoice paid. Stripe is the in-memory provider.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, TestTokensConfig.class, FakePaymentProviderConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvoicingHttpIntegrationTest {

    private static final String SCOPES_TEXT = "invoice:read,invoice:create,subscription:read,subscription:manage,payment:create,payment:read,wallet:manage,"
            + "wallet:read,customer:write,customer:read,charge:create";

    @Autowired MockMvc mvc;
    @Autowired ScopeTestSupport scopes;
    @Autowired ReplicaFixtures replica;
    @Autowired OwnerDatabase owner;
    @Autowired FakePaymentProvider stripe;
    @Autowired PricingTestWorld pricing;
    @Autowired ChargeWallet charges;
    @Autowired OutboxRelay relay;
    @Autowired CloseBillingCycleUseCase closeCycle;

    private final JsonMapper json = JsonMapper.builder().build();
    HttpWorld world;
    Account acme;
    String systemKey;
    String otherSystemKey;
    String viewerToken;
    String customerId;
    String walletId;
    TenantId tenant;

    @BeforeAll
    void createWorld() {
        world = new HttpWorld(mvc, replica, scopes);
        acme = world.newTenant();
        tenant = new TenantId(UUID.fromString(acme.tenantId()));
        systemKey = world.newSystem(acme, prefixed(SCOPES_TEXT.split(","))).token();
        otherSystemKey = world.newSystem(acme, prefixed(SCOPES_TEXT.split(","))).token();
        viewerToken = world.newUserToken(acme, java.util.Set.of("billing:invoice:read", "billing:subscription:read"));
        customerId = world.body(call(HttpMethod.POST, systemKey, "/api/v1/customers", "cust-" + UUID.randomUUID(),
                HttpWorld.Map2.of("externalUserId", "u-" + UUID.randomUUID(), "displayName", "Payer"))).get("id").asString();
        walletId = world.body(call(HttpMethod.POST, systemKey, "/api/v1/wallets", null,
                HttpWorld.Map2.of("customerId", customerId, "currency", "USD", "mode", "POSTPAID", "creditLimit", 1_000_000))).get("id").asString();
        world.body(call(HttpMethod.PUT, acme.adminToken(), "/api/v1/stripe/configuration/direct", null, HttpWorld.Map2.of("secretKey", "sk_test_http_invoicing_" + UUID.randomUUID().toString().replace("-", ""),
                "publishableKey", "pk_test_x", "webhookSecret", "whsec_http_invoicing_secret_0123456789")));
    }

    @BeforeEach
    void resetStripe() {
        stripe.reset();
    }

    private static String[] prefixed(String[] codes) {
        return java.util.Arrays.stream(codes).map(code -> "billing:" + code).toArray(String[]::new);
    }

    private MvcResult call(HttpMethod method, String token, String path, String idempotencyKey, Object payload) {
        try {
            var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(method, path).header("Host", acme.host());
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            if (idempotencyKey != null) {
                builder.header("Idempotency-Key", idempotencyKey);
            }
            if (payload != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
            }
            return mvc.perform(builder).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private ChargeResult charge(long amount) {
        return scopes.inTenant(tenant, () -> charges.charge(new ChargeCommand(dev.ngb.billing.sharedkernel.id.WalletId.of(walletId), null, Money.of(amount,
                dev.ngb.billing.sharedkernel.money.Currency.of("USD")), null, "http work", "charge-" + UUID.randomUUID())));
    }

    /** An open invoice for a fresh Postpaid charge, made the way a Tenant System would ask for one. */
    private JsonNode openInvoice(long amount) {
        ChargeResult charged = charge(amount);
        return world.body(call(HttpMethod.POST, systemKey, "/api/v1/invoices", null, HttpWorld.Map2.of("chargeId", charged.id().toString())));
    }

    private String invoiceId(JsonNode detail) {
        return detail.get("invoice").get("id").asString();
    }

    @Test
    void anInvoiceForAChargeIsCreatedOnceAndAskingAgainReturnsIt() {
        ChargeResult charged = charge(1_500);
        MvcResult first = call(HttpMethod.POST, systemKey, "/api/v1/invoices", null, HttpWorld.Map2.of("chargeId", charged.id().toString()));
        MvcResult again = call(HttpMethod.POST, systemKey, "/api/v1/invoices", null, HttpWorld.Map2.of("chargeId", charged.id().toString()));

        assertThat(status(first)).isEqualTo(201);
        assertThat(status(again)).isEqualTo(200);
        JsonNode created = world.body(first);
        assertThat(created.get("invoice").get("status").asString()).isEqualTo("OPEN");
        assertThat(created.get("invoice").get("total").get("amount").asLong()).isEqualTo(1_500);
        assertThat(created.get("invoice").get("number").asString()).startsWith("INV-");
        assertThat(created.get("items")).hasSize(1);
        assertThat(world.body(again).get("invoice").get("id").asString()).isEqualTo(invoiceId(created));
        assertThat(status(call(HttpMethod.POST, systemKey, "/api/v1/invoices", null, HttpWorld.Map2.of("chargeId", UUID.randomUUID().toString())))).isEqualTo(404);
        assertThat(status(call(HttpMethod.POST, systemKey, "/api/v1/invoices", null, HttpWorld.Map2.of()))).isEqualTo(400);
    }

    @Test
    void anAdministratorAndTheMappedSystemSeeInvoicesAndAnotherSystemDoesNot() {
        String id = invoiceId(openInvoice(900));

        for (String token : new String[] {acme.adminToken(), systemKey, viewerToken}) {
            JsonNode list = world.body(call(HttpMethod.GET, token, "/api/v1/invoices?filter[customer_id][eq]=" + customerId, null, null)).get("data");
            assertThat(list.valueStream().map(i -> i.get("id").asString())).contains(id);
            assertThat(status(call(HttpMethod.GET, token, "/api/v1/invoices/" + id, null, null))).isEqualTo(200);
        }
        assertThat(world.body(call(HttpMethod.GET, otherSystemKey, "/api/v1/invoices", null, null)).get("data")).isEmpty();
        assertThat(status(call(HttpMethod.GET, otherSystemKey, "/api/v1/invoices/" + id, null, null))).isEqualTo(404);
        assertThat(status(call(HttpMethod.GET, null, "/api/v1/invoices", null, null))).isEqualTo(401);
    }

    @Test
    void payingStartsAPaymentAndMarksNothingPaid() {
        String id = invoiceId(openInvoice(2_000));

        MvcResult started = call(HttpMethod.POST, systemKey, "/api/v1/invoices/" + id + "/payments", "pay-" + id, null);
        MvcResult replay = call(HttpMethod.POST, systemKey, "/api/v1/invoices/" + id + "/payments", "pay-" + id, null);

        assertThat(status(started)).isEqualTo(201);
        assertThat(status(replay)).isEqualTo(200);
        JsonNode body = world.body(started);
        assertThat(body.get("payment").get("status").asString()).isEqualTo("REQUIRES_ACTION");
        assertThat(body.get("payment").get("checkoutUrl").asString()).isNotBlank();
        assertThat(body.get("invoice").get("invoice").get("status").asString()).isEqualTo("OPEN");
        assertThat(body.get("invoice").get("payments")).hasSize(1);
        assertThat(world.body(replay).get("payment").get("id").asString()).isEqualTo(body.get("payment").get("id").asString());
        assertThat(status(call(HttpMethod.POST, systemKey, "/api/v1/invoices/" + id + "/payments", null, null))).as("an Idempotency-Key is required").isEqualTo(400);
        assertThat(status(call(HttpMethod.POST, otherSystemKey, "/api/v1/invoices/" + id + "/payments", "other-" + id, null))).isEqualTo(404);
        assertThat(status(call(HttpMethod.POST, viewerToken, "/api/v1/invoices/" + id + "/payments", "viewer-" + id, null))).isEqualTo(403);
        JsonNode after = world.body(call(HttpMethod.GET, systemKey, "/api/v1/invoices/" + id, null, null));
        assertThat(after.get("invoice").get("status").asString()).as("only a verified Stripe result can pay it").isEqualTo("OPEN");
    }

    @Test
    void onlyAnAdministratorCanVoidAndItNeedsAReason() {
        String id = invoiceId(openInvoice(700));
        String path = "/api/v1/invoices/" + id + "/void";

        assertThat(status(call(HttpMethod.POST, systemKey, path, null, HttpWorld.Map2.of("reason", "no")))).isEqualTo(403);
        assertThat(status(call(HttpMethod.POST, viewerToken, path, null, HttpWorld.Map2.of("reason", "no")))).isEqualTo(403);
        assertThat(status(call(HttpMethod.POST, acme.adminToken(), path, null, HttpWorld.Map2.of("reason", " ")))).isEqualTo(400);

        JsonNode voided = world.body(call(HttpMethod.POST, acme.adminToken(), path, null, HttpWorld.Map2.of("reason", "issued in error")));
        assertThat(voided.get("invoice").get("status").asString()).isEqualTo("VOID");
        assertThat(voided.get("invoice").get("voidReason").asString()).isEqualTo("issued in error");
        assertThat(status(call(HttpMethod.POST, acme.adminToken(), path, null, HttpWorld.Map2.of("reason", "again")))).isEqualTo(409);
        assertThat(status(call(HttpMethod.POST, systemKey, "/api/v1/invoices/" + id + "/payments", "late-" + id, null))).as("a void invoice cannot be paid").isEqualTo(409);
    }

    @Test
    void anOpenInvoiceCanBeWrittenOffAndStillBePaidLater() {
        String id = invoiceId(openInvoice(700));
        JsonNode written = world.body(call(HttpMethod.POST, acme.adminToken(), "/api/v1/invoices/" + id + "/uncollectible", null, null));
        assertThat(written.get("invoice").get("status").asString()).isEqualTo("UNCOLLECTIBLE");
        assertThat(status(call(HttpMethod.POST, systemKey, "/api/v1/invoices/" + id + "/payments", "late-" + id, null))).isEqualTo(201);
        assertThat(status(call(HttpMethod.POST, systemKey, "/api/v1/invoices/" + id + "/uncollectible", null, null))).isEqualTo(403);
    }

    @Test
    void aTenantSystemTokenWithoutInvoiceManageIsRefused() {
        // Billing no longer issues credentials; Platform refuses invoice:manage to a machine. Here a machine token
        // that simply lacks the code is refused by Billing's own check.
        String system = world.newSystem(acme, "billing:invoice:read").token();
        assertThat(status(call(HttpMethod.POST, system, "/api/v1/invoices/" + UUID.randomUUID() + "/void", null, null))).isEqualTo(403);
    }

    @Test
    void aSubscriptionIsCreatedIdempotentlyChargedAtOnceAndCanceledAtPeriodEnd() {
        String planId = pricing.plan(tenant, "pro", pricing.pro(), BillingCycle.MONTHLY, null, null).id().toString();
        java.util.Map<String, Object> request = HttpWorld.Map2.of("walletId", walletId, "planId", planId);
        String key = "sub-" + UUID.randomUUID();

        MvcResult created = call(HttpMethod.POST, systemKey, "/api/v1/subscriptions", key, request);
        MvcResult replay = call(HttpMethod.POST, systemKey, "/api/v1/subscriptions", key, request);

        assertThat(status(created)).isEqualTo(201);
        assertThat(status(replay)).isEqualTo(200);
        JsonNode body = world.body(created);
        String id = body.get("subscription").get("id").asString();
        assertThat(body.get("subscription").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.get("subscription").get("periodsCharged").asInt()).isEqualTo(1);
        assertThat(body.get("subscription").get("cycle").asString()).isEqualTo("MONTHLY");
        assertThat(body.get("history").valueStream().map(e -> e.get("type").asString())).containsExactly("CREATED", "RENEWED");
        assertThat(world.body(replay).get("subscription").get("id").asString()).isEqualTo(id);
        assertThat(status(call(HttpMethod.POST, systemKey, "/api/v1/subscriptions", null, request))).as("an Idempotency-Key is required").isEqualTo(400);
        assertThat(status(call(HttpMethod.POST, systemKey, "/api/v1/subscriptions", "bad-" + key, HttpWorld.Map2.of("walletId", walletId)))).isEqualTo(400);

        assertThat(world.body(call(HttpMethod.GET, acme.adminToken(),
                "/api/v1/subscriptions?filter[customer_id][eq]=" + customerId, null, null)).get("data").valueStream()
                .map(s -> s.get("id").asString())).contains(id);
        assertThat(world.body(call(HttpMethod.GET, otherSystemKey, "/api/v1/subscriptions", null, null)).get("data")).isEmpty();
        assertThat(status(call(HttpMethod.GET, otherSystemKey, "/api/v1/subscriptions/" + id, null, null))).isEqualTo(404);
        assertThat(status(call(HttpMethod.POST, otherSystemKey, "/api/v1/subscriptions/" + id + "/cancel", null, null))).isEqualTo(404);
        assertThat(status(call(HttpMethod.POST, viewerToken, "/api/v1/subscriptions/" + id + "/cancel", null, null))).isEqualTo(403);

        JsonNode canceled = world.body(call(HttpMethod.POST, systemKey, "/api/v1/subscriptions/" + id + "/cancel", null, null));
        assertThat(canceled.get("subscription").get("cancelAtPeriodEnd").asBoolean()).isTrue();
        assertThat(canceled.get("subscription").get("status").asString()).isEqualTo("ACTIVE");
        JsonNode again = world.body(call(HttpMethod.POST, systemKey, "/api/v1/subscriptions/" + id + "/cancel", null, null));
        assertThat(again.get("history").valueStream().filter(e -> e.get("type").asString().equals("CANCEL_REQUESTED"))).hasSize(1);
    }

    private JsonNode list(String token, String query) {
        MvcResult result = call(HttpMethod.GET, token, "/api/v1/invoices?" + query, null, null);
        assertThat(status(result)).as(query).isEqualTo(200);
        return world.body(result);
    }

    private List<Long> totals(JsonNode page) {
        return page.get("data").valueStream().map(i -> i.get("total").get("amount").asLong()).toList();
    }

    /**
     * Five open invoices with the totals {@code base + 11, 22, 33, 44, 55}. The world is shared by the whole class, so
     * each test takes a base of its own and never sees another test's invoices in its filter.
     */
    private String fiveInvoices(long base) {
        List<String> totals = new java.util.ArrayList<>();
        for (int step = 1; step <= 5; step++) {
            long amount = base + step * 11L;
            openInvoice(amount);
            totals.add(Long.toString(amount));
        }
        return String.join(",", totals);
    }

    @Test
    void aListingFiltersSortsAndOrsByTheContract() {
        String totals = fiveInvoices(7000);
        String mine = "filter[total][in]=" + totals + "&filter[customer_id][eq]=" + customerId;

        assertThat(totals(list(systemKey, mine + "&sort=-total"))).containsExactly(7055L, 7044L, 7033L, 7022L, 7011L);
        assertThat(totals(list(systemKey, mine + "&filter[total][gte]=7022&filter[total][lt]=7055&sort=total"))).containsExactly(7022L, 7033L, 7044L);
        assertThat(totals(list(systemKey, mine + "&filter[total][nin]=7011,7055&sort=total"))).containsExactly(7022L, 7033L, 7044L);
        // customer AND (total = 7011 OR total = 7055)
        assertThat(totals(list(systemKey, "filter[customer_id][eq]=" + customerId + "&filter[or][0][total][eq]=7011&filter[or][1][total][eq]=7055&sort=total")))
                .containsExactly(7011L, 7055L);
        // these invoices are issued, so each has a number
        assertThat(totals(list(systemKey, mine + "&filter[number][is_null]=false&filter[status][eq]=OPEN&sort=total"))).hasSize(5);
        assertThat(totals(list(systemKey, mine + "&filter[number][is_null]=true"))).isEmpty();
        assertThat(list(systemKey, mine).path("page").isNull()).as("no page info unless asked").isTrue();
    }

    @Test
    void offsetPagingReportsTheTotalAndTheNextCursor() {
        String totals = fiveInvoices(8000);
        JsonNode page = list(systemKey, "filter[total][in]=" + totals + "&sort=total&limit=2&offset=2&page_info=true");

        assertThat(totals(page)).containsExactly(8033L, 8044L);
        assertThat(page.get("page").get("total").asLong()).isEqualTo(5);
        assertThat(page.get("page").get("offset").asInt()).isEqualTo(2);
        assertThat(page.get("page").get("limit").asInt()).isEqualTo(2);
        assertThat(page.get("page").get("next_cursor").asString()).isNotBlank();
    }

    @Test
    void cursorPagingVisitsEveryRowOnceInOrder() {
        String totals = fiveInvoices(9000);
        String base = "filter[total][in]=" + totals + "&sort=-total&limit=2&page_info=true";
        List<Long> seen = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = list(systemKey, cursor == null ? base : base + "&cursor=" + cursor);
            seen.addAll(totals(page));
            cursor = page.get("page").get("next_cursor").isNull() ? null : page.get("page").get("next_cursor").asString();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).containsExactly(9055L, 9044L, 9033L, 9022L, 9011L);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void aRequestOutsideTheContractIsRefusedWithAReason() {
        String totals = fiveInvoices(6000);
        String cursor = list(systemKey, "filter[total][in]=" + totals + "&sort=total&limit=1&page_info=true").get("page").get("next_cursor").asString();
        String[][] refused = {
            {"filter[void_reason][eq]=x", "void_reason"},
            {"filter[total][contains]=1", "does not apply"},
            {"filter[status][eq]=NOPE", "status takes one of"},
            {"filter[total][gte]=lots", "whole number"},
            {"sort=settlement", "cannot be sorted"},
            {"before=" + UUID.randomUUID(), "not supported"},
            {"offset=10001", "between 0 and 10000"},
            {"filter[total][in]=" + totals + "&sort=total&limit=1&cursor=" + cursor + "&offset=1", "either offset or cursor"},
            {"filter[total][in]=" + totals + "&sort=-total&limit=1&cursor=" + cursor, "different filter or sort"},
        };
        for (String[] attempt : refused) {
            MvcResult result = call(HttpMethod.GET, systemKey, "/api/v1/invoices?" + attempt[0], null, null);
            assertThat(status(result)).as(attempt[0]).isEqualTo(400);
            assertThat(json.readTree(body(result)).path("detail").asString()).as(attempt[0] + " -> " + body(result)).contains(attempt[1]);
        }
    }

    private static String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aSystemWithNoMappedCustomerGetsAnEmptyPageEvenWithPageInfo() {
        JsonNode page = list(otherSystemKey, "page_info=true&limit=5");
        assertThat(page.get("data")).isEmpty();
        assertThat(page.get("page").get("total").asLong()).isZero();
        assertThat(page.get("page").get("next_cursor").isNull()).isTrue();
    }

    @Test
    void theCycleInvoiceAppearsThroughTheApiWithItsSubscriptionLine() {
        String planId = pricing.plan(tenant, "pro", pricing.pro(), BillingCycle.MONTHLY, null, null).id().toString();
        JsonNode sub = world.body(call(HttpMethod.POST, systemKey, "/api/v1/subscriptions", "sub-" + UUID.randomUUID(), HttpWorld.Map2.of("walletId", walletId, "planId", planId)));
        scopes.runInTenant(tenant, relay::relayDue);
        owner.jdbc().update("update invoice_items set cycle_start = cycle_start - interval '1 month', cycle_end = cycle_end - interval '1 month' "
                + "where wallet_id = ? and invoice_id is null", UUID.fromString(walletId));
        scopes.inTenant(tenant, closeCycle::runOnce);

        JsonNode invoices = world.body(call(HttpMethod.GET, systemKey, "/api/v1/invoices?filter[wallet_id][eq]=" + walletId + "&filter[status][eq]=OPEN", null, null)).get("data");
        JsonNode cycle = invoices.valueStream().filter(i -> i.get("origin").asString().equals("POSTPAID_CYCLE")).findFirst().orElseThrow();
        JsonNode detail = world.body(call(HttpMethod.GET, systemKey, "/api/v1/invoices/" + cycle.get("id").asString(), null, null));
        assertThat(detail.get("items").valueStream().map(i -> i.get("subscriptionId") == null || i.get("subscriptionId").isNull() ? "" : i.get("subscriptionId").asString()))
                .contains(sub.get("subscription").get("id").asString());
        assertThat(detail.get("items").valueStream().map(i -> i.get("source").asString())).contains("SUBSCRIPTION_PERIOD");
    }
}
