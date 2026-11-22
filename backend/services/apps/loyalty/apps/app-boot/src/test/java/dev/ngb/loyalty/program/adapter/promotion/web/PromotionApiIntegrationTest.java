package dev.ngb.loyalty.program.adapter.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.ngb.core.id.TenantId;
import dev.ngb.core.runtime.Clock;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.core.runtime.UnitOfWork;
import dev.ngb.loyalty.program.api.program.ProgramReader;
import dev.ngb.loyalty.program.api.promotion.PromotionMatch;
import dev.ngb.loyalty.program.api.promotion.PromotionStatus;
import dev.ngb.loyalty.program.api.promotion.PromotionView;
import dev.ngb.loyalty.program.application.promotion.command.CreatePromotionUseCase;
import dev.ngb.loyalty.program.application.promotion.port.PromotionRepository;
import dev.ngb.loyalty.program.domain.promotion.Promotion;
import dev.ngb.loyalty.program.domain.promotion.PromotionConditions;
import dev.ngb.loyalty.program.domain.promotion.PromotionId;
import dev.ngb.loyalty.shared.LoyaltyPermissions;
import dev.ngb.loyalty.support.LoyaltyApiTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * The promotion catalog: the closed condition set, the window rule, the cap and the read port the earn engine uses.
 */
