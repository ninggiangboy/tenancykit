package dev.ngb.billing.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.billing.Application;
import dev.ngb.billing.money.api.wallet.CreditCommand;
import dev.ngb.billing.money.api.wallet.CreditWallet;
import dev.ngb.billing.sharedkernel.id.WalletId;
import dev.ngb.billing.sharedkernel.money.Currency;
import dev.ngb.billing.sharedkernel.money.Money;
import dev.ngb.billing.support.HttpWorld;
import dev.ngb.billing.support.HttpWorld.Account;
import dev.ngb.billing.support.HttpWorld.System;
import dev.ngb.billing.support.PostgresContainerConfig;
import dev.ngb.billing.support.ReplicaFixtures;
import dev.ngb.billing.support.ScopeTestSupport;
import dev.ngb.billing.support.TestTokensConfig;
import dev.ngb.core.id.TenantId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The discount seam end to end: a wallet holding SAVE20 (20% off) and WELCOME10K (10,000 off) is charged 70,000 for
 * a gross 100,000, the charge records its gross and both lines, and the ledger records exactly the net.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, TestTokensConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponChargeIntegrationTest {

    private static final List<String> SCOPES = List.of(
            "billing:coupon:read", "billing:coupon:manage", "billing:coupon:redeem", "billing:wallet:read", "billing:wallet:manage",
            "billing:customer:read", "billing:customer:write", "billing:charge:create", "billing:reservation:manage");

    @Autowired MockMvc mvc;
    @Autowired ScopeTestSupport scopes;
    @Autowired ReplicaFixtures replica;
    @Autowired CreditWallet creditWallet;

    HttpWorld world;
    Account acme;
    System system;
    String wallet;
    String globalCoupon;

    @BeforeAll
    void createWorld() {
        world = new HttpWorld(mvc, replica, scopes);
        acme = world.newTenant();
        system = world.newSystem(acme, SCOPES.toArray(String[]::new));
        wallet = walletWith(100_000);
        globalCoupon = createPercentCoupon(2_000, null).get("coupon").get("id").asString();
    }

    private JsonNode createPercentCoupon(int basisPoints, String code) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "Save " + basisPoints);
        body.put("currency", "USD");
        body.put("benefit", HttpWorld.Map2.of("type", "PERCENT_OFF", "basisPoints", basisPoints));
        body.put("limits", HttpWorld.Map2.of("redeemBy", "2027-01-01T00:00:00Z"));
        body.put("stackable", true);
        if (code != null) {
            body.put("code", code);
        }
        return world.body(world.call(HttpMethod.POST, acme.host(), system.token(), "/api/v1/coupons", body));
    }

    private JsonNode createAmountCoupon(long amount, String code) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "Take " + amount);
        body.put("currency", "USD");
        body.put("benefit", HttpWorld.Map2.of("type", "AMOUNT_OFF", "amount", amount));
        body.put("limits", HttpWorld.Map2.of("redeemBy", "2027-01-01T00:00:00Z"));
        body.put("stackable", true);
        if (code != null) {
            body.put("code", code);
        }
        return world.body(world.call(HttpMethod.POST, acme.host(), system.token(), "/api/v1/coupons", body));
    }

    private String walletWith(long balance) {
        String customer = world.body(world.call(HttpMethod.POST, acme.host(), system.token(), "/api/v1/customers",
                HttpWorld.Map2.of("externalUserId", "user-" + UUID.randomUUID(), "displayName", "Acme Ltd"),
                Map.of("Idempotency-Key", "cust-" + UUID.randomUUID()))).get("id").asString();
        String id = world.body(world.call(HttpMethod.POST, acme.host(), system.token(), "/api/v1/wallets",
                HttpWorld.Map2.of("customerId", customer, "currency", "USD", "mode", "PREPAID"),
                Map.of("Idempotency-Key", "wallet-" + UUID.randomUUID()))).get("id").asString();
        if (balance > 0) {
            scopes.runInTenant(new TenantId(UUID.fromString(acme.tenantId())), () -> creditWallet.credit(new CreditCommand(
                    WalletId.of(id), Money.of(balance, Currency.of("USD")), "topup:" + UUID.randomUUID(), null,
                    "credit-" + UUID.randomUUID(), "system/test")));
        }
        return id;
    }

    private JsonNode attach(String walletId, String couponId, String key) {
        return world.body(world.call(HttpMethod.POST, acme.host(), system.token(),
                "/api/v1/wallets/" + walletId + "/coupon-redemptions/attach", HttpWorld.Map2.of("couponId", couponId),
                Map.of("Idempotency-Key", key)));
    }

    private MvcResult charge(String walletId, long amount, String key) {
        return world.call(HttpMethod.POST, acme.host(), system.token(), "/api/v1/charges",
                HttpWorld.Map2.of("walletId", walletId, "amount", amount, "currency", "USD", "reference", "test-charge"),
                Map.of("Idempotency-Key", key));
    }

    private JsonNode parse(MvcResult result) {
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Long> lineAmounts(JsonNode charge) {
        return charge.get("discounts").valueStream().map(line -> line.get("amount").get("amount").asLong()).toList();
    }

    private JsonNode balance(String walletId) {
        return world.body(world.call(HttpMethod.GET, acme.host(), system.token(), "/api/v1/wallets/" + walletId, null));
    }

    @Test
    void twentyPercentThenTenThousandOffChargesSeventyThousand() {
        String saving = createPercentCoupon(2_000, "SAVE20").get("coupon").get("id").asString();
        String welcome = createAmountCoupon(10_000, "WELCOME10K").get("coupon").get("id").asString();
        String target = walletWith(100_000);
        attach(target, saving, "key-1");
        attach(target, welcome, "key-2");

        JsonNode charge = world.body(charge(target, 100_000, "charge-1"));

        assertThat(charge.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(charge.get("amount").get("amount").asLong()).isEqualTo(70_000);
        assertThat(charge.get("grossAmount").get("amount").asLong()).isEqualTo(100_000);
        assertThat(charge.get("discounts")).hasSize(2);
        assertThat(lineAmounts(charge)).containsExactly(20_000L, 10_000L);
        assertThat(charge.get("ledgerEntryId").asString()).isNotNull();
        assertThat(balance(target).get("balance").get("amount").asLong()).isEqualTo(30_000);
    }

    @Test
    void aReplayedChargeIsNotDiscountedTwice() {
        String saving = createPercentCoupon(2_000, "REPLAY20").get("coupon").get("id").asString();
        String target = walletWith(100_000);
        attach(target, saving, "replay-1");

        JsonNode first = world.body(charge(target, 100_000, "replay-charge"));
        JsonNode replay = world.body(charge(target, 100_000, "replay-charge"));

        assertThat(first.get("amount").get("amount").asLong()).isEqualTo(80_000);
        assertThat(replay.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(balance(target).get("balance").get("amount").asLong()).isEqualTo(20_000);
        JsonNode redemption = world.body(world.call(HttpMethod.GET, acme.host(), system.token(),
                "/api/v1/coupon-redemptions/" + first.get("discounts").get(0).get("redemptionId").asString(), null));
        assertThat(redemption.get("applicationsUsed").asInt()).as("a replay consumes nothing").isEqualTo(1);
    }

    @Test
    void aPercentageCouponHasOneApplicationAndEndsWhenItIsSpent() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "One use");
        body.put("currency", "USD");
        body.put("benefit", HttpWorld.Map2.of("type", "PERCENT_OFF", "basisPoints", 1_000));
        body.put("maxApplications", 1);
        body.put("limits", HttpWorld.Map2.of("redeemBy", "2027-01-01T00:00:00Z"));
        body.put("stackable", true);
        String couponId = world.body(world.call(HttpMethod.POST, acme.host(), system.token(), "/api/v1/coupons", body))
                .get("coupon").get("id").asString();
        String target = walletWith(200_000);
        attach(target, couponId, "once-1");

        JsonNode first = world.body(charge(target, 100_000, "once-charge-1"));
        JsonNode second = world.body(charge(target, 100_000, "once-charge-2"));

        assertThat(first.get("amount").get("amount").asLong()).isEqualTo(90_000);
        assertThat(second.get("amount").get("amount").asLong()).as("the coupon is spent").isEqualTo(100_000);
        String redemptionId = first.get("discounts").get(0).get("redemptionId").asString();
        JsonNode redemption = world.body(world.call(HttpMethod.GET, acme.host(), system.token(),
                "/api/v1/coupon-redemptions/" + redemptionId, null));
        assertThat(redemption.get("status").asString()).isEqualTo("ENDED");
        assertThat(redemption.get("endReason").asString()).isEqualTo("EXHAUSTED");
    }

    @Test
    void aFullyDiscountedChargePostsNothingAndHasNoLedgerEntry() {
        String free = createPercentCoupon(10_000, "FREE100").get("coupon").get("id").asString();
        String target = walletWith(50_000);
        attach(target, free, "free-1");

        JsonNode charge = world.body(charge(target, 100_000, "free-charge"));

        assertThat(charge.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(charge.get("amount").get("amount").asLong()).isZero();
        assertThat(charge.get("grossAmount").get("amount").asLong()).isEqualTo(100_000);
        assertThat(charge.get("ledgerEntryId").isNull()).isTrue();
        assertThat(charge.get("discounts").get(0).get("amount").get("amount").asLong()).isEqualTo(100_000);
        assertThat(balance(target).get("balance").get("amount").asLong()).as("the wallet did not move").isEqualTo(50_000);
        assertThat(balance(target).get("lastSequence").asLong()).isEqualTo(1);
    }

    @Test
    void aChargeTheWalletCannotPayConsumesNothingAndALaterSuccessDoes() {
        String saving = createPercentCoupon(2_000, "LATER20").get("coupon").get("id").asString();
        String poor = walletWith(0);
        attach(poor, saving, "poor-1");

        MvcResult failed = charge(poor, 100_000, "poor-charge");
        assertThat(failed.getResponse().getStatus()).as("a charge the wallet cannot pay is recorded, not thrown").isEqualTo(402);
        JsonNode failedBody = parse(failed);
        assertThat(failedBody.get("status").asString()).isEqualTo("FAILED");
        assertThat(failedBody.get("discounts")).isEmpty();
        JsonNode redemption = world.body(world.call(HttpMethod.GET, acme.host(), system.token(),
                "/api/v1/wallets/" + poor + "/coupon-redemptions", null));
        assertThat(redemption.get(0).get("applicationsUsed").asInt()).as("a failed charge consumed nothing").isZero();

        scopes.runInTenant(new TenantId(UUID.fromString(acme.tenantId())), () -> creditWallet.credit(new CreditCommand(
                WalletId.of(poor), Money.of(100_000, Currency.of("USD")), "topup:" + UUID.randomUUID(), null,
                "credit-" + UUID.randomUUID(), "system/test")));
        JsonNode succeeded = world.body(charge(poor, 100_000, "poor-charge-2"));

        assertThat(succeeded.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(succeeded.get("amount").get("amount").asLong()).isEqualTo(80_000);
        assertThat(balance(poor).get("balance").get("amount").asLong()).isEqualTo(20_000);
    }

    @Test
    void aNonStackableCouponRunsAloneAndTheCheaperCombinationWins() {
        Map<String, Object> single = new java.util.LinkedHashMap<>();
        single.put("name", "Exclusive 5%");
        single.put("currency", "USD");
        single.put("benefit", HttpWorld.Map2.of("type", "PERCENT_OFF", "basisPoints", 500));
        single.put("limits", HttpWorld.Map2.of("redeemBy", "2027-01-01T00:00:00Z"));
        single.put("stackable", false);
        String exclusive = world.body(world.call(HttpMethod.POST, acme.host(), system.token(), "/api/v1/coupons", single))
                .get("coupon").get("id").asString();
        String stackable = createPercentCoupon(1_000, "STACK10").get("coupon").get("id").asString();
        String target = walletWith(100_000);
        attach(target, exclusive, "mixed-1");
        attach(target, stackable, "mixed-2");

        JsonNode charge = world.body(charge(target, 100_000, "mixed-charge"));

        assertThat(charge.get("amount").get("amount").asLong()).as("10% beats a lone 5%").isEqualTo(90_000);
        assertThat(charge.get("discounts")).hasSize(1);
    }

    @Test
    void aReservationCaptureIsDiscountedAndReturnsTheUnusedHold() {
        String saving = createPercentCoupon(2_000, "RESERVE20").get("coupon").get("id").asString();
        String target = walletWith(100_000);
        attach(target, saving, "reserve-1");
        JsonNode reservation = world.body(world.call(HttpMethod.POST, acme.host(), system.token(), "/api/v1/reservations",
                HttpWorld.Map2.of("walletId", target, "amount", 100_000, "currency", "USD", "ttlSeconds", 600, "reference", "vm"),
                Map.of("Idempotency-Key", "res-" + UUID.randomUUID())));

        JsonNode capture = world.body(world.call(HttpMethod.POST, acme.host(), system.token(),
                "/api/v1/reservations/" + reservation.get("id").asString() + "/capture",
                HttpWorld.Map2.of("amount", 100_000, "currency", "USD", "reference", "vm final"),
                Map.of("Idempotency-Key", "cap-" + UUID.randomUUID())));

        assertThat(capture.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(capture.get("amount").get("amount").asLong()).isEqualTo(80_000);
        assertThat(capture.get("grossAmount").get("amount").asLong()).isEqualTo(100_000);
        JsonNode after = balance(target);
        assertThat(after.get("balance").get("amount").asLong()).isEqualTo(20_000);
        assertThat(after.get("held").get("amount").asLong()).as("the whole hold was released").isZero();
    }

    @Test
    void aSourceScopedCouponDoesNotApplyToAStandaloneCharge() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "Usage only");
        body.put("currency", "USD");
        body.put("benefit", HttpWorld.Map2.of("type", "PERCENT_OFF", "basisPoints", 5_000));
        body.put("limits", HttpWorld.Map2.of("redeemBy", "2027-01-01T00:00:00Z"));
        body.put("scope", HttpWorld.Map2.of("kinds", List.of("USAGE")));
        body.put("stackable", true);
        String couponId = world.body(world.call(HttpMethod.POST, acme.host(), system.token(), "/api/v1/coupons", body))
                .get("coupon").get("id").asString();
        String target = walletWith(100_000);
        attach(target, couponId, "scoped-1");

        JsonNode charge = world.body(charge(target, 100_000, "scoped-charge"));

        assertThat(charge.get("amount").get("amount").asLong()).isEqualTo(100_000);
        assertThat(charge.get("discounts")).isEmpty();
    }
}
