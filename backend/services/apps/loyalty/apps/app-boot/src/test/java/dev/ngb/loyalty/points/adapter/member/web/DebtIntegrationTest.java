package dev.ngb.loyalty.points.adapter.member.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.support.EarnVectors;
import dev.ngb.loyalty.support.PointsApiTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The debt a clawback can leave behind: what it does to the balance, why it refuses a spend, and how an earn repays it.
 *
 * <p>A return of points the member already spent has nothing to take from, so the shortfall becomes debt. The member's
 * balance carries it as a negative number and the spend endpoint refuses until an earn has repaid it, because a member
 * who owes points cannot spend the ones it does not have.
 */
class DebtIntegrationTest extends PointsApiTest {

    private static final String ORDERED_AT = "2026-03-02T10:00:00Z";
    private static final String RETURNED_AT = "2026-03-20T12:00:00Z";

    private static final EarnVectors.Program PROGRAM = new EarnVectors.Program("USD", 100L, "UTC", 12, 30, 12);
    private static final List<EarnVectors.Tier> TIERS = List.of(
            new EarnVectors.Tier("BRONZE", 0L, new BigDecimal("1.00")),
            new EarnVectors.Tier("SILVER", 100000L, new BigDecimal("1.50")));

    @Test
    void aClawbackBeyondTheLotsBecomesDebtAndRefusesTheNextSpend() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 1000));
        postRedemption(tenant, "M-1", "K-1", 10, "RECEIPT-1");
        assertThat(available(tenant, "M-1")).isZero();

        ApiResponse returned = putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, BigDecimal.ONE, 1000));

        assertThat(returned.status()).isEqualTo(201);
        assertThat(returned.json().get("returns").get(0).get("debtAdded").asLong())
                .as("the spend left no lot to take the points from")
                .isEqualTo(10);
        ApiResponse member = getMember(tenant, "M-1");
        assertThat(member.json().get("debt").asLong()).isEqualTo(10);
        assertThat(member.json().get("available").asLong()).isEqualTo(-10);

        ApiResponse refused = postRedemption(tenant, "M-1", "K-2", 1, "RECEIPT-2");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("MEMBER_IN_DEBT");
        assertThat(scalar(tenant, "select count(*) from redemptions where status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    void anEarnRepaysTheDebtBeforeItBecomesALot() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 1000));
        postRedemption(tenant, "M-1", "K-1", 10, "RECEIPT-1");
        putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, BigDecimal.ONE, 1000));

        // Four points against ten owed repay four of them and leave six.
        putOrder(tenant, "O-2", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 400));
        assertThat(debt(tenant, "M-1")).isEqualTo(6);
        assertThat(available(tenant, "M-1")).isEqualTo(-6);
        assertThat(postRedemption(tenant, "M-1", "K-2", 1, "RECEIPT-2").code()).isEqualTo("MEMBER_IN_DEBT");

        // Ten points repay the rest and leave four that the member can spend.
        ApiResponse earned = putOrder(tenant, "O-3", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 1000));
        assertThat(earned.json().get("debt").asLong()).as("the debt is repaid first").isZero();
        assertThat(debt(tenant, "M-1")).isZero();
        assertThat(available(tenant, "M-1")).isEqualTo(4);

        ApiResponse paid = postRedemption(tenant, "M-1", "K-3", 4, "RECEIPT-3");

        assertThat(paid.status()).isEqualTo(201);
        assertThat(paid.json().get("available").asLong()).isZero();
        assertThat(paid.json().get("debt").asLong()).isZero();
    }

    @Test
    void aReversalAfterTheDebtWasAddedPutsThePointsBackButTheDebtStays() {
        TenantId tenant = tenant();
        seedRules(tenant, rules());
        putOrder(tenant, "O-1", orderBody("M-1", "USD", ORDERED_AT, "ST-01", 1000));
        UUID redemptionId = UUID.fromString(
                postRedemption(tenant, "M-1", "K-1", 10, "RECEIPT-1").json().get("redemptionId").asString());
        putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, BigDecimal.ONE, 1000));
        assertThat(debt(tenant, "M-1")).isEqualTo(10);

        ApiResponse reversed = reverseRedemption(tenant, "M-1", redemptionId);

        assertThat(reversed.status()).isEqualTo(200);
        assertThat(reversed.json().get("debt").asLong()).as("a reversal restores lots and never repays a debt").isEqualTo(10);
        assertThat(reversed.json().get("available").asLong()).as("ten points back to the lot, ten still owed").isZero();
        assertThat(scalar(tenant, "select sum(remaining) from point_lots")).isEqualTo(10);
        assertThat(postRedemption(tenant, "M-1", "K-2", 1, "RECEIPT-2").code())
                .as("the member still owes points, so it still cannot spend")
                .isEqualTo("MEMBER_IN_DEBT");
    }

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }

    private static EarnVectors.Rules rules() {
        return new EarnVectors.Rules(PROGRAM, TIERS, List.of());
    }
}
