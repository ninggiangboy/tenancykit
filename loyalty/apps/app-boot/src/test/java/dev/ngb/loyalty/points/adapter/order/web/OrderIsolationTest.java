package dev.ngb.loyalty.points.adapter.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.support.EarnVectors;
import dev.ngb.loyalty.support.PointsApiTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Two Tenants of one deployment never meet: the same reference names one member per Tenant, and another Tenant's order,
 * member, ledger or return is simply not there.
 *
 * <p>Both Tenants here use the same member reference, because that is the case row-level security has to separate; the
 * distinct references then show which answer each one gets for something it does not own.
 */
class OrderIsolationTest extends PointsApiTest {

    private static final String OCCURRED_AT = "2026-03-02T10:00:00Z";
    private static final EarnVectors.Program PROGRAM = new EarnVectors.Program("USD", 100L, "UTC", 12, 30, 12);
    private static final List<EarnVectors.Tier> TIERS = List.of(
            new EarnVectors.Tier("BRONZE", 0L, new BigDecimal("1.00")),
            new EarnVectors.Tier("SILVER", 100000L, new BigDecimal("1.50")));
    private static final EarnVectors.Rules RULES = new EarnVectors.Rules(PROGRAM, TIERS, List.of());

    @Test
    void theSameReferenceIsTwoMembersAndTwoBalances() {
        TenantId alpha = tenant();
        TenantId beta = tenant();
        seedRules(alpha, RULES);
        seedRules(beta, RULES);

        ApiResponse alphaOrder = putOrder(alpha, "O-1", orderBody("M-1", "USD", OCCURRED_AT, "ST-A", 1000));
        ApiResponse betaOrder = putOrder(beta, "O-1", orderBody("M-1", "USD", OCCURRED_AT, "ST-B", 2500));

        assertThat(alphaOrder.json().get("pointsGranted").asLong()).isEqualTo(10);
        assertThat(betaOrder.json().get("pointsGranted").asLong()).isEqualTo(25);
        assertThat(getMember(alpha, "M-1").json().get("memberId").asString())
                .as("one reference, one member per Tenant")
                .isNotEqualTo(getMember(beta, "M-1").json().get("memberId").asString());
        assertThat(available(alpha, "M-1")).isEqualTo(10);
        assertThat(available(beta, "M-1")).isEqualTo(25);
        assertThat(getOrder(alpha, "O-1").json().get("spendAmount").asLong()).isEqualTo(1000);
        assertThat(getOrder(beta, "O-1").json().get("spendAmount").asLong()).isEqualTo(2500);
        assertThat(scalar(alpha, "select count(*) from orders")).isEqualTo(1);
        assertThat(scalar(beta, "select count(*) from orders")).isEqualTo(1);
        assertThat(scalar(alpha, "select count(*) from members")).isEqualTo(1);
        assertThat(scalar(beta, "select count(*) from members")).isEqualTo(1);
        assertThat(getLedger(alpha, "M-1", 10).json().get("data")).hasSize(1);
        assertThat(getLedger(beta, "M-1", 10).json().get("data")).hasSize(1);
    }

    @Test
    void anotherTenantsOrderIsNotThere() {
        TenantId alpha = tenant();
        TenantId beta = tenant();
        seedRules(alpha, RULES);
        seedRules(beta, RULES);
        putOrder(alpha, "ALPHA-ORDER", orderBody("ALPHA-MEMBER", "USD", OCCURRED_AT, "ST-A", 4000));

        ApiResponse read = getOrder(beta, "ALPHA-ORDER");
        ApiResponse returned = putReturn(
                beta, "ALPHA-ORDER", "R-1", returnBody(OCCURRED_AT, BigDecimal.ONE, 1000));
        ApiResponse member = getMember(beta, "ALPHA-MEMBER");
        ApiResponse ledger = getLedger(beta, "ALPHA-MEMBER", 10);

        assertThat(read.status()).as("a read of an order of another Tenant").isEqualTo(404);
        assertThat(read.code()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(returned.status()).as("a return names an order this Tenant has not recorded").isEqualTo(422);
        assertThat(returned.code()).isEqualTo("ORDER_UNKNOWN");
        assertThat(member.status()).isEqualTo(404);
        assertThat(member.code()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(ledger.status()).isEqualTo(404);
        assertThat(scalar(beta, "select count(*) from orders")).as("the refund did not create an order").isZero();
        assertThat(scalar(beta, "select count(*) from members")).as("nor a member").isZero();
        assertThat(scalar(beta, "select count(*) from order_returns")).isZero();
        assertThat(scalar(alpha, "select count(*) from order_returns"))
                .as("the owner's order took no return").isZero();
    }

    @Test
    void aReturnInOneTenantLeavesTheOtherUntouched() {
        TenantId alpha = tenant();
        TenantId beta = tenant();
        seedRules(alpha, RULES);
        seedRules(beta, RULES);
        putOrder(alpha, "O-1", orderBody("M-1", "USD", OCCURRED_AT, "ST-A", 4000));
        putOrder(beta, "O-1", orderBody("M-1", "USD", OCCURRED_AT, "ST-B", 4000));

        ApiResponse returned = putReturn(alpha, "O-1", "R-1", returnBody(OCCURRED_AT, BigDecimal.ONE, 1000));

        assertThat(returned.status()).isEqualTo(201);
        assertThat(returned.json().get("returns").get(0).get("pointsClawedBack").asLong()).isEqualTo(10);
        assertThat(available(alpha, "M-1")).isEqualTo(30);
        assertThat(available(beta, "M-1")).as("the other Tenant's member still holds its own 40").isEqualTo(40);
        assertThat(getOrder(beta, "O-1").json().get("pointsGranted").asLong()).isEqualTo(40);
        assertThat(getOrder(beta, "O-1").json().get("returns")).isEmpty();
        assertThat(scalar(alpha, "select count(*) from order_returns")).isEqualTo(1);
        assertThat(scalar(beta, "select count(*) from order_returns")).isZero();
        assertThat(getLedger(beta, "M-1", 10).json().get("data")).hasSize(1);
        assertThat(getLedger(alpha, "M-1", 10).json().get("data")).hasSize(2);
    }

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }
}
