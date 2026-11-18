package dev.ngb.loyalty.points.adapter.redemption.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.EarnVectors;
import dev.ngb.loyalty.support.PointsApiTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The spend and its reversal: what the POS sends, what loyalty answers and what the schema holds afterwards.
 *
 * <p>Each case states one promise of the endpoint — the points come out of the lots that expire soonest, a replayed key
 * spends nothing, a debt refuses the spend until it is repaid, and a reversal gives back exactly what was taken.
 */
class RedemptionApiIntegrationTest extends PointsApiTest {

    private static final String ORDERED_AT = "2026-03-02T10:00:00Z";
    private static final String RETURNED_AT = "2026-03-20T12:00:00Z";

    private static final EarnVectors.Program PROGRAM = new EarnVectors.Program("USD", 100L, "UTC", 12, 30, 12);
    private static final List<EarnVectors.Tier> TIERS = List.of(
            new EarnVectors.Tier("BRONZE", 0L, new BigDecimal("1.00")),
            new EarnVectors.Tier("SILVER", 100000L, new BigDecimal("1.50")));

    @Test
    void aSpendTakesThePointsFromTheLotsAndAnswersTheBalanceItLeft() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 10000));

        ApiResponse spent = postRedemption(tenant, "M-1", "K-1", 30, "RECEIPT-1");

        assertThat(spent.status()).as("the spend answered " + spent.body()).isEqualTo(201);
        assertThat(spent.json().get("points").asLong()).isEqualTo(30);
        assertThat(spent.json().get("reference").asString()).isEqualTo("RECEIPT-1");
        assertThat(spent.json().get("externalRef").asString()).isEqualTo("M-1");
        assertThat(spent.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(spent.json().get("available").asLong()).isEqualTo(70);
        assertThat(spent.json().get("debt").asLong()).isZero();
        assertThat(spent.json().get("createdAt").asString()).isNotBlank();
        assertThat(spent.json().get("reversedAt").isNull()).as("an active redemption has no reversal instant").isTrue();
        assertThat(available(tenant, "M-1")).isEqualTo(70);
        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(1);
        assertThat(scalar(tenant, "select sum(points) from ledger_entries where kind = 'REDEEM'")).isEqualTo(30);
        assertThat(scalar(tenant, "select sum(points) from lot_allocations")).as("the ledger explains the spend lot by lot")
                .isEqualTo(30);
    }

    @Test
    void aSpendWithoutAnIdempotencyKeyIsABadRequest() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 1000));

        ApiResponse refused = call(
                tenant,
                post("/api/v1/members/M-1/redemptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"points\": 1}"),
                LoyaltyPermissions.POINTS_REDEEM);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(scalar(tenant, "select count(*) from redemptions")).as("nothing was spent").isZero();
        assertThat(available(tenant, "M-1")).isEqualTo(10);
    }

    @Test
    void aSpendOfNoPointsIsRefusedAsInvalid() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 1000));

        ApiResponse refused = postRedemption(tenant, "M-1", "K-1", 0, null);

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(scalar(tenant, "select count(*) from redemptions")).isZero();
    }

    @Test
    void theSameKeyAndBodyReplaysTheStoredRedemption() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 5000));

        ApiResponse first = postRedemption(tenant, "M-1", "K-1", 10, "RECEIPT-1");
        ApiResponse again = postRedemption(tenant, "M-1", "K-1", 10, "RECEIPT-1");

        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).as("the key spent before").isEqualTo(200);
        assertThat(again.json().get("redemptionId").asString())
                .isEqualTo(first.json().get("redemptionId").asString());
        assertThat(again.json().get("available").asLong()).isEqualTo(40);
        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(1);
        assertThat(scalar(tenant, "select sum(points) from ledger_entries where kind = 'REDEEM'")).isEqualTo(10);
        assertThat(available(tenant, "M-1")).isEqualTo(40);
    }

    @Test
    void theSameKeyWithADifferentBodyIsAConflict() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 5000));
        postRedemption(tenant, "M-1", "K-1", 10, "RECEIPT-1");

        ApiResponse refused = postRedemption(tenant, "M-1", "K-1", 20, "RECEIPT-1");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(1);
        assertThat(available(tenant, "M-1")).as("the refused call spent nothing").isEqualTo(40);
    }

    @Test
    void aSpendBeyondTheBalanceIsRefusedAndWritesNothing() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 5000));

        ApiResponse refused = postRedemption(tenant, "M-1", "K-1", 51, "RECEIPT-1");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("INSUFFICIENT_POINTS");
        assertThat(scalar(tenant, "select count(*) from redemptions")).isZero();
        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'REDEEM'")).isZero();
        assertThat(available(tenant, "M-1")).isEqualTo(50);
    }

    @Test
    void aMemberInDebtIsRefusedUntilTheDebtIsRepaid() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        seedMember(tenant, "M-1", "BRONZE", 5L);
        inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.execute(
                "insert into point_lots (tenant_id, id, member_id, entry_id, earned, remaining, earned_at, expires_at)"
                        + " values (?, gen_random_uuid(), (select id from members where external_ref = 'M-1'),"
                        + " gen_random_uuid(), 20, 20, now(), now() + interval '30 days')",
                tenant.value())));

        ApiResponse refused = postRedemption(tenant, "M-1", "K-1", 1, "RECEIPT-1");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("MEMBER_IN_DEBT");
        assertThat(available(tenant, "M-1")).as("20 of unexpired lots against 5 of debt").isEqualTo(15);
    }

    @Test
    void anUnknownMemberIsNotFoundForBothTheSpendAndTheRead() {
        TenantId tenant = tenant();

        ApiResponse spent = postRedemption(tenant, "M-nobody", "K-1", 1, null);
        ApiResponse read = getRedemption(tenant, "M-nobody", UUID.randomUUID());

        assertThat(spent.status()).isEqualTo(404);
        assertThat(spent.code()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(read.status()).isEqualTo(404);
        assertThat(read.code()).isEqualTo("MEMBER_NOT_FOUND");
    }

    @Test
    void aRedemptionIsReadByItsIdAndNoOtherTenantSeesIt() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 5000));
        UUID redemptionId = UUID.fromString(
                postRedemption(tenant, "M-1", "K-1", 10, "RECEIPT-1").json().get("redemptionId").asString());

        ApiResponse read = getRedemption(tenant, "M-1", redemptionId);
        ApiResponse unknown = getRedemption(tenant, "M-1", UUID.randomUUID());

        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().get("redemptionId").asString()).isEqualTo(redemptionId.toString());
        assertThat(read.json().get("points").asLong()).isEqualTo(10);
        assertThat(read.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(read.json().get("available").asLong()).isEqualTo(40);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("REDEMPTION_NOT_FOUND");

        // The other tenant has no member of that reference and no redemption of that id, so neither is reachable.
        TenantId other = tenant();
        assertThat(getRedemption(other, "M-1", redemptionId).code()).isEqualTo("MEMBER_NOT_FOUND");
        ApiResponse stolen = postRedemption(other, "M-1", "K-1", 1, "RECEIPT-1");
        assertThat(stolen.status()).isEqualTo(404);
        assertThat(stolen.code()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(scalar(tenant, "select count(*) from redemptions")).isEqualTo(1);
    }

    @Test
    void aReversalRestoresTheLotsAndReplaysSafely() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 5000));
        UUID redemptionId = UUID.fromString(
                postRedemption(tenant, "M-1", "K-1", 20, "RECEIPT-1").json().get("redemptionId").asString());

        ApiResponse reversed = reverseRedemption(tenant, "M-1", redemptionId);

        assertThat(reversed.status()).isEqualTo(200);
        assertThat(reversed.json().get("status").asString()).isEqualTo("REVERSED");
        assertThat(reversed.json().get("reversedAt").asString()).isNotBlank();
        assertThat(reversed.json().get("available").asLong()).as("the spend came back").isEqualTo(50);
        assertThat(available(tenant, "M-1")).isEqualTo(50);
        assertThat(scalar(tenant, "select sum(remaining) from point_lots")).isEqualTo(50);
        assertThat(scalar(tenant, "select sum(points) from ledger_entries where kind = 'REDEEM_REVERSAL'"))
                .isEqualTo(20);

        ApiResponse again = reverseRedemption(tenant, "M-1", redemptionId);

        assertThat(again.status()).as("a reversal replays").isEqualTo(200);
        assertThat(again.json().get("status").asString()).isEqualTo("REVERSED");
        assertThat(again.json().get("available").asLong()).isEqualTo(50);
        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'REDEEM_REVERSAL'"))
                .as("a replayed reversal writes nothing")
                .isEqualTo(1);
        assertThat(getRedemption(tenant, "M-1", redemptionId).json().get("status").asString()).isEqualTo("REVERSED");
    }

    @Test
    void pointsRestoredIntoALotThatExpiredInTheMeantimeStayExpired() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 5000));
        UUID redemptionId = UUID.fromString(
                postRedemption(tenant, "M-1", "K-1", 20, "RECEIPT-1").json().get("redemptionId").asString());
        inTenant(tenant, () -> unitOfWork.transactional(
                () -> dsl.execute("update point_lots set expires_at = now() - interval '1 day'")));

        ApiResponse reversed = reverseRedemption(tenant, "M-1", redemptionId);

        assertThat(reversed.status()).isEqualTo(200);
        assertThat(reversed.json().get("status").asString()).isEqualTo("REVERSED");
        assertThat(reversed.json().get("available").asLong()).as("an expired lot counts for nothing").isZero();
        assertThat(scalar(tenant, "select sum(remaining) from point_lots"))
                .as("the points went back to their lot, which stays expired")
                .isEqualTo(50);
        assertThat(available(tenant, "M-1")).isZero();
        assertThat(postRedemption(tenant, "M-1", "K-2", 1, null).code())
                .as("nothing spendable came back")
                .isEqualTo("INSUFFICIENT_POINTS");
    }

    @Test
    void aReversalOfARedemptionTheMemberNeverMadeIsNotFound() {
        TenantId tenant = tenant();
        putMember(tenant, "M-1", null);

        ApiResponse refused = reverseRedemption(tenant, "M-1", UUID.randomUUID());

        assertThat(refused.status()).isEqualTo(404);
        assertThat(refused.code()).isEqualTo("REDEMPTION_NOT_FOUND");
    }

    @Test
    void aReversalAndASpendOfTheSamePointsCannotBothCount() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 5000));
        String body = returnBody(RETURNED_AT, BigDecimal.ONE, 5000);
        UUID redemptionId = UUID.fromString(
                postRedemption(tenant, "M-1", "K-1", 50, "RECEIPT-1").json().get("redemptionId").asString());
        assertThat(available(tenant, "M-1")).isZero();

        // The return claws the order back and the reversal restores the spend, one after the other on the member's row.
        assertThat(putReturn(tenant, "O-1", "R-1", body).status()).isEqualTo(201);
        reverseRedemption(tenant, "M-1", redemptionId);

        long lots = scalar(tenant,
                "select coalesce(sum(remaining), 0) from point_lots where expires_at > now()");
        long debt = scalar(tenant,
                "select coalesce(sum(debt), 0) from members where external_ref = 'M-1'");
        assertThat(available(tenant, "M-1")).as("the balance is always the lots minus the debt").isEqualTo(lots - debt);
        assertThat(scalar(tenant, "select count(*) from lot_allocations where points > 0"))
                .as("the spend explained its lots and the reversal explained nothing new")
                .isEqualTo(1);
    }

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }

    private static EarnVectors.Rules rules() {
        return new EarnVectors.Rules(PROGRAM, TIERS, List.of());
    }
}
