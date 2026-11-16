package dev.ngb.loyalty.rewards.adapter.reward.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.RewardsApiTest;
import org.junit.jupiter.api.Test;

/**
 * The catalog an endpoint serves: a reward is written once, read with the ETag its version names, patched only against
 * that tag, and archived rather than deleted.
 *
 * <p>Each case states one promise of the endpoint. The ETag contract is the interesting one — 428 when the caller sends
 * no {@code If-Match}, 412 plus the current tag when it sends a stale one — because it is what stops two catalog edits
 * from silently overwriting each other.
 */
class RewardApiIntegrationTest extends RewardsApiTest {

    @Test
    void aCreatedRewardIsReadBackWithItsETagAndItsDefaults() {
        TenantId tenant = tenant();

        ApiResponse created = createReward(tenant, "Free coffee", 50L, 10L, 7);

        assertThat(created.status()).as("the catalog answered " + created.body()).isEqualTo(201);
        assertThat(created.etag()).as("a create answers the version it wrote").isEqualTo("\"1\"");
        String rewardId = created.json().get("rewardId").asString();
        assertThat(rewardId).isNotBlank();
        assertThat(created.json().get("name").asString()).isEqualTo("Free coffee");
        assertThat(created.json().get("pointsCost").asLong()).isEqualTo(50);
        assertThat(created.json().get("stock").asLong()).isEqualTo(10);
        assertThat(created.json().get("active").asBoolean()).as("a reward is active unless the caller says otherwise")
                .isTrue();
        assertThat(created.json().get("voucherValidDays").asInt()).isEqualTo(7);
        assertThat(created.json().get("version").asLong()).isEqualTo(1);

        ApiResponse read = getReward(tenant, rewardId);

        assertThat(read.status()).isEqualTo(200);
        assertThat(read.etag()).as("the read carries the same tag the write did").isEqualTo(created.etag());
        assertThat(read.json().get("rewardId").asString()).isEqualTo(rewardId);
        assertThat(read.json().get("pointsCost").asLong()).isEqualTo(50);

        // The documented default of a missing lifetime is thirty days.
        ApiResponse defaulted = createReward(tenant, "Defaulted", 10L, null, null);
        assertThat(defaulted.json().get("voucherValidDays").asInt()).isEqualTo(30);
        assertThat(defaulted.json().get("stock").isNull()).as("no stock is unlimited").isTrue();
    }

    @Test
    void theCatalogListsTheRewardsAndNarrowsThemByState() {
        TenantId tenant = tenant();
        createReward(tenant, "Cheap", 10L, null, null);
        ApiResponse second = createReward(tenant, "Pricey", 90L, 5L, null);
        String archivedId = second.json().get("rewardId").asString();
        assertThat(patchReward(tenant, archivedId, "\"1\"", "{\"active\": false}").status()).isEqualTo(200);

        ApiResponse all = listRewards(tenant, null);
        ApiResponse active = listRewards(tenant, true);
        ApiResponse inactive = listRewards(tenant, false);

        assertThat(all.status()).isEqualTo(200);
        assertThat(all.json().get("data")).hasSize(2);
        assertThat(all.json().get("data").get(0).get("name").asString())
                .as("the cheapest reward first, which is the catalog's default sort")
                .isEqualTo("Cheap");
        assertThat(active.json().get("data")).hasSize(1);
        assertThat(active.json().get("data").get(0).get("name").asString()).isEqualTo("Cheap");
        assertThat(inactive.json().get("data")).hasSize(1);
        assertThat(inactive.json().get("data").get(0).get("active").asBoolean()).isFalse();
    }

    @Test
    void aPatchNeedsIfMatchAndAnswers428WithoutIt() {
        TenantId tenant = tenant();
        String rewardId = seedReward(tenant, "Coffee", 50L, null, null);

        ApiResponse refused = patchReward(tenant, rewardId, null, "{\"name\": \"Tea\"}");

        assertThat(refused.status()).isEqualTo(428);
        assertThat(refused.code()).isEqualTo("IF_MATCH_REQUIRED");
        assertThat(getReward(tenant, rewardId).json().get("name").asString())
                .as("the refused patch wrote nothing")
                .isEqualTo("Coffee");
    }

    @Test
    void aPatchAgainstAStaleETagAnswers412WithTheCurrentOne() {
        TenantId tenant = tenant();
        String rewardId = seedReward(tenant, "Coffee", 50L, null, null);

        ApiResponse patched = patchReward(tenant, rewardId, "\"1\"", "{\"pointsCost\": 75}");
        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.etag()).isEqualTo("\"2\"");
        assertThat(patched.json().get("pointsCost").asLong()).isEqualTo(75);

