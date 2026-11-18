package dev.ngb.loyalty.points.adapter.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.support.EarnVectors;
import dev.ngb.loyalty.support.ParallelCalls;
import dev.ngb.loyalty.support.PointsApiTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What recording an order does to the ledger: it grants once however often it is sent, a different body under the same
 * reference is refused rather than applied, and the promises of the plan hold under concurrent callers.
 *
 * <p>Every case states what a caller can observe — the status, the points the answer reports, the balance the member
 * endpoint computes and the rows the schema holds — and never how the use case arrives at them.
 */
class OrderEarnIntegrationTest extends PointsApiTest {

    private static final String OCCURRED_AT = "2026-03-02T10:00:00Z";
    private static final EarnVectors.Program PROGRAM = new EarnVectors.Program("USD", 100L, "UTC", 12, 30, 12);
    private static final List<EarnVectors.Tier> TIERS = List.of(
            new EarnVectors.Tier("BRONZE", 0L, new BigDecimal("1.00")),
            new EarnVectors.Tier("SILVER", 100000L, new BigDecimal("1.50")));
    private static final EarnVectors.Rules RULES = new EarnVectors.Rules(PROGRAM, TIERS, List.of());

    /**
     * A list whose second tier the burst below cannot reach: a member that crossed a threshold would earn at the higher
     * multiplier for the orders that came after it, and which of the fifty orders those are would depend on the order the
     * calls happened to run in.
     */
    private static final EarnVectors.Rules OUT_OF_REACH =
            new EarnVectors.Rules(PROGRAM, List.of(
                    new EarnVectors.Tier("BRONZE", 0L, new BigDecimal("1.00")),
                    new EarnVectors.Tier("SILVER", 200000L, new BigDecimal("1.50"))), List.of());

    /** Four database connections are what the harness holds, so this is as wide as a burst can be without queueing. */
    private static final int WIDTH = 4;

    @Test
    void aReplayedOrderGrantsOnce() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        String body = orderBody("M-1", "USD", OCCURRED_AT, "ST-01", 1000);

        ApiResponse recorded = putOrder(tenant, "O-1", body);
        ApiResponse replayed = putOrder(tenant, "O-1", body);

