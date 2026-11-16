package dev.ngb.loyalty.points.adapter.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.EarnVectors;
import dev.ngb.loyalty.support.ParallelCalls;
import dev.ngb.loyalty.support.PointsApiTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * What a return does: it takes back the difference between what the order was granted and what it is worth now that part
 * of it came back, computed from the order's own snapshot and never from the rules of the day.
 *
 * <p>Each case states what a caller can observe: the status and the points of the return, the order read afterwards, the
 * member's balance and debt, and the rows the schema holds.
 */
class OrderReturnIntegrationTest extends PointsApiTest {

    private static final String ORDERED_AT = "2026-03-02T10:00:00Z";
    private static final String RETURNED_AT = "2026-03-20T12:00:00Z";

    private static final EarnVectors.Program PROGRAM = new EarnVectors.Program("USD", 100L, "UTC", 12, 30, 12);
    private static final List<EarnVectors.Tier> TIERS = List.of(
            new EarnVectors.Tier("BRONZE", 0L, new BigDecimal("1.00")),
            new EarnVectors.Tier("SILVER", 100000L, new BigDecimal("1.50")));
    private static final EarnVectors.Promotion GROCERY_DOUBLE = new EarnVectors.Promotion(
            "Grocery double", new BigDecimal("2.00"), null, null, "GROCERY", null, null, null, null,
            "2026-01-01T00:00:00", "2026-12-31T00:00:00");

    /** Four database connections are what the harness holds, so this is as wide as a burst can be without queueing. */
    private static final int WIDTH = 4;

    @Test
    void aReturnBeforeItsOrderIsUnknownAndSucceedsOnceTheOrderExists() {
        TenantId tenant = tenant();
        seedRules(tenant, rules(List.of()));
        String returned = returnBody(RETURNED_AT, new BigDecimal("2"), 1500);

        ApiResponse tooEarly = putReturn(tenant, "O-1", "R-1", returned);

        assertThat(tooEarly.status()).isEqualTo(422);
        assertThat(tooEarly.code()).isEqualTo("ORDER_UNKNOWN");
        assertThat(scalar(tenant, "select count(*) from orders")).isZero();
        assertThat(scalar(tenant, "select count(*) from order_returns")).as("a refused return is not remembered")
                .isZero();
        assertThat(scalar(tenant, "select count(*) from members")).isZero();

        putOrder(tenant, "O-1", fourUnits(4000));
        ApiResponse retried = putReturn(tenant, "O-1", "R-1", returned);

        assertThat(retried.status()).as("the caller retries the same body once the order is recorded").isEqualTo(201);
        assertThat(retried.json().get("returns").get(0).get("pointsClawedBack").asLong()).isEqualTo(15);
        assertThat(available(tenant, "M-1")).isEqualTo(25);
    }

