package dev.ngb.loyalty.points.application.earn.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.webhook.WebhookSigner;
import dev.ngb.loyalty.support.EarnVectors;
import dev.ngb.loyalty.support.PointsApiTest;
import dev.ngb.loyalty.support.RepositoryPaths;
import dev.ngb.loyalty.tiers.application.evaluation.service.TierEvaluationService;
import dev.ngb.loyalty.tiers.application.evaluation.service.TierEvents;
import dev.ngb.loyalty.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.loyalty.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The facts loyalty announces: a member was enrolled, an order granted points and a return took points back.
 *
 * <p>Each case drives the real endpoints, lets the outbox relay hand the rows to the webhook consumer, sends the
 * deliveries to a receiver on a loopback port and reads what arrived: once per subscribed endpoint, signed with that
 * endpoint's own secret, and valid against the schema in {@code contracts/events/loyalty}. A replay, a change of zero
 * points and a transaction that rolled back announce nothing, because nothing happened.
 */
class PointsWebhookIntegrationTest extends PointsApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Path CONTRACTS = RepositoryPaths.repositoryRoot().resolve("contracts/events/loyalty");

    private static final String OCCURRED_AT = "2026-03-02T10:00:00Z";
    private static final String RETURNED_AT = "2026-03-20T12:00:00Z";

    private static final EarnVectors.Program PROGRAM = new EarnVectors.Program("USD", 100L, "UTC", 12, 30, 12);
    private static final List<EarnVectors.Tier> TIERS = List.of(
            new EarnVectors.Tier("BRONZE", 0L, new BigDecimal("1.00")),
            new EarnVectors.Tier("SILVER", 100000L, new BigDecimal("1.50")));
    private static final EarnVectors.Rules RULES = new EarnVectors.Rules(PROGRAM, TIERS, List.of());

    @Autowired CreateEndpointUseCase createEndpoint;
    @Autowired DeliverWebhooksUseCase deliver;
    @Autowired OutboxRelay relay;
    @Autowired TierEvaluationService tierEvaluation;

    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> secrets = new HashMap<>();

    private HttpServer receiver;

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String path : List.of("/hook", "/second")) {
            receiver.createContext(path, exchange -> record(path, exchange));
        }
        receiver.start();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
    }

    @Test
    void anExplicitEnrolmentReachesEachSubscribedEndpointOnce() {
        TenantId tenant = tenant();
        subscribe(tenant, "/hook", PointsEvents.MEMBER_ENROLLED);
        subscribe(tenant, "/second", PointsEvents.MEMBER_ENROLLED);

        ApiResponse enrolled = putMember(tenant, "M-1", "Ada");
        assertThat(enrolled.status()).isEqualTo(201);
        deliverAll(tenant);

        assertThat(received).as("one delivery per subscribed endpoint").hasSize(2);
        assertThat(received).extracting(Received::path).containsExactlyInAnyOrder("/hook", "/second");
        for (Received delivery : received) {
            assertThat(delivery.eventType()).isEqualTo(PointsEvents.MEMBER_ENROLLED);
            assertThat(delivery.eventId()).isEqualTo(received.getFirst().eventId());
            assertThat(delivery.signature()).as("the delivery is signed").isNotBlank();
            JsonNode data = delivery.data();
            assertThat(data.get("memberId").asString()).isEqualTo(enrolled.json().get("memberId").asString());
            assertThat(data.get("externalRef").asString()).isEqualTo("M-1");
            assertThat(data.get("via").asString()).as("an explicit enrolment says so").isEqualTo("API");
            assertConformsToContract(delivery.eventType(), data);
            assertSignedByItsEndpoint(delivery);
        }

        // A second enrolment of the same reference creates nothing, so it announces nothing either.
        assertThat(putMember(tenant, "M-1", "Ada").status()).isEqualTo(200);
        deliverAll(tenant);
        assertThat(received).hasSize(2);
        assertThat(outboxRows(tenant)).isEqualTo(1);
    }

    @Test
    void theFirstOrderAnnouncesTheEnrolmentAndTheEarn() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        subscribe(tenant, "/hook", PointsEvents.MEMBER_ENROLLED, PointsEvents.POINTS_EARNED);

        ApiResponse order = putOrder(tenant, "O-1", orderBody("M-2", "USD", OCCURRED_AT, "ST-01", 2500));
        assertThat(order.status()).isEqualTo(201);
        deliverAll(tenant);

        assertThat(received).hasSize(2);
        Received enrolled = only(PointsEvents.MEMBER_ENROLLED);
        assertThat(enrolled.data().get("via").asString()).as("the first order enrolled the member").isEqualTo("ORDER");
        assertThat(enrolled.data().get("memberId").asString())
                .as("the announcement names the member the order joined")
                .isEqualTo(getMember(tenant, "M-2").json().get("memberId").asString());
        Received earned = only(PointsEvents.POINTS_EARNED);
        assertThat(earned.data().get("points").asLong()).isEqualTo(25);
        assertThat(earned.data().get("debtRepaid").asLong()).isZero();
        assertThat(earned.data().get("available").asLong()).isEqualTo(25);
        assertThat(earned.data().get("debt").asLong()).isZero();
        assertThat(earned.data().get("orderRef").asString()).isEqualTo("O-1");
        assertThat(earned.data().get("externalRef").asString()).isEqualTo("M-2");
        for (Received delivery : received) {
            assertConformsToContract(delivery.eventType(), delivery.data());
            assertSignedByItsEndpoint(delivery);
        }
        assertThat(outboxRows(tenant)).as("one row for the enrolment and one for the earn").isEqualTo(2);
    }

    @Test
    void pointsEarnedCarriesTheDebtItRepaid() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        seedMember(tenant, "M-3", "BRONZE", 10L);
        subscribe(tenant, "/hook", PointsEvents.POINTS_EARNED);

        putOrder(tenant, "O-1", orderBody("M-3", "USD", OCCURRED_AT, "ST-01", 2500));
        deliverAll(tenant);

        Received earned = only(PointsEvents.POINTS_EARNED);
        assertThat(earned.data().get("points").asLong()).isEqualTo(25);
        assertThat(earned.data().get("debtRepaid").asLong()).as("10 of the 25 closed the debt").isEqualTo(10);
        assertThat(earned.data().get("debt").asLong()).isZero();
        assertThat(earned.data().get("available").asLong()).isEqualTo(15);
        assertConformsToContract(earned.eventType(), earned.data());
        assertSignedByItsEndpoint(earned);
        assertThat(outboxRows(tenant)).as("a seeded member was never enrolled, so nothing announced that").isEqualTo(1);
    }

    @Test
    void aReturnAnnouncesTheClawbackWithTheDebtItAdded() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        subscribe(tenant, "/hook", PointsEvents.POINTS_CLAWED_BACK);
        putOrder(tenant, "O-1", orderBody("M-4", "USD", OCCURRED_AT, "ST-01", 1000));
        // The points are spent before the return, which is what turns the clawback into debt.
        inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.execute("update point_lots set remaining = 0")));

        ApiResponse returned = putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, BigDecimal.ONE, 1000));
        assertThat(returned.status()).isEqualTo(201);
        deliverAll(tenant);

        Received clawedBack = only(PointsEvents.POINTS_CLAWED_BACK);
        assertThat(clawedBack.data().get("points").asLong()).isEqualTo(10);
        assertThat(clawedBack.data().get("debtAdded").asLong()).as("no lot was left to take it from").isEqualTo(10);
        assertThat(clawedBack.data().get("debt").asLong()).isEqualTo(10);
        assertThat(clawedBack.data().get("available").asLong()).isEqualTo(-10);
        assertThat(clawedBack.data().get("orderRef").asString()).isEqualTo("O-1");
        assertThat(clawedBack.data().get("returnRef").asString()).isEqualTo("R-1");
        assertConformsToContract(clawedBack.eventType(), clawedBack.data());
        assertSignedByItsEndpoint(clawedBack);
        assertThat(outboxRows(tenant)).as("the enrolment, the earn and the clawback").isEqualTo(3);
    }

    @Test
    void aReplayOrAChangeOfZeroPointsAnnouncesNothing() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        subscribe(tenant, "/hook",
                PointsEvents.MEMBER_ENROLLED, PointsEvents.POINTS_EARNED, PointsEvents.POINTS_CLAWED_BACK);
        String body = """
                {"externalRef": "M-5", "currency": "USD", "occurredAt": "%s", "storeCode": "ST-01", "channel": "POS",
                 "lines": [{"lineNo": 1, "sku": "SKU-1", "category": "GROCERY", "quantity": 1, "amount": 1000,
                            "earnEligible": false}]}
                """
                .formatted(OCCURRED_AT);
        String returned = returnBody(RETURNED_AT, new BigDecimal("0.5"), 500);

        assertThat(putOrder(tenant, "O-1", body).json().get("pointsGranted").asLong()).as("an ineligible line earns")
                .isZero();
        assertThat(putReturn(tenant, "O-1", "R-1", returned).json().get("returns").get(0).get("pointsClawedBack")
                .asLong()).as("a return of an order worth nothing takes nothing").isZero();
        assertThat(putOrder(tenant, "O-1", body).status()).as("the replay records nothing").isEqualTo(200);
        assertThat(putReturn(tenant, "O-1", "R-1", returned).status()).as("the returned replay too").isEqualTo(200);
        deliverAll(tenant);

        assertThat(received).as("only the enrolment happened").hasSize(1);
        assertThat(only(PointsEvents.MEMBER_ENROLLED).data().get("via").asString()).isEqualTo("ORDER");
        assertThat(outboxRows(tenant)).isEqualTo(1);
    }

    @Test
    void aRolledBackRequestAnnouncesNothing() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        subscribe(tenant, "/hook", PointsEvents.MEMBER_ENROLLED, PointsEvents.POINTS_EARNED);
        String body = orderBody("M-6", "USD", OCCURRED_AT, "ST-01", 1000);

        Runnable orderInAFailingTransaction = () -> {
            assertThat(putOrder(tenant, "O-1", body).status()).isEqualTo(201);
            throw new IllegalStateException("the request could not be committed");
        };
        // The request runs inside a unit of work the test then fails, which is how a request that cannot commit unwinds.
        Runnable requestThatFails = () -> unitOfWork.transactional(orderInAFailingTransaction);

        assertThatThrownBy(() -> inTenant(tenant, requestThatFails)).isInstanceOf(IllegalStateException.class);

        assertThat(outboxRows(tenant)).as("the events went back with the transaction").isZero();
        assertThat(getMember(tenant, "M-6").status()).as("the enrolment rolled back with it").isEqualTo(404);
        assertThat(getOrder(tenant, "O-1").status()).isEqualTo(404);
        deliverAll(tenant);
        assertThat(received).isEmpty();

        // The same call outside a rollback does announce both facts, so the silence above is the rollback's.
        assertThat(putOrder(tenant, "O-1", body).status()).isEqualTo(201);
        deliverAll(tenant);
        assertThat(received).extracting(Received::eventType)
                .containsExactlyInAnyOrder(PointsEvents.MEMBER_ENROLLED, PointsEvents.POINTS_EARNED);
    }

    @Test
    void aSpendAnnouncesTheRedemptionToEachSubscribedEndpointOnce() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        subscribe(tenant, "/hook", PointsEvents.POINTS_REDEEMED);
        subscribe(tenant, "/second", PointsEvents.POINTS_REDEEMED);
        String memberId = putMember(tenant, "M-7", "Ada").json().get("memberId").asString();
        putOrder(tenant, "O-1", orderBody("M-7", "USD", OCCURRED_AT, "ST-01", 5000));

        ApiResponse spent = postRedemption(tenant, "M-7", "K-1", 10, "RECEIPT-1");
        assertThat(spent.status()).as("the spend answered " + spent.body()).isEqualTo(201);
        deliverAll(tenant);

        assertThat(received).as("one delivery per subscribed endpoint").hasSize(2);
        for (Received delivery : received) {
            assertThat(delivery.eventType()).isEqualTo(PointsEvents.POINTS_REDEEMED);
            assertThat(delivery.eventId()).isEqualTo(received.getFirst().eventId());
            JsonNode data = delivery.data();
            assertThat(data.get("memberId").asString()).isEqualTo(memberId);
            assertThat(data.get("externalRef").asString()).isEqualTo("M-7");
            assertThat(data.get("redemptionId").asString()).isEqualTo(spent.json().get("redemptionId").asString());
            assertThat(data.get("points").asLong()).isEqualTo(10);
            assertThat(data.get("reference").asString()).isEqualTo("RECEIPT-1");
            assertThat(data.get("available").asLong()).isEqualTo(40);
            assertThat(data.get("debt").asLong()).isZero();
            assertConformsToContract(delivery.eventType(), data);
            assertSignedByItsEndpoint(delivery);
        }
        assertThat(outboxRows(tenant)).as("the enrolment, the earn and the spend").isEqualTo(3);
    }

    @Test
    void aReversalAnnouncesTheRedemptionItRestored() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        subscribe(tenant, "/hook", PointsEvents.POINTS_REDEMPTION_REVERSED);
        putMember(tenant, "M-8", null);
        putOrder(tenant, "O-1", orderBody("M-8", "USD", OCCURRED_AT, "ST-01", 5000));
        String redemptionId = postRedemption(tenant, "M-8", "K-1", 10, "RECEIPT-1")
                .json()
                .get("redemptionId")
                .asString();

        assertThat(reverseRedemption(tenant, "M-8", java.util.UUID.fromString(redemptionId)).status())
                .isEqualTo(200);
        deliverAll(tenant);

        Received reversed = only(PointsEvents.POINTS_REDEMPTION_REVERSED);
        assertThat(reversed.data().get("redemptionId").asString()).isEqualTo(redemptionId);
        assertThat(reversed.data().get("points").asLong()).isEqualTo(10);
        assertThat(reversed.data().get("reference").asString()).isEqualTo("RECEIPT-1");
        assertThat(reversed.data().get("available").asLong()).as("the points came back to the member").isEqualTo(50);
        assertThat(reversed.data().get("debt").asLong()).isZero();
        assertConformsToContract(reversed.eventType(), reversed.data());
        assertSignedByItsEndpoint(reversed);
        assertThat(outboxRows(tenant)).as("the enrolment, the earn, the spend and the reversal").isEqualTo(4);
    }

    @Test
    void aReplayedOrRolledBackSpendAndReversalAnnounceNothing() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        subscribe(tenant, "/hook", PointsEvents.POINTS_REDEEMED, PointsEvents.POINTS_REDEMPTION_REVERSED);
        putMember(tenant, "M-9", null);
        putOrder(tenant, "O-1", orderBody("M-9", "USD", OCCURRED_AT, "ST-01", 5000));
        String redemptionId = postRedemption(tenant, "M-9", "K-1", 10, "RECEIPT-1")
                .json()
                .get("redemptionId")
                .asString();

        assertThat(postRedemption(tenant, "M-9", "K-1", 10, "RECEIPT-1").status()).as("the replay").isEqualTo(200);
        assertThat(reverseRedemption(tenant, "M-9", java.util.UUID.fromString(redemptionId)).status()).isEqualTo(200);
        assertThat(reverseRedemption(tenant, "M-9", java.util.UUID.fromString(redemptionId)).status())
                .as("the reversed reversal")
                .isEqualTo(200);
        deliverAll(tenant);

        assertThat(received).as("one spend and one reversal happened").hasSize(2);
        assertThat(received).extracting(Received::eventType)
                .containsExactlyInAnyOrder(PointsEvents.POINTS_REDEEMED, PointsEvents.POINTS_REDEMPTION_REVERSED);
        assertThat(outboxRows(tenant)).isEqualTo(4);

        // A request whose transaction fails leaves no redemption and no announcement behind.
        Runnable spendInAFailingTransaction = () -> {
            assertThat(postRedemption(tenant, "M-9", "K-2", 5, "RECEIPT-2").status()).isEqualTo(201);
            throw new IllegalStateException("the request could not be committed");
        };
        assertThatThrownBy(() -> inTenant(tenant, () -> unitOfWork.transactional(spendInAFailingTransaction)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(outboxRows(tenant)).as("the event went back with the transaction").isEqualTo(4);
        assertThat(scalar(tenant, "select count(*) from redemptions")).as("the spend rolled back with it").isEqualTo(1);
        deliverAll(tenant);
        assertThat(received).hasSize(2);
    }

    @Test
    void anOrderThatCrossesAThresholdAnnouncesTheRise() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        subscribe(tenant, "/hook", TierEvents.TIER_CHANGED);

        ApiResponse order = putOrder(tenant, "O-1", orderBody("M-10", "USD", OCCURRED_AT, "ST-01", 100_000));
        assertThat(order.status()).isEqualTo(201);
        deliverAll(tenant);

        Received changed = only(TierEvents.TIER_CHANGED);
        JsonNode data = changed.data();
        assertThat(data.get("memberId").asString())
                .isEqualTo(getMember(tenant, "M-10").json().get("memberId").asString());
        assertThat(data.get("externalRef").asString()).isEqualTo("M-10");
        assertThat(data.get("fromTier").asString()).isEqualTo("BRONZE");
        assertThat(data.get("toTier").asString()).isEqualTo("SILVER");
        assertThat(data.get("direction").asString()).isEqualTo("UP");
        assertThat(data.get("qualifyingSpend").asLong()).isEqualTo(100_000);
        assertConformsToContract(changed.eventType(), data);
        assertSignedByItsEndpoint(changed);
    }

    @Test
    void theEvaluationAnnouncesTheFallItDecides() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        subscribe(tenant, "/hook", TierEvents.TIER_CHANGED);
        putOrder(tenant, "O-1", orderBody("M-11", "USD", OCCURRED_AT, "ST-01", 100_000));
        assertThat(putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, BigDecimal.ONE, 100_000)).status())
                .isEqualTo(201);
        deliverAll(tenant);
        assertThat(only(TierEvents.TIER_CHANGED).data().get("direction").asString())
                .as("a return does not announce a fall")
                .isEqualTo("UP");

        inTenant(tenant, () -> tierEvaluation.reviewDue());
        deliverAll(tenant);

        List<Received> falls = received.stream()
                .filter(delivery -> TierEvents.TIER_CHANGED.equals(delivery.eventType()))
                .filter(delivery -> "DOWN".equals(delivery.data().get("direction").asString()))
                .toList();
        assertThat(falls).as("the pass announces the fall").hasSize(1);
        JsonNode data = falls.getFirst().data();
        assertThat(data.get("fromTier").asString()).isEqualTo("SILVER");
        assertThat(data.get("toTier").asString()).isEqualTo("BRONZE");
        assertThat(data.get("qualifyingSpend").asLong()).as("the return took the whole spend back").isZero();
        assertConformsToContract(TierEvents.TIER_CHANGED, data);
        assertSignedByItsEndpoint(falls.getFirst());
    }

    @Test
    void aTierThatDoesNotChangeAnnouncesNothing() {
        TenantId tenant = tenant();
        seedRules(tenant, RULES);
        subscribe(tenant, "/hook", TierEvents.TIER_CHANGED);

        putOrder(tenant, "O-1", orderBody("M-12", "USD", OCCURRED_AT, "ST-01", 2500));
        putReturn(tenant, "O-1", "R-1", returnBody(RETURNED_AT, BigDecimal.ONE, 1000));
        inTenant(tenant, () -> tierEvaluation.reviewDue());
        deliverAll(tenant);

        assertThat(received).as("the member stayed in the tier it held").isEmpty();
        assertThat(scalar(tenant, "select count(*) from outbox where type = 'tier.changed'")).isZero();
    }

    // ------------------------------------------------------------------ receiver

    /** One delivery as the receiver saw it: where it arrived, what the headers said and the envelope it carried. */
    private record Received(
            String path, String eventId, String eventType, String signature, String timestamp, String body) {

        JsonNode data() {
            return JSON.readTree(body).get("data");
        }
    }

    private void record(String path, HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        received.add(new Received(
                path,
                exchange.getRequestHeaders().getFirst("Loyalty-Event-Id"),
                exchange.getRequestHeaders().getFirst("Loyalty-Event-Type"),
                exchange.getRequestHeaders().getFirst("Loyalty-Signature"),
                exchange.getRequestHeaders().getFirst("Loyalty-Timestamp"),
                new String(body, StandardCharsets.UTF_8)));
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private void deliverAll(TenantId tenant) {
        inTenant(tenant, () -> relay.relayDue());
        inTenant(tenant, () -> deliver.runOnce());
    }

    private void subscribe(TenantId tenant, String path, String... eventTypes) {
        CreateEndpointUseCase.Result endpoint = inTenant(tenant, () -> createEndpoint.execute(
                new CreateEndpointUseCase.Command(url(path), null, List.of(eventTypes))));
        secrets.put(path, endpoint.signingSecret());
    }

    private String url(String path) {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + path;
    }

    private Received only(String eventType) {
        List<Received> matching = received.stream().filter(delivery -> eventType.equals(delivery.eventType())).toList();
        assertThat(matching).as("exactly one " + eventType + " delivery").hasSize(1);
        return matching.getFirst();
    }

    private long outboxRows(TenantId tenant) {
        return scalar(tenant, "select count(*) from outbox");
    }

    private void assertSignedByItsEndpoint(Received delivery) {
        String secret = secrets.get(delivery.path());
        assertThat(secret).as("the endpoint " + delivery.path() + " was subscribed").isNotNull();
        assertThat(new WebhookSigner()
                        .sign(secret, Long.parseLong(delivery.timestamp()),
                                delivery.body().getBytes(StandardCharsets.UTF_8)))
                .as("the delivery verifies with the endpoint's own signing secret")
                .isEqualTo(delivery.signature());
        assertThat(delivery.eventId()).as("the delivery names its event").isNotNull();
        UUID.fromString(delivery.eventId());
        assertThat(JSON.readTree(delivery.body()).get("type").asString()).isEqualTo(delivery.eventType());
    }

    // ------------------------------------------------------------------ contract

    /**
     * Reads the committed schema of the event and checks the payload against it: every required field present, no field
     * the contract does not name, and each declared type, format, bound and enum respected. It is the file in
     * {@code contracts/events/loyalty} that decides, so a payload that drifts from the contract fails here.
     */
    private static void assertConformsToContract(String eventType, JsonNode data) {
        JsonNode schema = read(eventType);
        assertThat(schema.get("additionalProperties").asBoolean()).as("the contract closes the payload").isFalse();
        for (JsonNode required : schema.get("required")) {
            assertThat(data.has(required.asString()))
                    .as(eventType + " carries the required " + required.asString())
                    .isTrue();
        }
        JsonNode properties = schema.get("properties");
        int present = 0;
        for (String name : properties.propertyNames()) {
            JsonNode value = data.get(name);
            if (value == null) {
                continue;
            }
            present++;
            assertConformsToField(eventType, name, value, properties.get(name));
        }
        assertThat(data.size()).as(eventType + " carries only the fields its contract names").isEqualTo(present);
    }

    private static JsonNode read(String eventType) {
        try {
            return JSON.readTree(Files.readString(
                    CONTRACTS.resolve(eventType + ".schema.json"), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("could not read the schema of " + eventType, e);
        }
    }

    private static void assertConformsToField(String eventType, String name, JsonNode value, JsonNode spec) {
        String where = eventType + "." + name;
        String declared = spec.get("type").asString();
        switch (declared) {
            case "string" -> assertString(where, value, spec);
            case "integer" -> assertInteger(where, value, spec);
            default -> throw new IllegalStateException(where + " has a type this test does not read: " + declared);
        }
    }

    private static void assertString(String where, JsonNode value, JsonNode spec) {
        assertThat(value.isString()).as(where + " is a string").isTrue();
        String text = value.asString();
        if (spec.has("minLength")) {
            assertThat(text.length()).as(where + " is at least " + spec.get("minLength").asInt())
                    .isGreaterThanOrEqualTo(spec.get("minLength").asInt());
        }
        if (spec.has("maxLength")) {
            assertThat(text.length()).as(where + " is at most " + spec.get("maxLength").asInt())
                    .isLessThanOrEqualTo(spec.get("maxLength").asInt());
        }
        if (spec.has("enum")) {
            Set<String> allowed = new LinkedHashSet<>();
            for (JsonNode option : spec.get("enum")) {
                allowed.add(option.asString());
            }
            assertThat(allowed).as(where + " is one of the values its contract lists").contains(text);
        }
        if (spec.has("format")) {
            switch (spec.get("format").asString()) {
                case "uuid" -> UUID.fromString(text);
                case "date-time" -> java.time.Instant.parse(text);
                default -> throw new IllegalStateException(where + " has a format this test does not read");
            }
        }
    }

    private static void assertInteger(String where, JsonNode value, JsonNode spec) {
        assertThat(value.isIntegralNumber()).as(where + " is an integer").isTrue();
        if (spec.has("minimum")) {
            assertThat(value.asLong()).as(where + " is at least " + spec.get("minimum").asLong())
                    .isGreaterThanOrEqualTo(spec.get("minimum").asLong());
        }
    }

    private static TenantId tenant() {
        return new TenantId(UUID.randomUUID());
    }
}