        ApiResponse stale = patchReward(tenant, rewardId, "\"1\"", "{\"pointsCost\": 10}");

        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.etag()).as("the answer names the version to retry against").isEqualTo("\"2\"");
        assertThat(getReward(tenant, rewardId).json().get("pointsCost").asLong())
                .as("the stale patch wrote nothing")
                .isEqualTo(75);
    }

    @Test
    void aPatchChangesOnlyWhatItNames() {
        TenantId tenant = tenant();
        ApiResponse created = postReward(tenant, """
                {"name": "Coffee", "description": "A flat white", "pointsCost": 50, "stock": 10,
                 "voucherValidDays": 7, "validTo": "2026-12-31T00:00:00Z"}
                """);
        String rewardId = created.json().get("rewardId").asString();

        ApiResponse patched = patchReward(tenant, rewardId, "\"1\"", "{\"stock\": 3}");

        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.json().get("stock").asLong()).isEqualTo(3);
        assertThat(patched.json().get("name").asString()).isEqualTo("Coffee");
        assertThat(patched.json().get("description").asString()).isEqualTo("A flat white");
        assertThat(patched.json().get("pointsCost").asLong()).isEqualTo(50);
        assertThat(patched.json().get("voucherValidDays").asInt()).isEqualTo(7);
        assertThat(patched.json().get("validTo").asString()).isEqualTo("2026-12-31T00:00:00Z");
    }

    @Test
    void anArchivedRewardKeepsItsVouchersButCannotBeRedeemedAgain() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, null, null);
        ApiResponse issued = redeemAndIssue(tenant, "M-1", "K-1", rewardId);
        String code = issued.json().get("voucher").get("code").asString();

        assertThat(patchReward(tenant, rewardId, "\"1\"", "{\"active\": false}").status()).isEqualTo(200);

        ApiResponse refused = redeemReward(tenant, "M-1", "K-2", rewardId);

        assertThat(refused.status()).as("an archived reward is not a catalog entry a member may redeem")
                .isEqualTo(422);
        assertThat(refused.code()).isEqualTo("REWARD_NOT_AVAILABLE");
        assertThat(getVoucher(tenant, code).status()).as("the voucher it issued stays readable").isEqualTo(200);
        assertThat(getVoucher(tenant, code).json().get("state").asString()).isEqualTo("ISSUED");
        assertThat(scalar(tenant, "select count(*) from redemptions")).as("only the first redemption happened")
                .isEqualTo(1);
        assertThat(available(tenant, "M-1")).isEqualTo(60);
    }

    @Test
    void aRewardOutsideItsWindowOrInTheFutureCannotBeRedeemed() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String future = postReward(tenant, """
                {"name": "Later", "pointsCost": 10, "validFrom": "2030-01-01T00:00:00Z"}
                """).json().get("rewardId").asString();
        String past = postReward(tenant, """
                {"name": "Gone", "pointsCost": 10, "validTo": "2020-01-01T00:00:00Z"}
                """).json().get("rewardId").asString();

        for (String rewardId : new String[] {future, past}) {
            ApiResponse refused = redeemReward(tenant, "M-1", "K-" + rewardId, rewardId);
            assertThat(refused.status()).isEqualTo(422);
            assertThat(refused.code()).isEqualTo("REWARD_NOT_AVAILABLE");
        }
        assertThat(scalar(tenant, "select count(*) from redemptions")).isZero();
        assertThat(vouchers(tenant)).isZero();
        assertThat(available(tenant, "M-1")).isEqualTo(100);
    }

    @Test
    void aCostStockOrWindowOutOfRangeIsRefusedAsInvalid() {
        TenantId tenant = tenant();

        assertInvalid(tenant, """
                {"name": "Negative cost", "pointsCost": -1}
                """);
        assertInvalid(tenant, """
                {"name": "Negative stock", "pointsCost": 10, "stock": -1}
                """);
        assertInvalid(tenant, """
                {"name": "Inverted", "pointsCost": 10, "validFrom": "2026-06-01T00:00:00Z",
                 "validTo": "2026-01-01T00:00:00Z"}
                """);
        assertInvalid(tenant, """
                {"name": "Short lived", "pointsCost": 10, "voucherValidDays": 0}
                """);
        assertInvalid(tenant, """
                {"name": "", "pointsCost": 10}
                """);
        assertInvalid(tenant, """
                {"name": "No cost"}
                """);

        assertThat(scalar(tenant, "select count(*) from rewards")).as("no refused write stored a reward").isZero();
    }

    /**
     * A cost of zero is refused, because it cannot be honoured: redeeming a reward spends its cost through the one spend
     * function and issues the voucher under the redemption that spend wrote, and a redemption of no points is not a
     * redemption the points context can store ({@code redemptions.points > 0}). A catalog entry that could never be
     * redeemed is not an entry, so the write is refused instead of accepted and then failing on every redemption.
     */
    @Test
    void aRewardCostingNothingIsRefusedBecauseItCouldNeverBeRedeemed() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 10L);

        ApiResponse refused = createReward(tenant, "Gift", 0L, null, null);

        assertThat(refused.status()).as("a zero cost answered " + refused.body()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(scalar(tenant, "select count(*) from rewards")).isZero();
    }

    @Test
    void aStalePatchCannotMoveAWindowPastItsOtherEnd() {
        TenantId tenant = tenant();
        ApiResponse created = postReward(tenant, """
                {"name": "Coffee", "pointsCost": 10, "validFrom": "2026-01-01T00:00:00Z",
                 "validTo": "2026-12-31T00:00:00Z"}
                """);
        String rewardId = created.json().get("rewardId").asString();

        ApiResponse refused = patchReward(tenant, rewardId, "\"1\"",
                "{\"validTo\": \"2025-01-01T00:00:00Z\"}");

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(getReward(tenant, rewardId).json().get("validTo").asString())
                .as("the refused patch merged nothing")
                .isEqualTo("2026-12-31T00:00:00Z");
    }

    @Test
    void anUnknownRewardIsNotFoundAndAMalformedIdIsABadRequest() {
        TenantId tenant = tenant();

        ApiResponse unknown = getReward(tenant, java.util.UUID.randomUUID().toString());
        ApiResponse malformed = call(
                tenant,
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/rewards/not-a-uuid"),
                LoyaltyPermissions.REWARD_READ);

        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("REWARD_NOT_FOUND");
        assertThat(malformed.status()).isEqualTo(400);
    }

    @Test
    void anotherTenantReadsPatchesAndRedeemsNoneOfIt() {
        TenantId owner = tenant();
        seedEarnedPoints(owner, "M-1", 100L);
        ApiResponse created = createReward(owner, "Owner only", 40L, 5L, null);
        String rewardId = created.json().get("rewardId").asString();

        TenantId other = tenant();
        putMember(other, "M-1", "Someone else");

        assertThat(getReward(other, rewardId).status()).isEqualTo(404);
        assertThat(getReward(other, rewardId).code()).isEqualTo("REWARD_NOT_FOUND");
        assertThat(patchReward(other, rewardId, "\"1\"", "{\"name\": \"Stolen\"}").status()).isEqualTo(404);
        assertThat(listRewards(other, null).json().get("data")).as("another tenant's catalog is empty").isEmpty();

        ApiResponse stolen = redeemReward(other, "M-1", "K-1", rewardId);

        assertThat(stolen.status()).as("the reward is not in this tenant").isEqualTo(404);
        assertThat(stolen.code()).isEqualTo("REWARD_NOT_FOUND");
        assertThat(getReward(owner, rewardId).json().get("name").asString()).isEqualTo("Owner only");
        assertThat(scalar(owner, "select count(*) from rewards")).isEqualTo(1);
        assertThat(scalar(owner, "select count(*) from redemptions")).isZero();
    }

    @Test
    void everyCatalogOperationNeedsItsOwnPermission() {
        TenantId tenant = tenant();
        String rewardId = seedReward(tenant, "Coffee", 40L, null, null);

        assertThat(call(tenant, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/v1/rewards"), LoyaltyPermissions.MEMBER_READ)
                        .status())
                .as("a read with the wrong permission")
                .isEqualTo(403);
        assertThat(getReward(tenant, rewardId, LoyaltyPermissions.MEMBER_READ).status()).isEqualTo(403);
        assertThat(postReward(tenant, """
                {"name": "Sneaky", "pointsCost": 1}
                """, LoyaltyPermissions.REWARD_READ).status())
                .as("a manage with only the read permission")
                .isEqualTo(403);
        assertThat(patchReward(tenant, rewardId, "\"1\"", "{\"name\": \"Sneaky\"}", LoyaltyPermissions.REWARD_READ)
                        .status())
                .isEqualTo(403);
        assertThat(redeemReward(tenant, "M-1", "K-1", rewardId, LoyaltyPermissions.REWARD_READ).status())
                .as("a redemption needs its own code, not the catalog's")
                .isEqualTo(403);
        assertThat(scalar(tenant, "select count(*) from rewards")).as("no refused call wrote").isEqualTo(1);
    }

    private void assertInvalid(TenantId tenant, String body) {
        ApiResponse refused = postReward(tenant, body);
        assertThat(refused.status()).as(body + " answered " + refused.body()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("VALIDATION_FAILED");
    }
}
