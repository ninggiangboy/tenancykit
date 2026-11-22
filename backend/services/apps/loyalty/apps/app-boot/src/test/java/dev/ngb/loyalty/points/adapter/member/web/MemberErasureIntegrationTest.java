package dev.ngb.loyalty.points.adapter.member.web;

import static dev.ngb.loyalty_jooq.Tables.ERASURE_REQUESTS;
import static dev.ngb.loyalty_jooq.Tables.LEDGER_ENTRIES;
import static dev.ngb.loyalty_jooq.Tables.MEMBERS;
import static dev.ngb.loyalty_jooq.Tables.ORDERS;
import static dev.ngb.loyalty_jooq.Tables.ORDER_EARNS;
import static dev.ngb.loyalty_jooq.Tables.OUTBOX;
import static dev.ngb.loyalty_jooq.Tables.POINT_LOTS;
import static dev.ngb.loyalty_jooq.Tables.REDEMPTIONS;
import static dev.ngb.loyalty_jooq.Tables.VOUCHERS;
import static dev.ngb.loyalty_jooq.Tables.WEBHOOK_DELIVERIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.messaging.outbox.OutboxRelay;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.points.application.earn.service.PointsEvents;
import dev.ngb.loyalty.rewards.application.voucher.service.VoucherEvents;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.EventContracts;
import dev.ngb.loyalty.support.EventContracts.Delivery;
import dev.ngb.loyalty.support.ParallelCalls;
import dev.ngb.loyalty.support.PersonalDataColumns;
import dev.ngb.loyalty.support.RewardsApiTest;
import dev.ngb.loyalty.support.WebhookReceiver;
import dev.ngb.loyalty.webhook.application.delivery.command.DeliverWebhooksUseCase;
import dev.ngb.loyalty.webhook.application.endpoint.command.CreateEndpointUseCase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /members/{externalRef}/erasure}: one call cuts every tie this tenant's loyalty holds to a person, a retry of
 * that call replays it, and what pointed at the member survives as an anonymous record.
 *
 * <p>The cases that decide the design are here. Every column the schema declares personal holds a value before the call
 * and none afterwards, so the classification the erasure tests against is the one {@code PersonalDataColumnsTest} checks
 * against {@code information_schema} — a column added later fails one of the two. The record of the request is keyed by
 * {@code sha256(tenant ‖ ref)} and never by the reference. Twenty parallel retries leave one record, one announcement and
 * one delivery per subscribed endpoint, and the reference reaches no member afterwards.
 */