        assertThat(recorded.status()).isEqualTo(201);
        assertThat(recorded.json().get("pointsGranted").asLong()).isEqualTo(10);
        assertThat(replayed.status()).as("a replay answers the stored order instead of recording it").isEqualTo(200);
        assertThat(replayed.json().get("pointsGranted").asLong()).isEqualTo(10);
        assertThat(available(tenant, "M-1")).isEqualTo(10);
        assertThat(scalar(tenant, "select count(*) from orders")).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from order_lines")).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from order_earns")).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from ledger_entries")).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from point_lots")).isEqualTo(1);
    }

    @Test
    void theSameReferenceWithADifferentBodyIsAConflict() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        putOrder(tenant, "O-1", orderBody("M-1", "USD", OCCURRED_AT, "ST-01", 1000));

        ApiResponse changed = putOrder(tenant, "O-1", orderBody("M-1", "USD", OCCURRED_AT, "ST-01", 2000));

        assertThat(changed.status()).isEqualTo(409);
        assertThat(changed.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(available(tenant, "M-1")).as("the refused call granted nothing").isEqualTo(10);
        assertThat(scalar(tenant, "select count(*) from ledger_entries")).isEqualTo(1);
    }

    @Test
    void fiftyIdenticalPutsCreateOneOrderAndOneLot() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        String body = orderBody("M-1", "USD", OCCURRED_AT, "ST-01", 2500);

        List<ApiResponse> answers = ParallelCalls.run(50, WIDTH, index -> putOrder(tenant, "O-1", body));

        assertThat(answers).extracting(ApiResponse::status).as("exactly one call records, the rest replay")
                .containsOnly(201, 200);
        assertThat(answers).filteredOn(answer -> answer.status() == 201).hasSize(1);
        assertThat(answers).filteredOn(answer -> answer.status() == 200).hasSize(49);
        assertThat(answers).allSatisfy(answer -> assertThat(answer.json().get("pointsGranted").asLong()).isEqualTo(25));
        assertThat(scalar(tenant, "select count(*) from orders")).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from point_lots")).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from ledger_entries")).isEqualTo(1);
        assertThat(available(tenant, "M-1")).isEqualTo(25);
    }

    @Test
    void fiftyConcurrentOrdersLeaveTheLedgerEqualToTheSumOfWhatWasGranted() {
        TenantId tenant = tenant();
        seedRules(tenant, OUT_OF_REACH);

        List<ApiResponse> answers = ParallelCalls.run(50, WIDTH, index -> putOrder(
                tenant, "O-" + index, orderBody("M-1", "USD", OCCURRED_AT, "ST-0" + (index % 5), 100L * (index + 1))));

        assertThat(answers).extracting(ApiResponse::status).containsOnly(201);
        long granted = answers.stream().mapToLong(answer -> answer.json().get("pointsGranted").asLong()).sum();
        assertThat(granted).as("100 to 5000 minor units at a 100 unit is 1 to 50 points").isEqualTo(1275L);
        assertThat(available(tenant, "M-1")).isEqualTo(granted);
        assertThat(earnedTotal(tenant, "M-1")).as("every EARN entry is one of these orders").isEqualTo(granted);
        assertThat(scalar(tenant, "select sum(granted_points) from order_earns")).isEqualTo(granted);
        assertThat(scalar(tenant, "select sum(points) from ledger_entries where kind = 'EARN'")).isEqualTo(granted);
        assertThat(scalar(tenant, "select sum(remaining) from point_lots")).as("nothing was spent or returned")
                .isEqualTo(granted);
        assertThat(scalar(tenant, "select count(*) from orders")).isEqualTo(50);
        assertThat(debt(tenant, "M-1")).isZero();
    }

    @Test
    void anOrderInAnotherCurrencyGrantsNothing() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);

        ApiResponse refused = putOrder(tenant, "O-1", orderBody("M-1", "EUR", OCCURRED_AT, "ST-01", 1000));

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("CURRENCY_MISMATCH");
        assertThat(getOrder(tenant, "O-1").status()).as("no order was recorded").isEqualTo(404);
        assertThat(getMember(tenant, "M-1").status()).as("not even the member was enrolled").isEqualTo(404);
        assertThat(scalar(tenant, "select count(*) from orders")).isZero();
        assertThat(scalar(tenant, "select count(*) from ledger_entries")).isZero();
        assertThat(scalar(tenant, "select count(*) from point_lots")).isZero();
    }

    @Test
    void anOrderBeforeTheProgramExistsIsAConflict() {
        TenantId tenant = tenant();

        ApiResponse refused = putOrder(tenant, "O-1", orderBody("M-1", "USD", OCCURRED_AT, "ST-01", 1000));

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.code()).isEqualTo("PROGRAM_NOT_CONFIGURED");
        assertThat(scalar(tenant, "select count(*) from orders")).isZero();
        assertThat(scalar(tenant, "select count(*) from members")).as("a refused order enrols nobody").isZero();
    }

    @Test
    void anOrderForAnEnrolledReferenceUsesThatMember() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        String memberId = putMember(tenant, "M-1", "Ada").json().get("memberId").asString();

        ApiResponse order = putOrder(tenant, "O-1", orderBody("M-1", "USD", OCCURRED_AT, "ST-01", 1000));

        assertThat(order.status()).isEqualTo(201);
        assertThat(getMember(tenant, "M-1").json().get("memberId").asString())
                .as("the order joined the member the enrolment created")
                .isEqualTo(memberId);
        assertThat(scalar(tenant, "select count(*) from members")).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from orders where member_id = cast(? as uuid)", memberId))
                .isEqualTo(1);
    }

    @Test
    void theOrderAnswerCarriesWhatItGrantedAndWhatTheMemberIsWorth() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);

        ApiResponse order = putOrder(tenant, "O-1", orderBody("M-1", "USD", OCCURRED_AT, "ST-01", 2500));
        ApiResponse read = getOrder(tenant, "O-1");

        assertThat(order.json().get("pointsGranted").asLong()).isEqualTo(25);
        assertThat(order.json().get("spendAmount").asLong()).as("the eligible net amount").isEqualTo(2500);
        assertThat(order.json().get("available").asLong()).as("the balance after the grant").isEqualTo(25);
        assertThat(order.json().get("debt").asLong()).isZero();
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().get("pointsGranted").asLong()).isEqualTo(25);
        assertThat(read.json().get("spendAmount").asLong()).isEqualTo(2500);
        assertThat(read.json().get("available").asLong()).isEqualTo(25);
        assertThat(read.json().get("earnSnapshot").get("unitAmount").asLong())
                .as("the order keeps the unit it was judged with")
                .isEqualTo(100);
        assertThat(read.json().get("earnSnapshot").get("lines")).hasSize(1);
    }

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }
}
