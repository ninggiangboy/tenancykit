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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

/** The coupon API over HTTP: creation, redemption, revocation, the wallet-credit benefit and its permission split. */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@Import({PostgresContainerConfig.class, TestTokensConfig.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponHttpIntegrationTest {

    private static final List<String> COUPON_SCOPES = List.of(
            "billing:coupon:read", "billing:coupon:manage", "billing:coupon:redeem", "billing:wallet:read", "billing:wallet:manage",
            "billing:customer:read", "billing:customer:write");

    @Autowired MockMvc mvc;
    @Autowired ScopeTestSupport scopes;
    @Autowired ReplicaFixtures replica;
    @Autowired CreditWallet creditWallet;

    HttpWorld world;
    Account acme;
    Account globex;
    System couponSystem;
    String walletId;
    String customerId;

    @BeforeAll
    void createWorld() {
        world = new HttpWorld(mvc, replica, scopes);
        acme = world.newTenant();
        globex = world.newTenant();
        couponSystem = world.newSystem(acme, COUPON_SCOPES.toArray(String[]::new));
        customerId = newCustomer(acme, couponSystem.token());
        walletId = openWallet(acme, couponSystem.token(), customerId, "USD");
    }

    private JsonNode postWithKey(String token, String path, Object body) {
        MvcResult result = world.call(HttpMethod.POST, acme.host(), token, path, body,
                java.util.Map.of("Idempotency-Key", "setup-" + UUID.randomUUID()));
        return world.body(result);
    }

    private String newCustomer(Account account, String token) {
        return postWithKey(token, "/api/v1/customers",
                HttpWorld.Map2.of("externalUserId", "user-" + UUID.randomUUID(), "displayName", "Acme Ltd")).get("id").asString();
    }

    private String openWallet(Account account, String token, String customer, String currency) {
        return postWithKey(token, "/api/v1/wallets",
                HttpWorld.Map2.of("customerId", customer, "currency", currency, "mode", "PREPAID")).get("id").asString();
    }

    private void credit(String wallet, long minorUnits) {
        scopes.runInTenant(new TenantId(UUID.fromString(acme.tenantId())), () -> creditWallet.credit(new CreditCommand(
                WalletId.of(wallet), Money.of(minorUnits, Currency.of("USD")), "topup:" + UUID.randomUUID(), null,
                "credit-" + UUID.randomUUID(), "system/test")));
    }

    private JsonNode createPercentCoupon(String token, int basisPoints, long maxRedemptions, String code) {
        MvcResult result = world.call(HttpMethod.POST, acme.host(), token, "/api/v1/coupons", HttpWorld.Map2.of(
                "name", "Save " + basisPoints,
                "currency", "USD",
                "benefit", HttpWorld.Map2.of("type", "PERCENT_OFF", "basisPoints", basisPoints),
                "limits", HttpWorld.Map2.of("redeemBy", "2027-01-01T00:00:00Z", "maxRedemptions", maxRedemptions),
                "stackable", true,
                "code", code));
        return world.body(result);
    }

    private JsonNode createCreditCoupon(String token, long amount, String code) {
        MvcResult result = world.call(HttpMethod.POST, acme.host(), token, "/api/v1/coupons", HttpWorld.Map2.of(
                "name", "Welcome credit",
                "currency", "USD",
                "benefit", HttpWorld.Map2.of("type", "WALLET_CREDIT", "amount", amount),
                "limits", HttpWorld.Map2.of("redeemBy", "2027-01-01T00:00:00Z"),
                "code", code));
        return world.body(result);
    }

    private MvcResult redeemByCode(String token, String wallet, String code, String key) {
        return world.call(HttpMethod.POST, acme.host(), token, "/api/v1/wallets/" + wallet + "/coupon-redemptions",
                HttpWorld.Map2.of("code", code), java.util.Map.of("Idempotency-Key", key));
    }

    private MvcResult attach(String token, String wallet, String couponId, String key) {
        return world.call(HttpMethod.POST, acme.host(), token, "/api/v1/wallets/" + wallet + "/coupon-redemptions/attach",
                HttpWorld.Map2.of("couponId", couponId), java.util.Map.of("Idempotency-Key", key));
    }

    @Test
    void aCouponAndItsCodeAreCreatedAndListed() {
        JsonNode created = createPercentCoupon(couponSystem.token(), 2_000, 100, "SAVE20");

        assertThat(created.get("coupon").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.get("coupon").get("benefitType").asString()).isEqualTo("PERCENT_OFF");
        assertThat(created.get("coupon").get("percentBasisPoints").asInt()).isEqualTo(2_000);
        assertThat(created.get("coupon").get("createdBy").asString()).isNotBlank();
        assertThat(created.get("code").get("code").asString()).isEqualTo("SAVE20");

        String couponId = created.get("coupon").get("id").asString();
        JsonNode listed = world.body(world.call(HttpMethod.GET, acme.host(), couponSystem.token(), "/api/v1/coupons", null));
        assertThat(listed.findValuesAsString("id")).contains(couponId);

        JsonNode codes = world.body(world.call(HttpMethod.GET, acme.host(), couponSystem.token(), "/api/v1/coupons/" + couponId + "/codes", null));
        assertThat(codes.findValuesAsString("code")).contains("SAVE20");
    }

    @Test
    void aCouponWithoutATotalBoundIsRefused() {
        MvcResult result = world.call(HttpMethod.POST, acme.host(), couponSystem.token(), "/api/v1/coupons", HttpWorld.Map2.of(
                "name", "Unbounded",
                "currency", "USD",
                "benefit", HttpWorld.Map2.of("type", "PERCENT_OFF", "basisPoints", 1_000),
                "limits", HttpWorld.Map2.of(),
                "stackable", true));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void redeemingByCodeCreatesOneActiveRedemptionAndReplaysUnderTheSameKey() {
        String wallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");
        credit(wallet, 10_000);
        createPercentCoupon(couponSystem.token(), 2_000, 100, "REDEEM1");

        MvcResult first = redeemByCode(couponSystem.token(), wallet, "redeem1", "key-1");
        MvcResult replay = redeemByCode(couponSystem.token(), wallet, "REDEEM1", "key-1");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        String redemptionId = world.body(first).get("id").asString();
        assertThat(world.body(replay).get("id").asString()).isEqualTo(redemptionId);

        JsonNode listed = world.body(world.call(HttpMethod.GET, acme.host(), couponSystem.token(),
                "/api/v1/wallets/" + wallet + "/coupon-redemptions", null));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void aSecondAttachOfTheSameCouponOnOneWalletIsRefused() {
        String wallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");
        String couponId = createPercentCoupon(couponSystem.token(), 1_000, 100, null).get("coupon").get("id").asString();

        assertThat(attach(couponSystem.token(), wallet, couponId, "attach-1").getResponse().getStatus()).isEqualTo(201);
        assertThat(attach(couponSystem.token(), wallet, couponId, "attach-2").getResponse().getStatus()).isEqualTo(409);
    }

    private String contentOf(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anUnknownCodeAnswersTheSameNotFoundAsAnExhaustedOne() {
        String wallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");
        createPercentCoupon(couponSystem.token(), 1_000, 1, "ONCEONLY");

        MvcResult unknownResponse = redeemByCode(couponSystem.token(), wallet, "DOESNOTEXIST", "unknown-key");
        assertThat(redeemByCode(couponSystem.token(), wallet, "ONCEONLY", "used-key").getResponse().getStatus()).isEqualTo(201);
        String exhaustedWallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");
        MvcResult exhausted = redeemByCode(couponSystem.token(), exhaustedWallet, "ONCEONLY", "exhausted-key");

        assertThat(exhausted.getResponse().getStatus()).isEqualTo(404);
        assertThat(detailOf(exhausted)).as("an exhausted code reads exactly like an unknown one").isEqualTo(detailOf(unknownResponse));
    }

    private String detailOf(MvcResult result) {
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(result.getResponse().getContentAsString()).get("detail").asString();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aWalletCreditCouponCreditsExactlyOnce() {
        String wallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");
        createCreditCoupon(couponSystem.token(), 25_000, "WELCOME10K");

        MvcResult first = redeemByCode(couponSystem.token(), wallet, "welcome10k", "credit-key");
        MvcResult replay = redeemByCode(couponSystem.token(), wallet, "WELCOME10K", "credit-key");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(world.body(first).get("status").asString()).isEqualTo("ENDED");
        assertThat(world.body(first).get("endReason").asString()).isEqualTo("GRANTED");

        JsonNode balance = world.body(world.call(HttpMethod.GET, acme.host(), couponSystem.token(), "/api/v1/wallets/" + wallet, null));
        assertThat(balance.get("balance").get("amount").asLong()).isEqualTo(25_000);
        assertThat(balance.get("lastSequence").asLong()).as("one ledger entry, however often the key arrives").isEqualTo(1);
    }

    @Test
    void revokingEndsTheRedemptionAndRepeatingItPublishesNothingNew() {
        String wallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");
        createPercentCoupon(couponSystem.token(), 2_000, 100, "REVOKE1");
        String redemptionId = world.body(redeemByCode(couponSystem.token(), wallet, "REVOKE1", "revoke-key")).get("id").asString();

        JsonNode revoked = world.body(world.call(HttpMethod.POST, acme.host(), couponSystem.token(),
                "/api/v1/coupon-redemptions/" + redemptionId + "/revoke", null));
        JsonNode again = world.body(world.call(HttpMethod.POST, acme.host(), couponSystem.token(),
                "/api/v1/coupon-redemptions/" + redemptionId + "/revoke", null));

        assertThat(revoked.get("status").asString()).isEqualTo("REVOKED");
        assertThat(revoked.get("endReason").asString()).isEqualTo("REVOKED");
        assertThat(again.get("status").asString()).isEqualTo("REVOKED");
    }

    @Test
    void deactivatingACouponStopsNewRedemptionsAndLeavesRunningOnesAlone() {
        String wallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");
        JsonNode created = createPercentCoupon(couponSystem.token(), 2_000, 100, "DEACT1");
        String couponId = created.get("coupon").get("id").asString();
        world.body(redeemByCode(couponSystem.token(), wallet, "DEACT1", "deact-key"));

        world.body(world.call(HttpMethod.POST, acme.host(), couponSystem.token(), "/api/v1/coupons/" + couponId + "/deactivate",
                HttpWorld.Map2.of("revokeRedemptions", false)));

        String otherWallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");
        assertThat(redeemByCode(couponSystem.token(), otherWallet, "DEACT1", "deact-key-2").getResponse().getStatus()).isEqualTo(404);
        JsonNode running = world.body(world.call(HttpMethod.GET, acme.host(), couponSystem.token(),
                "/api/v1/wallets/" + wallet + "/coupon-redemptions", null));
        assertThat(running.get(0).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void deactivatingWithRevocationEndsEveryRunningRedemption() {
        String wallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");
        String couponId = createPercentCoupon(couponSystem.token(), 2_000, 100, "PULLIT").get("coupon").get("id").asString();
        world.body(redeemByCode(couponSystem.token(), wallet, "PULLIT", "pull-key"));

        world.body(world.call(HttpMethod.POST, acme.host(), couponSystem.token(), "/api/v1/coupons/" + couponId + "/deactivate",
                HttpWorld.Map2.of("revokeRedemptions", true)));

        JsonNode running = world.body(world.call(HttpMethod.GET, acme.host(), couponSystem.token(),
                "/api/v1/wallets/" + wallet + "/coupon-redemptions", null));
        assertThat(running.get(0).get("status").asString()).isEqualTo("REVOKED");
        assertThat(running.get(0).get("endReason").asString()).isEqualTo("COUPON_DEACTIVATED");
    }

    @Test
    void redeemNeedsOnlyTheRedeemPermissionWhileAttachNeedsManage() {
        String wallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");
        createPercentCoupon(couponSystem.token(), 2_000, 100, "PERMCODE");
        String redeemOnly = couponSystem.tokenFor("billing:coupon:redeem");

        assertThat(redeemByCode(redeemOnly, wallet, "PERMCODE", "perm-key").getResponse().getStatus()).isEqualTo(201);
        assertThat(world.status(HttpMethod.POST, acme.host(), redeemOnly, "/api/v1/coupons", HttpWorld.Map2.of(
                "name", "x", "currency", "USD", "benefit", HttpWorld.Map2.of("type", "PERCENT_OFF", "basisPoints", 100),
                "limits", HttpWorld.Map2.of("redeemBy", "2027-01-01T00:00:00Z"), "stackable", true))).isEqualTo(403);
    }

    @Test
    void aTenantSystemCannotRedeemOntoAnUnmappedCustomer() {
        System narrow = world.newSystem(acme, "billing:coupon:redeem");
        createPercentCoupon(couponSystem.token(), 2_000, 100, "MAPPED");
        String ownWallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");

        assertThat(redeemByCode(couponSystem.token(), ownWallet, "MAPPED", "map-key").getResponse().getStatus()).isEqualTo(201);
        String anotherWallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");
        assertThat(redeemByCode(narrow.token(), anotherWallet, "MAPPED", "map-key-2").getResponse().getStatus())
                .as("the customer is not mapped to this system").isEqualTo(404);
    }

    @Test
    void aTenantSystemCannotReadTheRedemptionsOfAnUnmappedCustomer() {
        System narrow = world.newSystem(acme, "billing:coupon:read");
        createPercentCoupon(couponSystem.token(), 2_000, 100, "SEEN");
        String ownWallet = openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD");
        String redemptionId = world.body(redeemByCode(couponSystem.token(), ownWallet, "SEEN", "seen-key")).get("id").asString();

        assertThat(world.status(HttpMethod.GET, acme.host(), couponSystem.token(), "/api/v1/wallets/" + ownWallet + "/coupon-redemptions", null))
                .isEqualTo(200);
        assertThat(world.status(HttpMethod.GET, acme.host(), narrow.token(), "/api/v1/wallets/" + ownWallet + "/coupon-redemptions", null))
                .as("the customer is not mapped to this system").isEqualTo(404);
        assertThat(world.status(HttpMethod.GET, acme.host(), narrow.token(), "/api/v1/coupon-redemptions/" + redemptionId, null))
                .isEqualTo(404);
        assertThat(world.status(HttpMethod.GET, acme.host(), couponSystem.token(), "/api/v1/coupon-redemptions/" + redemptionId, null))
                .isEqualTo(200);
    }

    @Test
    void anotherTenantSeesNoCouponOrRedemption() {
        JsonNode created = createPercentCoupon(couponSystem.token(), 2_000, 100, "HIDDEN");
        String couponId = created.get("coupon").get("id").asString();

        assertThat(world.status(HttpMethod.GET, globex.host(), globex.adminToken(), "/api/v1/coupons/" + couponId, null)).isEqualTo(404);
        assertThat(world.body(world.call(HttpMethod.GET, globex.host(), globex.adminToken(), "/api/v1/coupons", null))).isEmpty();
    }

    @Test
    void twentyParallelRedeemsOfACouponWithOneLeftSucceedExactlyOnce() throws Exception {
        String couponId = createPercentCoupon(couponSystem.token(), 1_000, 1, "LASTONE").get("coupon").get("id").asString();
        List<String> wallets = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            wallets.add(openWallet(acme, couponSystem.token(), newCustomer(acme, couponSystem.token()), "USD"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String wallet = wallets.get(i);
                futures.add(CompletableFuture.supplyAsync(
                        () -> attach(couponSystem.token(), wallet, couponId, "last-" + UUID.randomUUID()).getResponse().getStatus(), pool));
            }
            int created = 0;
            for (CompletableFuture<Integer> future : futures) {
                if (future.get(120, TimeUnit.SECONDS) == 201) {
                    created++;
                }
            }
            assertThat(created).as("exactly one of twenty redeems of a coupon with one left succeeds").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