class MemberErasureIntegrationTest extends RewardsApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired DSLContext dsl;
    @Autowired UnitOfWork unitOfWork;
    @Autowired OutboxRelay relay;
    @Autowired DeliverWebhooksUseCase deliver;
    @Autowired CreateEndpointUseCase endpoints;

    @Test
    void theErasureClearsEveryPersonalColumnAndKeepsTheRecordsThatPointAtTheMember() {
        TenantId tenant = tenant();
        UUID memberId = wroteAPerson(tenant, "M-erase");
        long lotsHolding = scalar(tenant, "select count(*) from point_lots where remaining > 0");
        long held = scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots");
        assertThat(lotsHolding).as("the member holds points in a lot").isEqualTo(1);
        assertThat(held).isPositive();

        ApiResponse erased = eraseMember(tenant, "M-erase");

        assertThat(erased.status()).as(erased.body()).isEqualTo(200);
        assertThat(erased.json().get("memberId").asString()).isEqualTo(memberId.toString());
        assertThat(erased.json().get("vouchersVoided").asLong()).as("the unused voucher was voided").isEqualTo(1);
        Instant erasedAt = Instant.parse(erased.json().get("erasedAt").asString());

        var member = inTenant(tenant, () -> unitOfWork.transactional(
                () -> dsl.selectFrom(MEMBERS).where(MEMBERS.ID.eq(memberId)).fetchOne()));
        assertThat(member).as("the row stays, because its orders and ledger point at it").isNotNull();
        assertThat(member.getStatus()).isEqualTo("ERASED");
        assertThat(member.getExternalRef()).as("the reference is released").isNull();
        assertThat(member.getDisplayName()).isNull();

        assertThat(personalColumnsHoldingData(tenant, memberId))
                .as("no personal column holds a value any more")
                .isEmpty();
        assertThat(count(tenant, MEMBERS)).as("one member row, anonymous").isEqualTo(1);
        assertThat(count(tenant, ORDERS)).as("the order stays as a business record").isEqualTo(1);
        assertThat(count(tenant, ORDER_EARNS)).as("the earn record stays, keyed by the opaque id").isEqualTo(1);
        assertThat(count(tenant, POINT_LOTS)).as("the lot row stays as the history of what was held").isEqualTo(1);
        assertThat(count(tenant, REDEMPTIONS)).as("the two spends stay as anonymous records").isEqualTo(2);
        assertThat(count(tenant, ERASURE_REQUESTS)).as("one call, one record of it").isEqualTo(1);
        assertThat(scalar(tenant, "select coalesce(sum(remaining), 0) from point_lots"))
                .as("the lot holds nothing")
                .isZero();
        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'ERASE'"))
                .as("one ERASE entry per lot that still held points")
                .isEqualTo(lotsHolding);
        assertThat(scalar(tenant, "select coalesce(sum(points), 0) from ledger_entries where kind = 'ERASE'"))
                .as("the ERASE entry records what the lot still held")
                .isEqualTo(held);
        assertThat(scalar(tenant, "select count(*) from ledger_entries where kind = 'EARN'"))
                .as("the ledger's history stays")
                .isEqualTo(1);

        assertThat(erasureRefHash(tenant)).as("the record keeps the hash of the reference, not the reference")
                .isEqualTo(sha256Hex(tenant.toString() + "M-erase"))
                .hasSize(64)
                .isNotEqualTo("M-erase");
        assertThat(erasureMemberId(tenant)).isEqualTo(memberId);
        assertThat(erasureErasedAt(tenant))
                .isEqualTo(erasedAt.truncatedTo(ChronoUnit.MICROS));

        assertThat(outboxRows(tenant, PointsEvents.MEMBER_ERASED)).as("the erasure is announced once").isEqualTo(1);
        JsonNode announced = JSON.readTree(payloadOf(tenant, PointsEvents.MEMBER_ERASED));
        assertThat(names(announced))
                .as("the announcement carries loyalty's id and nothing about the person")
                .containsExactly("memberId");
        assertThat(announced.get("memberId").asString()).isEqualTo(memberId.toString());
        EventContracts.assertConforms(PointsEvents.MEMBER_ERASED, announced);
    }

    @Test
    void theErasureVoidsEveryUnusedVoucherAndGivesItsStockBack() {
        TenantId tenant = tenant();
        UUID memberId = wroteAPerson(tenant, "M-vouchers");
        String rewardId = seedReward(tenant, "Tea", 1L, 10L, 7);
        assertThat(stock(tenant, rewardId)).as("a fresh reward keeps its whole stock").isEqualTo(10L);
        // Two more vouchers for the same member, so the sweep has more than one to stop.
        assertThat(redeemReward(tenant, "M-vouchers", "K-second", rewardId).status()).isEqualTo(201);
        assertThat(redeemReward(tenant, "M-vouchers", "K-third", rewardId).status()).isEqualTo(201);
        assertThat(stock(tenant, rewardId)).isEqualTo(8L);
        long issued = scalar(tenant, "select count(*) from vouchers where status = 'ISSUED'");
        assertThat(issued).as("three vouchers are live before the erasure").isEqualTo(3);

        ApiResponse erased = eraseMember(tenant, "M-vouchers");

        assertThat(erased.status()).as(erased.body()).isEqualTo(200);
        assertThat(erased.json().get("vouchersVoided").asLong()).isEqualTo(3);
        assertThat(scalar(tenant, "select count(*) from vouchers where status = 'VOIDED'")).isEqualTo(3);
        assertThat(scalar(tenant, "select count(*) from vouchers where void_reason <> 'ERASURE'"))
                .as("every void is an erasure")
                .isZero();
        assertThat(scalar(tenant, "select count(*) from vouchers where external_ref is not null"))
                .as("no voucher names the reference any more")
                .isZero();
        assertThat(stock(tenant, rewardId)).as("each voucher's unit of stock went back").isEqualTo(10L);
        assertThat(outboxRows(tenant, VoucherEvents.VOUCHER_VOIDED))
                .as("one voucher.voided per voided voucher")
                .isEqualTo(3);
        assertThat(scalar(tenant,
                        "select count(*) from outbox where type = 'voucher.voided' and payload ->> 'reason' <> 'ERASURE'"))
                .isZero();
        assertThat(scalar(tenant,
                        "select count(*) from outbox where type = 'voucher.voided' and payload -> 'externalRef' is not null"
                                + " and payload -> 'externalRef' <> 'null'::jsonb"))
                .as("an ERASURE void carries a null reference")
                .isZero();
        assertThat(count(tenant, MEMBERS)).isEqualTo(1);
        assertThat(memberId).isNotNull();
    }

    @Test
    void twentyParallelRetriesAnswerOneErasureAndDeliverItOncePerEndpoint() {
        TenantId tenant = tenant();
        UUID memberId = wroteAPerson(tenant, "M-parallel");

        try (WebhookReceiver receiver = new WebhookReceiver(tenantContext, endpoints, "/one", "/two")) {
            receiver.subscribe(tenant, "/one", PointsEvents.MEMBER_ERASED, VoucherEvents.VOUCHER_VOIDED);
            receiver.subscribe(tenant, "/two", PointsEvents.MEMBER_ERASED, VoucherEvents.VOUCHER_VOIDED);

            List<ApiResponse> answers = ParallelCalls.run(20, 4, index -> eraseMember(tenant, "M-parallel"));

            assertThat(answers).as("every retry is answered, none is an error")
                    .allSatisfy(answer -> assertThat(answer.status()).as(answer.body()).isEqualTo(200));
            assertThat(distinct(answers, "memberId")).as("every retry names the same member")
                    .containsExactly(memberId.toString());
            assertThat(distinctInstants(answers, "erasedAt"))
                    .as("every retry names the same instant")
                    .hasSize(1);
            assertThat(distinct(answers, "vouchersVoided"))
                    .as("every retry counts the same vouchers")
                    .containsExactly("1");
            assertThat(answers.getFirst().json().get("erasedAt").asString())
                    .isEqualTo(erasureErasedAt(tenant).toString());

            assertThat(count(tenant, MEMBERS)).isEqualTo(1);
            assertThat(count(tenant, ERASURE_REQUESTS)).as("twenty retries leave one record").isEqualTo(1);
            assertThat(outboxRows(tenant, PointsEvents.MEMBER_ERASED))
                    .as("the fact is appended once, however many callers raced")
                    .isEqualTo(1);
            assertThat(outboxRows(tenant, VoucherEvents.VOUCHER_VOIDED))
                    .as("the void is appended once")
                    .isEqualTo(1);

            inTenant(tenant, () -> relay.relayDue());
            deliverUntilDry(tenant);

            assertThat(receiver.of(PointsEvents.MEMBER_ERASED)).as("one event, two subscribed endpoints").hasSize(2);
            assertThat(receiver.of(VoucherEvents.VOUCHER_VOIDED)).hasSize(2);
            assertThat(receiver.deliveries()).extracting(Delivery::eventId)
                    .as("both endpoints of the tenant are told about the same two events")
                    .hasSize(4);
            assertThat(receiver.deliveries()).allSatisfy(receiver::assertSigned);
            assertThat(receiver.only(PointsEvents.MEMBER_ERASED).data().get("memberId").asString())
                    .isEqualTo(memberId.toString());
            assertThat(count(tenant, WEBHOOK_DELIVERIES))
                    .as("two events, two subscribed endpoints, four deliveries")
                    .isEqualTo(4);
        }
    }

    @Test
    void theOldReferenceReachesNoMemberAfterwards() {
        TenantId tenant = tenant();
        UUID erasedId = wroteAPerson(tenant, "M-reused");

        assertThat(eraseMember(tenant, "M-reused").status()).isEqualTo(200);

        assertThat(getMember(tenant, "M-reused").status()).as("the reference names no member").isEqualTo(404);
        assertThat(getMember(tenant, "M-reused").code()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(getLedger(tenant, "M-reused", 10).status()).isEqualTo(404);
        assertThat(listMemberVouchers(tenant, "M-reused", null).status()).isEqualTo(404);
        assertThat(putOrder(tenant, "O-after", orderBody("M-reused", "USD", ORDERED_AT, "ST-01", 1000)).status())
                .as("an order for the released reference enrols a new member")
                .isEqualTo(201);
        UUID newId = UUID.fromString(getMember(tenant, "M-reused").json().get("memberId").asString());
        assertThat(newId).as("the reference starts a new member with a new id").isNotEqualTo(erasedId);
        assertThat(count(tenant, MEMBERS)).as("the anonymous record and the new member are two rows").isEqualTo(2);

        ApiResponse replay = eraseMember(tenant, "M-reused");

        assertThat(replay.status()).as("the record of the original call decides, not the new member").isEqualTo(200);
        assertThat(replay.json().get("memberId").asString()).isEqualTo(erasedId.toString());
        assertThat(count(tenant, MEMBERS)).as("the new member is untouched").isEqualTo(2);
        assertThat(getMember(tenant, "M-reused").status()).as("the new member still answers").isEqualTo(200);
    }

    @Test
    void anotherTenantsErasureCallIs404() {
        TenantId owner = tenant();
        TenantId rival = tenant();
        wroteAPerson(owner, "M-owner");

        ApiResponse rivalCall = eraseMember(rival, "M-owner");

        assertThat(rivalCall.status()).isEqualTo(404);
        assertThat(rivalCall.code()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(count(rival, ERASURE_REQUESTS)).as("another tenant's call erases nothing here").isZero();
        assertThat(count(owner, ERASURE_REQUESTS)).isZero();
        assertThat(getMember(owner, "M-owner").status()).as("the member is untouched").isEqualTo(200);
    }

    @Test
    void anUnknownReferenceIs404() {
        TenantId tenant = tenant();

        ApiResponse unknown = eraseMember(tenant, "M-unknown");

        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(count(tenant, ERASURE_REQUESTS)).isZero();
    }

    /**
     * A tenant holding the erasure code and nothing else: the endpoint answers 200 for a member it reaches, so the code it
     * names is the one it enforces and no other code is needed to call it.
     */
    @Test
    void theErasureCodeIsTheOneTheEndpointRequires() {
        TenantId tenant = tenant();
        seedEarnedPoints(tenant, "M-perm", 10L);

        ApiResponse refused = call(tenant, post("/api/v1/members/M-perm/erasure"),
                LoyaltyPermissions.MEMBER_READ);

        assertThat(refused.status()).as("a token without the erasure code is refused").isEqualTo(403);
        assertThat(getMember(tenant, "M-perm").status()).isEqualTo(200);
        assertThat(eraseMember(tenant, "M-perm").status()).isEqualTo(200);
    }

    /** A member holding a value in every column the schema declares personal, plus the records that stay. */
    private UUID wroteAPerson(TenantId tenant, String ref) {
        assertThat(putMember(tenant, ref, "Ada Lovelace").status()).isEqualTo(201);
        seedEarnedPoints(tenant, ref, 10L);
        String rewardId = seedReward(tenant, "Coffee", 5L, 10L, 7);
        assertThat(redeemAndIssue(tenant, ref, "K-" + ref, rewardId).status()).isEqualTo(201);
        assertThat(postRedemption(tenant, ref, "P-" + ref, 1, "POS-RECEIPT-1").status())
                .as("a plain spend carrying the Tenant's own receipt reference")
                .isEqualTo(201);
        UUID memberId = UUID.fromString(getMember(tenant, ref).json().get("memberId").asString());

        assertThat(personalColumnsHoldingData(tenant, memberId))
                .as("the fixture writes into every column classified as personal")
                .isEqualTo(PersonalDataColumns.personal());
        return memberId;
    }

    private void deliverUntilDry(TenantId tenant) {
        for (int round = 0; round < 5; round++) {
            DeliverWebhooksUseCase.Round sent = inTenant(tenant, () -> deliver.runOnce());
            if (sent.claimed() == 0) {
                return;
            }
        }
    }

    /**
     * The personal columns that still hold a value for one member: a column of the {@code members} row is keyed by its own
     * id, every other personal table by {@code member_id}.
     */
    private Set<String> personalColumnsHoldingData(TenantId tenant, UUID memberId) {
        return inTenant(tenant, () -> unitOfWork.transactional(() -> {
            Set<String> holding = new TreeSet<>();
            for (String column : PersonalDataColumns.personal()) {
                String table = PersonalDataColumns.table(column);
                String key = table.equals("members") ? "id" : "member_id";
                Field<Object> value = DSL.field(DSL.name(PersonalDataColumns.name(column)));
                Condition filled = value.isNotNull().and(value.cast(SQLDataType.VARCHAR).notIn("", "{}", "[]"));
                if (dsl.fetchCount(
                                DSL.table(DSL.name(table)),
                                filled.and(DSL.field(DSL.name(key)).eq(memberId)))
                        > 0) {
                    holding.add(column);
                }
            }
            return holding;
        }));
    }

    private String erasureRefHash(TenantId tenant) {
        return inTenant(tenant, () -> unitOfWork.transactional(
                () -> dsl.select(ERASURE_REQUESTS.REF_HASH).from(ERASURE_REQUESTS).fetchOne(ERASURE_REQUESTS.REF_HASH)));
    }

    private UUID erasureMemberId(TenantId tenant) {
        return inTenant(tenant, () -> unitOfWork.transactional(
                () -> dsl.select(ERASURE_REQUESTS.MEMBER_ID).from(ERASURE_REQUESTS)
                        .fetchOne(ERASURE_REQUESTS.MEMBER_ID)));
    }

    private Instant erasureErasedAt(TenantId tenant) {
        return inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.select(ERASURE_REQUESTS.ERASED_AT)
                .from(ERASURE_REQUESTS)
                .fetchOne(ERASURE_REQUESTS.ERASED_AT)
                .truncatedTo(ChronoUnit.MICROS)));
    }

    private String payloadOf(TenantId tenant, String type) {
        return inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.select(OUTBOX.PAYLOAD)
                .from(OUTBOX)
                .where(OUTBOX.TYPE.eq(type))
                .fetchOne(OUTBOX.PAYLOAD)
                .data()));
    }

    /** A row count of one table for one tenant, read inside the tenant's scope so row-level security supplies the tenant. */
    private long count(TenantId tenant, org.jooq.Table<?> table) {
        return inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.fetchCount(table)));
    }

    private static List<String> distinct(List<ApiResponse> answers, String field) {
        Set<String> values = new TreeSet<>();
        answers.forEach(answer -> values.add(answer.json().get(field).asString()));
        return List.copyOf(values);
    }

    private static List<Instant> distinctInstants(List<ApiResponse> answers, String field) {
        Set<Instant> values = new TreeSet<>();
        answers.forEach(answer -> values.add(Instant.parse(answer.json().get(field).asString())));
        return List.copyOf(values);
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this runtime", e);
        }
    }
}
