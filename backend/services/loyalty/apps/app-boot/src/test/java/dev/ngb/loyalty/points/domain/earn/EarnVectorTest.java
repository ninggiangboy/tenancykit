package dev.ngb.loyalty.points.domain.earn;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ngb.core.id.TenantId;
import dev.ngb.loyalty.points.domain.order.OrderLine;
import dev.ngb.loyalty.support.EarnVectors;
import dev.ngb.loyalty.support.PointsApiTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Every committed earn vector, run twice: once against {@link EarnCalculator} alone, and once through the real order and
 * return endpoints against PostgreSQL.
 *
 * <p>Both runs compare what the vector says for each step: the status the endpoint answers, the points the call computed
 * and the signed change to the member's available balance. Running the same file both ways is what keeps the engine and
 * the endpoints from answering two different numbers, and it is why a vector may replace the rules between an order and
 * its return: the endpoint run changes the stored rules and the answer must not move.
 */
class EarnVectorTest extends PointsApiTest {

    private final EarnCalculator calculator = new EarnCalculator();

    static Stream<EarnVectors.Vector> vectors() {
        return EarnVectors.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void everyVectorEarnsWhatItSaysThroughTheCalculator(EarnVectors.Vector vector) {
        EarnVectors.Rules rules = vector.rules();
        Map<String, EarnSnapshot> snapshots = new HashMap<>();
        Map<String, List<OrderLine>> lines = new HashMap<>();
        Map<String, Long> worth = new HashMap<>();
        int stepNo = 0;
        for (EarnVectors.Step step : vector.steps()) {
            stepNo++;
            if (step.rules() != null) {
                rules = step.rules();
            }
            String where = vector.file() + " step " + stepNo;
            if (step.isOrder()) {
                List<OrderLine> sold = lines(step.body());
                EarnOutcome outcome = calculator.evaluate(new EarnContext(
                        rules.program().currencyUnit(),
                        rules.program().currency(),
                        EarnVectors.tierCode(tierOf(vector), rules.tiers()),
                        EarnVectors.tierMultiplier(tierOf(vector), rules.tiers()),
                        Instant.parse(step.body().get("occurredAt").asString()),
                        text(step.body(), "storeCode"),
                        text(step.body(), "channel"),
                        sold,
                        rules.promotions().stream()
                                .map(promotion -> EarnVectors.view(promotion, vector.zone()))
                                .toList()));
                assertThat(outcome.points()).as(where + ": " + vector.name()).isEqualTo(step.expectedPoints());
                snapshots.put(step.orderRef(), outcome.snapshot());
                lines.put(step.orderRef(), sold);
                worth.put(step.orderRef(), outcome.points());
            } else if (snapshots.containsKey(step.orderRef())) {
                List<OrderLine> after = returned(lines.get(step.orderRef()), step.body());
                long worthNow = calculator
                        .recompute(snapshots.get(step.orderRef()), after)
                        .points();
                assertThat(worth.get(step.orderRef()) - worthNow)
                        .as(where + ": the clawback of the return")
                        .isEqualTo(step.expectedPoints());
                lines.put(step.orderRef(), after);
                worth.put(step.orderRef(), worthNow);
            } else {
                assertThat(step.expectedPoints())
                        .as(where + ": a return for an order loyalty has not seen computes nothing")
                        .isNull();
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void everyVectorAnswersTheSameThroughTheOrderAndReturnEndpoints(EarnVectors.Vector vector) {
        TenantId tenant = new TenantId(UUID.randomUUID());
        seedRules(tenant, vector.rules());
        if (vector.member() != null) {
            seedMember(tenant, vector.member().externalRef(), vector.member().tier(), vector.member().debtOrDefault());
        }
        String memberRef = memberRef(vector);
        int stepNo = 0;
        for (EarnVectors.Step step : vector.steps()) {
            stepNo++;
            if (step.rules() != null) {
                seedRules(tenant, step.rules());
            }
            String where = vector.file() + " step " + stepNo;
            long before = available(tenant, memberRef);
            ApiResponse answer = step.isOrder()
                    ? putOrder(tenant, step.orderRef(), step.body().toString())
                    : putReturn(tenant, step.orderRef(), step.returnRef(), step.body().toString());

            assertThat(answer.status()).as(where + ": " + vector.name() + " answered " + answer.body())
                    .isEqualTo(step.expectedStatus());
            if (step.expectedError() != null) {
                assertThat(answer.code()).as(where).isEqualTo(step.expectedError());
            }
            if (step.expectedPoints() != null) {
                assertThat(points(answer, step)).as(where + ": the points of the call")
                        .isEqualTo(step.expectedPoints());
            }
            long after = available(tenant, memberRef);
            if (step.expectedLedgerDelta() == null) {
                assertThat(after).as(where + ": a refused call writes nothing").isEqualTo(before);
            } else {
                assertThat(after - before).as(where + ": the signed change of the balance")
                        .isEqualTo(step.expectedLedgerDelta());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static long points(ApiResponse answer, EarnVectors.Step step) {
        JsonNode body = answer.json();
        if (step.isOrder()) {
            return body.get("pointsGranted").asLong();
        }
        JsonNode returns = body.get("returns");
        return returns.get(returns.size() - 1).get("pointsClawedBack").asLong();
    }

    private static String tierOf(EarnVectors.Vector vector) {
        return vector.member() == null ? null : vector.member().tier();
    }

    private static String memberRef(EarnVectors.Vector vector) {
        if (vector.member() != null) {
            return vector.member().externalRef();
        }
        return vector.steps().stream()
                .filter(EarnVectors.Step::isOrder)
                .map(step -> step.body().get("externalRef").asString())
                .findFirst()
                .orElseThrow();
    }

    private static List<OrderLine> lines(JsonNode body) {
        List<OrderLine> lines = new ArrayList<>();
        for (JsonNode line : body.get("lines")) {
            JsonNode eligible = line.get("earnEligible");
            lines.add(OrderLine.sold(
                    line.get("lineNo").asInt(),
                    text(line, "sku"),
                    text(line, "category"),
                    line.get("quantity").decimalValue(),
                    line.get("amount").asLong(),
                    eligible == null || eligible.asBoolean()));
        }
        return List.copyOf(lines);
    }

    private static List<OrderLine> returned(List<OrderLine> sold, JsonNode body) {
        Map<Integer, OrderLine> after = new HashMap<>();
        for (OrderLine line : sold) {
            after.put(line.lineNo(), line);
        }
        for (JsonNode returned : body.get("lines")) {
            OrderLine line = after.get(returned.get("lineNo").asInt());
            after.put(line.lineNo(), line.returned(
                    returned.get("quantity").decimalValue(), returned.get("amount").asLong()));
        }
        return List.copyOf(after.values());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
