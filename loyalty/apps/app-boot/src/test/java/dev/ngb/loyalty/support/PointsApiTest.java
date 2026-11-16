package dev.ngb.loyalty.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The order, return and member endpoints on top of the HTTP harness, plus the two pieces of setup a caller cannot send:
 * the program's rules, which the program endpoints own, and a member that already holds a tier or a debt, which no
 * endpoint of this phase sets.
 *
 * <p>Every helper takes the tenant, so a test never has to remember which tenant it is acting as.
 */
public abstract class PointsApiTest extends LoyaltyApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired protected DSLContext dsl;
    @Autowired protected UnitOfWork unitOfWork;

    // ------------------------------------------------------------------ program, tiers and promotions

    /** Seeds a whole rule set: the program, its tiers and its promotions, in the order a Tenant would send them. */
    protected void seedRules(TenantId tenant, EarnVectors.Rules rules) {
        String etag = seedProgram(tenant, rules.program());
        seedTiers(tenant, etag, rules.tiers());
        for (EarnVectors.Promotion promotion : rules.promotions()) {
            createPromotion(tenant, rules.program(), promotion);
        }
    }

    /** Creates or replaces the program and answers the ETag the tier replace then sends. */
    protected String seedProgram(TenantId tenant, EarnVectors.Program program) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency", program.currency());
        body.put("currencyUnit", program.currencyUnit());
        body.put("timeZone", program.timeZone());
        body.put("expiryMonths", program.expiryMonthsOrDefault());
        body.put("expiryNoticeDays", program.expiryNoticeDays());
        body.put("tierWindowMonths", program.tierWindowMonths());
        ApiResponse read = call(tenant, get("/api/v1/program"), LoyaltyPermissions.PROGRAM_READ);
        ApiResponse written = call(
                tenant,
                put("/api/v1/program")
                        .header("If-Match", read.status() == 200 ? read.etag() : "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)),
                LoyaltyPermissions.PROGRAM_MANAGE);
        if (written.status() != 200) {
            throw new IllegalStateException("the program could not be seeded: " + written.status() + " " + written.body());
        }
        return written.etag();
    }

    protected void seedTiers(TenantId tenant, String etag, List<EarnVectors.Tier> tiers) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (EarnVectors.Tier tier : tiers) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", tier.code());
            entry.put("name", tier.code());
            entry.put("minSpend", tier.minSpend());
            entry.put("earnMultiplier", tier.earnMultiplier());
            entries.add(entry);
        }
        ApiResponse written = call(
                tenant,
                put("/api/v1/tiers")
                        .header("If-Match", etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("tiers", entries))),
                LoyaltyPermissions.PROGRAM_MANAGE);
        if (written.status() != 200) {
            throw new IllegalStateException("the tiers could not be seeded: " + written.status() + " " + written.body());
        }
    }

    protected void createPromotion(TenantId tenant, EarnVectors.Program program, EarnVectors.Promotion promotion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", promotion.id());
        body.put("multiplier", promotion.multiplier());
        body.put("priority", promotion.priorityOrDefault());
        body.put("active", true);
        body.put("startsAt", EarnVectors.instant(promotion.from(), java.time.ZoneId.of(program.timeZone())));
        body.put("endsAt", EarnVectors.instant(promotion.to(), java.time.ZoneId.of(program.timeZone())));
        body.put("daysOfWeek", EarnVectors.weekdays(promotion.daysOfWeek()).isEmpty()
                ? null
                : EarnVectors.weekdays(promotion.daysOfWeek()));
        body.put("storeCodes", list(promotion.storeCode()));
        body.put("categories", list(promotion.category()));
        body.put("skus", list(promotion.sku()));
        ApiResponse written = call(
                tenant,
                post("/api/v1/promotions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)),
                LoyaltyPermissions.PROGRAM_MANAGE);
        if (written.status() != 201) {
            throw new IllegalStateException("a promotion could not be seeded: " + written.status() + " " + written.body());
        }
    }

    private static List<String> list(String value) {
        return value == null ? null : List.of(value);
    }

    // ------------------------------------------------------------------ members, orders and returns

    protected ApiResponse putMember(TenantId tenant, String externalRef, String displayName) {
        return call(
                tenant,
                put(java.net.URI.create("/api/v1/members/" + externalRef))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(displayName == null ? "{}" : JSON.writeValueAsString(Map.of("displayName", displayName))),
                LoyaltyPermissions.MEMBER_MANAGE);
    }

    protected ApiResponse getMember(TenantId tenant, String externalRef) {
        return call(tenant, get(java.net.URI.create("/api/v1/members/" + externalRef)), LoyaltyPermissions.MEMBER_READ);
    }

    protected ApiResponse putOrder(TenantId tenant, String orderRef, String body) {
        return call(
                tenant,
                put("/api/v1/orders/" + orderRef).contentType(MediaType.APPLICATION_JSON).content(body),
                LoyaltyPermissions.ORDER_RECORD);
    }

    protected ApiResponse putReturn(TenantId tenant, String orderRef, String returnRef, String body) {
        return call(
                tenant,
                put("/api/v1/orders/" + orderRef + "/returns/" + returnRef)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                LoyaltyPermissions.ORDER_RECORD);
    }

    protected ApiResponse getOrder(TenantId tenant, String orderRef) {
        return call(tenant, get("/api/v1/orders/" + orderRef), LoyaltyPermissions.MEMBER_READ);
    }

    protected ApiResponse getLedger(TenantId tenant, String externalRef, int limit) {
        return call(
                tenant,
                get("/api/v1/members/" + externalRef + "/ledger").param("limit", Integer.toString(limit)),
                LoyaltyPermissions.MEMBER_READ);
    }

    // ------------------------------------------------------------------ redemptions

    /** One spend as the POS sends it, with the key the caller chose for it. */
    protected ApiResponse postRedemption(
            TenantId tenant, String externalRef, String idempotencyKey, long points, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", points);
        if (reference != null) {
            body.put("reference", reference);
        }
        MockHttpServletRequestBuilder request = post("/api/v1/members/" + externalRef + "/redemptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return call(tenant, request, LoyaltyPermissions.POINTS_REDEEM);
    }

    protected ApiResponse getRedemption(TenantId tenant, String externalRef, UUID redemptionId) {
        return call(
                tenant,
                get("/api/v1/members/" + externalRef + "/redemptions/" + redemptionId),
                LoyaltyPermissions.MEMBER_READ);
    }

    protected ApiResponse reverseRedemption(TenantId tenant, String externalRef, UUID redemptionId) {
        return call(
                tenant,
                post("/api/v1/members/" + externalRef + "/redemptions/" + redemptionId + "/reversal"),
                LoyaltyPermissions.POINTS_REDEEM);
    }

    /** The member's balance as the member endpoint computes it, or 0 for a member loyalty does not have. */
    protected long available(TenantId tenant, String externalRef) {
        ApiResponse member = getMember(tenant, externalRef);
        if (member.status() == 404) {
            return 0L;
        }
        if (member.status() != 200) {
            throw new IllegalStateException("the member read failed: " + member.status() + " " + member.body());
        }
        return member.json().get("available").asLong();
    }

    /** The sum of the points the member's EARN entries granted, which is what the ledger must equal. */
    protected long earnedTotal(TenantId tenant, String externalRef) {
        ApiResponse page = getLedger(tenant, externalRef, 200);
        long total = 0L;
        for (JsonNode entry : page.json().get("data")) {
            if ("EARN".equals(entry.get("kind").asString())) {
                total += entry.get("points").asLong();
            }
        }
        return total;
    }

    /**
     * One number the schema holds for this tenant: a {@code count(*)}, a {@code sum(remaining)}, whatever a test needs to
     * state what the endpoints wrote. It runs inside the tenant's scope, so row-level security supplies the tenant and a
     * leak from another tenant would show up as a wrong number rather than as a silent pass.
     */
    protected long scalar(TenantId tenant, String sql, Object... bindings) {
        return inTenant(tenant, () -> unitOfWork.transactional(() -> {
            var row = dsl.fetchOne(sql, bindings);
            return row == null || row.get(0) == null ? 0L : row.get(0, Long.class);
        }));
    }

    /**
     * Seeds a member that already holds a tier or a debt, which no endpoint of this phase can set: the vector that starts
     * a member in a tier or owing points needs that state before its first step.
     */
    protected void seedMember(TenantId tenant, String externalRef, String tierCode, long debt) {
        UUID id = UUID.randomUUID();
        inTenant(tenant, () -> unitOfWork.transactional(() -> dsl.execute(
                "insert into members (tenant_id, id, external_ref, tier_code, debt, status, created_at) "
                        + "values (?, ?, ?, ?, ?, 'ACTIVE', now())",
                tenant.value(),
                id,
                externalRef,
                tierCode,
                debt)));
    }

    /** A member's stored debt, which a return that exceeds what is left turns into a debt rather than a minus lot. */
    protected long debt(TenantId tenant, String externalRef) {
        ApiResponse member = getMember(tenant, externalRef);
        return member.status() == 200 ? member.json().get("debt").asLong() : 0L;
    }

    /** One order body in the shared shape, with a single line of the given amount. */
    protected static String orderBody(String externalRef, String currency, String occurredAt, String storeCode,
            long amount) {
        return """
                {"externalRef": "%s", "currency": "%s", "occurredAt": "%s", "storeCode": "%s", "channel": "POS",
                 "lines": [{"lineNo": 1, "sku": "SKU-1", "category": "GROCERY", "quantity": 1, "amount": %d}]}
                """
                .formatted(externalRef, currency, occurredAt, storeCode, amount);
    }

    /** One return body against line 1 of an order. */
    protected static String returnBody(String occurredAt, BigDecimal quantity, long amount) {
        return """
                {"occurredAt": "%s", "lines": [{"lineNo": 1, "quantity": %s, "amount": %d}]}
                """
                .formatted(occurredAt, quantity.toPlainString(), amount);
    }
}