class PromotionApiIntegrationTest extends LoyaltyApiTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Instant MONDAY_MORNING = Instant.parse("2026-01-05T01:00:00Z");
    private static final Instant MONDAY_EVENING = Instant.parse("2026-01-05T12:00:00Z");
    private static final Instant SUNDAY_IN_UTC = Instant.parse("2026-01-04T23:30:00Z");
    private static final Instant OUTSIDE_THE_WINDOW = Instant.parse("2026-03-05T01:00:00Z");

    @Autowired ProgramReader reader;
    @Autowired CreatePromotionUseCase create;
    @Autowired PromotionRepository promotions;
    @Autowired UnitOfWork unitOfWork;
    @Autowired IdGenerator ids;
    @Autowired Clock clock;

    @Test
    void aPromotionIsCreatedWithItsDefaults() {
        TenantId tenant = new TenantId(UUID.randomUUID());

        ApiResponse created = createPromotion(tenant, """
                {"name": "Double points", "multiplier": 2.00}
                """);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.etag()).isEqualTo("\"1\"");
        assertThat(created.json().get("name").asString()).isEqualTo("Double points");
        assertThat(created.json().get("multiplier").decimalValue()).isEqualByComparingTo("2.00");
        assertThat(created.json().get("active").asBoolean()).isTrue();
        assertThat(created.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.json().get("priority").asInt()).isZero();
        assertThat(created.json().get("version").asLong()).isEqualTo(1);
        assertThat(created.json().get("promotionId").asString()).isNotBlank();
    }

    @Test
    void aWindowThatEndsBeforeItStartsIs422() {
        assertInvalid(new TenantId(UUID.randomUUID()), """
                {"name": "Backwards", "multiplier": 2.00,
                 "startsAt": "2026-02-01T00:00:00Z", "endsAt": "2026-01-01T00:00:00Z"}
                """);
    }

    @Test
    void aMultiplierBelowItsFloorIs422() {
        assertInvalid(new TenantId(UUID.randomUUID()), """
                {"name": "Nothing extra", "multiplier": 1.00}
                """);
        assertInvalid(new TenantId(UUID.randomUUID()), """
                {"name": "Half", "multiplier": 0.50}
                """);
    }

    @Test
    void aDayOfWeekOutsideOneToSevenIs422() {
        assertInvalid(new TenantId(UUID.randomUUID()), """
                {"name": "Eighth day", "multiplier": 2.00, "daysOfWeek": [8]}
                """);
    }

    @Test
    void aPatchWithoutIfMatchIs428AndAgainstAnOlderVersionIs412() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        String id = createPromotion(tenant, """
                {"name": "Double points", "multiplier": 2.00}
                """).json().get("promotionId").asString();

        ApiResponse withoutHeader = call(
                tenant,
                patch("/api/v1/promotions/" + id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"active": false}
                        """),
                LoyaltyPermissions.PROGRAM_MANAGE);
        ApiResponse stale = patchPromotion(tenant, id, "\"9\"", """
                {"active": false}
                """);

        assertThat(withoutHeader.status()).isEqualTo(428);
        assertThat(withoutHeader.code()).isEqualTo("IF_MATCH_REQUIRED");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.etag()).isEqualTo("\"1\"");
    }

    @Test
    void aPatchChangesOnlyWhatItNames() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        String id = createPromotion(tenant, """
                {"name": "Double points", "multiplier": 2.00, "storeCodes": ["S1"]}
                """).json().get("promotionId").asString();

        ApiResponse patched = patchPromotion(tenant, id, "\"1\"", """
                {"active": false}
                """);

        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.etag()).isEqualTo("\"2\"");
        assertThat(patched.json().get("status").asString()).isEqualTo("PAUSED");
        assertThat(patched.json().get("name").asString()).as("an unnamed field is left alone").isEqualTo("Double points");
        assertThat(patched.json().get("storeCodes").size()).isEqualTo(1);
    }

    @Test
    void anUnknownPromotionIs404() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        String unknown = UUID.randomUUID().toString();

        ApiResponse read = call(tenant, get("/api/v1/promotions/" + unknown), LoyaltyPermissions.PROGRAM_READ);
        ApiResponse patched = patchPromotion(tenant, unknown, "\"1\"", """
                {"active": false}
                """);

        assertThat(read.status()).isEqualTo(404);
        assertThat(read.code()).isEqualTo("PROMOTION_NOT_FOUND");
        assertThat(patched.status()).isEqualTo(404);
        assertThat(patched.code()).isEqualTo("PROMOTION_NOT_FOUND");
    }

    @Test
    void theCatalogNarrowsToOneState() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        createPromotion(tenant, """
                {"name": "Open", "multiplier": 2.00, "priority": 5}
                """);
        createPromotion(tenant, """
                {"name": "Paused", "multiplier": 3.00, "active": false}
                """);

        ApiResponse all = call(tenant, get("/api/v1/promotions"), LoyaltyPermissions.PROGRAM_READ);
        ApiResponse open = call(tenant, get("/api/v1/promotions").param("active", "true"), LoyaltyPermissions.PROGRAM_READ);
        ApiResponse closed = call(
                tenant, get("/api/v1/promotions").param("active", "false"), LoyaltyPermissions.PROGRAM_READ);

        assertThat(all.json().get("data").size()).isEqualTo(2);
        assertThat(all.json().get("data").get(0).get("name").asString())
                .as("the highest priority is listed first")
                .isEqualTo("Open");
        assertThat(open.json().get("data").size()).isEqualTo(1);
        assertThat(open.json().get("data").get(0).get("name").asString()).isEqualTo("Open");
        assertThat(closed.json().get("data").get(0).get("name").asString()).isEqualTo("Paused");
    }

    @Test
    void theCatalogStopsAtTwoHundredOpenPromotions() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        inTenant(tenant, () -> unitOfWork.transactional(() -> {
            for (int i = 0; i < 200; i++) {
                promotions.add(Promotion.create(
                        tenant,
                        new PromotionId(ids.newId()),
                        "rule " + i,
                        0,
                        PromotionStatus.ACTIVE,
                        new BigDecimal("2.00"),
                        null,
                        null,
                        PromotionConditions.NONE,
                        clock.now()));
            }
            return null;
        }));

        ApiResponse response = createPromotion(tenant, """
                {"name": "One too many", "multiplier": 2.00}
                """);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void theReadPortReturnsOnlyActivePromotionsWhoseWindowContainsTheInstant() {
        TenantId tenant = configured();

        create(tenant, "morning", 10, PromotionStatus.ACTIVE, "2.00", window(), conditions()
                .daysOfWeek(List.of(1))
                .timeFrom(LocalTime.of(9, 0))
                .timeTo(LocalTime.of(17, 0))
                .storeCodes(List.of("S1"))
                .categories(List.of("food"))
                .skus(List.of("SKU-1")));
        create(tenant, "any-monday", 0, PromotionStatus.ACTIVE, "1.50", window(), conditions().daysOfWeek(List.of(1)));
        create(tenant, "expired", 0, PromotionStatus.ACTIVE, "4.00", outOfWindow(), conditions());
        create(tenant, "paused", 0, PromotionStatus.PAUSED, "4.00", window(), conditions());
        create(tenant, "archived", 0, PromotionStatus.ARCHIVED, "4.00", window(), conditions());

        List<PromotionView> active = inTenant(tenant, () -> reader.activePromotionsAt(MONDAY_MORNING));

        assertThat(active.stream().map(PromotionView::name))
                .as("only an active promotion inside its window is a candidate")
                .containsExactlyInAnyOrder("morning", "any-monday");
        assertThat(active).allSatisfy(view -> assertThat(view.timeZone()).isEqualTo(TOKYO));
    }

    @Test
    void everyKindThatIsSetMustMatchAndAnyValueInsideAKindMatches() {
        TenantId tenant = configured();
        create(tenant, "morning", 0, PromotionStatus.ACTIVE, "2.00", window(), conditions()
                .daysOfWeek(List.of(1))
                .timeFrom(LocalTime.of(9, 0))
                .timeTo(LocalTime.of(17, 0))
                .storeCodes(List.of("S1"))
                .categories(List.of("food"))
                .skus(List.of("SKU-1")));
        PromotionView promotion = inTenant(tenant, () -> reader.activePromotionsAt(MONDAY_MORNING)).get(0);

        assertThat(promotion.matches(match(MONDAY_MORNING, "S1", "food", "SKU-1"))).isTrue();
        assertThat(promotion.matches(match(MONDAY_MORNING, "S2", "food", "SKU-1")))
                .as("a store outside the list does not match")
                .isFalse();
        assertThat(promotion.matches(match(MONDAY_MORNING, "S1", "drink", "SKU-1")))
                .as("a category outside the list does not match")
                .isFalse();
        assertThat(promotion.matches(match(MONDAY_MORNING, "S1", "food", "SKU-2")))
                .as("a sku outside the list does not match")
                .isFalse();
        assertThat(promotion.matches(match(MONDAY_EVENING, "S1", "food", "SKU-1")))
                .as("a time outside the range does not match")
                .isFalse();
    }

    @Test
    void theWeekdayAndTheTimeOfDayAreReadInTheProgramZone() {
        TenantId tenant = configured();
        create(tenant, "any-monday", 0, PromotionStatus.ACTIVE, "2.00", window(), conditions().daysOfWeek(List.of(1)));

        PromotionView promotion = inTenant(tenant, () -> reader.activePromotionsAt(MONDAY_MORNING)).get(0);

        assertThat(promotion.matches(match(SUNDAY_IN_UTC, null, null, null)))
                .as("the instant is Monday morning in the program's zone")
                .isTrue();
        assertThat(promotion.inZone(ZoneId.of("UTC")).matches(match(SUNDAY_IN_UTC, null, null, null)))
                .as("the same instant is Sunday in UTC")
                .isFalse();
    }

    @Test
    void anArchivedPromotionLeavesTheReadPort() {
        TenantId tenant = configured();
        create(tenant, "one-off", 0, PromotionStatus.ACTIVE, "2.00", window(), conditions());
        String id = inTenant(tenant, () -> reader.activePromotionsAt(MONDAY_MORNING)).get(0).promotionId().toString();

        ApiResponse archived = patchPromotion(tenant, id, "\"1\"", """
                {"status": "ARCHIVED"}
                """);

        assertThat(archived.status()).isEqualTo(200);
        assertThat(inTenant(tenant, () -> reader.activePromotionsAt(MONDAY_MORNING))).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private TenantId configured() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        call(
                tenant,
                put("/api/v1/program").header("If-Match", "*").contentType(MediaType.APPLICATION_JSON).content("""
                        {"currency": "USD", "currencyUnit": 1000, "timeZone": "Asia/Tokyo"}
                        """),
                LoyaltyPermissions.PROGRAM_MANAGE);
        return tenant;
    }

    private void create(
            TenantId tenant,
            String name,
            int priority,
            PromotionStatus status,
            String multiplier,
            Instant[] window,
            Conditions conditions) {
        inTenant(tenant, () -> create.execute(new CreatePromotionUseCase.Command(
                name,
                priority,
                status.name(),
                null,
                new BigDecimal(multiplier),
                window[0],
                window[1],
                conditions.build())));
    }

    private ApiResponse createPromotion(TenantId tenant, String body) {
        return call(
                tenant,
                post("/api/v1/promotions").contentType(MediaType.APPLICATION_JSON).content(body),
                LoyaltyPermissions.PROGRAM_MANAGE);
    }

    private ApiResponse patchPromotion(TenantId tenant, String id, String ifMatch, String body) {
        return call(
                tenant,
                patch("/api/v1/promotions/" + id)
                        .header("If-Match", ifMatch)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                LoyaltyPermissions.PROGRAM_MANAGE);
    }

    private void assertInvalid(TenantId tenant, String body) {
        ApiResponse response = createPromotion(tenant, body);
        assertThat(response.status()).as(body).isEqualTo(422);
        assertThat(response.code()).as(body).isEqualTo("VALIDATION_FAILED");
    }

    private static PromotionMatch match(Instant at, String storeCode, String category, String sku) {
        return new PromotionMatch(storeCode, category, sku, null, at);
    }

    private static Instant[] window() {
        return new Instant[] {Instant.parse("2025-12-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z")};
    }

    private static Instant[] outOfWindow() {
        return new Instant[] {Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-02-01T00:00:00Z")};
    }

    private static Conditions conditions() {
        return new Conditions();
    }

    /** A small builder so a case names only the condition kinds it cares about. */
    private static final class Conditions {

        private List<String> categories;
        private List<String> skus;
        private List<String> storeCodes;
        private List<Integer> daysOfWeek;
        private LocalTime timeFrom;
        private LocalTime timeTo;

        Conditions daysOfWeek(List<Integer> values) {
            this.daysOfWeek = values;
            return this;
        }

        Conditions storeCodes(List<String> values) {
            this.storeCodes = values;
            return this;
        }

        Conditions categories(List<String> values) {
            this.categories = values;
            return this;
        }

        Conditions skus(List<String> values) {
            this.skus = values;
            return this;
        }

        Conditions timeFrom(LocalTime value) {
            this.timeFrom = value;
            return this;
        }

        Conditions timeTo(LocalTime value) {
            this.timeTo = value;
            return this;
        }

        PromotionConditions build() {
            return new PromotionConditions(categories, skus, storeCodes, daysOfWeek, null, timeFrom, timeTo);
        }
    }
}
