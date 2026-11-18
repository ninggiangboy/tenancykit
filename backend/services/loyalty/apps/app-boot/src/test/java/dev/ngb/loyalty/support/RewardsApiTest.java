package dev.ngb.loyalty.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rewards catalog, its redemption and the vouchers it issues, on top of the HTTP harness.
 *
 * <p>Every helper takes the tenant, so a case says which tenant answers what rather than reaching into a context the
 * endpoint owns. The catalog is written through the endpoints themselves — there is no seed of a reward, exactly as
 * there is no seed of a program: a reward a case redeems is a reward a case created.
 */
public abstract class RewardsApiTest extends PointsApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The program every case seeds: one point per hundred minor units, so an order's points are its amount over 100. */
    public static final EarnVectors.Program PROGRAM = new EarnVectors.Program("USD", 100L, "UTC", 12, 30, 12);

    public static final List<EarnVectors.Tier> TIERS = List.of(
            new EarnVectors.Tier("BRONZE", 0L, new BigDecimal("1.00")),
            new EarnVectors.Tier("SILVER", 100000L, new BigDecimal("1.50")));

    public static final String ORDERED_AT = "2026-03-02T10:00:00Z";

    // ------------------------------------------------------------------ setup

    protected static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }

    protected static EarnVectors.Rules rules() {
        return new EarnVectors.Rules(PROGRAM, TIERS, List.of());
    }

    /** Seeds the program and grants {@code externalRef} exactly {@code points} unexpired points through one order. */
    protected void seedEarnedPoints(TenantId tenant, String externalRef, long points) {
        seedRules(tenant, rules());
        ApiResponse order = putOrder(
                tenant, "O-" + externalRef, orderBody(externalRef, "USD", ORDERED_AT, "ST-01", points * 100));
        assertThat(order.status()).as("the order granting the points: " + order.body()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ catalog

    /** One catalog write as a caller sends it. */
    protected ApiResponse postReward(TenantId tenant, String json, String... permissions) {
        return call(
                tenant,
                post("/api/v1/rewards").contentType(MediaType.APPLICATION_JSON).content(json),
                permissions.length == 0 ? new String[] {LoyaltyPermissions.REWARD_MANAGE} : permissions);
    }

    protected ApiResponse createReward(TenantId tenant, String name, Long pointsCost, Long stock,
            Integer voucherValidDays) {
        return postReward(tenant, JSON.writeValueAsString(rewardBody(name, pointsCost, stock, null, null,
                voucherValidDays)));
    }

    protected static Map<String, Object> rewardBody(
            String name, Long pointsCost, Long stock, Boolean active, String validTo, Integer voucherValidDays) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("pointsCost", pointsCost);
        body.put("stock", stock);
        body.put("active", active);
        body.put("validTo", validTo);
        body.put("voucherValidDays", voucherValidDays);
        return body;
    }

    /** Creates a reward a case is about to redeem and answers its id. */
    protected String seedReward(TenantId tenant, String name, long pointsCost, Long stock, Integer voucherValidDays) {
        ApiResponse created = createReward(tenant, name, pointsCost, stock, voucherValidDays);
        assertThat(created.status()).as("the reward could be created: " + created.body()).isEqualTo(201);
        return created.json().get("rewardId").asString();
    }

    protected ApiResponse getReward(TenantId tenant, String rewardId, String... permissions) {
        return call(
                tenant,
                get("/api/v1/rewards/" + rewardId),
                permissions.length == 0 ? new String[] {LoyaltyPermissions.REWARD_READ} : permissions);
    }

    protected ApiResponse listRewards(TenantId tenant, Boolean active, String... permissions) {
        MockHttpServletRequestBuilder request = get("/api/v1/rewards");
        if (active != null) {
            request = request.param("active", Boolean.toString(active));
        }
        return call(
                tenant,
                request,
                permissions.length == 0 ? new String[] {LoyaltyPermissions.REWARD_READ} : permissions);
    }

    /** A patch; a null {@code ifMatch} omits the header, which is what the 428 case proves. */
    protected ApiResponse patchReward(TenantId tenant, String rewardId, String ifMatch, String json,
            String... permissions) {
        MockHttpServletRequestBuilder request =
                patch("/api/v1/rewards/" + rewardId).contentType(MediaType.APPLICATION_JSON).content(json);
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return call(
                tenant,
                request,
                permissions.length == 0 ? new String[] {LoyaltyPermissions.REWARD_MANAGE} : permissions);
    }

    // ------------------------------------------------------------------ redemption

    /** One reward redemption as the POS sends it: which reward, and the caller's key for this redemption. */
    protected ApiResponse redeemReward(TenantId tenant, String externalRef, String idempotencyKey, String rewardId,
            String... permissions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rewardId", rewardId);
        MockHttpServletRequestBuilder request =
                post("/api/v1/members/" + externalRef + "/reward-redemptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return call(
                tenant,
                request,
                permissions.length == 0 ? new String[] {LoyaltyPermissions.REWARD_REDEEM} : permissions);
    }

    /** Redeems one unit of a reward and answers the voucher the redemption issued. */
    protected ApiResponse redeemAndIssue(TenantId tenant, String externalRef, String idempotencyKey, String rewardId) {
        ApiResponse redeemed = redeemReward(tenant, externalRef, idempotencyKey, rewardId);
        assertThat(redeemed.status()).as("the redemption answered " + redeemed.body()).isEqualTo(201);
        return redeemed;
    }

    // ------------------------------------------------------------------ vouchers

    protected ApiResponse getVoucher(TenantId tenant, String code, String... permissions) {
        return call(
                tenant,
                get("/api/v1/vouchers/" + code),
                permissions.length == 0 ? new String[] {LoyaltyPermissions.VOUCHER_READ} : permissions);
    }

    protected ApiResponse useVoucher(TenantId tenant, String code, String useRef, String... permissions) {
        MockHttpServletRequestBuilder request = post("/api/v1/vouchers/" + code + "/use")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("useRef", useRef)));
        return call(
                tenant,
                request,
                permissions.length == 0 ? new String[] {LoyaltyPermissions.VOUCHER_USE} : permissions);
    }

    protected ApiResponse listMemberVouchers(TenantId tenant, String externalRef, String status,
            String... permissions) {
        MockHttpServletRequestBuilder request = get("/api/v1/members/" + externalRef + "/vouchers");
        if (status != null) {
            request = request.param("status", status);
        }
        return call(
                tenant,
                request,
                permissions.length == 0 ? new String[] {LoyaltyPermissions.VOUCHER_READ} : permissions);
    }

    // ------------------------------------------------------------------ schema helpers

    /** The stored stock of a reward, read straight from the row the redemption decrements. */
    protected long stock(TenantId tenant, String rewardId) {
        return scalar(tenant, "select stock from rewards where id = ?", UUID.fromString(rewardId));
    }

    /** The points the member's REDEEM entries recorded, which is what a spend writes exactly once. */
    protected long redeemedTotal(TenantId tenant) {
        return scalar(tenant, "select coalesce(sum(points), 0) from ledger_entries where kind = 'REDEEM'");
    }

    protected long vouchers(TenantId tenant) {
        return scalar(tenant, "select count(*) from vouchers");
    }

    protected long outboxRows(TenantId tenant, String type) {
        return scalar(tenant, "select count(*) from outbox where type = ?", type);
    }

    /**
     * Moves a voucher's expiry into the past. Expiry is not a state and no endpoint sets it, so a case that proves an
     * expired voucher cannot be used states the instant the read derives from rather than waiting a day for it.
     */
    protected void expireVoucher(TenantId tenant, String code) {
        int updated = inTenant(tenant, () -> unitOfWork.transactional(() -> dsl
                .execute("update vouchers set expires_at = now() - interval '1 hour' where code = ?", code)));
        assertThat(updated).as("the voucher " + code + " could be expired").isEqualTo(1);
    }

    /** The redemption a voucher's code names, which is what a reversal is asked for. */
    protected UUID redemptionOfVoucher(TenantId tenant, String code) {
        return inTenant(tenant, () -> unitOfWork.transactional(() -> dsl
                .fetchOne("select redemption_id from vouchers where code = ?", code)
                .get(0, UUID.class)));
    }
}
