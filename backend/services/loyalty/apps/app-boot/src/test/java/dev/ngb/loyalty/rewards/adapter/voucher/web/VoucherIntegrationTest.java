package dev.ngb.loyalty.rewards.adapter.voucher.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.RewardsApiTest;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Vouchers as the point of sale reads them: one by the code it presents, and the ones a member holds.
 *
 * <p>Expiry is not a state, it is an instant a reader compares, so a voucher that has outlived it is still
 * {@code ISSUED} on a read and is refused on a use — no job has to move it along. A code is unique per tenant and is
 * Crockford's base32, which is what makes it safe to read out loud.
 */
class VoucherIntegrationTest extends RewardsApiTest {

    /** Crockford's alphabet: the digits, then the letters without I, L, O and U. */
    private static final Pattern CROCKFORD = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{12}$");

    @Test
    void aVoucherIsReadByItsCodeWithTheRewardItWasIssuedFor() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, 5L, 7);
        var issued = redeemAndIssue(tenant, "M-1", "K-1", rewardId).json().get("voucher");
        String code = issued.get("code").asString();

        ApiResponse read = getVoucher(tenant, code);

        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().get("voucherId").asString()).isEqualTo(issued.get("voucherId").asString());
        assertThat(read.json().get("code").asString()).isEqualTo(code);
        assertThat(read.json().get("rewardId").asString()).as("the Tenant maps the reward from this").isEqualTo(rewardId);
        assertThat(read.json().get("externalRef").asString()).isEqualTo("M-1");
        assertThat(read.json().get("state").asString()).isEqualTo("ISSUED");
        assertThat(read.json().get("expiresAt").asString()).isEqualTo(issued.get("expiresAt").asString());
        assertThat(read.json().get("usedAt").isNull()).isTrue();
        assertThat(read.json().get("voidReason").isNull()).isTrue();

        // A code read the way Crockford's alphabet says to read one out loud names the same voucher.
        ApiResponse canonical = getVoucher(tenant, code.toLowerCase(java.util.Locale.ROOT));
        assertThat(canonical.status()).as("the code is canonicalised, not refused").isEqualTo(200);
        assertThat(canonical.json().get("code").asString()).isEqualTo(code);
        ApiResponse spaced = getVoucher(tenant, code.substring(0, 4) + "-" + code.substring(4));
        assertThat(spaced.status()).as("a separator a person adds is decoration").isEqualTo(200);
    }

    @Test
    void aMembersVoucherListCarriesTheRewardAndNarrowsByState() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String coffee = seedReward(tenant, "Coffee", 40L, null, 7);
        String tea = seedReward(tenant, "Tea", 20L, null, 7);
        String firstCode = redeemAndIssue(tenant, "M-1", "K-1", coffee).json().get("voucher").get("code").asString();
        String secondCode = redeemAndIssue(tenant, "M-1", "K-2", tea).json().get("voucher").get("code").asString();
        assertThat(useVoucher(tenant, secondCode, "USE-1").status()).isEqualTo(200);

        ApiResponse all = listMemberVouchers(tenant, "M-1", null);
        ApiResponse issued = listMemberVouchers(tenant, "M-1", "ISSUED");
        ApiResponse used = listMemberVouchers(tenant, "M-1", "USED");
        ApiResponse voided = listMemberVouchers(tenant, "M-1", "VOIDED");

        assertThat(all.status()).isEqualTo(200);
        assertThat(all.json().get("data")).hasSize(2);
        assertThat(all.json().get("data").get(0).get("rewardId").asString())
                .as("the newest voucher first, which is how a wallet reads")
                .isEqualTo(tea);
        assertThat(all.json().get("data").get(1).get("rewardId").asString()).isEqualTo(coffee);
        assertThat(issued.json().get("data")).hasSize(1);
        assertThat(issued.json().get("data").get(0).get("code").asString()).isEqualTo(firstCode);
        assertThat(issued.json().get("data").get(0).get("rewardId").asString()).isEqualTo(coffee);
        assertThat(used.json().get("data")).hasSize(1);
        assertThat(used.json().get("data").get(0).get("useRef").asString()).isEqualTo("USE-1");
        assertThat(voided.json().get("data")).isEmpty();
        assertThat(listMemberVouchers(tenant, "M-1", "NONSENSE").status()).isEqualTo(422);
    }

    /**
     * Expiry is derived from the stored instant at read time: the row is untouched, stays {@code ISSUED} in every read,
     * and the use is what refuses it. No job moves it along, so nothing has to run for this to hold.
     */
    @Test
    void anExpiredVoucherStaysIssuedOnAReadAndCannotBeUsed() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, null, 1);
        String code = redeemAndIssue(tenant, "M-1", "K-1", rewardId).json().get("voucher").get("code").asString();
        expireVoucher(tenant, code);

        ApiResponse read = getVoucher(tenant, code);
        ApiResponse listed = listMemberVouchers(tenant, "M-1", "ISSUED");
        ApiResponse refused = useVoucher(tenant, code, "USE-1");

        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().get("state").asString()).as("expiry is not a state").isEqualTo("ISSUED");
        assertThat(Instant.parse(read.json().get("expiresAt").asString())).isBefore(Instant.now());
        assertThat(listed.json().get("data")).as("the wallet still lists it until it is voided").hasSize(1);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("VOUCHER_EXPIRED");
        assertThat(scalar(tenant, "select count(*) from vouchers where status = 'USED'"))
                .as("the refused use wrote nothing")
                .isZero();
        assertThat(outboxRows(tenant, "voucher.used")).isZero();

        // Reversing the redemption still voids it, because an expired voucher was never used.
        assertThat(reverseRedemption(tenant, "M-1", redemptionOfVoucher(tenant, code)).status()).isEqualTo(200);
        assertThat(getVoucher(tenant, code).json().get("state").asString()).isEqualTo("VOIDED");
        assertThat(getVoucher(tenant, code).json().get("voidReason").asString()).isEqualTo("REVERSAL");
    }

    @Test
    void anUnknownOrAnotherTenantsCodeAnswersTheSameConstantNotFound() {
        TenantId owner = tenant();
        seedEarnedPoints(owner, "M-1", 100L);
        String rewardId = seedReward(owner, "Coffee", 40L, null, 7);
        String code = redeemAndIssue(owner, "M-1", "K-1", rewardId).json().get("voucher").get("code").asString();

        TenantId other = tenant();
        ApiResponse unknown = getVoucher(other, "ZZZZZZZZZZZZ");
        ApiResponse foreign = getVoucher(other, code);

        assertThat(unknown.status()).isEqualTo(404);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.code()).isEqualTo("VOUCHER_NOT_FOUND");
        assertSameRefusal(unknown, foreign);
        assertThat(foreign.json().get("detail").asString()).doesNotContain(code);

        ApiResponse usedElsewhere = useVoucher(other, code, "USE-1");
        assertThat(usedElsewhere.status()).isEqualTo(404);
        assertThat(usedElsewhere.code()).isEqualTo("VOUCHER_NOT_FOUND");
        assertSameRefusal(usedElsewhere, useVoucher(other, "ZZZZZZZZZZZZ", "USE-1"));
        assertThat(getVoucher(owner, code).json().get("state").asString()).isEqualTo("ISSUED");
        assertThat(listMemberVouchers(other, "M-1", null).code()).isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    void everyCodeIsTwelveCharactersOfCrockfordBase32AndUnique() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 1000L);
        String rewardId = seedReward(tenant, "Coffee", 1L, null, 7);

        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            String code = redeemAndIssue(tenant, "M-1", "K-" + i, rewardId).json().get("voucher").get("code").asString();
            assertThat(code).as("a twelve-character Crockford base32 code").matches(CROCKFORD);
            assertThat(codes.add(code)).as("the code " + code + " was generated before").isTrue();
        }
        assertThat(scalar(tenant, "select count(distinct code) from vouchers")).isEqualTo(25);
    }

    @Test
    void aUseThatConsumesTheVoucherAnswersWithTheUsedStateAndTheUseRef() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, null, 7);
        String code = redeemAndIssue(tenant, "M-1", "K-1", rewardId).json().get("voucher").get("code").asString();

        ApiResponse used = useVoucher(tenant, code, "POS-1");

        assertThat(used.status()).isEqualTo(200);
        assertThat(used.json().get("state").asString()).isEqualTo("USED");
        assertThat(used.json().get("useRef").asString()).isEqualTo("POS-1");
        assertThat(used.json().get("usedAt").asString()).isNotBlank();
        assertThat(used.json().get("rewardId").asString()).isEqualTo(rewardId);
        assertThat(getVoucher(tenant, code).json().get("useRef").asString()).isEqualTo("POS-1");

        // The same reference replays what it wrote; another one is refused.
        ApiResponse replay = useVoucher(tenant, code, "POS-1");
        ApiResponse other = useVoucher(tenant, code, "POS-2");

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.json().get("useRef").asString()).isEqualTo("POS-1");
        assertThat(replay.json().get("usedAt").asString()).isEqualTo(used.json().get("usedAt").asString());
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.code()).isEqualTo("VOUCHER_USED");
        assertThat(outboxRows(tenant, "voucher.used")).as("only the one call that wrote announced it").isEqualTo(1);
    }

    @Test
    void aUseWithoutAReferenceOrWithABlankOneIsRefused() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, null, 7);
        String code = redeemAndIssue(tenant, "M-1", "K-1", rewardId).json().get("voucher").get("code").asString();

        ApiResponse blank = useVoucher(tenant, code, "   ");

        assertThat(blank.status()).isEqualTo(422);
        assertThat(blank.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(getVoucher(tenant, code).json().get("state").asString()).isEqualTo("ISSUED");
        assertThat(blank.json().get("detail").asString()).as("a refusal never echoes the code").doesNotContain(code);
    }

    @Test
    void everyVoucherOperationNeedsItsOwnPermission() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-1", 100L);
        String rewardId = seedReward(tenant, "Coffee", 40L, null, 7);
        String code = redeemAndIssue(tenant, "M-1", "K-1", rewardId).json().get("voucher").get("code").asString();

        assertThat(getVoucher(tenant, code, LoyaltyPermissions.REWARD_READ).status()).isEqualTo(403);
        assertThat(listMemberVouchers(tenant, "M-1", null, LoyaltyPermissions.MEMBER_READ).status()).isEqualTo(403);
        assertThat(useVoucher(tenant, code, "USE-1", LoyaltyPermissions.VOUCHER_READ).status()).isEqualTo(403);
        assertThat(getVoucher(tenant, code).json().get("state").asString()).as("no refused call used it")
                .isEqualTo("ISSUED");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Two refusals of a code this tenant does not hold are indistinguishable but for the {@code instance}, which is the
     * URL the caller itself sent: the status, the title, the code and the detail a caller branches on are identical, so
     * guessing a code cannot tell a real code of another tenant from one that never existed.
     */
    private static void assertSameRefusal(ApiResponse one, ApiResponse other) {
        assertThat(one.status()).isEqualTo(other.status());
        assertThat(one.code()).isEqualTo(other.code());
        assertThat(one.json().get("title").asString()).isEqualTo(other.json().get("title").asString());
        assertThat(one.json().get("detail").asString()).isEqualTo(other.json().get("detail").asString());
        assertThat(one.json().get("instance").asString())
                .as("only the echoed request URL differs, which the caller already knows")
                .isNotEqualTo(other.json().get("instance").asString());
    }
}