    @Test
    void aPartialReturnClawsBackTheDropInTheOrdersWorth() {
        TenantId tenant = tenant();
        seedRules(tenant, rules(List.of()));
        putOrder(tenant, "O-1", fourUnits(4000));

        ApiResponse returned = putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, new BigDecimal("2"), 1500));

        assertThat(returned.status()).as("the return answered " + returned.body()).isEqualTo(201);
        assertThat(returned.json().get("returns").get(0).get("pointsClawedBack").asLong())
                .as("40 granted, 2500 left of the order at 100 a point is 25, so 15 comes back")
                .isEqualTo(15);
        ApiResponse order = getOrder(tenant, "O-1");
        assertThat(order.json().get("pointsGranted").asLong()).isEqualTo(25);
        assertThat(order.json().get("spendAmount").asLong()).isEqualTo(2500);
        assertThat(order.json().get("lines").get(0).get("returnedQuantity").decimalValue())
                .isEqualByComparingTo("2");
        assertThat(order.json().get("lines").get(0).get("returnedAmount").asLong()).isEqualTo(1500);
        assertThat(available(tenant, "M-1")).isEqualTo(25);
        assertThat(earnedTotal(tenant, "M-1")).as("the ledger keeps both entries: EARN 40 and CLAWBACK 15").isEqualTo(40);
        assertThat(scalar(tenant, "select sum(points) from ledger_entries where kind = 'CLAWBACK'")).isEqualTo(15);
    }

    @Test
    void twoConcurrentReturnsOfTheLastUnitCannotBothSucceed() {
        TenantId tenant = tenant();
        seedRules(tenant, rules(List.of()));
        putOrder(tenant, "O-1", oneUnit(1000));

        List<ApiResponse> answers = ParallelCalls.run(2, 2, index -> putReturn(
                tenant, "O-1", "R-" + index, returnBody(RETURNED_AT, BigDecimal.ONE, 1000)));

        assertThat(answers).filteredOn(answer -> answer.status() == 201).as("one return records the unit")
                .hasSize(1);
        assertThat(answers).filteredOn(answer -> answer.status() == 422).as("the other finds nothing left to return")
                .singleElement()
                .satisfies(answer -> assertThat(answer.code()).isEqualTo("RETURN_EXCEEDS_SALE"));
        assertThat(scalar(tenant, "select count(*) from order_returns")).isEqualTo(1);
        assertThat(scalar(tenant, "select sum(points) from ledger_entries where kind = 'CLAWBACK'")).isEqualTo(10);
        assertThat(available(tenant, "M-1")).isZero();
    }

    @Test
    void aReturnAfterThePromotionWasArchivedUsesTheStoredSnapshot() {
        TenantId tenant = tenant();
        seedRules(tenant, rules(List.of(GROCERY_DOUBLE)));
        ApiResponse order = putOrder(tenant, "O-1", groceryOrder(1000));
        assertThat(order.json().get("pointsGranted").asLong()).as("1000 at twice the base rate").isEqualTo(20);

        archivePromotion(tenant, GROCERY_DOUBLE.id());
        // The unit changes too, so a recompute that read today's rules would answer a different number twice over.
        seedProgram(tenant, new EarnVectors.Program("USD", 1000L, "UTC", 12, 30, 12));

        ApiResponse returned = putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, BigDecimal.ONE, 500));

        assertThat(returned.status()).as("the return answered " + returned.body()).isEqualTo(201);
        assertThat(returned.json().get("returns").get(0).get("pointsClawedBack").asLong())
                .as("500 of the order at the stored 2x and the stored 100 unit is 10, not the 0 today's rules compute")
                .isEqualTo(10);
        ApiResponse read = getOrder(tenant, "O-1");
        assertThat(read.json().get("earnSnapshot").get("unitAmount").asLong())
                .as("the snapshot is what the return read").isEqualTo(100);
        assertThat(read.json().get("earnSnapshot").get("lines").get(0).get("multiplier").decimalValue())
                .isEqualByComparingTo("2");
        assertThat(available(tenant, "M-1")).isEqualTo(10);

        ApiResponse afterTheChange = putOrder(tenant, "O-2", oneUnit(1000));
        assertThat(afterTheChange.json().get("pointsGranted").asLong())
                .as("a new order does earn at the rules of today, which is what makes the return above meaningful")
                .isEqualTo(1);
    }

    @Test
    void aReturnAfterThePointsWereSpentLeavesDebtThatTheNextEarnRepaysFirst() {
        TenantId tenant = tenant();
        seedRules(tenant, rules(List.of()));
        putOrder(tenant, "O-1", oneUnit(1000));
        spent(tenant);

        ApiResponse returned = putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, BigDecimal.ONE, 1000));

        assertThat(returned.status()).as("the return answered " + returned.body()).isEqualTo(201);
        assertThat(returned.json().get("returns").get(0).get("pointsClawedBack").asLong()).isEqualTo(10);
        assertThat(returned.json().get("returns").get(0).get("debtAdded").asLong())
                .as("no lot is left to take the points from").isEqualTo(10);
        assertThat(debt(tenant, "M-1")).isEqualTo(10);
        assertThat(available(tenant, "M-1")).isEqualTo(-10);

        ApiResponse next = putOrder(tenant, "O-2", oneUnit(2500));

        assertThat(next.json().get("pointsGranted").asLong()).isEqualTo(25);
        assertThat(next.json().get("debt").asLong()).as("the earn repaid the debt before creating a lot").isZero();
        assertThat(next.json().get("available").asLong()).as("25 earned less the 10 that closed the debt").isEqualTo(15);
        assertThat(debt(tenant, "M-1")).isZero();
        assertThat(scalar(tenant, "select sum(remaining) from point_lots")).as("only 15 became a lot").isEqualTo(15);
        assertThat(scalar(tenant, "select count(*) from point_lots")).as("a repaid debt creates no lot").isEqualTo(2);
    }

    @Test
    void aFullReturnZeroesTheOrder() {
        TenantId tenant = tenant();
        seedRules(tenant, rules(List.of()));
        putOrder(tenant, "O-1", fourUnits(4000));

        ApiResponse returned = putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, new BigDecimal("4"), 4000));

        assertThat(returned.status()).as("the return answered " + returned.body()).isEqualTo(201);
        assertThat(returned.json().get("returns").get(0).get("pointsClawedBack").asLong()).isEqualTo(40);
        ApiResponse order = getOrder(tenant, "O-1");
        assertThat(order.json().get("pointsGranted").asLong()).isZero();
        assertThat(order.json().get("spendAmount").asLong()).isZero();
        assertThat(order.json().get("lines").get(0).get("returnedQuantity").decimalValue()).isEqualByComparingTo("4");
        assertThat(order.json().get("returns")).hasSize(1);
        assertThat(available(tenant, "M-1")).isZero();
        assertThat(scalar(tenant, "select sum(remaining) from point_lots")).as("the whole lot came back").isZero();
        assertThat(debt(tenant, "M-1")).isZero();
    }

    @Test
    void aReplayedReturnChangesNothingAndADifferentBodyIsAConflict() {
        TenantId tenant = tenant();
        seedRules(tenant, rules(List.of()));
        putOrder(tenant, "O-1", fourUnits(4000));
        String body = returnBody(RETURNED_AT, new BigDecimal("2"), 1500);

        ApiResponse recorded = putReturn(tenant, "O-1", "R-1", body);
        ApiResponse replayed = putReturn(tenant, "O-1", "R-1", body);
        ApiResponse changed = putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, new BigDecimal("2"), 1400));

        assertThat(recorded.status()).isEqualTo(201);
        assertThat(replayed.status()).as("a replay answers the stored return").isEqualTo(200);
        assertThat(replayed.json().get("returns").get(0).get("pointsClawedBack").asLong()).isEqualTo(15);
        assertThat(changed.status()).isEqualTo(409);
        assertThat(changed.code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(available(tenant, "M-1")).as("neither the replay nor the refusal moved the balance").isEqualTo(25);
        assertThat(scalar(tenant, "select count(*) from order_returns")).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from order_return_lines")).isEqualTo(1);
        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'CLAWBACK'")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }

    private static EarnVectors.Rules rules(List<EarnVectors.Promotion> promotions) {
        return new EarnVectors.Rules(PROGRAM, TIERS, promotions);
    }

    /** One order of a single unit worth {@code amount} minor units. */
    private static String oneUnit(long amount) {
        return orderBody("M-1", "USD", ORDERED_AT, "ST-01", amount);
    }

    /** One order of four units worth {@code amount} minor units in all, so a return can be a part of it. */
    private static String fourUnits(long amount) {
        return """
                {"externalRef": "M-1", "currency": "USD", "occurredAt": "%s", "storeCode": "ST-01", "channel": "POS",
                 "lines": [{"lineNo": 1, "sku": "SKU-1", "category": "GROCERY", "quantity": 4, "amount": %d}]}
                """
                .formatted(ORDERED_AT, amount);
    }

    /** One order of two GROCERY units worth {@code amount} minor units, which the 2x promotion covers. */
    private static String groceryOrder(long amount) {
        return """
                {"externalRef": "M-1", "currency": "USD", "occurredAt": "%s", "storeCode": "ST-01", "channel": "POS",
                 "lines": [{"lineNo": 1, "sku": "SKU-1", "category": "GROCERY", "quantity": 2, "amount": %d}]}
                """
                .formatted(ORDERED_AT, amount);
    }

    /** Simulates a spend that emptied the member's lots, which no endpoint of this phase can do yet. */
    private void spent(TenantId tenant) {
        inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.execute("update point_lots set remaining = 0")));
        assertThat(available(tenant, "M-1")).as("the member has nothing left to claw back from").isZero();
    }

    /** Archives one seeded promotion through the endpoint a Tenant would use, so it leaves the rules of today. */
    private void archivePromotion(TenantId tenant, String name) {
        ApiResponse listed = call(tenant, get("/api/v1/promotions"), LoyaltyPermissions.PROGRAM_READ);
        for (JsonNode promotion : listed.json().get("data")) {
            if (!name.equals(promotion.get("name").asString())) {
                continue;
            }
            ApiResponse archived = call(
                    tenant,
                    patch("/api/v1/promotions/" + promotion.get("promotionId").asString())
                            .header("If-Match", "\"" + promotion.get("version").asLong() + "\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\": \"ARCHIVED\"}"),
                    LoyaltyPermissions.PROGRAM_MANAGE);
            assertThat(archived.status()).as("the promotion could be archived: " + archived.body()).isEqualTo(200);
            return;
        }
        throw new IllegalStateException("no promotion is named " + name);
    }
}
